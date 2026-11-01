package io.github.anderskruger.platelog;

import android.content.Context;
import android.util.Base64;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import java.io.FileOutputStream;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * window.Capacitor.Plugins.Session: a lock-screen card with a timer and a button (see SessionCard for the state).
 *   show(state)                    start or update the card
 *   hide()                         remove it, its alerts and anything not yet taken
 *   take() -> { events }           steps completed from the card since the last take: [{ id, data, at }]
 *   cacheSound({ group, name, data (base64) }), listSounds() -> { sounds: ["group/name"] }, pruneSounds({ keep })
 *   event "step" { id, data, at }  when the card's button is pressed while the page is running
 */
@CapacitorPlugin(name = "Session")
public class SessionPlugin extends Plugin {
    static volatile boolean visible = false;
    private static SessionPlugin instance;

    @Override
    public void load() { instance = this; visible = true; }

    @Override
    protected void handleOnResume() { visible = true; }

    @Override
    protected void handleOnPause() { visible = false; }

    @Override
    protected void handleOnDestroy() { if (instance == this) instance = null; }

    static void emit(JSONObject event) {
        SessionPlugin p = instance;
        if (p == null) return;
        try { p.notifyListeners("step", JSObject.fromJSONObject(event)); } catch (JSONException ignored) {}
    }

    static File soundDir(Context c) { return new File(c.getFilesDir(), "session-sounds"); }

    @PluginMethod
    public void show(PluginCall call) {
        Context c = getContext();
        JSObject st = call.getData();
        st.put("active", true);
        // steps the card already completed but the page hasn't taken yet aren't shown again
        Set<String> taken = new HashSet<>();
        JSONArray pending = SessionCard.pending(c);
        for (int i = 0; i < pending.length(); i++) taken.add(pending.optJSONObject(i).optString("id"));
        JSONArray steps = st.optJSONArray("steps");
        if (steps != null && !taken.isEmpty()) {
            JSONArray kept = new JSONArray();
            for (int i = 0; i < steps.length(); i++) {
                JSONObject s = steps.optJSONObject(i);
                if (kept.length() > 0 || !taken.contains(s.optString("id"))) kept.put(s);
            }
            st.put("steps", kept);
        }
        SessionCard.saveState(c, st);
        SessionCard.post(c);
        SessionCard.scheduleAlerts(c);
        call.resolve();
    }

    @PluginMethod
    public void hide(PluginCall call) {
        Context c = getContext();
        SessionCard.saveState(c, new JSONObject());
        SessionCard.savePending(c, new JSONArray());
        SessionCard.cancel(c);
        call.resolve();
    }

    @PluginMethod
    public void take(PluginCall call) {
        Context c = getContext();
        JSONArray pending = SessionCard.pending(c);
        SessionCard.savePending(c, new JSONArray());
        JSObject out = new JSObject();
        try { out.put("events", new JSArray(pending.toString())); } catch (JSONException e) { out.put("events", new JSArray()); }
        call.resolve(out);
    }

    @PluginMethod
    public void cacheSound(PluginCall call) {
        String group = safe(call.getString("group", "")), name = safe(call.getString("name", "")), data = call.getString("data", "");
        if (group.isEmpty() || name.isEmpty() || data.isEmpty()) { call.reject("group, name and data are needed"); return; }
        File dir = new File(soundDir(getContext()), group);
        dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, name))) {
            out.write(Base64.decode(data, Base64.DEFAULT));
            call.resolve();
        } catch (Exception e) {
            call.reject("couldn't save " + name);
        }
    }

    @PluginMethod
    public void listSounds(PluginCall call) {
        JSArray list = new JSArray();
        File[] groups = soundDir(getContext()).listFiles();
        if (groups != null) for (File g : groups) {
            File[] files = g.listFiles();
            if (files != null) for (File f : files) list.put(g.getName() + "/" + f.getName());
        }
        JSObject out = new JSObject();
        out.put("sounds", list);
        call.resolve(out);
    }

    @PluginMethod
    public void pruneSounds(PluginCall call) {
        Set<String> keep = new HashSet<>();
        JSArray k = call.getArray("keep", new JSArray());
        for (int i = 0; i < k.length(); i++) keep.add(k.optString(i));
        File[] groups = soundDir(getContext()).listFiles();
        if (groups != null) for (File g : groups) {
            File[] files = g.listFiles();
            if (files != null) for (File f : files) if (!keep.contains(g.getName() + "/" + f.getName())) f.delete();
        }
        call.resolve();
    }

    private static String safe(String s) { return s == null ? "" : s.replaceAll("[^A-Za-z0-9._-]", "_"); }
}
