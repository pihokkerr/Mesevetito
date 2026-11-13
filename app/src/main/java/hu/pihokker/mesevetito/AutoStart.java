package hu.pihokker.mesevetito;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

/**
 * Automatikus indítás a projektor bekapcsolásakor és pendrive bedugásakor.
 * Bedugáskor kicsit vár, hogy a rendszer "mit tegyek a pendrive-val?" ablaka már
 * megjelenjen, és utána hozza előre az appot, így az ablak a háttérbe kerül.
 *
 * Android 10 felett háttérből csak "megjelenítés más alkalmazások felett"
 * engedéllyel indulhat app – ezt a Beállításokban kell egyszer megadni.
 */
public final class AutoStart extends BroadcastReceiver {

    static final String PREF = "autostart";

    static boolean enabled(Context c) {
        return c.getSharedPreferences("mesevetito", Context.MODE_PRIVATE).getBoolean(PREF, false);
    }

    static boolean permitted(Context c) {
        return Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(c);
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (!enabled(ctx) || !permitted(ctx)) return;
        String a = intent.getAction();
        boolean mount = Intent.ACTION_MEDIA_MOUNTED.equals(a);
        Context app = ctx.getApplicationContext();
        PendingResult pr = goAsync();
        Handler h = new Handler(Looper.getMainLooper());
        // bedugásnál kétszer is előrejövünk: ha a rendszerablak később ugrik fel, azt is eltakarjuk
        long[] delays = mount ? new long[]{1500, 4000} : new long[]{2500, 6000};
        h.postDelayed(() -> bringToFront(app), delays[0]);
        h.postDelayed(() -> {
            bringToFront(app);
            pr.finish();
        }, delays[1]);
    }

    static void bringToFront(Context c) {
        try {
            Intent i = new Intent(c, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            i.putExtra("autostart", true);
            c.startActivity(i);
        } catch (Exception ignored) {
        }
    }
}
