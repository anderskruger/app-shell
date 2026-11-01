package io.github.anderskruger.platelog;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.RemoteViews;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import java.util.Date;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The lock-screen card: an ongoing notification with a big timer and a button that completes the next step
 * without unlocking the phone. Its state lives in SharedPreferences so the card, the button and the timed
 * alerts keep working after Android stops the app; the page reads back what was done with Session.take().
 *
 * State (set by the page with Session.show):
 *   steps     [{ id, title, text, sound, data, at }]  what's next, in order; the button completes the first open one
 *             (a step with `at` opens at that time, e.g. a quest that arrives later)
 *   since     ms timestamp the timer counts up from (0 = no timer yet)
 *   restart   false: the button doesn't restart the timer (default true)
 *   waitTitle, waitText  shown while every remaining step opens later: "Next quest" / "arrives at" + time
 *   label     line above the timer ("Resting")
 *   alerts    [{ after (s), label, title, channel, channelName, sound }]  beeps after `since`, only while the app is out of sight
 *   button, doneTitle, doneText, restLabel, volume, silent
 */
final class SessionCard {
    static final int CARD_ID = 7301;
    static final int ALERT_ID = 7302;
    static final int MAX_ALERTS = 10;
    static final String CHANNEL = "session";
    private static final String PREFS = "session";

    private SessionCard() {}

    static JSONObject state(Context c) {
        try { return new JSONObject(prefs(c).getString("state", "{}")); } catch (JSONException e) { return new JSONObject(); }
    }

    static void saveState(Context c, JSONObject st) { prefs(c).edit().putString("state", st.toString()).apply(); }

    static JSONArray pending(Context c) {
        try { return new JSONArray(prefs(c).getString("pending", "[]")); } catch (JSONException e) { return new JSONArray(); }
    }

    static void savePending(Context c, JSONArray p) { prefs(c).edit().putString("pending", p.toString()).apply(); }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static boolean canNotify(Context c) {
        return Build.VERSION.SDK_INT < 33
            || ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    /** Draws (or redraws) the card from the saved state. */
    static void post(Context c) {
        JSONObject st = state(c);
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (!st.optBoolean("active")) { nm.cancel(CARD_ID); return; }
        if (!canNotify(c)) return;
        ensureCardChannel(c);

        long now = System.currentTimeMillis();
        JSONObject next = nextStep(st, now);
        long upcoming = nextUpcoming(st, now);
        String title, text;
        if (next != null) {
            title = next.optString("title");
            text = next.optString("text");
            int more = next.optLong("at", 0) > 0 ? available(st, now) - 1 : 0;
            if (more > 0) text += " · +" + more + " more";
        } else if (upcoming > 0) {   // steps that only open later: say when the next one does
            title = st.optString("waitTitle", "Next");
            text = st.optString("waitText", "at") + " " + DateFormat.getTimeFormat(c).format(new Date(upcoming));
        } else {
            title = st.optString("doneTitle", "All done");
            text = st.optString("doneText", "");
        }
        long since = st.optLong("since", 0);
        boolean waiting = next == null && upcoming > 0;
        String label = next == null && !waiting ? "Done" : since > 0 ? st.optString("label", "Resting") : "Next up";

        RemoteViews small = views(c, R.layout.session_card, st, next, waiting, title, text, label, since);
        RemoteViews big = views(c, R.layout.session_card_big, st, next, waiting, title, text, label, since);

        Notification n = new NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setColor(Color.parseColor("#f3c233"))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(new NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(small)
            .setCustomBigContentView(big)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openApp(c))
            .build();
        try { nm.notify(CARD_ID, n); } catch (SecurityException ignored) {}
    }

    // A step with `at` (ms) only opens at that time; steps without one are open straight away.
    static int nextIndex(JSONObject st, long now) {
        JSONArray steps = st.optJSONArray("steps");
        if (steps == null) return -1;
        for (int i = 0; i < steps.length(); i++) if (steps.optJSONObject(i).optLong("at", 0) <= now + 1000) return i;
        return -1;
    }

    static JSONObject nextStep(JSONObject st, long now) {
        int i = nextIndex(st, now);
        return i < 0 ? null : st.optJSONArray("steps").optJSONObject(i);
    }

    private static long nextUpcoming(JSONObject st, long now) {
        JSONArray steps = st.optJSONArray("steps");
        long soonest = 0;
        if (steps != null) for (int i = 0; i < steps.length(); i++) {
            long at = steps.optJSONObject(i).optLong("at", 0);
            if (at > now + 1000 && (soonest == 0 || at < soonest)) soonest = at;
        }
        return soonest;
    }

    private static int available(JSONObject st, long now) {
        JSONArray steps = st.optJSONArray("steps");
        int n = 0;
        if (steps != null) for (int i = 0; i < steps.length(); i++) {
            long at = steps.optJSONObject(i).optLong("at", 0);
            if (at > 0 && at <= now + 1000) n++;
        }
        return n;
    }

    private static RemoteViews views(Context c, int layout, JSONObject st, JSONObject next, boolean waiting,
                                     String title, String text, String label, long since) {
        RemoteViews v = new RemoteViews(c.getPackageName(), layout);
        v.setTextViewText(R.id.session_title, title);
        v.setTextViewText(R.id.session_text, text);
        v.setTextViewText(R.id.session_label, label);
        if (since > 0 && (next != null || waiting)) {
            // Chronometer counts on elapsedRealtime; the system keeps it ticking, so nothing here has to run.
            long base = SystemClock.elapsedRealtime() - (System.currentTimeMillis() - since);
            v.setChronometer(R.id.session_clock, base, null, true);
            v.setViewVisibility(R.id.session_clock, View.VISIBLE);
            v.setViewVisibility(R.id.session_clock_text, View.GONE);
        } else {
            v.setTextViewText(R.id.session_clock_text, waiting ? "…" : next == null ? "✓" : "Go");
            v.setViewVisibility(R.id.session_clock, View.GONE);
            v.setViewVisibility(R.id.session_clock_text, View.VISIBLE);
        }
        if (next != null) {
            v.setTextViewText(R.id.session_button, st.optString("button", "Done"));
            v.setOnClickPendingIntent(R.id.session_button, broadcast(c, SessionReceiver.ACTION_STEP, 1, 0));
            v.setViewVisibility(R.id.session_button, View.VISIBLE);
        } else {
            v.setViewVisibility(R.id.session_button, View.GONE);
        }
        return v;
    }

    static void cancel(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.cancel(CARD_ID);
        nm.cancel(ALERT_ID);
        cancelAlerts(c);
    }

    /** Timed alerts after `since`, as exact alarms so they fire while the phone sleeps. */
    static void scheduleAlerts(Context c) {
        cancelAlerts(c);
        JSONObject st = state(c);
        long since = st.optLong("since", 0);
        JSONArray alerts = st.optJSONArray("alerts");
        if (!st.optBoolean("active") || since <= 0 || alerts == null) return;
        AlarmManager am = c.getSystemService(AlarmManager.class);
        long now = System.currentTimeMillis();
        for (int i = 0; i < Math.min(alerts.length(), MAX_ALERTS); i++) {
            long at = since + alerts.optJSONObject(i).optLong("after") * 1000;
            if (at <= now + 500) continue;
            PendingIntent pi = broadcast(c, SessionReceiver.ACTION_ALERT, 200 + i, since, i);
            try {
                if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
                }
            } catch (SecurityException e) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            }
        }
    }

    private static void cancelAlerts(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        for (int i = 0; i < MAX_ALERTS; i++) am.cancel(broadcast(c, SessionReceiver.ACTION_ALERT, 200 + i, 0, i));
    }

    /** One alert: a short-lived notification on the alert's own channel, which carries its beep. */
    static void postAlert(Context c, JSONObject alert, JSONObject st) {
        if (!canNotify(c)) return;
        String channel = alert.optString("channel", "session-alert");
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm.getNotificationChannel(channel) == null) {
                NotificationChannel ch = new NotificationChannel(channel, alert.optString("channelName", "Alerts"), NotificationManager.IMPORTANCE_HIGH);
                String sound = alert.optString("sound", "");
                if (!sound.isEmpty()) {
                    ch.setSound(Uri.parse("android.resource://" + c.getPackageName() + "/raw/" + sound),
                        new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build());
                }
                ch.enableVibration(true);
                ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
                nm.createNotificationChannel(ch);
            }
        }
        JSONObject next = nextStep(st, System.currentTimeMillis());
        String body = next != null ? next.optString("title") + " · " + next.optString("text") : st.optString("doneText", "");
        Notification n = new NotificationCompat.Builder(c, channel)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setColor(Color.parseColor("#f3c233"))
            .setContentTitle(alert.optString("title", alert.optString("label")))
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setTimeoutAfter(20000)
            .setContentIntent(openApp(c))
            .build();
        try { c.getSystemService(NotificationManager.class).notify(ALERT_ID, n); } catch (SecurityException ignored) {}
    }

    private static void ensureCardChannel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Lock-screen card", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("Timer and button on the lock screen while a session runs");
        ch.setSound(null, null);
        ch.enableVibration(false);
        ch.setShowBadge(false);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(ch);
    }

    private static PendingIntent openApp(Context c) {
        Intent i = c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
        if (i == null) i = new Intent(c, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    // Broadcasts, not activities: Android runs them straight from the lock screen without asking to unlock.
    private static PendingIntent broadcast(Context c, String action, int code, long since) { return broadcast(c, action, code, since, 0); }

    private static PendingIntent broadcast(Context c, String action, int code, long since, int index) {
        Intent i = new Intent(c, SessionReceiver.class).setAction(action)
            .putExtra("since", since).putExtra("index", index);
        return PendingIntent.getBroadcast(c, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
