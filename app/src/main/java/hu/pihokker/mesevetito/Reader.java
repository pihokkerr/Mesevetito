package hu.pihokker.mesevetito;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Egy megnyitott könyv kezelése: diafilmes lapozás fel/le, gyors lapozás balra/jobbra,
 * a szomszédos oldalak előtöltése és a könyvjelző (hol tartottatok).
 */
final class Reader {

    interface PageCallback {
        void onPage(Bitmap b);
    }

    private static final long SETTLE_MS = 380;     // gyors lapozás: ennyi csend után tölt be a nagy kép
    private static final long STRIP_HIDE_MS = 2600;

    private final ReaderView view;
    private final FilmStripView strip;
    private final ThumbCache thumbs;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newSingleThreadExecutor();

    private Library.Book book;
    private int index;
    private volatile int generation;
    private final SparseArray<Bitmap> cache = new SparseArray<>();
    private final SparseArray<List<PageCallback>> loading = new SparseArray<>();
    private int fastTarget = -1;
    private int boxW = 1920, boxH = 1080;

    Reader(ReaderView view, FilmStripView strip, ThumbCache thumbs, SharedPreferences prefs) {
        this.view = view;
        this.strip = strip;
        this.thumbs = thumbs;
        this.prefs = prefs;
        strip.setAlpha(0f);
        strip.setVisibility(View.GONE);
    }

    void setBox(int w, int h) {
        if (w > 0 && h > 0) {
            boxW = w;
            boxH = h;
        }
    }

    Library.Book book() {
        return book;
    }

    boolean isOpen() {
        return book != null;
    }

    static int savedPage(SharedPreferences prefs, Library.Book b) {
        return prefs.getInt("pos:" + b.key, 0);
    }

    // ------------------------------------------------------------ megnyitás / bezárás

    void open(Library.Book b, Runnable shown) {
        close();
        book = b;
        generation++;
        int saved = savedPage(prefs, b);
        // ha a végére értetek, legközelebb elölről
        index = (saved > 0 && saved < b.pages.size() - 1) ? saved : 0;
        strip.bind(b.pages, thumbs);
        strip.setCenter(index);
        view.setPage(null);
        getPage(index, bmp -> {
            view.setPage(bmp);
            if (shown != null) shown.run();
            preloadAround();
            // a gyors lapozó kis képei a háttérben, az aktuális oldaltól kifelé
            for (int d = 0; d < b.pages.size(); d++) {
                if (index + d < b.pages.size()) thumbs.request(b.pages.get(index + d), false);
                if (d > 0 && index - d >= 0) thumbs.request(b.pages.get(index - d), false);
            }
        });
    }

    void close() {
        if (book != null) save();
        generation++;
        book = null;
        cache.clear();
        loading.clear();
        fastTarget = -1;
        main.removeCallbacks(settle);
        main.removeCallbacks(hideStrip);
        strip.animate().cancel();
        strip.setAlpha(0f);
        strip.setVisibility(View.GONE);
        thumbs.clearPending();
        view.setPage(null);
    }

    private void save() {
        prefs.edit().putInt("pos:" + book.key, index).apply();
    }

    // ------------------------------------------------------------ diafilmes lapozás

    /** fel/le nyíl: egy oldal, filmcsík-mozgással. */
    void step(int dir) {
        if (book == null || view.isAnimating() || fastTarget >= 0) return;
        int target = index + dir;
        if (target < 0 || target >= book.pages.size()) return;
        int gen = generation;
        getPage(target, bmp -> {
            if (gen != generation || view.isAnimating() || bmp == null) return;
            index = target;
            save();
            strip.setCenter(index);
            view.slideTo(bmp, dir > 0, this::preloadAround);
        });
    }

    // ------------------------------------------------------------ gyors lapozás

    /** balra/jobbra nyíl: azonnal ugrik, alul képsorral; a nagy kép csak megálláskor töltődik. */
    void fast(int dir) {
        if (book == null) return;
        int from = fastTarget >= 0 ? fastTarget : index;
        int target = Math.max(0, Math.min(book.pages.size() - 1, from + dir));
        fastTarget = target;
        strip.setCenter(target);
        showStrip();
        main.removeCallbacks(settle);
        main.postDelayed(settle, SETTLE_MS);
    }

    private final Runnable settle = new Runnable() {
        @Override
        public void run() {
            if (book == null || fastTarget < 0) return;
            if (view.isAnimating()) {
                main.postDelayed(this, 200);
                return;
            }
            final int target = fastTarget;
            if (target == index) {
                fastTarget = -1;
                return;
            }
            int gen = generation;
            getPage(target, bmp -> {
                if (gen != generation || bmp == null) return;
                if (fastTarget != target) return; // közben tovább lapoztak
                fastTarget = -1;
                index = target;
                save();
                view.fadeTo(bmp, 320, Reader.this::preloadAround);
            });
        }
    };

    private void showStrip() {
        main.removeCallbacks(hideStrip);
        if (strip.getVisibility() != View.VISIBLE) {
            strip.setVisibility(View.VISIBLE);
            strip.animate().alpha(1f).setDuration(220).start();
        }
        main.postDelayed(hideStrip, STRIP_HIDE_MS);
    }

    private final Runnable hideStrip = this::hideStripNow;

    private void hideStripNow() {
        strip.animate().alpha(0f).setDuration(600)
                .withEndAction(() -> strip.setVisibility(View.GONE)).start();
    }

    // ------------------------------------------------------------ betöltés, előtöltés

    private void preloadAround() {
        if (book == null) return;
        // csak az aktuális és a két szomszéd marad memóriában
        for (int i = cache.size() - 1; i >= 0; i--) {
            int k = cache.keyAt(i);
            if (Math.abs(k - index) > 1) cache.removeAt(i);
        }
        getPage(index + 1, null);
        getPage(index - 1, null);
    }

    private void getPage(int i, PageCallback cb) {
        if (book == null || i < 0 || i >= book.pages.size()) return;
        Bitmap b = cache.get(i);
        if (b != null) {
            if (cb != null) cb.onPage(b);
            return;
        }
        List<PageCallback> waiting = loading.get(i);
        if (waiting != null) {
            if (cb != null) waiting.add(cb);
            return;
        }
        waiting = new ArrayList<>();
        if (cb != null) waiting.add(cb);
        loading.put(i, waiting);

        final Node node = book.pages.get(i);
        final int gen = generation;
        final int w = boxW, h = boxH;
        loader.execute(() -> {
            if (gen != generation) return;
            Bitmap bmp = Images.decodeFit(node, w, h);
            main.post(() -> {
                if (gen != generation) return;
                List<PageCallback> cbs = loading.get(i);
                loading.remove(i);
                if (bmp != null) cache.put(i, bmp);
                if (cbs != null) for (PageCallback c : cbs) c.onPage(bmp);
            });
        });
    }
}
