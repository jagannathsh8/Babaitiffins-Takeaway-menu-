package com.pp.kds.dosa;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.graphics.ImageFormat;
import android.media.AudioManager;
import android.media.Image;
import android.media.ImageReader;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.pp.kds.scan.KotDecoder;
import com.pp.kds.scan.ScannerActivity;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Hands-free camera for the KDS (phone, tablet or a USB camera on the KDS screen).
 *
 *  - A KOT barcode in front of the camera is marked Food Ready (same as the scanner).
 *  - On the rider screen, a rider's phone held up shows that order's brand, ID, slot, status
 *    and items ({@link RiderOcr} finds it on the board from the text the camera reads).
 *
 * Never in the way of the KDS: the camera runs on its own thread, barcodes are decoded on a
 * second one (only the newest frame; frames that arrive while it is busy are dropped), and text
 * is read by {@link OcrEngine} in its own WebView process. Watchdogs restart a camera that stops
 * sending pictures; any failure only switches the camera off, never the KDS.
 */
final class RiderCam {

    static final String PREFS = "dosa_live_ui";
    static final int MODE_OFF = 0, MODE_RIDER = 1, MODE_ALWAYS = 2;
    static final int CAM_AUTO = 0, CAM_BACK = 1, CAM_FRONT = 2, CAM_USB = 3;
    static final int FLASH_OFF = 0, FLASH_ON = 1, FLASH_AUTO = 2;

    // settings
    private static volatile int mode, which, flash;
    private static volatile boolean preview, food, rider;

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static HandlerThread camThread, decThread;
    private static Handler cam, dec;

    private static Activity act;
    private static Application.ActivityLifecycleCallbacks life;
    private static boolean resumed, askedPermission;
    private static volatile boolean riderVisible, running;
    private static volatile String status = "Camera off";

    // camera (camera thread)
    private static CameraDevice device;
    private static CameraCaptureSession session;
    private static ImageReader reader;
    private static CaptureRequest.Builder request;
    private static TextureView texture;
    private static Surface previewSurface;
    private static int retryMs = 3_000;
    private static boolean hasFlash, torchOn, autoFocusOnly;
    private static volatile int sensorDeg, displayDeg;
    private static volatile boolean front;

    // frames (camera thread -> decode thread)
    private static byte[] yBuf = new byte[0];
    private static volatile int fw, fh;
    private static volatile boolean decoding;
    private static volatile long decodingSince, lastFrameAt, openedAt;
    private static final KotDecoder decoder = new KotDecoder();
    private static int frameNo, darkFrames, brightFrames;

    // barcode / OCR results
    private static String lastCode;
    private static long lastCodeAt;
    private static volatile long nextOcrAt;
    private static int ocrTurn;
    private static String prevKey, prevUnknown;
    private static long prevAt, prevUnknownAt, lastTextAt;
    private static volatile List<RiderCalls.Waiting> boardReady = Collections.emptyList();
    private static volatile List<RiderCalls.Order> boardPrep = Collections.emptyList();
    private static volatile String showingKey;
    private static ToneGenerator tone;
    private static final ByteArrayOutputStream jpegOut = new ByteArrayOutputStream(96 * 1024);
    private static int[] px = new int[0];

    private RiderCam() {}

    // ---- settings -----------------------------------------------------------------------------

    static void loadSettings(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        mode = p.getInt("cam_mode", MODE_OFF);
        which = p.getInt("cam_which", CAM_AUTO);
        flash = p.getInt("cam_flash", FLASH_OFF);
        preview = p.getBoolean("cam_preview", true);
        food = p.getBoolean("cam_food", true);
        rider = p.getBoolean("cam_rider", true);
        KotDecoder.setLearned(p.getString("cam_fmt", null));
    }

    static void saveSettings(Context c, int m, int w, int f, boolean pv, boolean fd, boolean rd) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("cam_mode", m).putInt("cam_which", w)
                .putInt("cam_flash", f).putBoolean("cam_preview", pv).putBoolean("cam_food", fd)
                .putBoolean("cam_rider", rd).apply();
        loadSettings(c);
        restart();
    }

    static int mode() { return mode; }
    static int which() { return which; }
    static int flash() { return flash; }
    static boolean preview() { return preview; }
    static boolean food() { return food; }
    static boolean riderReading() { return rider; }

    static String status() {
        String s = status;
        if (running && rider) s += "\n" + "Text reader: " + OcrEngine.status();
        String f = KotDecoder.learned();
        if (f != null) s += "\nKOT barcode type: " + f;
        return s;
    }

    /** Camera names for the settings window ("Back", "Front", "USB camera"). */
    static String cameras(Context c) {
        try {
            CameraManager cm = (CameraManager) c.getSystemService(Context.CAMERA_SERVICE);
            List<String> names = new ArrayList<String>();
            for (String id : cm.getCameraIdList()) {
                Integer f = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                names.add(f == null ? "camera " + id : f == CameraCharacteristics.LENS_FACING_FRONT ? "Front"
                        : f == CameraCharacteristics.LENS_FACING_BACK ? "Back" : "USB camera");
            }
            return names.isEmpty() ? "No camera found on this device" : "Cameras found: " + android.text.TextUtils.join(", ", names);
        } catch (Throwable t) {
            return "Cameras: cannot read (" + t.getClass().getSimpleName() + ")";
        }
    }

    // ---- life --------------------------------------------------------------------------------

    /** Called once from DosaLive.install. */
    static void init(Activity a) {
        try {
            if (act != null && act != a) {
                end();
                try {
                    act.getApplication().unregisterActivityLifecycleCallbacks(life);
                } catch (Throwable ignored) {
                }
            }
            act = a;
            resumed = true;
            loadSettings(a);
            if (camThread == null) {
                camThread = new HandlerThread("babai-cam");
                camThread.start();
                cam = new Handler(camThread.getLooper());
            }
            newDecodeThread();
            life = new Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityResumed(Activity x) {
                    if (x == act) {
                        resumed = true;
                        askedPermission = false;
                        update();
                    }
                }

                @Override public void onActivityPaused(Activity x) {
                    if (x == act) {
                        resumed = false;
                        update();
                    }
                }

                @Override public void onActivityDestroyed(Activity x) {
                    if (x == act) {
                        resumed = false;
                        end();
                        OcrEngine.stop();
                    }
                }

                @Override public void onActivityCreated(Activity x, Bundle b) {}
                @Override public void onActivityStarted(Activity x) {}
                @Override public void onActivityStopped(Activity x) {}
                @Override public void onActivitySaveInstanceState(Activity x, Bundle b) {}
            };
            a.getApplication().registerActivityLifecycleCallbacks(life);
            update();
        } catch (Throwable t) {
            status = "Camera unavailable";
        }
    }

    private static void newDecodeThread() {
        if (decThread != null) decThread.quitSafely();
        decThread = new HandlerThread("babai-decode");
        decThread.start();
        dec = new Handler(decThread.getLooper());
        decoding = false;
    }

    /** The rider pickup screen opened / closed. */
    static void setRiderVisible(boolean on) {
        if (riderVisible == on) return;
        riderVisible = on;
        update();
    }

    /** Rider board orders (for finding the rider's order). Main thread, from bindRider. */
    static void setBoard(List<RiderCalls.Waiting> ready, List<RiderCalls.Order> prep) {
        boardReady = new ArrayList<RiderCalls.Waiting>(ready);
        boardPrep = new ArrayList<RiderCalls.Order>(prep);
    }

    /** The details card closed (OK / timeout): the same phone may show it again. */
    static void lookupClosed(boolean byUser) {
        if (byUser && showingKey != null) {
            suppressKey = showingKey;
            suppressUntil = SystemClock.uptimeMillis() + 8_000L;
        }
        showingKey = null;
        prevKey = null;
    }

    private static volatile String suppressKey;
    private static volatile long suppressUntil;

    static boolean wantsOcr() {
        return running && rider && riderVisible && mode != MODE_OFF;
    }

    private static void restart() {
        main.post(new Runnable() {
            @Override public void run() {
                end();
                update();
            }
        });
    }

    /** Opens or closes the camera as the settings, screen and app state say. Main thread. */
    static void update() {
        try {
            boolean want = act != null && resumed && mode != MODE_OFF && (mode == MODE_ALWAYS || riderVisible)
                    && (food || rider);
            if (want && !permitted()) {
                status = "Allow the camera for the KDS app";
                if (!askedPermission && Build.VERSION.SDK_INT >= 23) {
                    askedPermission = true;
                    try {
                        act.requestPermissions(new String[]{"android.permission.CAMERA"}, 7002);
                    } catch (Throwable ignored) {
                    }
                }
                main.removeCallbacks(permissionPoll);
                main.postDelayed(permissionPoll, 2_000L);
                return;
            }
            if (want && !running) begin();
            else if (!want && running) end();
            if (running && rider && riderVisible) {
                main.removeCallbacks(startOcr);
                main.postDelayed(startOcr, 1_500L);
            } else {
                main.removeCallbacks(startOcr);
                OcrEngine.stop();   // text reader only lives while the rider screen shows (memory)
            }
            showPreview(riderVisible);
        } catch (Throwable t) {
            status = "Camera problem: " + t.getClass().getSimpleName();
        }
    }

    private static final Runnable startOcr = new Runnable() {
        @Override public void run() {
            if (wantsOcr() && act != null && !act.isFinishing()) OcrEngine.start(act);
        }
    };

    /** The small camera picture only shows on the rider screen (never over the KDS's own buttons). */
    private static void showPreview(boolean on) {
        TextureView tv = texture;
        if (tv == null) return;
        try {
            View frame = (View) tv.getParent();
            if (frame != null) frame.setAlpha(on ? 1f : 0f);
        } catch (Throwable ignored) {
        }
    }

    private static final Runnable permissionPoll = new Runnable() {
        @Override public void run() {
            if (act != null && resumed && mode != MODE_OFF) update();
        }
    };

    private static boolean permitted() {
        return Build.VERSION.SDK_INT < 23
                || act.checkSelfPermission("android.permission.CAMERA") == PackageManager.PERMISSION_GRANTED;
    }

    private static void begin() {
        running = true;
        status = "Starting camera…";
        retryMs = 3_000;
        displayDeg = displayDegrees();
        if (tone == null) {
            try {
                tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90);
            } catch (Throwable ignored) {
            }
        }
        if (preview) addPreview();
        else openLater(0);
        main.removeCallbacks(watchdog);
        main.postDelayed(watchdog, 5_000L);
    }

    private static void end() {
        running = false;
        main.removeCallbacks(watchdog);
        main.removeCallbacks(permissionPoll);
        main.removeCallbacks(startOcr);
        if (cam != null) {
            cam.removeCallbacksAndMessages(null);
            cam.post(new Runnable() {
                @Override public void run() {
                    closeCamera();
                }
            });
        }
        removePreview();
        OcrEngine.stop();
        status = mode == MODE_OFF ? "Camera off" : "Camera paused";
    }

    // ---- preview (small picture so staff can aim) ----------------------------------------------

    private static void addPreview() {
        try {
            float d = act.getResources().getDisplayMetrics().density;
            final TextureView tv = new TextureView(act);
            FrameLayout frame = new FrameLayout(act);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFF000000);
            bg.setStroke((int) (2 * d), 0xCCF5B21B);
            bg.setCornerRadius(10 * d);
            frame.setBackground(bg);
            frame.setPadding((int) (2 * d), (int) (2 * d), (int) (2 * d), (int) (2 * d));
            frame.setClipToOutline(true);
            frame.addView(tv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams((int) (168 * d), (int) (112 * d),
                    Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            lp.topMargin = (int) (6 * d);
            frame.setElevation(35 * d);   // above the rider board (28), below the scan banner (40)
            frame.setFocusable(false);
            frame.setClickable(false);
            ((ViewGroup) act.findViewById(android.R.id.content)).addView(frame, lp);
            texture = tv;
            tv.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                    openLater(0);
                }

                @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
                    fitPreview();
                }

                @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                    return true;
                }

                @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}
            });
        } catch (Throwable t) {
            texture = null;
            openLater(0);
        }
    }

    private static void removePreview() {
        TextureView tv = texture;
        texture = null;
        if (tv == null) return;
        try {
            View frame = (View) tv.getParent();
            if (frame != null && frame.getParent() != null) ((ViewGroup) frame.getParent()).removeView(frame);
        } catch (Throwable ignored) {
        }
    }

    /** Keeps the small preview upright and undistorted (main thread). */
    private static void fitPreview() {
        final TextureView tv = texture;
        final int w = fw, h = fh;
        if (tv == null || w == 0) return;
        main.post(new Runnable() {
            @Override public void run() {
                try {
                    int vw = tv.getWidth(), vh = tv.getHeight();
                    if (vw == 0 || vh == 0) return;
                    int rot = act.getWindowManager().getDefaultDisplay().getRotation();
                    Matrix m = new Matrix();
                    RectF view = new RectF(0, 0, vw, vh);
                    float cx = view.centerX(), cy = view.centerY();
                    if (rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270) {
                        RectF buf = new RectF(0, 0, h, w);
                        buf.offset(cx - buf.centerX(), cy - buf.centerY());
                        m.setRectToRect(view, buf, Matrix.ScaleToFit.FILL);
                        float sc = Math.max((float) vh / h, (float) vw / w);
                        m.postScale(sc, sc, cx, cy);
                        m.postRotate(90 * (rot - 2), cx, cy);
                    } else if (rot == Surface.ROTATION_180) {
                        m.postRotate(180, cx, cy);
                    }
                    tv.setTransform(m);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    // ---- camera (camera thread) ------------------------------------------------------------

    private static void openLater(long delay) {
        if (cam == null) return;
        cam.postDelayed(new Runnable() {
            @Override public void run() {
                if (running && device == null) openCamera();
            }
        }, delay);
    }

    private static String pickCamera(CameraManager cm) throws CameraAccessException {
        String back = null, frontId = null, usb = null;
        for (String id : cm.getCameraIdList()) {
            Integer f = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (f == null) continue;
            if (f == CameraCharacteristics.LENS_FACING_BACK && back == null) back = id;
            else if (f == CameraCharacteristics.LENS_FACING_FRONT && frontId == null) frontId = id;
            else if (f != CameraCharacteristics.LENS_FACING_BACK && f != CameraCharacteristics.LENS_FACING_FRONT && usb == null) usb = id;
        }
        switch (which) {
            case CAM_BACK: return back;
            case CAM_FRONT: return frontId;
            case CAM_USB: return usb;
            default: return usb != null ? usb : back != null ? back : frontId;  // a plugged-in camera wins
        }
    }

    private static void openCamera() {
        try {
            CameraManager cm = (CameraManager) act.getApplicationContext().getSystemService(Context.CAMERA_SERVICE);
            String id = pickCamera(cm);
            if (id == null) {
                fail(which == CAM_USB ? "No USB camera found (plug it in)" : which == CAM_FRONT ? "No front camera"
                        : which == CAM_BACK ? "No back camera" : "No camera found");
                return;
            }
            CameraCharacteristics ch = cm.getCameraCharacteristics(id);
            Integer so = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
            Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
            sensorDeg = so == null ? 0 : so;
            front = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT;
            Boolean fl = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            hasFlash = fl != null && fl;
            int[] af = ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            autoFocusOnly = af != null && !contains(af, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    && contains(af, CaptureRequest.CONTROL_AF_MODE_AUTO);
            StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size size = chooseSize(map == null ? null : map.getOutputSizes(ImageFormat.YUV_420_888));
            fw = size.getWidth();
            fh = size.getHeight();
            reader = ImageReader.newInstance(fw, fh, ImageFormat.YUV_420_888, 3);
            reader.setOnImageAvailableListener(onImage, cam);
            final int[] afModes = af;
            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice d) {
                    if (!running) {
                        d.close();
                        return;
                    }
                    device = d;
                    startSession(afModes);
                }

                @Override public void onDisconnected(CameraDevice d) {
                    d.close();
                    if (device == d) device = null;
                    fail("Camera disconnected");
                }

                @Override public void onError(CameraDevice d, int error) {
                    d.close();
                    if (device == d) device = null;
                    fail(error == ERROR_CAMERA_IN_USE || error == ERROR_MAX_CAMERAS_IN_USE
                            ? "Camera is used by another app" : "Camera error " + error);
                }
            }, cam);
        } catch (Throwable t) {
            fail("Camera cannot start (" + t.getClass().getSimpleName() + ")");
        }
    }

    private static boolean contains(int[] a, int v) {
        if (a == null) return false;
        for (int x : a) if (x == v) return true;
        return false;
    }

    /** About 1280 x 720: enough pixels for thin KOT bars and phone text, light to process. */
    private static Size chooseSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return new Size(640, 480);
        Size best = null;
        long bestScore = Long.MAX_VALUE;
        for (Size s : sizes) {
            long area = (long) s.getWidth() * s.getHeight();
            long score = Math.abs(area - 1280L * 720L);
            if (s.getWidth() > 1920 || s.getWidth() < 640) score += 50_000_000L;
            if (Math.abs(s.getWidth() * 9 - s.getHeight() * 16) > s.getWidth()) score += 200_000L; // prefer 16:9
            if (score < bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return best;
    }

    private static void startSession(final int[] afModes) {
        try {
            List<Surface> targets = new ArrayList<Surface>();
            targets.add(reader.getSurface());
            TextureView tv = texture;
            previewSurface = null;
            if (tv != null && tv.isAvailable() && tv.getSurfaceTexture() != null) {
                SurfaceTexture st = tv.getSurfaceTexture();
                st.setDefaultBufferSize(fw, fh);
                previewSurface = new Surface(st);
                targets.add(previewSurface);
                fitPreview();
            }
            device.createCaptureSession(targets, new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession s) {
                    if (device == null) return;
                    session = s;
                    try {
                        request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        request.addTarget(reader.getSurface());
                        if (previewSurface != null) request.addTarget(previewSurface);
                        if (contains(afModes, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
                            request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                        } else if (contains(afModes, CaptureRequest.CONTROL_AF_MODE_AUTO)) {
                            request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
                        }
                        request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                        torchOn = hasFlash && flash == FLASH_ON;
                        request.set(CaptureRequest.FLASH_MODE, torchOn ? CaptureRequest.FLASH_MODE_TORCH
                                : CaptureRequest.FLASH_MODE_OFF);
                        s.setRepeatingRequest(request.build(), null, cam);
                        openedAt = SystemClock.uptimeMillis();
                        lastFrameAt = openedAt;
                        retryMs = 3_000;
                        status = "Camera on (" + (front ? "front" : "back/USB") + ", " + fw + "×" + fh + ")";
                        if (autoFocusOnly) cam.postDelayed(refocus, 2_500L);
                    } catch (Throwable t) {
                        fail("Camera cannot start (" + t.getClass().getSimpleName() + ")");
                    }
                }

                @Override public void onConfigureFailed(CameraCaptureSession s) {
                    fail("Camera cannot start");
                }
            }, cam);
        } catch (Throwable t) {
            fail("Camera cannot start (" + t.getClass().getSimpleName() + ")");
        }
    }

    /** Cameras without continuous focus: focus again every few seconds. */
    private static final Runnable refocus = new Runnable() {
        @Override public void run() {
            try {
                if (session == null || request == null) return;
                request.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
                session.capture(request.build(), null, cam);
                request.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
                cam.postDelayed(this, 2_500L);
            } catch (Throwable ignored) {
            }
        }
    };

    private static void setTorch(boolean on) {
        if (!hasFlash || torchOn == on || session == null || request == null) return;
        try {
            torchOn = on;
            request.set(CaptureRequest.FLASH_MODE, on ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
            session.setRepeatingRequest(request.build(), null, cam);
        } catch (Throwable ignored) {
        }
    }

    private static void fail(String why) {
        status = why;
        closeCamera();
        if (running) {
            openLater(retryMs);
            retryMs = Math.min(30_000, retryMs * 2);
        }
    }

    private static void closeCamera() {
        cam.removeCallbacks(refocus);
        try {
            if (session != null) session.close();
        } catch (Throwable ignored) {
        }
        session = null;
        try {
            if (device != null) device.close();
        } catch (Throwable ignored) {
        }
        device = null;
        try {
            if (reader != null) reader.close();
        } catch (Throwable ignored) {
        }
        reader = null;
        request = null;
        if (previewSurface != null) {
            try {
                previewSurface.release();
            } catch (Throwable ignored) {
            }
            previewSurface = null;
        }
        torchOn = false;
    }

    /** Camera stopped sending pictures, or the decoder hangs: start them again. */
    private static final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long now = SystemClock.uptimeMillis();
            if (device != null && session != null && now - lastFrameAt > 10_000L) {
                status = "Camera restarted (no picture)";
                cam.post(new Runnable() {
                    @Override public void run() {
                        closeCamera();
                        openLater(500);
                    }
                });
            }
            if (decoding && now - decodingSince > 15_000L) newDecodeThread();
            int deg = displayDegrees();
            if (deg != displayDeg) {
                displayDeg = deg;
                fitPreview();
            }
            main.postDelayed(this, 4_000L);
        }
    };

    private static int displayDegrees() {
        try {
            switch (act.getWindowManager().getDefaultDisplay().getRotation()) {
                case Surface.ROTATION_90: return 90;
                case Surface.ROTATION_180: return 180;
                case Surface.ROTATION_270: return 270;
                default: return 0;
            }
        } catch (Throwable t) {
            return 0;
        }
    }

    // ---- frames ------------------------------------------------------------------------------

    private static final ImageReader.OnImageAvailableListener onImage = new ImageReader.OnImageAvailableListener() {
        @Override public void onImageAvailable(ImageReader r) {
            Image img = null;
            try {
                img = r.acquireLatestImage();
                if (img == null) return;
                lastFrameAt = SystemClock.uptimeMillis();
                if (decoding) return;               // decoder busy: drop this frame, keep the newest
                int w = img.getWidth(), h = img.getHeight();
                Image.Plane p = img.getPlanes()[0];
                ByteBuffer b = p.getBuffer();
                int rs = p.getRowStride(), ps = p.getPixelStride();
                if (yBuf.length != w * h) yBuf = new byte[w * h];
                if (ps == 1 && rs == w) {
                    b.get(yBuf, 0, w * h);
                } else if (ps == 1) {
                    for (int y = 0; y < h; y++) {
                        b.position(y * rs);
                        b.get(yBuf, y * w, w);
                    }
                } else {
                    for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) yBuf[y * w + x] = b.get(y * rs + x * ps);
                }
                fw = w;
                fh = h;
                decoding = true;
                decodingSince = lastFrameAt;
                dec.post(decodeFrame);
            } catch (Throwable ignored) {
                decoding = false;
            } finally {
                if (img != null) {
                    try {
                        img.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    };

    private static final Runnable decodeFrame = new Runnable() {
        @Override public void run() {
            try {
                frameNo++;
                byte[] y = yBuf;
                int w = fw, h = fh;
                if (y.length < w * h) return;
                if (food) {
                    String code = decoder.decodeAnyWay(y, w, h);
                    if (code != null) onCode(code);
                }
                if (flash == FLASH_AUTO && hasFlash && frameNo % 10 == 0) autoTorch(y, w, h);
                long now = SystemClock.uptimeMillis();
                if (wantsOcr() && OcrEngine.ready() && !OcrEngine.busy() && now >= nextOcrAt) readText(y, w, h, now);
            } catch (Throwable ignored) {
            } finally {
                decoding = false;
            }
        }
    };

    private static void autoTorch(byte[] y, int w, int h) {
        long sum = 0;
        int n = 0;
        for (int i = 0; i < w * h; i += 97) {
            sum += y[i] & 0xFF;
            n++;
        }
        int mean = (int) (sum / Math.max(1, n));
        if (mean < 45) {
            brightFrames = 0;
            if (++darkFrames >= 3) cam.post(new Runnable() {
                @Override public void run() {
                    setTorch(true);
                }
            });
        } else if (mean > 120) {
            darkFrames = 0;
            if (++brightFrames >= 3) cam.post(new Runnable() {
                @Override public void run() {
                    setTorch(false);
                }
            });
        }
    }

    // ---- KOT barcode -> Food Ready ---------------------------------------------------------------

    private static void onCode(final String code) {
        long now = SystemClock.uptimeMillis();
        if (code.equals(lastCode) && now - lastCodeAt < 4_000L) {
            lastCodeAt = now;   // still in front of the camera: not again
            return;
        }
        lastCode = code;
        lastCodeAt = now;
        main.post(new Runnable() {
            @Override public void run() {
                try {
                    Activity a = act;
                    if (a == null) return;
                    int n = ScannerActivity.record(a, code, "Camera");
                    DosaLive.showScanBanner(a, ScannerActivity.lastSummary(), n > 0);
                    beep(n > 0 ? ToneGenerator.TONE_PROP_BEEP : ToneGenerator.TONE_PROP_NACK);
                    String f = KotDecoder.learned();
                    if (f != null) a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("cam_fmt", f).apply();
                    DosaLive.riderActivity();
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private static void beep(int t) {
        try {
            if (tone != null) tone.startTone(t, 150);
        } catch (Throwable ignored) {
        }
    }

    // ---- rider phone -> order details --------------------------------------------------------------

    /**
     * Cuts the middle of the frame, turns it upright, stretches the contrast and hands it to the
     * text reader as a small JPEG. Two framings take turns: a wide one and a close one (big text).
     */
    private static void readText(byte[] y, int w, int h, long now) {
        int rot = front ? (sensorDeg + displayDeg) % 360 : (sensorDeg - displayDeg + 360) % 360;
        boolean swap = rot == 90 || rot == 270;
        int uw = swap ? h : w, uh = swap ? w : h;          // upright frame size
        boolean wide = (ocrTurn++ & 1) == 0;
        float bw = wide ? 0.86f : 0.58f, bh = wide ? 0.74f : 0.46f;
        int cw = Math.round(uw * bw), ch = Math.round(uh * bh);
        int cx0 = (uw - cw) / 2, cy0 = (uh - ch) / 2;
        float scale = Math.min(1f, 1000f / Math.max(cw, ch));
        int ow = Math.max(1, Math.round(cw * scale)), oh = Math.max(1, Math.round(ch * scale));
        if (px.length != ow * oh) px = new int[ow * oh];
        int[] hist = new int[256];
        for (int oy = 0; oy < oh; oy++) {
            int uy = cy0 + (int) (oy / scale);
            for (int ox = 0; ox < ow; ox++) {
                int ux = cx0 + (int) (ox / scale);
                int sx, sy;
                switch (rot) {
                    case 90: sx = uy; sy = h - 1 - ux; break;
                    case 180: sx = w - 1 - ux; sy = h - 1 - uy; break;
                    case 270: sx = w - 1 - uy; sy = ux; break;
                    default: sx = ux; sy = uy;
                }
                int g = y[sy * w + sx] & 0xFF;
                px[oy * ow + ox] = g;
                hist[g]++;
            }
        }
        int total = ow * oh, lo = 0, hi = 255, acc = 0;
        while (lo < 255 && (acc += hist[lo]) < total / 50) lo++;
        acc = 0;
        while (hi > 0 && (acc += hist[hi]) < total / 50) hi--;
        if (hi - lo < 40) {                        // nothing but a plain surface: no text to read
            nextOcrAt = now + 400;
            return;
        }
        float k = 255f / (hi - lo);
        for (int i = 0; i < total; i++) {
            int g = (int) ((px[i] - lo) * k);
            g = g < 0 ? 0 : g > 255 ? 255 : g;
            px[i] = 0xFF000000 | (g << 16) | (g << 8) | g;
        }
        Bitmap bmp = Bitmap.createBitmap(px, ow, oh, Bitmap.Config.ARGB_8888);
        jpegOut.reset();
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, jpegOut);
        bmp.recycle();
        final long sent = now;
        boolean taken = OcrEngine.recognize(jpegOut.toByteArray(), wide ? 3 : 11, new OcrEngine.Callback() {
            @Override public void onText(final String text, final long ms) {
                Handler d = dec;
                if (d == null) return;
                d.post(new Runnable() {
                    @Override public void run() {
                        RiderCam.onText(text, ms, sent);
                    }
                });
            }
        });
        nextOcrAt = now + (taken ? 250 : 500);
    }

    private static void onText(String text, long ms, long sent) {
        long now = SystemClock.uptimeMillis();
        // Pace the reader by how fast this device is; slower while a card shows or nothing is seen.
        long gap = ms > 0 ? Math.max(300, Math.min(2_000, ms / 2)) : 800;
        if (showingKey != null) gap = Math.max(gap, 900);
        if (now - lastTextAt > 30_000L) gap = Math.max(gap, 1_200);
        nextOcrAt = now + gap;
        if (!wantsOcr() || text == null || text.isEmpty()) return;
        List<String> cands = RiderOcr.candidates(text);
        if (cands.isEmpty()) return;
        lastTextAt = now;
        List<RiderCalls.Order> orders = new ArrayList<RiderCalls.Order>();
        List<RiderCalls.Waiting> ready = boardReady;
        for (RiderCalls.Waiting w : ready) orders.add(w.order);
        orders.addAll(boardPrep);
        RiderOcr.Match m = RiderOcr.match(cands, orders);
        if (m != null) {
            String key = m.order.orderId.isEmpty() ? String.valueOf(m.order.id) : m.order.orderId;
            boolean twice = key.equals(prevKey) && now - prevAt < 6_000L;
            prevKey = key;
            prevAt = now;
            prevUnknown = null;
            if (!m.sure && !twice) return;
            long readyAt = 0;
            boolean isReady = false;
            for (RiderCalls.Waiting w : ready) {
                if (w.order == m.order) {
                    isReady = true;
                    readyAt = w.readyAt;
                }
            }
            showFound(key, m.order, isReady, readyAt);
            return;
        }
        String unknown = RiderOcr.unknownId(cands);
        if (unknown == null || !RiderOcr.labelled(text)) return;
        boolean twice = unknown.equals(prevUnknown) && now - prevUnknownAt < 6_000L;
        prevUnknown = unknown;
        prevUnknownAt = now;
        if (twice) showFound("?" + unknown, null, false, 0);
    }

    private static void showFound(final String key, final RiderCalls.Order order, final boolean ready, final long readyAt) {
        long now = SystemClock.uptimeMillis();
        if (key.equals(suppressKey) && now < suppressUntil) {
            suppressUntil = now + 8_000L;   // closed with OK and still in front of the camera: stay closed
            return;
        }
        final boolean fresh = !key.equals(showingKey);
        showingKey = key;
        final String unknown = order == null ? key.substring(1) : null;
        main.post(new Runnable() {
            @Override public void run() {
                try {
                    if (fresh) beep(order != null ? ToneGenerator.TONE_PROP_ACK : ToneGenerator.TONE_PROP_NACK);
                    DosaLive.showRiderLookup(order, ready, readyAt, unknown, fresh);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    /** For tests / debugging: the board orders used for matching. */
    static List<RiderCalls.Order> boardOrders() {
        List<RiderCalls.Order> out = new ArrayList<RiderCalls.Order>();
        for (RiderCalls.Waiting w : boardReady) out.add(w.order);
        out.addAll(boardPrep);
        return out;
    }

    static String debug() {
        return Arrays.toString(new int[]{fw, fh, sensorDeg, displayDeg});
    }
}
