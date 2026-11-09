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

/** A pendrive beolvasása: könyvek (képes mappák) és zenék. */
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

    int status = NO_USB;
    final List<Book> books = new ArrayList<>();
    final List<Node> music = new ArrayList<>();

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

        for (Node r : roots) walk(r, 0, lib);
        Collections.sort(lib.books, (a, b) -> NaturalOrder.compare(a.title, b.title));
        Collections.sort(lib.music, (a, b) -> NaturalOrder.compare(a.name(), b.name()));
        lib.status = OK;
        return lib;
    }

    private static void walk(Node dir, int depth, Library lib) {
        List<Node> kids = dir.children();
        if (kids == null) return;
        for (Node k : kids) {
            if (!k.isDir() || isHidden(k.name())) continue;
            List<Node> files = k.children();
            if (files == null) continue;
            List<Node> images = new ArrayList<>();
            List<Node> audio = new ArrayList<>();
            for (Node f : files) {
                if (f.isDir() || f.name().startsWith(".")) continue;
                if (isImage(f.name())) images.add(f);
                else if (isAudio(f.name())) audio.add(f);
            }
            if (!images.isEmpty()) {
                Collections.sort(images, (a, b) -> NaturalOrder.compare(a.name(), b.name()));
                lib.books.add(new Book(k.name(), images, k.key()));
            } else if (!audio.isEmpty()) {
                lib.music.addAll(audio);
            } else if (depth < 2) {
                // pl. egy "Mesék" gyűjtőmappa: belenézünk
                walk(k, depth + 1, lib);
            }
        }
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
