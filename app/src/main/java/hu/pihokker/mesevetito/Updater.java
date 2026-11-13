package hu.pihokker.mesevetito;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Frissítés közvetlenül a GitHubról: a legfrissebb build verziószáma a version.txt-ben,
 * az APK mellette. A telepítést a rendszer telepítője végzi (egy megerősítő ablakkal).
 */
final class Updater {

    static final String BASE = "https://github.com/pihokkerr/Mesevetito/releases/download/latest/";
    static final String VERSION_URL = BASE + "version.txt";
    static final String APK_URL = BASE + "Mesevetito.apk";
    static final String ACTION_RESULT = "hu.pihokker.mesevetito.INSTALL_RESULT";

    interface Listener {
        void onState(String text);
    }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean busy;
    int remoteVersion = -1;

    Updater(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    boolean isBusy() {
        return busy;
    }

    long localVersion() {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).getLongVersionCode();
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    String localName() {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    boolean updateAvailable() {
        return remoteVersion > localVersion();
    }

    /** Csak megnézi, van-e újabb verzió. */
    void check(Listener l) {
        if (busy) return;
        busy = true;
        new Thread(() -> {
            String msg;
            try {
                remoteVersion = fetchVersion();
                msg = updateAvailable()
                        ? "új verzió: 1.0." + remoteVersion + "  –  OK: telepítés"
                        : "naprakész  (" + localName() + ")";
            } catch (Exception e) {
                msg = "nem sikerült ellenőrizni (van internet?)";
            }
            busy = false;
            post(l, msg);
        }, "update-check").start();
    }

    /** Letölti és a rendszer telepítőjének átadja a legfrissebb verziót. */
    void downloadAndInstall(Listener l) {
        if (busy) return;
        busy = true;
        new Thread(() -> {
            PackageInstaller.Session session = null;
            try {
                post(l, "letöltés…");
                PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params =
                        new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(ctx.getPackageName());
                int id = pi.createSession(params);
                session = pi.openSession(id);

                HttpURLConnection c = open(APK_URL);
                long total = c.getContentLengthLong();
                try (InputStream in = c.getInputStream();
                     OutputStream out = session.openWrite("base.apk", 0, total > 0 ? total : -1)) {
                    byte[] buf = new byte[64 * 1024];
                    long done = 0;
                    int lastPct = -1;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        done += n;
                        if (total > 0) {
                            int pct = (int) (done * 100 / total);
                            if (pct != lastPct && pct % 5 == 0) {
                                lastPct = pct;
                                post(l, "letöltés…  " + pct + " %");
                            }
                        }
                    }
                    session.fsync(out);
                } finally {
                    c.disconnect();
                }

                Intent cb = new Intent(ACTION_RESULT).setPackage(ctx.getPackageName());
                PendingIntent pending = PendingIntent.getBroadcast(ctx, id, cb,
                        PendingIntent.FLAG_UPDATE_CURRENT | (android.os.Build.VERSION.SDK_INT >= 31
                                ? PendingIntent.FLAG_MUTABLE : 0));
                post(l, "telepítés…");
                session.commit(pending.getIntentSender());
                session.close();
                session = null;
            } catch (Exception e) {
                if (session != null) session.abandon();
                post(l, "a letöltés nem sikerült (van internet?)");
            } finally {
                busy = false;
            }
        }, "update-download").start();
    }

    private int fetchVersion() throws Exception {
        HttpURLConnection c = open(VERSION_URL + "?t=" + System.currentTimeMillis());
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
            return Integer.parseInt(r.readLine().trim());
        } finally {
            c.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(20_000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "Mesevetito");
        int code = c.getResponseCode();
        if (code != 200) throw new Exception("HTTP " + code);
        return c;
    }

    private void post(Listener l, String msg) {
        main.post(() -> l.onState(msg));
    }
}
