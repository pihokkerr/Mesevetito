package hu.pihokker.mesevetito;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Egy fájl vagy mappa a pendrive-on. Kétféle elérés van mögötte:
 * közvetlen fájlrendszer (gyorsabb) vagy a rendszer mappaválasztója (SAF, tartalék).
 */
abstract class Node {

    abstract String name();

    abstract boolean isDir();

    abstract long size();

    abstract long modified();

    /** A mappa tartalma, vagy null, ha nem olvasható. */
    abstract List<Node> children();

    abstract InputStream open() throws IOException;

    abstract Uri uri();

    String key() {
        return uri().toString();
    }

    // ------------------------------------------------------------ közvetlen fájl

    static final class FileNode extends Node {
        final File file;

        FileNode(File file) {
            this.file = file;
        }

        @Override String name() { return file.getName(); }
        @Override boolean isDir() { return file.isDirectory(); }
        @Override long size() { return file.length(); }
        @Override long modified() { return file.lastModified(); }
        @Override Uri uri() { return Uri.fromFile(file); }

        @Override
        List<Node> children() {
            File[] kids = file.listFiles();
            if (kids == null) return null;
            List<Node> out = new ArrayList<>(kids.length);
            for (File k : kids) out.add(new FileNode(k));
            return out;
        }

        @Override
        InputStream open() throws IOException {
            return new FileInputStream(file);
        }
    }

    // ------------------------------------------------------------ SAF (mappaválasztó)

    static final class DocNode extends Node {
        private final Context ctx;
        private final Uri tree;
        private final String docId;
        private final String name;
        private final boolean dir;
        private final long size;
        private final long modified;

        DocNode(Context ctx, Uri tree, String docId, String name, boolean dir, long size, long modified) {
            this.ctx = ctx.getApplicationContext();
            this.tree = tree;
            this.docId = docId;
            this.name = name;
            this.dir = dir;
            this.size = size;
            this.modified = modified;
        }

        static DocNode root(Context ctx, Uri tree) {
            return new DocNode(ctx, tree, DocumentsContract.getTreeDocumentId(tree), "", true, 0, 0);
        }

        @Override String name() { return name; }
        @Override boolean isDir() { return dir; }
        @Override long size() { return size; }
        @Override long modified() { return modified; }

        @Override
        Uri uri() {
            return DocumentsContract.buildDocumentUriUsingTree(tree, docId);
        }

        @Override
        List<Node> children() {
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
            String[] cols = {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED};
            List<Node> out = new ArrayList<>();
            try (Cursor c = ctx.getContentResolver().query(children, cols, null, null, null)) {
                if (c == null) return null;
                while (c.moveToNext()) {
                    boolean isDir = DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2));
                    out.add(new DocNode(ctx, tree, c.getString(0), c.getString(1), isDir,
                            c.isNull(3) ? 0 : c.getLong(3), c.isNull(4) ? 0 : c.getLong(4)));
                }
            } catch (Exception e) {
                return null;
            }
            return out;
        }

        @Override
        InputStream open() throws IOException {
            InputStream in = ctx.getContentResolver().openInputStream(uri());
            if (in == null) throw new IOException("Nem nyitható meg: " + name);
            return in;
        }
    }
}
