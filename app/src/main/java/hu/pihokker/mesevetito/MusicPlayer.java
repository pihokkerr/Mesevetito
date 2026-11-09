package hu.pihokker.mesevetito;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Halk háttérzene. Két lejátszó váltja egymást: a szám vége előtt a következő
 * lassan felerősödik, az előző elhalkul, így a váltás szinte észrevétlen.
 */
final class MusicPlayer {

    private static final float MAX_GAIN = 0.6f;     // a rendszer-hangerőn belül ennyire szól
    private static final long CROSSFADE_MS = 7000;  // két szám közti áttűnés
    private static final long FADE_IN_MS = 3500;    // bekapcsoláskor
    private static final long FADE_OUT_MS = 2500;   // kikapcsoláskor / kilépéskor
    private static final long TICK_MS = 80;

    /** Egy lejátszó a saját hangerő-rámpájával. */
    private static final class Voice {
        MediaPlayer mp;
        float level;      // 0..1, ebből számoljuk a hangerőt
        float target;
        float step;       // tickenkénti változás
        boolean releaseAtZero;
        boolean pauseAtZero;
    }

    private final Context ctx;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final List<Uri> tracks = new ArrayList<>();
    private int next = 0;
    private Voice current;
    private Voice fading; // a kiúszó előző szám
    private boolean enabled;
    private boolean foreground;
    private boolean ticking;

    MusicPlayer(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    void setTracks(List<Node> nodes) {
        List<Uri> uris = new ArrayList<>();
        for (Node n : nodes) uris.add(n.uri());
        if (uris.equals(tracks)) return;
        tracks.clear();
        tracks.addAll(uris);
        Collections.shuffle(tracks);
        next = 0;
        if (tracks.isEmpty()) stopAll();
        update();
    }

    boolean hasTracks() {
        return !tracks.isEmpty();
    }

    boolean isEnabled() {
        return enabled;
    }

    void setEnabled(boolean on) {
        enabled = on;
        update();
    }

    void setForeground(boolean fg) {
        foreground = fg;
        update();
    }

    private boolean shouldPlay() {
        return enabled && foreground && !tracks.isEmpty();
    }

    private void update() {
        if (shouldPlay()) {
            if (current == null) {
                current = startTrack();
                if (current != null) ramp(current, 1f, FADE_IN_MS);
            } else {
                // szünetből folytatás
                if (!current.mp.isPlaying()) current.mp.start();
                current.pauseAtZero = false;
                current.releaseAtZero = false;
                ramp(current, 1f, FADE_IN_MS);
            }
        } else if (current != null) {
            current.pauseAtZero = true;
            ramp(current, 0f, FADE_OUT_MS);
            if (fading != null) ramp(fading, 0f, FADE_OUT_MS);
        }
        startTicking();
    }

    private Voice startTrack() {
        for (int tries = 0; tries < tracks.size(); tries++) {
            Uri u = tracks.get(next);
            next = (next + 1) % tracks.size();
            if (next == 0 && tracks.size() > 2) {
                // új kör: újrakeverés, de az épp lejátszott ne jöjjön rögtön újra
                Collections.shuffle(tracks);
                if (tracks.get(0).equals(u)) Collections.swap(tracks, 0, tracks.size() - 1);
            }
            try {
                MediaPlayer mp = new MediaPlayer();
                mp.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
                mp.setDataSource(ctx, u);
                mp.prepare();
                mp.setVolume(0f, 0f);
                Voice v = new Voice();
                v.mp = mp;
                mp.setOnCompletionListener(p -> onCompleted(v));
                mp.setOnErrorListener((p, what, extra) -> {
                    onCompleted(v);
                    return true;
                });
                mp.start();
                return v;
            } catch (Exception ignored) {
                // hibás fájl: próbáljuk a következőt
            }
        }
        return null;
    }

    /** Ha egy szám az áttűnés előtt ért véget (pl. nagyon rövid volt). */
    private void onCompleted(Voice v) {
        if (v == current) {
            release(v);
            current = null;
            if (shouldPlay()) {
                current = startTrack();
                if (current != null) ramp(current, 1f, 1500);
            }
        } else if (v == fading) {
            release(v);
            fading = null;
        }
    }

    private void ramp(Voice v, float target, long ms) {
        v.target = target;
        float dist = Math.abs(target - v.level);
        v.step = ms <= 0 ? 1f : Math.max(0.0001f, (float) TICK_MS / ms);
        if (dist == 0) v.step = 1f;
    }

    private void startTicking() {
        if (ticking) return;
        ticking = true;
        h.postDelayed(tick, TICK_MS);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            // áttűnés indítása a szám vége előtt
            if (shouldPlay() && current != null && fading == null && current.target == 1f) {
                try {
                    int dur = current.mp.getDuration();
                    int pos = current.mp.getCurrentPosition();
                    if (dur > CROSSFADE_MS * 2 && dur - pos <= CROSSFADE_MS) {
                        fading = current;
                        fading.releaseAtZero = true;
                        ramp(fading, 0f, CROSSFADE_MS);
                        current = startTrack();
                        if (current != null) ramp(current, 1f, CROSSFADE_MS);
                    }
                } catch (IllegalStateException ignored) {
                }
            }

            boolean active = false;
            active |= step(current);
            active |= step(fading);
            if (fading != null && fading.mp == null) fading = null;
            if (current != null && current.mp == null) current = null;

            // ha valamiért nem szól semmi, de kellene: új szám
            boolean broken = false;
            if (shouldPlay() && current == null && fading == null) {
                current = startTrack();
                if (current != null) ramp(current, 1f, FADE_IN_MS);
                else broken = true; // egyik fájl sem játszható le
            }

            boolean keep = !broken && (active || shouldPlay());
            if (keep) {
                h.postDelayed(this, TICK_MS);
            } else {
                ticking = false;
            }
        }
    };

    /** Egy lépés a rámpán; true, ha még van teendő. */
    private boolean step(Voice v) {
        if (v == null || v.mp == null) return false;
        if (v.level < v.target) v.level = Math.min(v.target, v.level + v.step);
        else if (v.level > v.target) v.level = Math.max(v.target, v.level - v.step);
        // egyenletes hangerősségű áttűnés (equal power)
        float gain = (float) (MAX_GAIN * Math.sin(v.level * Math.PI / 2));
        try {
            v.mp.setVolume(gain, gain);
        } catch (IllegalStateException ignored) {
        }
        if (v.level == 0f && v.target == 0f) {
            if (v.releaseAtZero) {
                release(v);
                return false;
            }
            if (v.pauseAtZero) {
                try {
                    if (v.mp.isPlaying()) v.mp.pause();
                } catch (IllegalStateException ignored) {
                }
                return false;
            }
        }
        return v.level != v.target;
    }

    private void release(Voice v) {
        if (v == null || v.mp == null) return;
        try {
            v.mp.release();
        } catch (Exception ignored) {
        }
        v.mp = null;
    }

    private void stopAll() {
        release(current);
        release(fading);
        current = null;
        fading = null;
    }

    void destroy() {
        h.removeCallbacks(tick);
        ticking = false;
        stopAll();
    }
}
