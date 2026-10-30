package com.rabiah.waassistant;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;

import org.json.JSONObject;

/** Fires when a scheduled message is due and hands it to the accessibility service to send. */
public class AlarmReceiver extends BroadcastReceiver {
    @Override
    @SuppressWarnings("deprecation")
    public void onReceive(Context context, Intent intent) {
        int id = intent.getIntExtra("id", -1);
        JSONObject item = Store.findSchedule(context, id);
        if (item == null) return;

        // Turn the screen on so WhatsApp can be opened.
        try {
            PowerManager pm = context.getSystemService(PowerManager.class);
            PowerManager.WakeLock wl = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "waassistant:send");
            wl.acquire(40_000);
        } catch (Exception ignored) {}

        if (SendService.instance == null) {
            Store.log(context, "Couldn't send to " + item.optString("label")
                    + ": turn on WA Assistant in Accessibility settings");
        } else {
            SendService.instance.enqueue(item);
        }

        try {
            if (item.optBoolean("daily")) {
                item.put("at", Scheduler.nextOccurrence(item.optInt("hour"), item.optInt("minute")));
                Store.putSchedule(context, item);
                Scheduler.schedule(context, item);
            } else {
                Store.removeSchedule(context, id);
            }
        } catch (Exception ignored) {}
    }
}
