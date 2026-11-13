package hu.pihokker.mesevetito;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.RectF;

import java.io.IOException;
import java.io.InputStream;

/**
 * Képek dekódolása. A projektor dekódere a kicsinyítve (inSampleSize) betöltött JPEG-eket
 * néha hibásan adja vissza (zöld-lila csíkok), ezért teljes méretben töltünk be, és
 * szoftveresen kicsinyítünk. Csak óriási képeknél felezünk.
 */
final class Images {

    private static final ColorMatrixColorFilter WARM;

    static {
        // enyhén meleg tónus, mint a vetített diánál
        ColorMatrix cm = new ColorMatrix();
        cm.setScale(1f, 0.97f, 0.9f, 1f);
        WARM = new ColorMatrixColorFilter(cm);
    }

    private Images() {
    }

    /** Méret lekérdezése: {szélesség, magasság}, vagy null. */
    static int[] bounds(Node n) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = n.open()) {
            BitmapFactory.decodeStream(in, null, o);
        } catch (IOException e) {
            return null;
        }
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        return new int[]{o.outWidth, o.outHeight};
    }

    /** Kis előnézet (borító, gyors lapozó). */
    static Bitmap decodeThumb(Node n, int boxW, int boxH) {
        return decode(n, boxW, boxH, false);
    }

    /** A vetített oldal: meleg tónussal, a chipre előre feltöltve. */
    static Bitmap decodeFit(Node n, int boxW, int boxH) {
        Bitmap b = decode(n, boxW, boxH, true);
        if (b != null) b.prepareToDraw();
        return b;
    }

    private static Bitmap decode(Node n, int boxW, int boxH, boolean warm) {
        int[] b = bounds(n);
        if (b == null) return null;
        int w = b[0], h = b[1];
        int sample = 1;
        while ((long) (w / sample) * (h / sample) > 16_000_000L) sample *= 2;

        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inScaled = false;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap full;
        try (InputStream in = n.open()) {
            full = BitmapFactory.decodeStream(in, null, o);
        } catch (IOException | OutOfMemoryError e) {
            return null;
        }
        if (full == null) return null;

        float s = Math.min(1f, Math.min((float) boxW / full.getWidth(), (float) boxH / full.getHeight()));
        int tw = Math.max(1, Math.round(full.getWidth() * s));
        int th = Math.max(1, Math.round(full.getHeight() * s));
        try {
            // két lépésben kicsinyítünk, így simább (kevésbé recés) lesz
            Bitmap mid = full;
            if (s < 0.5f) {
                mid = Bitmap.createScaledBitmap(full, tw * 2, th * 2, true);
                if (mid != full) full.recycle();
            }
            if (!warm && mid.getWidth() == tw && mid.getHeight() == th) return mid;

            Bitmap out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            if (warm) p.setColorFilter(WARM);
            new Canvas(out).drawBitmap(mid, null, new RectF(0, 0, tw, th), p);
            mid.recycle();
            return out;
        } catch (OutOfMemoryError e) {
            return null;
        }
    }
}
