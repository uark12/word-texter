package com.rabiah.waassistant;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import org.json.JSONObject;

import java.util.Calendar;

/** Sets and cancels alarms for scheduled WhatsApp messages. */
public final class Scheduler {
    private Scheduler() {}

    /** Next time (ms) that hour:minute occurs, today if still ahead, otherwise tomorrow. */
    public static long nextOccurrence(int hour, int minute) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, minute);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis() + 5000) c.add(Calendar.DAY_OF_YEAR, 1);
        return c.getTimeInMillis();
    }

    private static PendingIntent pi(Context c, int id) {
        Intent i = new Intent(c, AlarmReceiver.class);
        i.putExtra("id", id);
        return PendingIntent.getBroadcast(c, id, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    public static boolean canExact(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (Build.VERSION.SDK_INT >= 31) return am.canScheduleExactAlarms();
        return true;
    }

    public static void schedule(Context c, JSONObject item) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        long at = item.optLong("at");
        PendingIntent p = pi(c, item.optInt("id"));
        if (canExact(c)) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p);
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p);
        }
    }

    public static void cancel(Context c, int id) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        am.cancel(pi(c, id));
    }

    /** Re-arms every saved schedule (after reboot or app update). Missed one-off messages are dropped. */
    public static void rescheduleAll(Context c) {
        for (JSONObject item : Store.schedules(c)) {
            try {
                if (item.optLong("at") <= System.currentTimeMillis()) {
                    if (item.optBoolean("daily")) {
                        item.put("at", nextOccurrence(item.optInt("hour"), item.optInt("minute")));
                        Store.putSchedule(c, item);
                    } else {
                        Store.removeSchedule(c, item.optInt("id"));
                        Store.log(c, "Missed scheduled message to " + item.optString("label") + " (phone was off)");
                        continue;
                    }
                }
                schedule(c, item);
            } catch (Exception ignored) {}
        }
    }
}
