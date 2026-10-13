package com.babai.scanner;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Back camera -> preview on a TextureView + frames to the barcode reader on its own thread.
 * Frames that arrive while the reader is busy are dropped (the newest frame always wins), so the
 * preview never stutters. Continuous autofocus, torch on request, restarts itself if it stalls.
 */
final class CamScan {

    interface Callback {
        void onCode(String text);
        void onState(String s);
    }

    private final Activity act;
    private final TextureView view;
    private final Callback cb;
    private final Decoder decoder;
    private HandlerThread camThread, decThread;
    private Handler cam, dec;
    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader reader;
    private CaptureRequest.Builder req;
    private volatile boolean decoding, running, torch;
    private byte[] plane;
    private Size size;
    private long lastFrameAt;

    CamScan(Activity a, TextureView v, boolean qr, Callback c) {
        act = a;
        view = v;
        cb = c;
        decoder = new Decoder(qr);
        decoder.setLearned(Pairing.prefs(a).getString("fmt", null));
    }

    void start() {
        if (running) return;
        running = true;
        camThread = new HandlerThread("babai-cam");
        camThread.start();
        cam = new Handler(camThread.getLooper());
        decThread = new HandlerThread("babai-decode");
        decThread.start();
        dec = new Handler(decThread.getLooper());
        if (view.isAvailable()) open();
        else view.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) { open(); }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { transform(); }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}
        });
        cam.postDelayed(watchdog, 6_000L);
    }

    void stop() {
        running = false;
        if (cam != null) cam.removeCallbacks(watchdog);
        closeCamera();
        if (camThread != null) camThread.quitSafely();
        if (decThread != null) decThread.quitSafely();
        camThread = decThread = null;
        String f = decoder.learned();
        if (f != null) Pairing.prefs(act).edit().putString("fmt", f).apply();
    }

    boolean torch() {
        return torch;
    }

    void setTorch(boolean on) {
        torch = on;
        if (cam == null) return;
        cam.post(new Runnable() {
            @Override public void run() {
                try {
                    if (req != null && session != null) {
                        req.set(CaptureRequest.FLASH_MODE, torch ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
                        session.setRepeatingRequest(req.build(), null, cam);
                    }
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (!running) return;
            if (device != null && lastFrameAt > 0 && SystemClock.uptimeMillis() - lastFrameAt > 5_000L) {
                closeCamera();      // frames stopped: start the camera again
                open();
            }
            cam.postDelayed(this, 4_000L);
        }
    };

    @SuppressLint("MissingPermission")
    private void open() {
        if (cam == null) return;
        cam.post(new Runnable() {
            @Override public void run() {
                try {
                    CameraManager cm = (CameraManager) act.getSystemService(Context.CAMERA_SERVICE);
                    String id = null;
                    for (String c : cm.getCameraIdList()) {
                        Integer f = cm.getCameraCharacteristics(c).get(CameraCharacteristics.LENS_FACING);
                        if (f != null && f == CameraCharacteristics.LENS_FACING_BACK) { id = c; break; }
                        if (id == null) id = c;
                    }
                    if (id == null) { state("No camera on this device"); return; }
                    CameraCharacteristics ch = cm.getCameraCharacteristics(id);
                    StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    size = pick(map.getOutputSizes(ImageFormat.YUV_420_888));
                    reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 3);
                    reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                        @Override public void onImageAvailable(ImageReader r) { frame(r); }
                    }, cam);
                    act.runOnUiThread(new Runnable() { @Override public void run() { transform(); } });
                    cm.openCamera(id, new CameraDevice.StateCallback() {
                        @Override public void onOpened(CameraDevice d) {
                            device = d;
                            startSession();
                        }
                        @Override public void onDisconnected(CameraDevice d) {
                            d.close();
                            device = null;
                        }
                        @Override public void onError(CameraDevice d, int e) {
                            d.close();
                            device = null;
                            state("Camera error " + e + " – retrying");
                            if (running) cam.postDelayed(new Runnable() { @Override public void run() { open(); } }, 2_000L);
                        }
                    }, cam);
                } catch (SecurityException e) {
                    state("Allow the camera for Babai Scanner");
                } catch (Throwable t) {
                    state("Camera: " + t.getClass().getSimpleName());
                }
            }
        });
    }

    /** About 1280x720: sharp enough for small KOT barcodes, light enough for any phone. */
    private static Size pick(Size[] sizes) {
        Size best = sizes[0];
        long bestScore = Long.MAX_VALUE;
        for (Size s : sizes) {
            long score = Math.abs((long) s.getWidth() * s.getHeight() - 1280L * 720L) + (s.getWidth() * 9 == s.getHeight() * 16 ? 0 : 200_000);
            if (score < bestScore) { bestScore = score; best = s; }
        }
        return best;
    }

    private void startSession() {
        try {
            SurfaceTexture st = view.getSurfaceTexture();
            if (st == null || device == null) return;
            st.setDefaultBufferSize(size.getWidth(), size.getHeight());
            Surface preview = new Surface(st);
            req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            req.addTarget(preview);
            req.addTarget(reader.getSurface());
            req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            req.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            req.set(CaptureRequest.FLASH_MODE, torch ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
            device.createCaptureSession(Arrays.asList(preview, reader.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession s) {
                    session = s;
                    try {
                        s.setRepeatingRequest(req.build(), null, cam);
                        state("");
                    } catch (CameraAccessException | IllegalStateException e) {
                        state("Camera busy – retrying");
                    }
                }
                @Override public void onConfigureFailed(CameraCaptureSession s) {
                    state("Camera couldn't start");
                }
            }, cam);
        } catch (Throwable t) {
            state("Camera: " + t.getClass().getSimpleName());
        }
    }

    private void frame(ImageReader r) {
        Image img = null;
        try {
            img = r.acquireLatestImage();
            if (img == null) return;
            lastFrameAt = SystemClock.uptimeMillis();
            if (decoding) return;   // reader busy: drop this frame
            Image.Plane p = img.getPlanes()[0];
            final int w = img.getWidth(), h = img.getHeight();
            int rowStride = p.getRowStride();
            ByteBuffer buf = p.getBuffer();
            if (plane == null || plane.length != w * h) plane = new byte[w * h];
            if (rowStride == w) buf.get(plane, 0, Math.min(plane.length, buf.remaining()));
            else for (int y = 0; y < h; y++) { buf.position(y * rowStride); buf.get(plane, y * w, w); }
            final byte[] data = plane;
            decoding = true;
            dec.post(new Runnable() {
                @Override public void run() {
                    try {
                        final String t = decoder.decode(data, w, h);
                        if (t != null) act.runOnUiThread(new Runnable() { @Override public void run() { cb.onCode(t); } });
                    } catch (Throwable ignored) {
                    } finally {
                        decoding = false;
                    }
                }
            });
        } catch (Throwable ignored) {
        } finally {
            if (img != null) img.close();
        }
    }

    /** Fill the view with the picture, turned to match the screen. */
    private void transform() {
        if (size == null || view.getWidth() == 0) return;
        int rot = act.getWindowManager().getDefaultDisplay().getRotation();
        Matrix m = new Matrix();
        float vw = view.getWidth(), vh = view.getHeight();
        RectF vr = new RectF(0, 0, vw, vh);
        boolean portrait = rot == Surface.ROTATION_0 || rot == Surface.ROTATION_180;
        float bw = portrait ? size.getHeight() : size.getWidth(), bh = portrait ? size.getWidth() : size.getHeight();
        RectF br = new RectF(0, 0, bw, bh);
        br.offset(vr.centerX() - br.centerX(), vr.centerY() - br.centerY());
        if (!portrait) {
            m.setRectToRect(vr, br, Matrix.ScaleToFit.FILL);
            float sc = Math.max(vh / size.getHeight(), vw / size.getWidth());
            m.postScale(sc, sc, vr.centerX(), vr.centerY());
            m.postRotate(90 * (rot - 2), vr.centerX(), vr.centerY());
        } else {
            float sc = Math.max(vw / bw, vh / bh);
            m.setScale(sc * bw / vw, sc * bh / vh, vr.centerX(), vr.centerY());
            if (rot == Surface.ROTATION_180) m.postRotate(180, vr.centerX(), vr.centerY());
        }
        view.setTransform(m);
    }

    private void closeCamera() {
        try {
            if (session != null) session.close();
        } catch (Throwable ignored) {
        }
        try {
            if (device != null) device.close();
        } catch (Throwable ignored) {
        }
        try {
            if (reader != null) reader.close();
        } catch (Throwable ignored) {
        }
        session = null;
        device = null;
        reader = null;
    }

    private void state(final String s) {
        act.runOnUiThread(new Runnable() { @Override public void run() { cb.onState(s); } });
    }
}
