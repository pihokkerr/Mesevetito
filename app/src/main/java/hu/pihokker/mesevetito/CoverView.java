package hu.pihokker.mesevetito;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/**
 * Egy könyv borítója a könyvtárban, vagy egy gyűjteményé: ott a benne lévő könyvek
 * borítóiból készül mozaik, mögötte két halvány "lap", mintha egy köteg könyv lenne.
 */
final class CoverView extends View {

    private static final int CANDLE = Color.rgb(232, 200, 138);

    private Bitmap bitmap;
    private Bitmap[] tiles;     // gyűjteménynél: max. 4 borító (lehet benne null, ha még készül)
    private int tileCount;      // hány könyv van (max. 4 számít a mozaikhoz)
    private String initial = "";
    private float progress;     // 0..1, hol tartottatok
    private boolean highlighted;

    private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint placeholder = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tileBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint letter = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint layer = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final RectF cell = new RectF();
    private final Rect src = new Rect();
    private final Path clip = new Path();

    CoverView(Context c) {
        super(c);
        setFocusable(false);
        placeholder.setColor(Color.rgb(32, 30, 32));
        tileBg.setColor(Color.rgb(26, 24, 26));
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

    /** Egy könyv. */
    void bind(Bitmap b, String title, float progress) {
        this.bitmap = b;
        this.tiles = null;
        this.initial = title.isEmpty() ? "" : title.substring(0, 1).toUpperCase();
        this.progress = progress;
        invalidate();
    }

    /** Gyűjtemény: a benne lévő első (max. 4) könyv borítója. */
    void bindMosaic(Bitmap[] tiles, int bookCount, String title) {
        this.bitmap = null;
        this.tiles = tiles;
        this.tileCount = Math.max(1, Math.min(4, bookCount));
        this.initial = title.isEmpty() ? "" : title.substring(0, 1).toUpperCase();
        this.progress = 0f;
        invalidate();
    }

    void setBitmap(Bitmap b) {
        bitmap = b;
        invalidate();
    }

    void setTile(int i, Bitmap b) {
        if (tiles != null && i >= 0 && i < tiles.length) {
            tiles[i] = b;
            invalidate();
        }
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
        boolean mosaic = tiles != null;

        if (mosaic) {
            // a köteg "lapjainak" hagyunk helyet jobbra fent
            float off = h * 0.035f;
            float bw = boxW - 2 * off, bh = boxH - 2 * off;
            float iw = Math.min(bw, bh * 1.37f), ih = iw / 1.37f;
            float cx = w / 2f - off, cy = h / 2f + off;
            r.set(cx - iw / 2, cy - ih / 2, cx + iw / 2, cy + ih / 2);
            int a = highlighted ? 255 : 170;
            layer.setColor(Color.rgb(40, 37, 38));
            layer.setAlpha(a);
            c.drawRoundRect(r.left + 2 * off, r.top - 2 * off, r.right + 2 * off, r.bottom - 2 * off,
                    radius, radius, layer);
            layer.setColor(Color.rgb(54, 50, 50));
            layer.setAlpha(a);
            c.drawRoundRect(r.left + off, r.top - off, r.right + off, r.bottom - off,
                    radius, radius, layer);
        } else if (bitmap != null) {
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

        // halvány fény a kijelölt borító körül
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
        bmp.setAlpha(highlighted ? 255 : 150); // a többi könyv halkabb, kevesebb fény este
        if (mosaic) {
            drawMosaic(c);
        } else if (bitmap != null) {
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

    private void drawMosaic(Canvas c) {
        c.drawRect(r, tileBg);
        float g = Math.max(2f, r.height() * 0.022f); // rés a kis borítók között
        float x0 = r.left, y0 = r.top, x1 = r.right, y1 = r.bottom;
        float mx = (x0 + x1) / 2f, my = (y0 + y1) / 2f;
        switch (tileCount) {
            case 1:
                tile(c, 0, x0, y0, x1, y1);
                break;
            case 2:
                tile(c, 0, x0, y0, mx - g / 2, y1);
                tile(c, 1, mx + g / 2, y0, x1, y1);
                break;
            case 3:
                tile(c, 0, x0, y0, mx - g / 2, y1);
                tile(c, 1, mx + g / 2, y0, x1, my - g / 2);
                tile(c, 2, mx + g / 2, my + g / 2, x1, y1);
                break;
            default:
                tile(c, 0, x0, y0, mx - g / 2, my - g / 2);
                tile(c, 1, mx + g / 2, y0, x1, my - g / 2);
                tile(c, 2, x0, my + g / 2, mx - g / 2, y1);
                tile(c, 3, mx + g / 2, my + g / 2, x1, y1);
        }
    }

    /** Egy kis borító a cellába, középre vágva (kitölti a cellát). */
    private void tile(Canvas c, int i, float l, float t, float rr, float b) {
        cell.set(l, t, rr, b);
        Bitmap bm = i < tiles.length ? tiles[i] : null;
        if (bm == null) {
            c.drawRect(cell, placeholder);
            return;
        }
        float cw = cell.width(), ch = cell.height();
        float s = Math.max(cw / bm.getWidth(), ch / bm.getHeight());
        int sw = Math.round(cw / s), sh = Math.round(ch / s);
        int sx = (bm.getWidth() - sw) / 2, sy = (bm.getHeight() - sh) / 2;
        src.set(sx, sy, sx + sw, sy + sh);
        c.drawBitmap(bm, src, cell, bmp);
    }
}
