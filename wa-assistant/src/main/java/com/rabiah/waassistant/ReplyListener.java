package com.rabiah.waassistant;

import android.app.Notification;
import android.app.Person;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Watches WhatsApp notifications and replies with Claude through the notification's Reply action. */
public class ReplyListener extends NotificationListenerService {

    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final LinkedHashSet<String> seen = new LinkedHashSet<>();
    private static final Map<String, List<Long>> replyTimes = new HashMap<>();
    private static final Map<String, String> lastSent = new HashMap<>();

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            handle(sbn);
        } catch (Exception e) {
            Store.log(this, "Auto-reply error: " + e.getMessage());
        }
    }

    private void handle(StatusBarNotification sbn) {
        String pkg = sbn.getPackageName();
        if (!Store.WA.equals(pkg) && !Store.WA_BUSINESS.equals(pkg)) return;
        if (!Store.autoEnabled(this)) return;

        Notification n = sbn.getNotification();
        if (n == null || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;

        Notification.Action replyAction = findReplyAction(n);
        if (replyAction == null) return;

        Bundle ex = n.extras;
        String title = str(ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE));
        if (title.isEmpty()) title = str(ex.getCharSequence(Notification.EXTRA_TITLE));
        if (title.isEmpty() || title.equalsIgnoreCase("WhatsApp") || title.equalsIgnoreCase("WhatsApp Business")) return;

        boolean group = false;
        if (Build.VERSION.SDK_INT >= 28) group = ex.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false);

        // Read the conversation from the MessagingStyle extras.
        List<String[]> history = new ArrayList<>();
        String lastText = null;
        boolean lastFromMe = false;
        long lastTime = 0;
        Parcelable[] msgs = ex.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (msgs != null) {
            for (Parcelable p : msgs) {
                if (!(p instanceof Bundle)) continue;
                Bundle b = (Bundle) p;
                String text = str(b.getCharSequence("text"));
                String sender = senderName(b);
                boolean fromMe = sender == null;
                String role = fromMe ? "assistant" : "user";
                String content = (!fromMe && group) ? sender + ": " + text : text;
                history.add(new String[]{role, content});
                lastText = text;
                lastFromMe = fromMe;
                lastTime = b.getLong("time", 0);
            }
        }
        if (lastText == null) {
            lastText = str(ex.getCharSequence(Notification.EXTRA_TEXT));
            history.add(new String[]{"user", lastText});
        }
        if (lastText.isEmpty() || lastFromMe) return;
        if (lastText.equals(lastSent.get(title))) return; // our own reply echoed back

        String dedupKey = pkg + "|" + title + "|" + lastText + "|" + lastTime;
        synchronized (seen) {
            if (seen.contains(dedupKey)) return;
            seen.add(dedupKey);
            if (seen.size() > 300) { Iterator<String> it = seen.iterator(); it.next(); it.remove(); }
        }

        if (group && !Store.replyGroups(this)) return;
        if (!allowedChat(title)) return;
        if (!withinActiveHours()) return;
        if (!underRateLimit(title)) {
            Store.log(this, "Skipped " + title + " (reply limit reached)");
            return;
        }

        while (history.size() > 12) history.remove(0);

        final String chat = title;
        final boolean isGroup = group;
        final Notification.Action action = replyAction;
        final Context ctx = getApplicationContext();
        worker.execute(() -> {
            try {
                String reply = Claude.reply(ctx, chat, isGroup, history);
                if (Claude.SKIP.equals(reply)) {
                    Store.log(ctx, "No reply needed for " + chat);
                    return;
                }
                String text = Store.addTag(ctx) ? "🤖 " + reply : reply;
                sendReply(ctx, action, text);
                lastSent.put(chat, text);
                recordReply(chat);
                Store.log(ctx, "Replied to " + chat + ": " + text);
            } catch (Exception e) {
                Store.log(ctx, "Couldn't reply to " + chat + ": " + e.getMessage());
            }
        });
    }

    private static Notification.Action findReplyAction(Notification n) {
        if (n.actions == null) return null;
        for (Notification.Action a : n.actions) {
            RemoteInput[] ri = a.getRemoteInputs();
            if (ri != null && ri.length > 0 && a.actionIntent != null) return a;
        }
        return null;
    }

    private static void sendReply(Context c, Notification.Action action, String text) throws Exception {
        RemoteInput[] inputs = action.getRemoteInputs();
        Intent intent = new Intent();
        Bundle results = new Bundle();
        for (RemoteInput ri : inputs) results.putCharSequence(ri.getResultKey(), text);
        RemoteInput.addResultsToIntent(inputs, intent, results);
        action.actionIntent.send(c, 0, intent);
    }

    private static String senderName(Bundle b) {
        if (Build.VERSION.SDK_INT >= 28) {
            Object p = b.get("sender_person");
            if (p instanceof Person) {
                CharSequence name = ((Person) p).getName();
                if (name != null) return name.toString();
            }
        }
        CharSequence s = b.getCharSequence("sender");
        return s == null ? null : s.toString();
    }

    private boolean allowedChat(String title) {
        int mode = Store.mode(this);
        if (mode == Store.MODE_ALL) return true;
        boolean listed = false;
        String t = title.toLowerCase(Locale.UK);
        for (String name : Store.names(this).split(",")) {
            String nm = name.trim().toLowerCase(Locale.UK);
            if (!nm.isEmpty() && t.contains(nm)) { listed = true; break; }
        }
        return mode == Store.MODE_ONLY ? listed : !listed;
    }

    private boolean withinActiveHours() {
        int from = minutes(Store.fromTime(this));
        int to = minutes(Store.toTime(this));
        if (from < 0 || to < 0 || from == to) return true;
        Calendar now = Calendar.getInstance();
        int m = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        return from < to ? (m >= from && m < to) : (m >= from || m < to);
    }

    static int minutes(String hhmm) {
        try {
            String[] p = hhmm.trim().split(":");
            int h = Integer.parseInt(p[0].trim());
            int m = p.length > 1 ? Integer.parseInt(p[1].trim()) : 0;
            if (h < 0 || h > 23 || m < 0 || m > 59) return -1;
            return h * 60 + m;
        } catch (Exception e) {
            return -1;
        }
    }

    private boolean underRateLimit(String chat) {
        int max = Store.maxPerHour(this);
        if (max <= 0) return true;
        long cutoff = System.currentTimeMillis() - 3600_000L;
        synchronized (replyTimes) {
            List<Long> times = replyTimes.get(chat);
            if (times == null) return true;
            times.removeIf(t -> t < cutoff);
            return times.size() < max;
        }
    }

    private static void recordReply(String chat) {
        synchronized (replyTimes) {
            List<Long> times = replyTimes.get(chat);
            if (times == null) { times = new ArrayList<>(); replyTimes.put(chat, times); }
            times.add(System.currentTimeMillis());
        }
    }

    private static String str(CharSequence cs) { return cs == null ? "" : cs.toString().trim(); }
}
