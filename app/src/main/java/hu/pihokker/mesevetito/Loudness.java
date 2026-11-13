package hu.pihokker.mesevetito;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Process;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * Számok hangerejének mérése, hogy mind egyformán szóljon.
 * Minden számot egyszer, a háttérben végigdekódol, és megméri az átlagos hangerőt
 * (a csendes részek nélkül, a hangossági szabványok mintájára). Az eredményt eltárolja,
 * így legközelebb nem kell újra mérni.
 */
final class Loudness {

    /** Ehhez a hangerőhöz igazítunk minden számot (dBFS, kapuzott átlag). */
    static final float TARGET_DB = -27f;
    static final float UNKNOWN = Float.NaN;

    private final SharedPreferences prefs;
    private final Context ctx;
    private final LinkedBlockingDeque<Node> queue = new LinkedBlockingDeque<>();

    Loudness(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.prefs = ctx.getSharedPreferences("loudness", Context.MODE_PRIVATE);
        Thread t = new Thread(this::loop, "loudness");
        t.setDaemon(true);
        t.start();
    }

    private static String keyOf(Node n) {
        return n.key() + "|" + n.size() + "|" + n.modified();
    }

    /** A szám mért hangereje dBFS-ben, vagy NaN, ha még nincs megmérve. */
    float get(Node n) {
        return prefs.getFloat(keyOf(n), UNKNOWN);
    }

    /** A hiányzó mérések sorba állítása, a megadott (lejátszási) sorrendben. */
    void measureAll(List<Node> tracks) {
        queue.clear();
        for (Node n : tracks) if (Float.isNaN(get(n))) queue.addLast(n);
    }

    /** Ezt a számot mérje meg következőnek. */
    void prioritize(Node n) {
        if (Float.isNaN(get(n)) && queue.remove(n)) queue.addFirst(n);
    }

    private void loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        while (true) {
            Node n;
            try {
                n = queue.takeFirst();
            } catch (InterruptedException e) {
                return;
            }
            if (!Float.isNaN(get(n))) continue;
            float db;
            try {
                db = measure(ctx, n.uri());
            } catch (Throwable t) {
                db = TARGET_DB; // nem mérhető: hagyjuk változatlanul
            }
            prefs.edit().putFloat(keyOf(n), db).apply();
        }
    }

    // ------------------------------------------------------------ mérés

    private static final float BLOCK_SEC = 0.4f;

    static float measure(Context ctx, Uri uri) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec codec = null;
        try {
            ex.setDataSource(ctx, uri, null);
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    fmt = f;
                    break;
                }
            }
            if (track < 0) return TARGET_DB;
            ex.selectTrack(track);
            int rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);

            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            codec.configure(fmt, null, null, 0);
            codec.start();

            int encoding = AudioFormat.ENCODING_PCM_16BIT;
            long blockFrames = Math.max(1, (long) (rate * BLOCK_SEC));
            double acc = 0;
            long accFrames = 0;
            List<Double> blocks = new ArrayList<>();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inDone = false, outDone = false;
            while (!outDone) {
                if (!inDone) {
                    int ii = codec.dequeueInputBuffer(10_000);
                    if (ii >= 0) {
                        ByteBuffer buf = codec.getInputBuffer(ii);
                        int size = ex.readSampleData(buf, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inDone = true;
                        } else {
                            codec.queueInputBuffer(ii, 0, size, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int oi = codec.dequeueOutputBuffer(info, 10_000);
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (of.containsKey("pcm-encoding")) encoding = of.getInteger("pcm-encoding");
                } else if (oi >= 0) {
                    ByteBuffer out = codec.getOutputBuffer(oi);
                    if (out != null && info.size > 0) {
                        out.position(info.offset);
                        out.limit(info.offset + info.size);
                        ByteBuffer bb = out.slice().order(ByteOrder.nativeOrder());
                        int samples = info.size / (encoding == AudioFormat.ENCODING_PCM_FLOAT ? 4 : 2);
                        int frames = samples / Math.max(1, ch);
                        for (int f = 0; f < frames; f++) {
                            double sq = 0;
                            for (int c = 0; c < ch; c++) {
                                double v = encoding == AudioFormat.ENCODING_PCM_FLOAT
                                        ? bb.getFloat() : bb.getShort() / 32768.0;
                                sq += v * v;
                            }
                            acc += sq / ch;
                            if (++accFrames >= blockFrames) {
                                blocks.add(acc / accFrames);
                                acc = 0;
                                accFrames = 0;
                            }
                        }
                    }
                    codec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
                }
            }
            return gated(blocks);
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (Exception ignored) {
                }
                codec.release();
            }
            ex.release();
        }
    }

    /** Kapuzott átlag: a csendet (−70 dB alatt) és a halk átvezetéseket kihagyja. */
    private static float gated(List<Double> blocks) {
        double sum = 0;
        int n = 0;
        for (double m : blocks) {
            if (toDb(m) > -70) {
                sum += m;
                n++;
            }
        }
        if (n == 0) return TARGET_DB;
        double rel = toDb(sum / n) - 10;
        sum = 0;
        n = 0;
        for (double m : blocks) {
            if (toDb(m) > rel) {
                sum += m;
                n++;
            }
        }
        if (n == 0) return TARGET_DB;
        return (float) toDb(sum / n);
    }

    private static double toDb(double meanSquare) {
        return 10 * Math.log10(Math.max(meanSquare, 1e-12));
    }
}
