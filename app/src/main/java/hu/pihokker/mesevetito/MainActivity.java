package hu.pihokker.mesevetito;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements ThumbCache.Listener {

    private static final int REQ_READ = 1;
    private static final int REQ_TREE = 2;

    private static final int CREAM = Color.rgb(243, 230, 200);
    private static final int CANDLE = Color.rgb(232, 200, 138);
    private static final int MUTED = Color.rgb(170, 160, 144);
    private static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
    private static final Typeface LIGHT = Typeface.create("sans-serif-light", Typeface.NORMAL);
    private static final int SCALE_MIN = 50, SCALE_STEP = 5;          // képméret: 100% → 50%
    private static final long[] SPEEDS = {1200, 1800, 2600, 3600};    // lapozás hossza (ms)
    private static final String[] SPEED_NAMES = {"gyors", "normál", "lassú", "nagyon lassú"};
    private static final int SPEED_DEFAULT = 2;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService scanner = Executors.newSingleThreadExecutor();

    private SharedPreferences prefs;
    private ThumbCache thumbs;
    private MusicPlayer music;
    private Reader reader;

    private float unit; // 1 egység = képernyőmagasság / 1080

    private View libraryLayer;
    private GridView grid;
    private Button settingsBtn;
    private FrameLayout settingsLayer;
    private LinearLayout settingsRows;
    private TextView settingsHint;
    private final List<Row> rows = new ArrayList<>();
    private LinearLayout messageLayer;
    private TextView messageText;
    private Button actionBtn;
    private FrameLayout readerLayer;
    private ReaderView readerView;
    private TextView toast;

    /** Ami épp a rácsban látszik: Library.Shelf (gyűjtemény) vagy Library.Book. */
    private final List<Object> items = new ArrayList<>();
    private Library library;
    private Library.Shelf shelf;      // a megnyitott gyűjtemény, vagy null (főképernyő)
    private TextView crumb;
    private BooksAdapter adapter;
    private int selected = 0;
    private int lastStatus = -1;
    private boolean scanning;
    private boolean rescanQueued;
    private boolean swallowBackUp;

    // ================================================================ életciklus

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("mesevetito", MODE_PRIVATE);
        thumbs = new ThumbCache(this);
        thumbs.addListener(this);
        music = new MusicPlayer(this);
        music.setEnabled(prefs.getBoolean("music", true));

        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        unit = Math.min(dm.widthPixels, dm.heightPixels) / 1080f;

        buildUi();
        reader = new Reader(readerView, (FilmStripView) readerLayer.getChildAt(1), thumbs, prefs);
        reader.setBox(Math.max(dm.widthPixels, dm.heightPixels), Math.min(dm.widthPixels, dm.heightPixels));
        applyScale();
        applySpeed();

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_MEDIA_MOUNTED);
        f.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        f.addAction(Intent.ACTION_MEDIA_REMOVED);
        f.addAction(Intent.ACTION_MEDIA_EJECT);
        f.addAction(Intent.ACTION_MEDIA_BAD_REMOVAL);
        f.addDataScheme("file");
        registerReceiver(usbReceiver, f);

        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_READ);
        } else {
            rescan();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        stoppedAt = -1;
        music.setForeground(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSettings(); // pl. visszatérés az engedély-képernyőről
        // pl. visszatérés az engedély-beállításból
        if (lastStatus != -1 && lastStatus != Library.OK) rescan();
    }

    @Override
    protected void onStop() {
        super.onStop();
        stoppedAt = android.os.SystemClock.elapsedRealtime();
        music.setForeground(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(usbReceiver);
        } catch (Exception ignored) {
        }
        music.destroy();
        reader.close();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode == REQ_READ) rescan();
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (!Intent.ACTION_MEDIA_MOUNTED.equals(i.getAction()) && reader.isOpen()) {
                closeBook(false);
            }
            if (Intent.ACTION_MEDIA_MOUNTED.equals(i.getAction()) && wasForeground()) {
                // a rendszer "mit tegyek a pendrive-val?" ablaka után visszajövünk előre
                main.postDelayed(bringBack, 1500);
                main.postDelayed(bringBack, 4000);
            }
            main.removeCallbacks(delayedRescan);
            main.postDelayed(delayedRescan, 900);
        }
    };

    private final Runnable delayedRescan = this::rescan;

    private long stoppedAt = -1; // mikor került háttérbe (−1: épp látszik)

    /** Látszik az app, vagy csak most (pár másodperce) takarta el valami. */
    private boolean wasForeground() {
        return stoppedAt < 0 || android.os.SystemClock.elapsedRealtime() - stoppedAt < 8000;
    }

    private final Runnable bringBack = () -> {
        if (stoppedAt < 0) return; // már elöl vagyunk
        try {
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception ignored) {
        }
    };

    // ================================================================ beolvasás

    private void rescan() {
        if (scanning) {
            rescanQueued = true;
            return;
        }
        scanning = true;
        if (items.isEmpty()) showMessage("Könyvek keresése…", null, null);
        scanner.execute(() -> {
            Library lib = Library.scan(this);
            main.post(() -> {
                scanning = false;
                applyLibrary(lib);
                if (rescanQueued) {
                    rescanQueued = false;
                    rescan();
                }
            });
        });
    }

    private void applyLibrary(Library lib) {
        lastStatus = lib.status;
        music.setTracks(lib.music);
        refreshSettings();

        library = lib;
        String prevKey = selected < items.size() ? keyOf(items.get(selected)) : null;
        if (shelf != null) shelf = lib.shelf(shelf.key); // eltűnt? → vissza a főképernyőre
        items.clear();
        items.addAll(shelf != null ? shelf.books : lib.top());
        updateCrumb();
        adapter.notifyDataSetChanged();

        if (lib.status == Library.NO_USB) {
            showMessage("Dugd be a pendrive-ot a mesékkel.", null, null);
            return;
        }
        if (lib.status == Library.NEED_PERMISSION) {
            showMessage("A pendrive olvasásához engedély kell.", "Engedélyezés", this::askPermission);
            return;
        }
        if (items.isEmpty()) {
            showMessage("Nem találtam könyvet a pendrive-on.\n"
                    + "Minden könyv egy mappa legyen, benne az oldalak képei.", null, null);
            return;
        }

        hideMessage();
        int sel = 0;
        for (int i = 0; i < items.size(); i++) {
            if (keyOf(items.get(i)).equals(prevKey)) sel = i;
            requestCovers(items.get(i), i < 12);
        }
        selected = sel;
        if (!reader.isOpen()) {
            grid.requestFocus();
            grid.setSelection(sel);
        }
    }

    private void askPermission() {
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
                return;
            } catch (ActivityNotFoundException ignored) {
            }
        }
        // tartalék: a rendszer mappaválasztója
        Intent intent = null;
        StorageManager sm = (StorageManager) getSystemService(Context.STORAGE_SERVICE);
        for (StorageVolume v : sm.getStorageVolumes()) {
            if (v.isRemovable()) {
                intent = v.createOpenDocumentTreeIntent();
                break;
            }
        }
        if (intent == null) intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQ_TREE);
        } catch (ActivityNotFoundException e) {
            showMessage("Ezen a készüléken nem sikerült engedélyt kérni.", null, null);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_TREE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            try {
                getContentResolver().takePersistableUriPermission(data.getData(),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
            }
            rescan();
        }
    }

    // ================================================================ gyűjtemények

    private static String keyOf(Object o) {
        return o instanceof Library.Shelf ? ((Library.Shelf) o).key : ((Library.Book) o).key;
    }

    /** A gyűjtemény mozaikjához: az első (max. 4) könyv borítója. */
    private static List<Node> coverNodes(Library.Shelf s) {
        List<Node> out = new ArrayList<>(4);
        for (int i = 0; i < s.books.size() && i < 4; i++) out.add(s.books.get(i).pages.get(0));
        return out;
    }

    private void requestCovers(Object o, boolean urgent) {
        if (o instanceof Library.Shelf) {
            for (Node n : coverNodes((Library.Shelf) o)) thumbs.request(n, urgent);
        } else {
            thumbs.request(((Library.Book) o).pages.get(0), urgent);
        }
    }

    private void activate(int pos) {
        if (pos < 0 || pos >= items.size() || reader.isOpen()) return;
        Object o = items.get(pos);
        if (o instanceof Library.Shelf) enterShelf((Library.Shelf) o);
        else openBook(pos);
    }

    private void enterShelf(Library.Shelf s) {
        shelf = s;
        showItems(new ArrayList<>(s.books), 0);
    }

    private void exitShelf() {
        if (shelf == null || library == null) return;
        String key = shelf.key;
        shelf = null;
        List<Object> top = library.top();
        int sel = 0;
        for (int i = 0; i < top.size(); i++) if (keyOf(top.get(i)).equals(key)) sel = i;
        showItems(top, sel);
    }

    /** Csendes áttűnés a főképernyő és egy gyűjtemény között. */
    private void showItems(List<?> list, int sel) {
        grid.animate().cancel();
        grid.animate().alpha(0f).setDuration(200).withEndAction(() -> {
            items.clear();
            items.addAll(list);
            selected = sel;
            adapter.notifyDataSetChanged();
            updateCrumb();
            for (int i = 0; i < items.size(); i++) requestCovers(items.get(i), i < 12);
            grid.requestFocus();
            grid.setSelection(sel);
            grid.animate().alpha(1f).setDuration(380)
                    .setInterpolator(new DecelerateInterpolator()).start();
        }).start();
    }

    private void updateCrumb() {
        if (crumb != null) crumb.setText(shelf != null ? "‹   " + shelf.title : "");
    }

    // ================================================================ könyv nyitása / zárása

    private void openBook(int pos) {
        if (pos < 0 || pos >= items.size() || reader.isOpen()) return;
        if (!(items.get(pos) instanceof Library.Book)) return;
        selected = pos;
        Library.Book b = (Library.Book) items.get(pos);
        readerLayer.setAlpha(0f);
        readerLayer.setVisibility(View.VISIBLE);
        readerView.setScaleX(1.04f);
        readerView.setScaleY(1.04f);
        reader.open(b, () -> {
            readerLayer.animate().alpha(1f).setDuration(900)
                    .setInterpolator(new DecelerateInterpolator())
                    .withEndAction(() -> libraryLayer.setVisibility(View.INVISIBLE)).start();
            readerView.animate().scaleX(1f).scaleY(1f).setDuration(1600)
                    .setInterpolator(new DecelerateInterpolator()).start();
        });
    }

    private void closeBook(boolean animated) {
        if (!reader.isOpen()) return;
        libraryLayer.setVisibility(View.VISIBLE);
        adapter.notifyDataSetChanged(); // a haladás-csík frissüljön
        grid.requestFocus();
        grid.setSelection(selected);
        if (animated) {
            readerLayer.animate().alpha(0f).setDuration(550)
                    .withEndAction(() -> {
                        readerLayer.setVisibility(View.GONE);
                        reader.close();
                    }).start();
        } else {
            readerLayer.setVisibility(View.GONE);
            reader.close();
        }
    }

    // ================================================================ gombok

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();

        // a könyvből kilépő Vissza gomb felengedése ne zárja be az egész appot
        if ((code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE)
                && e.getAction() == KeyEvent.ACTION_UP && swallowBackUp) {
            swallowBackUp = false;
            return true;
        }

        if (settingsLayer.getVisibility() == View.VISIBLE) return settingsKey(e);

        if (code == KeyEvent.KEYCODE_MENU && !reader.isOpen()) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) openSettings();
            return true;
        }
        if (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_MENU) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) toggleMusic();
            return true;
        }

        if (reader.isOpen()) {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_PAGE_DOWN:
                case KeyEvent.KEYCODE_CHANNEL_DOWN:
                case KeyEvent.KEYCODE_MEDIA_NEXT:
                    reader.step(+1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_PAGE_UP:
                case KeyEvent.KEYCODE_CHANNEL_UP:
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                    reader.step(-1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_MEDIA_REWIND:
                    reader.fast(-1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                    reader.fast(+1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (e.getRepeatCount() == 0) toggleMusic();
                    return true;
                case KeyEvent.KEYCODE_BACK:
                case KeyEvent.KEYCODE_ESCAPE:
                    if (e.getRepeatCount() == 0) {
                        swallowBackUp = true;
                        closeBook(true);
                    }
                    return true;
                default:
                    return super.dispatchKeyEvent(e);
            }
        }
        if (shelf != null && (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE)) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                swallowBackUp = true;
                exitShelf();
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private void toggleMusic() {
        boolean on = !music.isEnabled();
        music.setEnabled(on);
        prefs.edit().putBoolean("music", on).apply();
        refreshSettings();
        if (reader.isOpen()) {
            if (!music.hasTracks()) showToast("Nincs zene a pendrive-on");
            else showToast(on ? "♪  Zene be" : "Zene ki");
        }
    }

    private int scalePct() {
        int pct = Math.round(prefs.getFloat("scale", 1f) * 100);
        return Math.max(SCALE_MIN, Math.min(100, Math.round(pct / (float) SCALE_STEP) * SCALE_STEP));
    }

    private void applyScale() {
        readerView.setImageScale(scalePct() / 100f);
    }

    private int speedIndex() {
        return Math.max(0, Math.min(SPEEDS.length - 1, prefs.getInt("speed", SPEED_DEFAULT)));
    }

    private void applySpeed() {
        readerView.setSlideMs(SPEEDS[speedIndex()]);
    }

    // ================================================================ beállítások

    /** Egy sor a beállítások panelen: név, érték, és a ‹ › gombokra a változtatás. */
    private static final class Row {
        LinearLayout view;
        TextView label, value;
        java.util.function.Supplier<String> text;
        java.util.function.BiConsumer<Integer, Boolean> change; // irány, körbeforduljon-e
    }

    private void buildSettingsRows() {
        addRow("Háttérzene",
                () -> !music.hasTracks() ? "nincs zene a pendrive-on" : (music.isEnabled() ? "be" : "ki"),
                (dir, wrap) -> {
                    if (music.hasTracks()) toggleMusic();
                });
        addRow("Lapozás tempója",
                () -> {
                    int i = speedIndex();
                    return SPEED_NAMES[i] + "  (" + String.format(java.util.Locale.ROOT, "%.1f",
                            SPEEDS[i] / 1000f).replace('.', ',') + " mp)";
                },
                (dir, wrap) -> {
                    int i = speedIndex() - dir; // jobbra = gyorsabb
                    if (wrap) i = (i + SPEEDS.length) % SPEEDS.length;
                    i = Math.max(0, Math.min(SPEEDS.length - 1, i));
                    prefs.edit().putInt("speed", i).apply();
                    applySpeed();
                });
        addRow("Képméret",
                () -> scalePct() + " %",
                (dir, wrap) -> {
                    int pct = scalePct() + dir * SCALE_STEP; // jobbra = nagyobb
                    if (wrap && pct > 100) pct = SCALE_MIN;
                    pct = Math.max(SCALE_MIN, Math.min(100, pct));
                    prefs.edit().putFloat("scale", pct / 100f).apply();
                    applyScale();
                });
        addRow("Automatikus indítás",
                () -> {
                    if (!AutoStart.enabled(this)) return "ki";
                    return AutoStart.permitted(this) ? "be" : "be – engedély kell (OK)";
                },
                (dir, wrap) -> {
                    if (wrap && AutoStart.enabled(this) && !AutoStart.permitted(this)) {
                        requestOverlayPermission(); // OK: engedély-képernyő újra
                        return;
                    }
                    boolean on = !AutoStart.enabled(this);
                    prefs.edit().putBoolean(AutoStart.PREF, on).apply();
                    if (on && !AutoStart.permitted(this)) requestOverlayPermission();
                });
    }

    private void addRow(String label, java.util.function.Supplier<String> text,
                        java.util.function.BiConsumer<Integer, Boolean> change) {
        Row r = new Row();
        r.text = text;
        r.change = change;
        r.view = new LinearLayout(this);
        r.view.setOrientation(LinearLayout.HORIZONTAL);
        r.view.setGravity(Gravity.CENTER_VERTICAL);
        r.view.setPadding(px(34), 0, px(34), 0);
        r.view.setFocusable(true);
        GradientDrawable normal = new GradientDrawable();
        normal.setCornerRadius(px(14));
        normal.setColor(Color.TRANSPARENT);
        GradientDrawable focused = new GradientDrawable();
        focused.setCornerRadius(px(14));
        focused.setColor(Color.argb(26, 243, 230, 200));
        focused.setStroke(Math.max(1, px(2)), Color.argb(130, 232, 200, 138));
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_focused}, focused);
        sl.addState(new int[]{}, normal);
        r.view.setBackground(sl);

        r.label = new TextView(this);
        r.label.setText(label);
        r.label.setTypeface(LIGHT);
        r.label.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(30));
        r.view.addView(r.label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.value = new TextView(this);
        r.value.setTypeface(REGULAR);
        r.value.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(30));
        r.view.addView(r.value);
        r.view.setOnFocusChangeListener((v, has) -> paintRow(r));
        r.view.setOnClickListener(v -> {
            r.change.accept(+1, true);
            refreshSettings();
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px(84));
        lp.bottomMargin = px(6);
        settingsRows.addView(r.view, lp);
        rows.add(r);
    }

    private void paintRow(Row r) {
        boolean f = r.view.isFocused();
        r.label.setTextColor(f ? CREAM : MUTED);
        r.value.setTextColor(f ? CREAM : MUTED);
        r.value.setText((f ? "‹   " : "") + r.text.get() + (f ? "   ›" : ""));
    }

    private void refreshSettings() {
        for (Row r : rows) paintRow(r);
    }

    private void openSettings() {
        if (reader.isOpen()) return;
        settingsHint.setText("‹ ›  módosítás        Vissza  bezárás");
        refreshSettings();
        settingsLayer.animate().cancel();
        settingsLayer.setAlpha(0f);
        settingsLayer.setVisibility(View.VISIBLE);
        settingsLayer.animate().alpha(1f).setDuration(260).start();
        rows.get(0).view.requestFocus();
    }

    private void closeSettings() {
        settingsLayer.animate().cancel();
        settingsLayer.animate().alpha(0f).setDuration(220)
                .withEndAction(() -> settingsLayer.setVisibility(View.GONE)).start();
        if (grid.getVisibility() == View.VISIBLE) grid.requestFocus();
        else settingsBtn.requestFocus();
    }

    private boolean settingsKey(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        Row focused = null;
        for (Row r : rows) if (r.view.isFocused()) focused = r;
        switch (code) {
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_MENU:
                if (down && e.getRepeatCount() == 0) {
                    swallowBackUp = code != KeyEvent.KEYCODE_MENU;
                    closeSettings();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down && focused != null) {
                    focused.change.accept(code == KeyEvent.KEYCODE_DPAD_RIGHT ? +1 : -1, false);
                    refreshSettings();
                }
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    private void requestOverlayPermission() {
        android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (am.isLowRamDevice()) {
            // itt a "más appok felett" engedély le van tiltva → kisegítő szolgáltatás
            settingsHint.setText("Kisegítő lehetőségek → „Mesevetítő – automatikus indítás” → be");
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (ActivityNotFoundException e) {
                settingsHint.setText("Ezen a projektoron nem érhetők el a Kisegítő lehetőségek.");
            }
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        } catch (ActivityNotFoundException ignored) {
        }
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
            settingsHint.setText("Keresd meg a listában a Mesevetítőt, és engedélyezd.");
        } catch (ActivityNotFoundException e) {
            settingsHint.setText("Ezen a projektoron nem érhető el az engedély-képernyő.");
        }
    }

    private void showToast(String text) {
        toast.setText(text);
        toast.animate().cancel();
        toast.setAlpha(0f);
        toast.setVisibility(View.VISIBLE);
        toast.animate().alpha(1f).setDuration(250).withEndAction(() ->
                toast.animate().alpha(0f).setStartDelay(1400).setDuration(700)
                        .withEndAction(() -> toast.setVisibility(View.GONE)).start()).start();
    }

    // ================================================================ előnézetek

    @Override
    public void onThumbReady(String key) {
        for (int i = 0; i < grid.getChildCount(); i++) {
            View child = grid.getChildAt(i);
            Object tag = child.getTag();
            if (tag instanceof Holder) {
                Holder h = (Holder) tag;
                if (key.equals(h.coverKey)) {
                    Bitmap b = thumbs.get(h.coverNode);
                    if (b != null) h.cover.setBitmap(b);
                }
                if (h.tileKeys != null) {
                    for (int j = 0; j < h.tileKeys.length; j++) {
                        if (key.equals(h.tileKeys[j])) h.cover.setTile(j, thumbs.get(h.tileNodes[j]));
                    }
                }
            }
        }
        FilmStripView strip = (FilmStripView) readerLayer.getChildAt(1);
        if (strip.getVisibility() == View.VISIBLE) strip.invalidate();
    }

    // ================================================================ felület

    private int px(float v) {
        return Math.round(v * unit);
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        GradientDrawable sky = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.rgb(15, 16, 21), Color.rgb(18, 17, 20), Color.rgb(22, 18, 17)});
        root.setBackground(sky);

        // ---- könyvtár
        LinearLayout lib = new LinearLayout(this);
        lib.setOrientation(LinearLayout.VERTICAL);
        lib.setPadding(px(90), px(36), px(90), 0);
        libraryLayer = lib;

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        crumb = new TextView(this);
        crumb.setTextColor(MUTED);
        crumb.setTypeface(LIGHT);
        crumb.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(32));
        crumb.setLetterSpacing(0.03f);
        crumb.setSingleLine(true);
        crumb.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(crumb, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        settingsBtn = makeButton("Beállítások");
        settingsBtn.setOnClickListener(v -> openSettings());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, px(52));
        blp.leftMargin = px(14);
        header.addView(settingsBtn, blp);

        lib.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px(70)));

        grid = new GridView(this);
        grid.setNumColumns(4);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(px(40));
        grid.setVerticalSpacing(px(34));
        grid.setPadding(0, px(30), 0, px(70));
        grid.setClipToPadding(false);
        grid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        grid.setVerticalScrollBarEnabled(false);
        grid.setFocusable(true);
        adapter = new BooksAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> activate(pos));
        grid.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                selected = pos;
                refreshHighlight();
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
                refreshHighlight();
            }
        });
        grid.setOnFocusChangeListener((v, has) -> refreshHighlight());
        lib.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(lib, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- üzenet (nincs pendrive, engedély, stb.)
        messageLayer = new LinearLayout(this);
        messageLayer.setOrientation(LinearLayout.VERTICAL);
        messageLayer.setGravity(Gravity.CENTER);
        messageText = new TextView(this);
        messageText.setTextColor(CREAM);
        messageText.setGravity(Gravity.CENTER);
        messageText.setTypeface(LIGHT);
        messageText.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(34));
        messageText.setLineSpacing(px(10), 1f);
        messageLayer.addView(messageText);
        actionBtn = makeButton("");
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, px(72));
        alp.topMargin = px(40);
        messageLayer.addView(actionBtn, alp);
        messageLayer.setVisibility(View.GONE);
        root.addView(messageLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- olvasó
        readerLayer = new FrameLayout(this);
        readerLayer.setBackgroundColor(Color.rgb(8, 7, 6));
        readerView = new ReaderView(this);
        readerLayer.addView(readerView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FilmStripView strip = new FilmStripView(this);
        readerLayer.addView(strip, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px(330), Gravity.BOTTOM));
        toast = new TextView(this);
        toast.setTextColor(Color.rgb(200, 190, 170));
        toast.setTypeface(LIGHT);
        toast.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(28));
        toast.setPadding(px(30), px(14), px(30), px(14));
        GradientDrawable tbg = new GradientDrawable();
        tbg.setColor(Color.argb(140, 12, 10, 8));
        tbg.setCornerRadius(px(40));
        toast.setBackground(tbg);
        toast.setVisibility(View.GONE);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        tlp.setMargins(0, px(40), px(50), 0);
        readerLayer.addView(toast, tlp);
        readerLayer.setVisibility(View.GONE);
        root.addView(readerLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- beállítások panel
        settingsLayer = new FrameLayout(this);
        settingsLayer.setBackgroundColor(Color.argb(170, 6, 6, 8));
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(px(40), px(40), px(40), px(30));
        GradientDrawable pbg = new GradientDrawable();
        pbg.setCornerRadius(px(22));
        pbg.setColor(Color.rgb(24, 22, 24));
        pbg.setStroke(Math.max(1, px(1)), Color.argb(40, 243, 230, 200));
        panel.setBackground(pbg);
        TextView ptitle = new TextView(this);
        ptitle.setText("Beállítások");
        ptitle.setTextColor(CREAM);
        ptitle.setTypeface(LIGHT);
        ptitle.setLetterSpacing(0.04f);
        ptitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(36));
        ptitle.setPadding(px(34), 0, 0, px(24));
        panel.addView(ptitle);
        settingsRows = new LinearLayout(this);
        settingsRows.setOrientation(LinearLayout.VERTICAL);
        panel.addView(settingsRows);
        settingsHint = new TextView(this);
        settingsHint.setTextColor(Color.rgb(110, 103, 92));
        settingsHint.setTypeface(LIGHT);
        settingsHint.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(24));
        settingsHint.setGravity(Gravity.CENTER_HORIZONTAL);
        settingsHint.setPadding(0, px(22), 0, 0);
        panel.addView(settingsHint);
        settingsLayer.addView(panel, new FrameLayout.LayoutParams(px(980),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        settingsLayer.setVisibility(View.GONE);
        root.addView(settingsLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        buildSettingsRows();

        setContentView(root);
    }

    private Button makeButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(new android.content.res.ColorStateList(
                new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{CREAM, MUTED}));
        b.setTypeface(LIGHT);
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(23));
        b.setLetterSpacing(0.04f);
        b.setPadding(px(24), 0, px(24), 0);
        b.setFocusable(true);
        GradientDrawable normal = new GradientDrawable();
        normal.setCornerRadius(px(26));
        normal.setColor(Color.TRANSPARENT);
        GradientDrawable focused = new GradientDrawable();
        focused.setCornerRadius(px(26));
        focused.setColor(Color.argb(28, 243, 230, 200));
        focused.setStroke(Math.max(1, px(2)), Color.argb(160, 232, 200, 138));
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_focused}, focused);
        sl.addState(new int[]{android.R.attr.state_pressed}, focused);
        sl.addState(new int[]{}, normal);
        b.setBackground(sl);
        b.setStateListAnimator(null);
        return b;
    }

    private void showMessage(String text, String action, Runnable onAction) {
        messageText.setText(text);
        if (action != null) {
            actionBtn.setText(action);
            actionBtn.setOnClickListener(v -> onAction.run());
            actionBtn.setVisibility(View.VISIBLE);
            actionBtn.requestFocus();
        } else {
            actionBtn.setVisibility(View.GONE);
        }
        messageLayer.setVisibility(View.VISIBLE);
        grid.setVisibility(View.INVISIBLE);
    }

    private void hideMessage() {
        messageLayer.setVisibility(View.GONE);
        grid.setVisibility(View.VISIBLE);
    }

    private void refreshHighlight() {
        boolean focused = grid.hasFocus();
        int first = grid.getFirstVisiblePosition();
        for (int i = 0; i < grid.getChildCount(); i++) {
            View child = grid.getChildAt(i);
            boolean on = focused && first + i == selected;
            Object tag = child.getTag();
            if (tag instanceof Holder) {
                Holder h = (Holder) tag;
                h.cover.setHighlighted(on);
                h.title.setTextColor(on ? CREAM : MUTED);
            }
            float s = on ? 1.04f : 1f;
            child.animate().scaleX(s).scaleY(s).setDuration(320)
                    .setInterpolator(new DecelerateInterpolator()).start();
        }
    }

    // ================================================================ borítórács

    private static final class Holder {
        CoverView cover;
        TextView title;
        Node coverNode;
        String coverKey;
        Node[] tileNodes;
        String[] tileKeys;
    }

    private final class BooksAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int i) {
            return items.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            Holder h;
            if (convert == null) {
                LinearLayout cell = new LinearLayout(MainActivity.this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                h = new Holder();
                h.cover = new CoverView(MainActivity.this);
                cell.addView(h.cover, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, px(270)));
                h.title = new TextView(MainActivity.this);
                h.title.setTextColor(MUTED);
                h.title.setTypeface(REGULAR);
                h.title.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(31));
                h.title.setLetterSpacing(0.02f);
                h.title.setLineSpacing(px(4), 1f);
                h.title.setGravity(Gravity.CENTER_HORIZONTAL);
                h.title.setMaxLines(2);
                h.title.setEllipsize(TextUtils.TruncateAt.END);
                h.title.setPadding(px(6), px(14), px(6), 0);
                cell.addView(h.title, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, px(104)));
                cell.setTag(h);
                convert = cell;
            } else {
                h = (Holder) convert.getTag();
            }
            Object item = items.get(pos);
            if (item instanceof Library.Shelf) {
                Library.Shelf sh = (Library.Shelf) item;
                List<Node> ns = coverNodes(sh);
                h.coverNode = null;
                h.coverKey = null;
                h.tileNodes = ns.toArray(new Node[0]);
                h.tileKeys = new String[ns.size()];
                Bitmap[] tiles = new Bitmap[ns.size()];
                for (int j = 0; j < ns.size(); j++) {
                    h.tileKeys[j] = ThumbCache.keyOf(ns.get(j));
                    tiles[j] = thumbs.get(ns.get(j));
                    if (tiles[j] == null) thumbs.request(ns.get(j), true);
                }
                h.cover.bindMosaic(tiles, sh.books.size(), sh.title);
                h.title.setText(sh.title + "   ·   " + sh.books.size());
                boolean on = grid.hasFocus() && pos == selected;
                h.cover.setHighlighted(on);
                h.title.setTextColor(on ? CREAM : MUTED);
                convert.setScaleX(on ? 1.04f : 1f);
                convert.setScaleY(on ? 1.04f : 1f);
                return convert;
            }
            Library.Book b = (Library.Book) item;
            h.tileNodes = null;
            h.tileKeys = null;
            h.coverNode = b.pages.get(0);
            h.coverKey = ThumbCache.keyOf(h.coverNode);
            Bitmap bmp = thumbs.get(h.coverNode);
            if (bmp == null) thumbs.request(h.coverNode, true);
            int saved = Reader.savedPage(prefs, b);
            float progress = b.pages.size() > 1 && saved > 0
                    ? Math.min(1f, (float) saved / (b.pages.size() - 1)) : 0f;
            h.cover.bind(bmp, b.title, progress);
            h.title.setText(b.title);
            boolean on = grid.hasFocus() && pos == selected;
            h.cover.setHighlighted(on);
            h.title.setTextColor(on ? CREAM : MUTED);
            convert.setScaleX(on ? 1.04f : 1f);
            convert.setScaleY(on ? 1.04f : 1f);
            return convert;
        }
    }
}
