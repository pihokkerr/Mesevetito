package hu.pihokker.mesevetito;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * Kis előnézeti képek (borító, gyors lapozó). Egyszer készülnek el a szkenből,
 * utána a belső tárhelyen JPEG-ként tárolódnak, így legközelebb azonnal betöltenek.
 */
final class ThumbCache {

    static final int SIZE = 400; // a kép ebbe a négyzetbe fér bele

    interface Listener {
        void onThumbReady(String key);
    }

    private final File dir;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> mem;
    private final LinkedBlockingDeque<Node> queue = new LinkedBlockingDeque<>();
    private final Set<String> queued = new HashSet<>();
    private final List<Listener> listeners = new ArrayList<>();

    ThumbCache(Context ctx) {
        dir = new File(ctx.getCacheDir(), "thumbs");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        mem = new LruCache<String, Bitmap>(28 * 1024 * 1024) {
            @Override
            protected int sizeOf(String k, Bitmap v) {
                return v.getByteCount();
            }
        };
        Thread worker = new Thread(this::loop, "thumbs");
        worker.setDaemon(true);
        worker.start();
    }

    void addListener(Listener l) {
        listeners.add(l);
    }

    static String keyOf(Node n) {
        return md5(n.key() + "|" + n.size() + "|" + n.modified());
    }

    /** Memóriából, ha már megvan; különben null (és ilyenkor érdemes request()-et hívni). */
    Bitmap get(Node n) {
        return mem.get(keyOf(n));
    }

    /** Elkészítteti a bélyegképet. urgent = a sor elejére kerül (éppen látszik). */
    void request(Node n, boolean urgent) {
        String k = keyOf(n);
        if (mem.get(k) != null) return;
        synchronized (queued) {
            if (queued.contains(k)) {
                if (urgent && queue.remove(n)) queue.addFirst(n);
                return;
            }
            queued.add(k);
        }
        if (urgent) queue.addFirst(n);
        else queue.addLast(n);
    }

    /** A sorban várakozó, már nem érdekes kérések törlése (pl. könyv bezárásakor). */
    void clearPending() {
        synchronized (queued) {
            queue.clear();
            queued.clear();
        }
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
            String k = keyOf(n);
            Bitmap b = null;
            try {
                b = load(n, k);
            } catch (Throwable ignored) {
            }
            synchronized (queued) {
                queued.remove(k);
            }
            if (b != null) {
                mem.put(k, b);
                main.post(() -> {
                    for (Listener l : listeners) l.onThumbReady(k);
                });
            }
        }
    }

    private Bitmap load(Node n, String k) {
        File f = new File(dir, k + ".jpg");
        if (f.exists()) {
            Bitmap b = BitmapFactory.decodeFile(f.getPath());
            if (b != null) return b;
        }
        Bitmap b = Images.decodeFit(n, SIZE, SIZE);
        if (b == null) return null;
        try (FileOutputStream out = new FileOutputStream(f)) {
            b.compress(Bitmap.CompressFormat.JPEG, 85, out);
        } catch (Exception ignored) {
        }
        return b;
    }

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
