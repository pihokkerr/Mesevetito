package hu.pihokker.mesevetito;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

import java.util.List;

/** Gyors lapozáskor alul megjelenő képsor az oldalak kis képeivel és az oldalszámmal. */
final class FilmStripView extends View {

    private static final int CREAM = Color.rgb(243, 230, 200);
    private static final int CANDLE = Color.rgb(232, 200, 138);

    private List<Node> pages;
    private int center;
    private ThumbCache thumbs;

    private final Paint bg = new Paint();
    private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint slot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint num = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    FilmStripView(Context c) {
        super(c);
        setFocusable(false);
        slot.setColor(Color.rgb(40, 34, 28));
        frame.setStyle(Paint.Style.STROKE);
        frame.setColor(CANDLE);
        num.setColor(Color.rgb(150, 135, 110));
        num.setTextAlign(Paint.Align.CENTER);
        label.setColor(CREAM);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
    }

    void bind(List<Node> pages, ThumbCache thumbs) {
        this.pages = pages;
        this.thumbs = thumbs;
    }

    void setCenter(int i) {
        center = i;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        bg.setShader(new LinearGradient(0, 0, 0, h, 0x00000000, 0xE6000000, Shader.TileMode.CLAMP));
        frame.setStrokeWidth(Math.max(2f, h * 0.012f));
        num.setTextSize(h * 0.1f);
        label.setTextSize(h * 0.13f);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (pages == null || pages.isEmpty()) return;
        int w = getWidth(), h = getHeight();
        c.drawRect(0, 0, w, h, bg);

        float slotH = h * 0.5f;
        float slotW = slotH * 1.4f;
        float gapX = slotH * 0.18f;
        float cy = h * 0.62f;
        int side = (int) Math.ceil((w / 2f) / (slotW + gapX)) + 1;

        for (int k = -side; k <= side; k++) {
            int i = center + k;
            if (i < 0 || i >= pages.size()) continue;
            boolean sel = k == 0;
            float sw = sel ? slotW * 1.18f : slotW;
            float sh = sel ? slotH * 1.18f : slotH;
            float cx = w / 2f + k * (slotW + gapX) + (k == 0 ? 0 : Math.signum(k) * slotW * 0.09f);
            r.set(cx - sw / 2, cy - sh / 2, cx + sw / 2, cy + sh / 2);

            Node n = pages.get(i);
            Bitmap b = thumbs.get(n);
            if (b == null) thumbs.request(n, Math.abs(k) <= 3);
            int alpha = sel ? 255 : Math.max(60, 200 - Math.abs(k) * 30);

            if (b != null) {
                float s = Math.min(sw / b.getWidth(), sh / b.getHeight());
                float iw = b.getWidth() * s, ih = b.getHeight() * s;
                RectF ir = new RectF(cx - iw / 2, cy - ih / 2, cx + iw / 2, cy + ih / 2);
                bmp.setAlpha(alpha);
                c.drawBitmap(b, null, ir, bmp);
                if (sel) c.drawRoundRect(ir, 4, 4, frame);
            } else {
                slot.setAlpha(alpha);
                c.drawRoundRect(r, 6, 6, slot);
                num.setAlpha(alpha);
                c.drawText(String.valueOf(i + 1), cx, cy + num.getTextSize() / 3, num);
                if (sel) c.drawRoundRect(r, 6, 6, frame);
            }
        }
        c.drawText((center + 1) + " / " + pages.size(), w / 2f, cy - slotH * 0.75f - h * 0.04f, label);
    }
}
