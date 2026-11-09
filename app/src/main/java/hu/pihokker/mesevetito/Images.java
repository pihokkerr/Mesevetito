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
     * Betölti a képet úgy, hogy beleférjen a boxW x boxH dobozba (arányt tartva).
     * A kicsinyítés már dekódolás közben történik.
     */
    static Bitmap decodeFit(Node n, int boxW, int boxH) {
        int[] b = bounds(n);
        if (b == null) return null;
        int w = b[0], h = b[1];

        // a végső méret: arányosan bele a dobozba; addig felezünk, amíg még nem lesz kisebb annál
        float fit = Math.min((float) boxW / w, (float) boxH / h);
        int sample = 1;
        while (sample * 2 * fit <= 1f) sample *= 2;
        // ha még így is nagyon nagy a kép (pl. hosszú csík), tovább felezünk
        while ((long) (w / sample) * (h / sample) > 4L * boxW * boxH) sample *= 2;

        int sw = (w + sample - 1) / sample;
        int sh = (h + sample - 1) / sample;
        float ratio = Math.min((float) boxW / sw, (float) boxH / sh);

        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        if (ratio < 1f) {
            // pontos kicsinyítés dekódolás közben, külön másolat nélkül
            o.inScaled = true;
            o.inDensity = sw;
            o.inTargetDensity = Math.max(1, Math.round(sw * ratio));
        } else {
            o.inScaled = false;
        }
        try (InputStream in = n.open()) {
            return BitmapFactory.decodeStream(in, null, o);
        } catch (IOException | OutOfMemoryError e) {
            return null;
        }
    }
}
