package hu.pihokker.mesevetito;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;

/**
 * Kisegítő szolgáltatás, kizárólag az automatikus indításhoz.
 * A kevés memóriájú projektoron a "megjelenítés más appok felett" engedély le van tiltva;
 * egy bekapcsolt kisegítő szolgáltatással viszont az app a háttérből is előrejöhet.
 * A képernyő tartalmát nem olvassa, eseményeket nem figyel.
 */
public final class Starter extends AccessibilityService {

    static boolean isOn(Context c) {
        String list = Settings.Secure.getString(c.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (list == null) return false;
        String me = c.getPackageName() + "/" + Starter.class.getName();
        String shortMe = c.getPackageName() + "/." + Starter.class.getSimpleName();
        for (String s : list.split(":")) {
            if (s.equalsIgnoreCase(me) || s.equalsIgnoreCase(shortMe)) return true;
        }
        return false;
    }

    @Override
    protected void onServiceConnected() {
        // a rendszer induláskor kapcsolja be a szolgáltatást → ez a "bekapcsolás" jele
        if (AutoStart.enabled(this) && SystemClock.elapsedRealtime() < 5 * 60_000L) {
            Context app = getApplicationContext();
            Handler h = new Handler(Looper.getMainLooper());
            h.postDelayed(() -> AutoStart.bringToFront(app), 3000);
            h.postDelayed(() -> AutoStart.bringToFront(app), 8000);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
