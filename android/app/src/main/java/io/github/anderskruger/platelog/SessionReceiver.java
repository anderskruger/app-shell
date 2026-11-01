package io.github.anderskruger.platelog;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import java.io.File;
import java.util.Random;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** The card's button and the timed alerts. Runs even when the app's page isn't, and without unlocking. */
public class SessionReceiver extends BroadcastReceiver {
    static final String ACTION_STEP = "io.github.anderskruger.session.STEP";
    static final String ACTION_ALERT = "io.github.anderskruger.session.ALERT";
    private static long lastStepAt = 0;

    @Override
    public void onReceive(Context c, Intent intent) {
        if (ACTION_STEP.equals(intent.getAction())) step(c);
        else if (ACTION_ALERT.equals(intent.getAction())) alert(c, intent.getLongExtra("since", 0), intent.getIntExtra("index", 0));
    }

    /** Completes the next step: records it for the page, restarts the timer and plays the step's sound. */
    private void step(Context c) {
        JSONObject st = SessionCard.state(c);
        JSONArray steps = st.optJSONArray("steps");
        long now = System.currentTimeMillis();
        int index = SessionCard.nextIndex(st, now);
        if (!st.optBoolean("active") || steps == null || index < 0) return;
        if (now - lastStepAt < 1500) return;   // a double tap shouldn't count twice
        lastStepAt = now;

        JSONObject step = steps.optJSONObject(index);
        JSONObject event = new JSONObject();
        try {
            event.put("id", step.optString("id"));
            event.put("data", step.opt("data"));
            event.put("at", now);
            st.put("steps", without(steps, index));
            if (st.optBoolean("restart", true)) {
                st.put("since", now);
                st.put("label", st.optString("restLabel", "Resting"));
            }
        } catch (JSONException e) { return; }
        JSONArray pending = SessionCard.pending(c);
        pending.put(event);
        SessionCard.savePending(c, pending);
        SessionCard.saveState(c, st);
        SessionCard.post(c);
        SessionCard.scheduleAlerts(c);
        SessionPlugin.emit(event);
        vibrate(c, 60);

        String sound = step.optString("sound", "");
        if (st.optBoolean("silent") || sound.isEmpty()) return;
        play(c, sound, (float) st.optDouble("volume", 0.6), goAsync());
    }

    private void alert(Context c, long since, int index) {
        JSONObject st = SessionCard.state(c);
        if (!st.optBoolean("active") || st.optLong("since", 0) != since) return;   // the timer restarted since this was set
        JSONArray alerts = st.optJSONArray("alerts");
        JSONObject alert = alerts != null ? alerts.optJSONObject(index) : null;
        if (alert == null) return;
        try { st.put("label", alert.optString("label")); } catch (JSONException ignored) {}
        SessionCard.saveState(c, st);
        SessionCard.post(c);
        // the page beeps by itself while it's on screen
        if (!SessionPlugin.visible && !st.optBoolean("silent")) SessionCard.postAlert(c, alert, st);
    }

    /** A random take from files/session-sounds/<group>/, at the page's volume, on the media stream like the page's own sounds. */
    private static void play(Context c, String group, float volume, PendingResult done) {
        File[] files = new File(SessionPlugin.soundDir(c), group).listFiles();
        if (files == null || files.length == 0) { done.finish(); return; }
        MediaPlayer mp = new MediaPlayer();
        try {
            mp.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            mp.setDataSource(files[new Random().nextInt(files.length)].getAbsolutePath());
            mp.setVolume(volume, volume);
            mp.setOnCompletionListener(p -> { p.release(); done.finish(); });
            mp.setOnErrorListener((p, what, extra) -> { p.release(); done.finish(); return true; });
            mp.prepare();
            mp.start();
        } catch (Exception e) {
            mp.release();
            done.finish();
        }
    }

    private static void vibrate(Context c, long ms) {
        Vibrator v = c.getSystemService(Vibrator.class);
        if (v == null) return;
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
        else v.vibrate(ms);
    }

    private static JSONArray without(JSONArray a, int index) {
        JSONArray out = new JSONArray();
        for (int i = 0; i < a.length(); i++) if (i != index) out.put(a.opt(i));
        return out;
    }
}
