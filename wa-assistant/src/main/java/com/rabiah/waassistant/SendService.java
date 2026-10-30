package com.rabiah.waassistant;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayDeque;
import java.util.List;

/**
 * Sends scheduled messages: opens the WhatsApp chat with the text pre-filled,
 * then presses the Send button.
 */
public class SendService extends AccessibilityService {
    public static volatile SendService instance;

    private static final long TIMEOUT_MS = 25_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<JSONObject> queue = new ArrayDeque<>();
    private JSONObject current;
    private long deadline;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { /* polling is used instead */ }

    @Override
    public void onInterrupt() {}

    public void enqueue(JSONObject item) {
        handler.post(() -> {
            queue.add(item);
            if (current == null) startNext();
        });
    }

    private void startNext() {
        current = queue.poll();
        if (current == null) return;
        try {
            String pkg = current.optString("pkg", Store.WA);
            String url = "https://api.whatsapp.com/send?phone=" + current.optString("phone")
                    + "&text=" + URLEncoder.encode(current.optString("text"), "UTF-8");
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.setPackage(pkg);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(i);
            deadline = System.currentTimeMillis() + TIMEOUT_MS;
            handler.postDelayed(this::tryPressSend, 1500);
        } catch (Exception e) {
            finish("Couldn't open WhatsApp for " + current.optString("label") + ": " + e.getMessage(), false);
        }
    }

    private void tryPressSend() {
        if (current == null) return;
        String pkg = current.optString("pkg", Store.WA);
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null && pkg.equals(String.valueOf(root.getPackageName()))) {
            AccessibilityNodeInfo send = findSend(root, pkg);
            if (send != null) {
                AccessibilityNodeInfo target = send;
                while (target != null && !target.isClickable()) target = target.getParent();
                if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    finish("Sent scheduled message to " + current.optString("label")
                            + ": " + current.optString("text"), true);
                    return;
                }
            }
        }
        if (System.currentTimeMillis() > deadline) {
            finish("Couldn't send to " + current.optString("label")
                    + " (Send button not found; is the phone locked or the number not on WhatsApp?)", false);
            return;
        }
        handler.postDelayed(this::tryPressSend, 700);
    }

    private static AccessibilityNodeInfo findSend(AccessibilityNodeInfo root, String pkg) {
        List<AccessibilityNodeInfo> byId = root.findAccessibilityNodeInfosByViewId(pkg + ":id/send");
        if (byId != null && !byId.isEmpty()) return byId.get(0);
        return findByDescription(root, "Send", 0);
    }

    private static AccessibilityNodeInfo findByDescription(AccessibilityNodeInfo node, String desc, int depth) {
        if (node == null || depth > 40) return null;
        CharSequence cd = node.getContentDescription();
        if (cd != null && cd.toString().equalsIgnoreCase(desc)) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findByDescription(node.getChild(i), desc, depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    private void finish(String logLine, boolean sent) {
        Store.log(this, logLine);
        current = null;
        // Leave WhatsApp and go back to the home screen, then do the next one.
        handler.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_HOME), sent ? 1200 : 200);
        handler.postDelayed(this::startNext, 3000);
    }
}
