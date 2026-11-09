package hu.pihokker.mesevetito.teszt;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Teszt-app a Magcubic HY310x projektorhoz.
 * Kideríti, hogy melyik módon látja az app a pendrive-on lévő mappákat és képeket,
 * és hogy mennyi idő egy nagy szkennelt oldal betöltése vetítési méretre kicsinyítve.
 */
public class MainActivity extends Activity {

    private static final int REQ_READ_PERM = 1;
    private static final int REQ_TREE = 2;
    private static final String PREFS = "teszt";
    private static final String KEY_TREE = "tree_uri";

    private TextView logView;
    private ScrollView logScroll;
    private FrameLayout root;
    private ImageView preview;
    private final Handler ui = new Handler(Looper.getMainLooper());

    // A legnagyobb talált kép (fájl vagy SAF uri), ezt használja a betöltési teszt
    private Uri biggestImage;
    private long biggestSize = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        log("=== Mesevetítő teszt 0.1 ===");
        deviceInfo();
        log("");
        log("Nyilakkal válassz gombot, OK-val indítsd.");
        log("A végén fényképezd le a képernyőt és küldd el.");
    }

    // ---------------------------------------------------------------- UI

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(24, 20, 16));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(24), dp(20), dp(24), dp(20));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.VERTICAL);

        addButton(buttons, "1. Tárolók listázása", v -> runBg(this::listVolumes));
        addButton(buttons, "2. Közvetlen fájl-olvasás", v -> runBg(this::directFileTest));
        addButton(buttons, "3. Olvasási engedély kérése", v -> requestReadPerm());
        addButton(buttons, "4. Teljes fájlhozzáférés", v -> openAllFilesAccess());
        addButton(buttons, "5. Mappaválasztó (pendrive)", v -> openTreePicker());
        addButton(buttons, "6. Mentett mappa újraolvasása", v -> runBg(this::rescanSavedTree));
        addButton(buttons, "7. Nagy kép betöltése", v -> runBg(this::imageLoadTest));
        addButton(buttons, "Napló törlése", v -> logView.setText(""));

        row.addView(buttons, new LinearLayout.LayoutParams(dp(300),
                LinearLayout.LayoutParams.MATCH_PARENT));

        logScroll = new ScrollView(this);
        logScroll.setFocusable(false);
        logView = new TextView(this);
        logView.setTextColor(Color.rgb(235, 225, 205));
        logView.setTextSize(15);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setPadding(dp(20), 0, 0, 0);
        logScroll.addView(logView);
        row.addView(logScroll, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        root.addView(row);

        preview = new ImageView(this);
        preview.setBackgroundColor(Color.BLACK);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setVisibility(View.GONE);
        root.addView(preview, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(root);
        buttons.getChildAt(0).requestFocus();
    }

    private void addButton(LinearLayout parent, String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTextColor(Color.rgb(240, 230, 210));
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setPadding(dp(16), 0, dp(8), 0);
        b.setFocusable(true);
        b.setOnClickListener(l);

        GradientDrawable normal = new GradientDrawable();
        normal.setCornerRadius(dp(6));
        normal.setColor(Color.rgb(48, 40, 32));
        GradientDrawable focused = new GradientDrawable();
        focused.setCornerRadius(dp(6));
        focused.setColor(Color.rgb(110, 84, 50));
        focused.setStroke(dp(2), Color.rgb(232, 200, 138));
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_focused}, focused);
        sl.addState(new int[]{android.R.attr.state_pressed}, focused);
        sl.addState(new int[]{}, normal);
        b.setBackground(sl);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        lp.bottomMargin = dp(8);
        parent.addView(b, lp);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (preview.getVisibility() == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode == KeyEvent.KEYCODE_ENTER) {
                preview.setVisibility(View.GONE);
                preview.setImageBitmap(null);
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void log(String s) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            ui.post(() -> log(s));
            return;
        }
        logView.append(s + "\n");
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void runBg(Runnable r) {
        new Thread(() -> {
            try {
                r.run();
            } catch (Throwable t) {
                log("HIBA: " + t);
            }
        }).start();
    }

    // ---------------------------------------------------------------- 0. Info

    private void deviceInfo() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        log("Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        log("Gyártó/modell: " + Build.MANUFACTURER + " / " + Build.MODEL);
        log("Kijelző: " + dm.widthPixels + "x" + dm.heightPixels + ", density " + dm.density);
        log("App memória: " + am.getMemoryClass() + " MB (large: "
                + am.getLargeMemoryClass() + " MB), max heap: "
                + (Runtime.getRuntime().maxMemory() / 1024 / 1024) + " MB");
        log("Olvasási engedély: " + yesNo(hasReadPerm()));
        if (Build.VERSION.SDK_INT >= 30) {
            log("Teljes fájlhozzáférés: " + yesNo(Environment.isExternalStorageManager()));
        }
        List<UriPermission> perms = getContentResolver().getPersistedUriPermissions();
        log("Mentett mappa-engedélyek: " + perms.size());
        for (UriPermission p : perms) log("  " + p.getUri());
    }

    private String yesNo(boolean b) {
        return b ? "IGEN" : "nem";
    }

    private boolean hasReadPerm() {
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    // ---------------------------------------------------------------- 1. Tárolók

    private List<File> candidateDirs() {
        List<File> dirs = new ArrayList<>();
        StorageManager sm = (StorageManager) getSystemService(Context.STORAGE_SERVICE);
        for (StorageVolume v : sm.getStorageVolumes()) {
            if (!v.isRemovable()) continue;
            File d = null;
            if (Build.VERSION.SDK_INT >= 30) d = v.getDirectory();
            if (d == null && v.getUuid() != null) d = new File("/storage/" + v.getUuid());
            if (d != null && !dirs.contains(d)) dirs.add(d);
        }
        File storage = new File("/storage");
        File[] kids = storage.listFiles();
        if (kids != null) {
            for (File k : kids) {
                String n = k.getName();
                if (n.equals("emulated") || n.equals("self")) continue;
                if (!dirs.contains(k)) dirs.add(k);
            }
        }
        for (String p : new String[]{"/mnt/usb", "/mnt/media_rw", "/mnt/usb_storage"}) {
            File f = new File(p);
            if (f.exists() && !dirs.contains(f)) dirs.add(f);
        }
        return dirs;
    }

    private void listVolumes() {
        log("");
        log("--- 1. Tárolók ---");
        StorageManager sm = (StorageManager) getSystemService(Context.STORAGE_SERVICE);
        for (StorageVolume v : sm.getStorageVolumes()) {
            String dir = "";
            if (Build.VERSION.SDK_INT >= 30 && v.getDirectory() != null) {
                dir = v.getDirectory().getPath();
            }
            log("* " + v.getDescription(this)
                    + " | cserélhető: " + yesNo(v.isRemovable())
                    + " | állapot: " + v.getState()
                    + " | uuid: " + v.getUuid()
                    + (dir.isEmpty() ? "" : " | " + dir));
        }
        File[] kids = new File("/storage").listFiles();
        log("/storage tartalma: " + (kids == null ? "NEM olvasható" : kids.length + " elem"));
        if (kids != null) for (File k : kids) log("  " + k.getName());
        log("Pendrive-jelöltek: " + candidateDirs());
    }

    // ---------------------------------------------------------------- 2. Közvetlen fájl

    private void directFileTest() {
        log("");
        log("--- 2. Közvetlen fájl-olvasás ---");
        List<File> dirs = candidateDirs();
        if (dirs.isEmpty()) {
            log("Nem találtam pendrive-ot. Be van dugva?");
            return;
        }
        for (File d : dirs) {
            File[] kids = d.listFiles();
            if (kids == null) {
                log(d + " → NEM listázható (nincs jog)");
                continue;
            }
            log(d + " → " + kids.length + " elem");
            scanFolders(d, 0);
        }
    }

    /** Végigmegy a mappákon (max 2 szint), kiírja, hány kép van bennük. */
    private void scanFolders(File dir, int depth) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        int shown = 0;
        for (File k : kids) {
            if (!k.isDirectory() || k.getName().startsWith(".")) continue;
            int imgs = 0;
            long t0 = SystemClock.elapsedRealtime();
            File[] files = k.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isFile() && isImage(f.getName())) {
                        imgs++;
                        if (f.length() > biggestSize) {
                            biggestSize = f.length();
                            biggestImage = Uri.fromFile(f);
                        }
                    }
                }
            }
            long ms = SystemClock.elapsedRealtime() - t0;
            log(indent(depth + 1) + "[" + k.getName() + "] " + imgs + " kép ("
                    + ms + " ms)" + (files == null ? " – NEM olvasható" : ""));
            if (imgs == 0 && depth < 1) scanFolders(k, depth + 1);
            if (++shown >= 15) {
                log(indent(depth + 1) + "... (további mappák kihagyva)");
                break;
            }
        }
    }

    private String indent(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append("  ");
        return sb.toString();
    }

    private boolean isImage(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")
                || n.endsWith(".webp") || n.endsWith(".bmp");
    }

    // ---------------------------------------------------------------- 3–4. Engedélyek

    private void requestReadPerm() {
        log("");
        log("--- 3. Olvasási engedély ---");
        if (hasReadPerm()) {
            log("Már megvan.");
            return;
        }
        requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_READ_PERM);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode == REQ_READ_PERM) {
            log("Olvasási engedély: " + yesNo(hasReadPerm()) + " → most próbáld újra a 2-est.");
        }
    }

    private void openAllFilesAccess() {
        log("");
        log("--- 4. Teljes fájlhozzáférés ---");
        if (Build.VERSION.SDK_INT < 30) {
            log("Erre az Android-verzióra nem vonatkozik.");
            return;
        }
        if (Environment.isExternalStorageManager()) {
            log("Már megvan.");
            return;
        }
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
            log("Beállítás megnyitva. Kapcsold be, lépj vissza, aztán 2-es.");
        } catch (ActivityNotFoundException e1) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                log("Általános lista megnyitva. Keresd meg a 'Mesevetítő teszt'-et.");
            } catch (ActivityNotFoundException e2) {
                log("EZ A KÉPERNYŐ HIÁNYZIK a projektor rendszeréből.");
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (logView != null && Build.VERSION.SDK_INT >= 30 && logView.length() > 0) {
            log("(visszatérés) teljes hozzáférés: " + yesNo(Environment.isExternalStorageManager())
                    + ", olvasási: " + yesNo(hasReadPerm()));
        }
    }

    // ---------------------------------------------------------------- 5–6. Mappaválasztó (SAF)

    private void openTreePicker() {
        log("");
        log("--- 5. Mappaválasztó ---");
        Intent intent = null;
        StorageManager sm = (StorageManager) getSystemService(Context.STORAGE_SERVICE);
        for (StorageVolume v : sm.getStorageVolumes()) {
            if (v.isRemovable() && Build.VERSION.SDK_INT >= 29) {
                intent = v.createOpenDocumentTreeIntent();
                log("A pendrive gyökerét kínálom fel: " + v.getDescription(this));
                break;
            }
        }
        if (intent == null) intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQ_TREE);
            log("Választó megnyitva. Válaszd a pendrive-ot (vagy a könyvek mappáját),");
            log("majd 'Használja ezt a mappát' / 'Engedélyezés'.");
        } catch (ActivityNotFoundException e) {
            log("A MAPPAVÁLASZTÓ HIÁNYZIK a projektor rendszeréből.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_TREE) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            log("Választó: megszakítva / nem adott vissza mappát.");
            return;
        }
        Uri tree = data.getData();
        log("Kiválasztva: " + tree);
        try {
            getContentResolver().takePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
            log("Tartós engedély: IGEN (újraindítás után is megmarad)");
        } catch (SecurityException e) {
            log("Tartós engedély: NEM – " + e.getMessage());
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_TREE, tree.toString()).apply();
        runBg(() -> scanTree(tree));
    }

    private void rescanSavedTree() {
        log("");
        log("--- 6. Mentett mappa ---");
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        String s = p.getString(KEY_TREE, null);
        if (s == null) {
            log("Még nincs mentett mappa (előbb az 5-ös).");
            return;
        }
        scanTree(Uri.parse(s));
    }

    private void scanTree(Uri tree) {
        long t0 = SystemClock.elapsedRealtime();
        String rootId = DocumentsContract.getTreeDocumentId(tree);
        List<String[]> top = listChildren(tree, rootId);
        if (top == null) {
            log("A mappa NEM olvasható (pendrive kihúzva vagy elveszett az engedély).");
            return;
        }
        log("Gyökér: " + top.size() + " elem");
        int shown = 0;
        for (String[] c : top) {
            if (!DocumentsContract.Document.MIME_TYPE_DIR.equals(c[2])) continue;
            long t1 = SystemClock.elapsedRealtime();
            List<String[]> pages = listChildren(tree, c[0]);
            int imgs = 0;
            if (pages != null) {
                for (String[] pg : pages) {
                    if (isImage(pg[1])) {
                        imgs++;
                        long size = Long.parseLong(pg[3]);
                        if (size > biggestSize) {
                            biggestSize = size;
                            biggestImage = DocumentsContract.buildDocumentUriUsingTree(tree, pg[0]);
                        }
                    }
                }
            }
            log("  [" + c[1] + "] " + imgs + " kép (" + (SystemClock.elapsedRealtime() - t1) + " ms)");
            if (++shown >= 15) {
                log("  ... (további mappák kihagyva)");
                break;
            }
        }
        log("Teljes beolvasás: " + (SystemClock.elapsedRealtime() - t0) + " ms");
    }

    /** {docId, név, mime, méret} sorok, vagy null ha nem olvasható. */
    private List<String[]> listChildren(Uri tree, String docId) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        String[] cols = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE};
        List<String[]> out = new ArrayList<>();
        try (Cursor c = getContentResolver().query(children, cols, null, null, null)) {
            if (c == null) return null;
            while (c.moveToNext()) {
                out.add(new String[]{c.getString(0), c.getString(1), c.getString(2),
                        String.valueOf(c.isNull(3) ? 0 : c.getLong(3))});
            }
        } catch (Exception e) {
            log("Olvasási hiba: " + e.getMessage());
            return null;
        }
        return out;
    }

    // ---------------------------------------------------------------- 7. Kép betöltés

    private void imageLoadTest() {
        log("");
        log("--- 7. Nagy kép betöltése ---");
        if (biggestImage == null) {
            log("Még nem találtam képet. Előbb a 2-es vagy az 5/6-os, ami működött.");
            return;
        }
        log("Legnagyobb talált kép: " + (biggestSize / 1024) + " KB");
        log(biggestImage.toString());

        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        int tw = Math.max(dm.widthPixels, dm.heightPixels);
        int th = Math.min(dm.widthPixels, dm.heightPixels);

        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            long t0 = SystemClock.elapsedRealtime();
            try (InputStream in = getContentResolver().openInputStream(biggestImage)) {
                BitmapFactory.decodeStream(in, null, o);
            }
            log("Eredeti méret: " + o.outWidth + "x" + o.outHeight + " ("
                    + (SystemClock.elapsedRealtime() - t0) + " ms a fejléc)");
            if (o.outWidth <= 0) {
                log("Nem sikerült dekódolni.");
                return;
            }

            int sample = 1;
            while (o.outWidth / (sample * 2) >= tw || o.outHeight / (sample * 2) >= th) sample *= 2;

            for (Bitmap.Config cfg : new Bitmap.Config[]{Bitmap.Config.ARGB_8888, Bitmap.Config.RGB_565}) {
                BitmapFactory.Options d = new BitmapFactory.Options();
                d.inSampleSize = sample;
                d.inPreferredConfig = cfg;
                long t1 = SystemClock.elapsedRealtime();
                Bitmap bmp;
                try (InputStream in = getContentResolver().openInputStream(biggestImage)) {
                    bmp = BitmapFactory.decodeStream(in, null, d);
                }
                long ms = SystemClock.elapsedRealtime() - t1;
                if (bmp == null) {
                    log(cfg + ": sikertelen");
                    continue;
                }
                log(cfg + ": " + bmp.getWidth() + "x" + bmp.getHeight() + " (1/" + sample + "), "
                        + (bmp.getByteCount() / 1024 / 1024) + " MB, " + ms + " ms");
                if (cfg == Bitmap.Config.ARGB_8888) {
                    final Bitmap show = bmp;
                    ui.post(() -> {
                        preview.setImageBitmap(show);
                        preview.setVisibility(View.VISIBLE);
                    });
                    log("A kép megjelent. Vissza vagy OK gombbal zárd be.");
                } else {
                    bmp.recycle();
                }
            }
        } catch (OutOfMemoryError e) {
            log("ELFOGYOTT A MEMÓRIA a betöltésnél!");
        } catch (Exception e) {
            log("Hiba: " + e);
        }
    }
}
