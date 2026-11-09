package hu.pihokker.mesevetito;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;

/**
 * Az oldalak kirajzolása. Lapozáskor a két kép egy filmcsíkként mozog:
 * a régi felfelé kiúszik, az új alulról beúszik, egyszerre.
 */
final class ReaderView extends View {

    static final long SLIDE_MS = 1500;

    private static final int NONE = 0, SLIDE_FWD = 1, SLIDE_BACK = 2, FADE = 3;
    private static final int BG = Color.rgb(8, 7, 6);

    private Bitmap cur, other;
    private int mode = NONE;
    private float p;
    private float imageScale = 1f;
    private ValueAnimator anim;

    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint vignette = new Paint();
    private final RectF dst = new RectF();

    ReaderView(Context c) {
        super(c);
        setBackgroundColor(BG);
        // enyhén meleg tónus, mint a vetített diánál
        ColorMatrix cm = new ColorMatrix();
        cm.setScale(1f, 0.97f, 0.9f, 1f);
        bmpPaint.setColorFilter(new ColorMatrixColorFilter(cm));
        setFocusable(false);
    }

    void setImageScale(float s) {
        imageScale = s;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        float r = (float) Math.hypot(w / 2f, h / 2f);
        vignette.setShader(new RadialGradient(w / 2f, h / 2f, r,
                new int[]{0x00000000, 0x00000000, 0x8C000000},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
    }

    boolean isAnimating() {
        return anim != null && anim.isRunning();
    }

    Bitmap current() {
        return cur;
    }

    void setPage(Bitmap b) {
        cancel();
        cur = b;
        other = null;
        mode = NONE;
        invalidate();
    }

    void slideTo(Bitmap b, boolean forward, Runnable done) {
        if (cur == null) {
            setPage(b);
            if (done != null) done.run();
            return;
        }
        cancel();
        other = b;
        mode = forward ? SLIDE_FWD : SLIDE_BACK;
        run(SLIDE_MS, new AccelerateDecelerateInterpolator(), done);
    }

    void fadeTo(Bitmap b, long ms, Runnable done) {
        if (cur == null) {
            setPage(b);
            if (done != null) done.run();
            return;
        }
        cancel();
        other = b;
        mode = FADE;
        run(ms, new DecelerateInterpolator(), done);
    }

    private void run(long ms, android.animation.TimeInterpolator ip, Runnable done) {
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(ms);
        anim.setInterpolator(ip);
        anim.addUpdateListener(a -> {
            p = (float) a.getAnimatedValue();
            invalidate();
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            boolean cancelled;

            @Override
            public void onAnimationCancel(android.animation.Animator a) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                if (cancelled) return;
                cur = other;
                other = null;
                mode = NONE;
                p = 0f;
                invalidate();
                if (done != null) done.run();
            }
        });
        anim.start();
    }

    private void cancel() {
        if (anim != null) {
            anim.cancel();
            anim = null;
        }
        if (other != null) {
            cur = other;
            other = null;
        }
        mode = NONE;
        p = 0f;
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        float gap = h * 0.06f;      // fekete sáv a két kocka között
        float dist = h + gap;
        float dy = p * dist;

        switch (mode) {
            case SLIDE_FWD:
                draw(c, cur, -dy, 255);
                draw(c, other, dist - dy, 255);
                break;
            case SLIDE_BACK:
                draw(c, cur, dy, 255);
                draw(c, other, -dist + dy, 255);
                break;
            case FADE:
                draw(c, cur, 0, Math.round(255 * (1 - p)));
                draw(c, other, 0, Math.round(255 * p));
                break;
            default:
                draw(c, cur, 0, 255);
        }
        c.drawRect(0, 0, w, h, vignette);
    }

    private void draw(Canvas c, Bitmap b, float dy, int alpha) {
        if (b == null || alpha <= 0) return;
        float bw = getWidth() * imageScale, bh = getHeight() * imageScale;
        float s = Math.min(bw / b.getWidth(), bh / b.getHeight());
        float iw = b.getWidth() * s, ih = b.getHeight() * s;
        float left = (getWidth() - iw) / 2f, top = (getHeight() - ih) / 2f + dy;
        if (top > getHeight() || top + ih < 0) return;
        dst.set(left, top, left + iw, top + ih);
        bmpPaint.setAlpha(alpha);
        c.drawBitmap(b, null, dst, bmpPaint);
    }
}
