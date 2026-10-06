package com.pp.kds.dosa;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

/**
 * Food-photo slideshow background that is cheap enough for TV boxes.
 *
 * Each slide (base colour + photo + tint/glow layers) is painted ONCE into a half-resolution
 * bitmap; every frame then only draws that one bitmap (with the slow zoom/pan), instead of
 * 4-5 full-screen layers with gradients. The next slide is prepared a moment before the
 * cross-fade starts, so there is no hiccup at the change.
 */
final class SlideBackdrop {

    interface Painter {
        /** Paint slide {@code k} (photo may be null) into a {@code w x h} canvas. */
        void paint(Canvas c, int w, int h, int k, Bitmap photo);
    }

    private final Bitmap[] photos;
    private final float slide, fade;
    private final Bitmap[] pool = new Bitmap[3];
    private final int[] poolK = {-1, -1, -1};
    private int bw, bh;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF dst = new RectF();

    SlideBackdrop(Bitmap[] photos, float slideSeconds, float fadeSeconds) {
        this.photos = photos;
        slide = slideSeconds;
        fade = fadeSeconds;
    }

    /** Colours changed (theme): repaint the slides. */
    void invalidate() {
        poolK[0] = poolK[1] = poolK[2] = -1;
    }

    int count() {
        return photos == null ? 0 : photos.length;
    }

    void draw(Canvas c, float w, float h, float t, Painter painter) {
        int tw = Math.max(1, (int) (w / 2)), th = Math.max(1, (int) (h / 2));
        if (tw != bw || th != bh) {
            for (int i = 0; i < pool.length; i++) {
                if (pool[i] != null) pool[i].recycle();
                pool[i] = null;
                poolK[i] = -1;
            }
            bw = tw;
            bh = th;
        }
        int k = (int) (t / slide);
        float local = t - k * slide;
        drawSlide(c, w, h, t, k, get(k, painter), 255);
        if (local > slide - fade) {
            drawSlide(c, w, h, t, k + 1, get(k + 1, painter), (int) (255 * (local - (slide - fade)) / fade));
        } else if (local > slide - fade - 2f) {
            get(k + 1, painter); // prepare ahead of the cross-fade
        }
    }

    private Bitmap get(int k, Painter painter) {
        int slot = ((k % 3) + 3) % 3;
        if (poolK[slot] != k || pool[slot] == null) {
            if (pool[slot] == null) pool[slot] = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            Canvas cc = new Canvas(pool[slot]);
            int n = count();
            painter.paint(cc, bw, bh, k, n == 0 ? null : photos[((k % n) + n) % n]);
            poolK[slot] = k;
        }
        return pool[slot];
    }

    /** Slow Ken Burns zoom / pan of the prepared slide. */
    private void drawSlide(Canvas c, float w, float h, float t, int k, Bitmap b, int alpha) {
        float p = Math.max(0f, Math.min(1f, (t - k * slide) / (slide + fade)));
        float sc = (w / bw) * (1.04f + 0.08f * p);
        float dw = bw * sc, dh = bh * sc;
        int dir = ((k % 4) + 4) % 4;
        float px = (dir == 0 || dir == 3 ? 1 : -1) * (p - 0.5f) * 0.9f;
        float py = (dir < 2 ? 1 : -1) * (p - 0.5f) * 0.6f;
        float x = (w - dw) / 2f + (dw - w) / 2f * px;
        float y = (h - dh) / 2f + (dh - h) / 2f * py;
        dst.set(x, y, x + dw, y + dh);
        paint.setAlpha(alpha);
        c.drawBitmap(b, null, dst, paint);
    }

    /** Draws {@code photo} to cover the whole {@code w x h} canvas (centre crop). */
    static void cover(Canvas c, Bitmap photo, int w, int h, Paint p) {
        if (photo == null) return;
        float sc = Math.max(w / (float) photo.getWidth(), h / (float) photo.getHeight());
        float dw = photo.getWidth() * sc, dh = photo.getHeight() * sc;
        RectF r = new RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f);
        c.drawBitmap(photo, null, r, p);
    }
}
