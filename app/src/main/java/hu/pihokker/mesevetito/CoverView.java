package hu.pihokker.mesevetito;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/** Egy könyv borítója a könyvtárban: lekerekített kép, kijelöléskor meleg fénykeret. */
final class CoverView extends View {

    private static final int CANDLE = Color.rgb(232, 200, 138);

    private Bitmap bitmap;
    private String initial = "";
    private float progress; // 0..1, hol tartottatok
    private boolean highlighted;

    private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint placeholder = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint letter = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path clip = new Path();

    CoverView(Context c) {
        super(c);
        setFocusable(false);
        placeholder.setColor(Color.rgb(32, 30, 32));
        letter.setColor(Color.rgb(96, 88, 78));
        letter.setTextAlign(Paint.Align.CENTER);
        letter.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        frame.setStyle(Paint.Style.STROKE);
        frame.setColor(Color.argb(200, 243, 230, 200));
        glow.setStyle(Paint.Style.STROKE);
        glow.setColor(Color.argb(40, 232, 200, 138));
        shadow.setColor(Color.argb(90, 0, 0, 0));
        bar.setColor(CANDLE);
    }

    void bind(Bitmap b, String title, float progress) {
        this.bitmap = b;
        this.initial = title.isEmpty() ? "" : title.substring(0, 1).toUpperCase();
        this.progress = progress;
        invalidate();
    }

    void setBitmap(Bitmap b) {
        bitmap = b;
        invalidate();
    }

    void setHighlighted(boolean h) {
        if (highlighted != h) {
            highlighted = h;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float pad = h * 0.04f;          // hely a fénynek
        float boxW = w - 2 * pad, boxH = h - 2 * pad;
        float radius = h * 0.025f;

        if (bitmap != null) {
            float s = Math.min(boxW / bitmap.getWidth(), boxH / bitmap.getHeight());
            float iw = bitmap.getWidth() * s, ih = bitmap.getHeight() * s;
            r.set((w - iw) / 2, (h - ih) / 2, (w + iw) / 2, (h + ih) / 2);
        } else {
            float iw = boxH * 1.37f > boxW ? boxW : boxH * 1.37f;
            r.set((w - iw) / 2, pad, (w + iw) / 2, h - pad);
        }

        // árnyék
        c.drawRoundRect(r.left + pad * 0.3f, r.top + pad * 0.5f, r.right + pad * 0.3f,
                r.bottom + pad * 0.5f, radius, radius, shadow);

        // fény a kijelölt borító körül
        if (highlighted) {
            float o = pad * 0.45f;
            glow.setStrokeWidth(pad * 0.5f);
            c.drawRoundRect(r.left - o, r.top - o, r.right + o, r.bottom + o,
                    radius + o, radius + o, glow);
        }

        c.save();
        clip.reset();
        clip.addRoundRect(r, radius, radius, Path.Direction.CW);
        c.clipPath(clip);
        if (bitmap != null) {
            bmp.setAlpha(highlighted ? 255 : 150); // a többi könyv halkabb, kevesebb fény este
            c.drawBitmap(bitmap, null, r, bmp);
        } else {
            c.drawRect(r, placeholder);
            letter.setTextSize(r.height() * 0.4f);
            c.drawText(initial, r.centerX(), r.centerY() + letter.getTextSize() / 3, letter);
        }
        // hol tartottatok: vékony csík alul
        if (progress > 0f) {
            float bh = Math.max(3f, h * 0.014f);
            bar.setAlpha(highlighted ? 210 : 120);
            c.drawRect(r.left, r.bottom - bh, r.left + r.width() * progress, r.bottom, bar);
        }
        c.restore();

        if (highlighted) {
            frame.setStrokeWidth(Math.max(1.5f, pad * 0.14f));
            c.drawRoundRect(r, radius, radius, frame);
        }
    }
}
