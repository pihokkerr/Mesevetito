package hu.pihokker.mesevetito;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.IOException;
import java.io.InputStream;

/** Képek dekódolása úgy, hogy soha ne kerüljön teljes méretű szken a memóriába. */
final class Images {

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

    /**
     * Kis előnézet. A projektor dekódere a kicsinyítve (inSampleSize) betöltött JPEG-eket
     * néha hibásan adja vissza (zöld-lila csíkok), ezért itt teljes méretben töltünk be,
     * és szoftveresen kicsinyítünk. Csak óriási képeknél felezünk.
     */
    static Bitmap decodeThumb(Node n, int boxW, int boxH) {
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
        float s = Math.min((float) boxW / full.getWidth(), (float) boxH / full.getHeight());
        if (s >= 1f) return full;
        int tw = Math.max(1, Math.round(full.getWidth() * s));
        int th = Math.max(1, Math.round(full.getHeight() * s));
        // két lépésben kicsinyítünk, így simább (kevésbé recés) lesz
        Bitmap mid = full;
        if (s < 0.5f) {
            mid = Bitmap.createScaledBitmap(full, tw * 2, th * 2, true);
            if (mid != full) full.recycle();
        }
        Bitmap out = Bitmap.createScaledBitmap(mid, tw, th, true);
        if (out != mid) mid.recycle();
        return out;
    }

    /** A teljes oldal a vetítéshez: ugyanazon a biztonságos úton, mint az előnézet. */
    static Bitmap decodeFit(Node n, int boxW, int boxH) {
        return decodeThumb(n, boxW, boxH);
    }
}
