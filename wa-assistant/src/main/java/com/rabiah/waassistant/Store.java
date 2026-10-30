package com.rabiah.waassistant;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Settings, scheduled messages and the activity log, all kept in SharedPreferences. */
public final class Store {
    public static final String WA = "com.whatsapp";
    public static final String WA_BUSINESS = "com.whatsapp.w4b";

    public static final int MODE_ALL = 0, MODE_ONLY = 1, MODE_EXCEPT = 2;

    private static final int MAX_LOG_LINES = 300;

    private Store() {}

    public static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("cfg", Context.MODE_PRIVATE);
    }

    // ---- auto-reply settings ----
    public static boolean autoEnabled(Context c) { return prefs(c).getBoolean("auto_on", false); }
    public static String apiKey(Context c) { return prefs(c).getString("api_key", ""); }
    public static String model(Context c) { return prefs(c).getString("model", "claude-haiku-5-5"); }
    public static String instructions(Context c) {
        return prefs(c).getString("instructions",
                "I'm Rabiah. I'm busy right now. Reply casually and briefly, like a friendly text. "
                        + "If it's about work, say I'll get back to them as soon as I can.");
    }
    public static int mode(Context c) { return prefs(c).getInt("mode", MODE_ALL); }
    public static String names(Context c) { return prefs(c).getString("names", ""); }
    public static boolean replyGroups(Context c) { return prefs(c).getBoolean("groups", false); }
    public static boolean addTag(Context c) { return prefs(c).getBoolean("tag", true); }
    public static String fromTime(Context c) { return prefs(c).getString("from", ""); }
    public static String toTime(Context c) { return prefs(c).getString("to", ""); }
    public static int maxPerHour(Context c) { return prefs(c).getInt("max_hour", 3); }
    public static int delayMin(Context c) { return prefs(c).getInt("delay_min", 60); }
    public static int delayMax(Context c) { return prefs(c).getInt("delay_max", 240); }

    // ---- schedule ----
    public static List<JSONObject> schedules(Context c) {
        List<JSONObject> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString("schedules", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getJSONObject(i));
        } catch (Exception ignored) {}
        return out;
    }

    public static void saveSchedules(Context c, List<JSONObject> list) {
        JSONArray a = new JSONArray();
        for (JSONObject o : list) a.put(o);
        prefs(c).edit().putString("schedules", a.toString()).apply();
    }

    public static JSONObject findSchedule(Context c, int id) {
        for (JSONObject o : schedules(c)) if (o.optInt("id") == id) return o;
        return null;
    }

    public static void putSchedule(Context c, JSONObject item) {
        List<JSONObject> list = schedules(c);
        boolean replaced = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).optInt("id") == item.optInt("id")) { list.set(i, item); replaced = true; }
        }
        if (!replaced) list.add(item);
        saveSchedules(c, list);
    }

    public static void removeSchedule(Context c, int id) {
        List<JSONObject> list = schedules(c);
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).optInt("id") == id) list.remove(i);
        saveSchedules(c, list);
    }

    public static int nextId(Context c) {
        int id = prefs(c).getInt("next_id", 1);
        prefs(c).edit().putInt("next_id", id + 1).apply();
        return id;
    }

    // ---- log ----
    public static synchronized void log(Context c, String line) {
        String stamp = new SimpleDateFormat("dd MMM HH:mm", Locale.UK).format(new Date());
        String old = prefs(c).getString("log", "");
        String all = stamp + "  " + line + "\n" + old;
        String[] lines = all.split("\n");
        if (lines.length > MAX_LOG_LINES) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < MAX_LOG_LINES; i++) sb.append(lines[i]).append('\n');
            all = sb.toString();
        }
        prefs(c).edit().putString("log", all).apply();
    }

    public static String logText(Context c) { return prefs(c).getString("log", ""); }
    public static void clearLog(Context c) { prefs(c).edit().putString("log", "").apply(); }
}
