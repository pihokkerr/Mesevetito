package hu.pihokker.mesevetito;

import android.content.Context;
import android.content.UriPermission;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * A pendrive beolvasása.
 * - Képes mappa = könyv.
 * - Olyan mappa, amelyben könyvmappák vannak = gyűjtemény (pl. "Bogyó és Babóca").
 * - Hangfájlok bárhol = háttérzene.
 */
final class Library {

    static final int OK = 0;
    static final int NO_USB = 1;
    static final int NEED_PERMISSION = 2;

    static final class Book {
        final String title;
        final List<Node> pages;
        final String key;

        Book(String title, List<Node> pages, String key) {
            this.title = title;
            this.pages = pages;
            this.key = key;
        }
    }

    /** Gyűjtemény: könyvek egy mappában. */
    static final class Shelf {
        final String title;
        final String key;
        final List<Book> books;

        Shelf(String title, String key, List<Book> books) {
            this.title = title;
            this.key = key;
            this.books = books;
        }
    }

    int status = NO_USB;
    final List<Shelf> shelves = new ArrayList<>();
    final List<Book> books = new ArrayList<>();   // gyűjteményen kívüli könyvek
    final List<Node> music = new ArrayList<>();

    /** A főképernyő elemei: előbb a gyűjtemények, aztán a különálló könyvek. */
    List<Object> top() {
        List<Object> out = new ArrayList<>(shelves.size() + books.size());
        out.addAll(shelves);
        out.addAll(books);
        return out;
    }

    Shelf shelf(String key) {
        for (Shelf s : shelves) if (s.key.equals(key)) return s;
        return null;
    }

    static Library scan(Context ctx) {
        Library lib = new Library();
        List<Node> roots = new ArrayList<>();
        boolean usbPresent = false;

        // 1) Közvetlen fájlelérés a cserélhető tárolókon
        StorageManager sm = (StorageManager) ctx.getSystemService(Context.STORAGE_SERVICE);
        for (StorageVolume v : sm.getStorageVolumes()) {
            if (!v.isRemovable() || !Environment.MEDIA_MOUNTED.equals(v.getState())) continue;
            usbPresent = true;
            File dir = null;
            if (Build.VERSION.SDK_INT >= 30) dir = v.getDirectory();
            if (dir == null && v.getUuid() != null) dir = new File("/storage/" + v.getUuid());
            if (dir != null && dir.listFiles() != null) roots.add(new Node.FileNode(dir));
        }

        // 2) Tartalék: a mappaválasztóval engedélyezett mappák
        if (roots.isEmpty()) {
            for (UriPermission p : ctx.getContentResolver().getPersistedUriPermissions()) {
                if (!p.isReadPermission()) continue;
                Node.DocNode root = Node.DocNode.root(ctx, p.getUri());
                if (root.children() != null) roots.add(root);
            }
        }

        if (roots.isEmpty()) {
            lib.status = usbPresent ? NEED_PERMISSION : NO_USB;
            return lib;
        }

        for (Node r : roots) {
            List<Node> kids = r.children();
            if (kids != null) scanTop(kids, lib);
        }

        // Ha minden egyetlen gyűjtőmappában van (pl. "Mesék/"), az ne legyen külön szint
        if (lib.books.isEmpty() && lib.shelves.size() == 1) {
            lib.books.addAll(lib.shelves.remove(0).books);
        }

        Collections.sort(lib.shelves, (a, b) -> NaturalOrder.compare(a.title, b.title));
        Collections.sort(lib.books, (a, b) -> NaturalOrder.compare(a.title, b.title));
        Collections.sort(lib.music, (a, b) -> NaturalOrder.compare(a.name(), b.name()));
        lib.status = OK;
        return lib;
    }

    private static void scanTop(List<Node> kids, Library lib) {
        for (Node k : kids) {
            if (!k.isDir() || isHidden(k.name())) continue;
            List<Node> files = k.children();
            if (files == null) continue;
            Book b = bookOf(k, files, lib.music);
            if (b != null) {
                lib.books.add(b);
                continue;
            }
            List<Book> inner = new ArrayList<>();
            gather(files, 1, inner, lib.music);
            if (!inner.isEmpty()) {
                Collections.sort(inner, (x, y) -> NaturalOrder.compare(x.title, y.title));
                lib.shelves.add(new Shelf(k.name(), k.key(), inner));
            }
        }
    }

    /** Egy gyűjtemény összes könyve, az almappákat is beleértve (laposan). */
    private static void gather(List<Node> kids, int depth, List<Book> out, List<Node> music) {
        for (Node k : kids) {
            if (!k.isDir() || isHidden(k.name())) continue;
            List<Node> files = k.children();
            if (files == null) continue;
            Book b = bookOf(k, files, music);
            if (b != null) out.add(b);
            else if (depth < 3) gather(files, depth + 1, out, music);
        }
    }

    /** Könyv, ha a mappában képek vannak; a talált hangfájlokat a zenékhez teszi. */
    private static Book bookOf(Node dir, List<Node> files, List<Node> music) {
        List<Node> images = new ArrayList<>();
        for (Node f : files) {
            if (f.isDir() || f.name().startsWith(".")) continue;
            if (isImage(f.name())) images.add(f);
            else if (isAudio(f.name())) music.add(f);
        }
        if (images.isEmpty()) return null;
        Collections.sort(images, (a, b) -> NaturalOrder.compare(a.name(), b.name()));
        return new Book(dir.name(), images, dir.key());
    }

    private static boolean isHidden(String n) {
        return n.startsWith(".") || n.equalsIgnoreCase("System Volume Information")
                || n.equalsIgnoreCase("Android") || n.equalsIgnoreCase("LOST.DIR")
                || n.startsWith("$");
    }

    static boolean isImage(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")
                || n.endsWith(".webp") || n.endsWith(".bmp") || n.endsWith(".gif");
    }

    static boolean isAudio(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".aac")
                || n.endsWith(".ogg") || n.endsWith(".opus") || n.endsWith(".flac")
                || n.endsWith(".wav");
    }
}
