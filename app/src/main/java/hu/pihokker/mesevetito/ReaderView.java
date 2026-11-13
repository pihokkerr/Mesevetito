package hu.pihokker.mesevetito;

import android.animation.TimeInterpolator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;

/**
 * Az oldalak megjelenítése. Két külön képréteg van; lapozáskor nem rajzolunk újra semmit,
 * csak a rétegek helyzetét mozgatja a grafikus chip (translationY) – ez a gyenge
 * projektoron is egyenletes. A régi oldal felfelé kiúszik, az új alulról beúszik, egyszerre.
 */
final class ReaderView extends FrameLayout {

    private static final int BG = Color.rgb(8, 7, 6);

    /** Egy oldal: a kép középre, arányosan, a beállított méretben. */
    private static final class PageView extends View {
        Bitmap bitmap;
        float scale = 1f;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final RectF dst = new RectF();

        PageView(Context c) {
            super(c);
            setFocusable(false);
        }

        void setBitmap(Bitmap b) {
            bitmap = b;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            if (bitmap == null) return;
            float bw = getWidth() * scale, bh = getHeight() * scale;
            float s = Math.min(bw / bitmap.getWidth(), bh / bitmap.getHeight());
            float iw = bitmap.getWidth() * s, ih = bitmap.getHeight() * s;
            float l = (getWidth() - iw) / 2f, t = (getHeight() - ih) / 2f;
            dst.set(l, t, l + iw, t + ih);
            c.drawBitmap(bitmap, null, dst, paint);
        }
    }

    /** Enyhe sötétedés a szélek felé, mint egy vetített diánál. Egyszer rajzolódik. */
    private static final class Vignette extends View {
        private final Paint p = new Paint();

        Vignette(Context c) {
            super(c);
            setFocusable(false);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            float r = (float) Math.hypot(w / 2f, h / 2f);
            p.setShader(new RadialGradient(w / 2f, h / 2f, r,
                    new int[]{0x00000000, 0x00000000, 0x8C000000},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        }

        @Override
        protected void onDraw(Canvas c) {
            c.drawRect(0, 0, getWidth(), getHeight(), p);
        }
    }

    // lágy indulás és nagyon lassú megérkezés, mint a kézzel tekert diafilm
    private static final TimeInterpolator FILM = new PathInterpolator(0.42f, 0f, 0.22f, 1f);

    private final PageView[] pages = new PageView[2];
    private int front = 0;
    private long slideMs = 2600;
    private boolean animating;
    private int token;
    private Runnable pendingDone;

    ReaderView(Context c) {
        super(c);
        setBackgroundColor(BG);
        setFocusable(false);
        for (int i = 0; i < 2; i++) {
            pages[i] = new PageView(c);
            addView(pages[i], new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }
        pages[1].setVisibility(INVISIBLE);
        Vignette v = new Vignette(c);
        v.setLayerType(LAYER_TYPE_HARDWARE, null); // kész textúra, nem rajzolódik újra
        addView(v, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    void setSlideMs(long ms) {
        slideMs = ms;
    }

    void setImageScale(float s) {
        for (PageView p : pages) {
            p.scale = s;
            p.invalidate();
        }
    }

    boolean isAnimating() {
        return animating;
    }

    Bitmap current() {
        return pages[front].bitmap;
    }

    private PageView cur() {
        return pages[front];
    }

    private PageView back() {
        return pages[1 - front];
    }

    void setPage(Bitmap b) {
        cancel();
        PageView c = cur();
        c.setBitmap(b);
        c.setTranslationY(0);
        c.setAlpha(1f);
        c.setVisibility(VISIBLE);
        back().setBitmap(null);
        back().setVisibility(INVISIBLE);
    }

    void slideTo(Bitmap b, boolean forward, Runnable done) {
        if (cur().bitmap == null) {
            setPage(b);
            if (done != null) done.run();
            return;
        }
        cancel();
        final float dist = getHeight() * 1.06f; // kis fekete sáv a két kocka között
        final PageView out = cur(), in = back();
        in.setBitmap(b);
        in.setAlpha(1f);
        in.setTranslationY(forward ? dist : -dist);
        in.setVisibility(VISIBLE);
        animating = true;
        pendingDone = done;
        final int t = ++token;
        // két képkockányi várakozás: addig a chip feltölti az új képet, így az indulás nem akad
        postOnAnimation(() -> postOnAnimation(() -> {
            if (t != token) return;
            out.animate().translationY(forward ? -dist : dist)
                    .setDuration(slideMs).setInterpolator(FILM).start();
            in.animate().translationY(0)
                    .setDuration(slideMs).setInterpolator(FILM)
                    .withEndAction(() -> {
                        if (t == token) finish();
                    }).start();
        }));
    }

    void fadeTo(Bitmap b, long ms, Runnable done) {
        if (cur().bitmap == null) {
            setPage(b);
            if (done != null) done.run();
            return;
        }
        cancel();
        final PageView in = back();
        in.setBitmap(b);
        in.setTranslationY(0);
        in.setAlpha(0f);
        in.setVisibility(VISIBLE);
        animating = true;
        pendingDone = done;
        final int t = ++token;
        postOnAnimation(() -> {
            if (t != token) return;
            in.animate().alpha(1f).setDuration(ms).setInterpolator(new DecelerateInterpolator())
                    .withEndAction(() -> {
                        if (t == token) finish();
                    }).start();
        });
    }

    /** Az animáció vége: a beúszott oldal lesz az aktuális. */
    private void finish() {
        PageView old = cur();
        front = 1 - front;
        old.animate().cancel();
        old.setVisibility(INVISIBLE);
        old.setBitmap(null);
        old.setTranslationY(0);
        old.setAlpha(1f);
        PageView c = cur();
        c.setTranslationY(0);
        c.setAlpha(1f);
        animating = false;
        Runnable d = pendingDone;
        pendingDone = null;
        if (d != null) d.run();
    }

    /** Futó animáció azonnali befejezése (pl. könyv bezárásakor). */
    private void cancel() {
        token++;
        if (!animating) return;
        for (PageView p : pages) p.animate().cancel();
        pendingDone = null;
        finish();
    }
}
