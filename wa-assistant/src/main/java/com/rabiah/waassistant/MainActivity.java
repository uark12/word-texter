package com.rabiah.waassistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_CONTACT = 7;

    private LinearLayout content;
    private Button tabAuto, tabSchedule, tabLog;
    private int currentTab = 0;
    private boolean pickingContact = false;
    private final Handler ui = new Handler(Looper.getMainLooper());

    // schedule form state
    private EditText schedPhone;
    private TextView schedContactLabel;
    private String schedContactName = "";
    private int schedHour = -1, schedMinute = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Scheduler.rescheduleAll(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabAuto = tabButton("AI auto-reply", 0);
        tabSchedule = tabButton("Schedule", 1);
        tabLog = tabButton("Log", 2);
        tabs.addView(tabAuto, weight());
        tabs.addView(tabSchedule, weight());
        tabs.addView(tabLog, weight());
        root.addView(tabs);

        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        content.setPadding(pad, dp(8), pad, pad);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        showTab(0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pickingContact) { pickingContact = false; return; }
        if (content != null) showTab(currentTab);
    }

    // ------------------------------------------------------------------ tabs

    private Button tabButton(String text, int index) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(v -> showTab(index));
        return b;
    }

    private void showTab(int index) {
        currentTab = index;
        tabAuto.setTypeface(null, index == 0 ? Typeface.BOLD : Typeface.NORMAL);
        tabSchedule.setTypeface(null, index == 1 ? Typeface.BOLD : Typeface.NORMAL);
        tabLog.setTypeface(null, index == 2 ? Typeface.BOLD : Typeface.NORMAL);
        content.removeAllViews();
        if (index == 0) buildAutoTab();
        else if (index == 1) buildScheduleTab();
        else buildLogTab();
    }

    // ------------------------------------------------------------ auto-reply

    private void buildAutoTab() {
        boolean listenerOn = notificationAccessOn();
        content.addView(status(listenerOn
                ? "✅ Notification access is on"
                : "⚠️ Notification access is off. Auto-reply can't see your WhatsApp messages until you turn it on.", listenerOn));
        if (!listenerOn) {
            content.addView(button("Turn on notification access", v ->
                    startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))));
        }

        Switch on = new Switch(this);
        on.setText("Auto-reply is ON");
        on.setTextSize(18);
        on.setChecked(Store.autoEnabled(this));
        on.setText(on.isChecked() ? "Auto-reply is ON" : "Auto-reply is OFF");
        on.setOnCheckedChangeListener((b, checked) -> {
            Store.prefs(this).edit().putBoolean("auto_on", checked).apply();
            on.setText(checked ? "Auto-reply is ON" : "Auto-reply is OFF");
        });
        on.setPadding(0, dp(12), 0, dp(4));
        content.addView(on);

        content.addView(label("Anthropic API key"));
        EditText key = edit(Store.apiKey(this), "sk-ant-…");
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        content.addView(key);

        content.addView(label("Model"));
        EditText model = edit(Store.model(this), "claude-haiku-5-5");
        content.addView(model);

        content.addView(label("Instructions for the AI (who you are, how to reply)"));
        EditText instr = edit(Store.instructions(this), "");
        instr.setMinLines(4);
        instr.setGravity(Gravity.TOP | Gravity.START);
        instr.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        content.addView(instr);

        content.addView(label("Who should it reply to?"));
        RadioGroup mode = new RadioGroup(this);
        RadioButton all = radio("Everyone", 100);
        RadioButton only = radio("Only these chats", 101);
        RadioButton except = radio("Everyone except these chats", 102);
        mode.addView(all);
        mode.addView(only);
        mode.addView(except);
        int m = Store.mode(this);
        mode.check(m == Store.MODE_ONLY ? 101 : m == Store.MODE_EXCEPT ? 102 : 100);
        content.addView(mode);
        EditText names = edit(Store.names(this), "Names as shown in WhatsApp, separated by commas");
        content.addView(names);

        CheckBox groups = check("Also reply in group chats", Store.replyGroups(this));
        content.addView(groups);
        CheckBox tag = check("Start replies with 🤖 so people know it's automatic", Store.addTag(this));
        content.addView(tag);

        content.addView(label("Only reply between (leave blank for any time)"));
        LinearLayout hours = new LinearLayout(this);
        EditText from = edit(Store.fromTime(this), "e.g. 18:00");
        EditText to = edit(Store.toTime(this), "e.g. 08:00");
        from.setInputType(InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_TIME);
        to.setInputType(InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_TIME);
        hours.addView(from, weight());
        TextView andTxt = new TextView(this);
        andTxt.setText("  and  ");
        hours.addView(andTxt);
        hours.addView(to, weight());
        content.addView(hours);

        content.addView(label("Max replies per chat per hour"));
        EditText max = edit(String.valueOf(Store.maxPerHour(this)), "3");
        max.setInputType(InputType.TYPE_CLASS_NUMBER);
        content.addView(max);

        Runnable save = () -> {
            int modeVal = mode.getCheckedRadioButtonId() == 101 ? Store.MODE_ONLY
                    : mode.getCheckedRadioButtonId() == 102 ? Store.MODE_EXCEPT : Store.MODE_ALL;
            int maxVal;
            try { maxVal = Integer.parseInt(max.getText().toString().trim()); } catch (Exception e) { maxVal = 3; }
            Store.prefs(this).edit()
                    .putString("api_key", key.getText().toString().trim())
                    .putString("model", model.getText().toString().trim().isEmpty()
                            ? "claude-haiku-5-5" : model.getText().toString().trim())
                    .putString("instructions", instr.getText().toString().trim())
                    .putInt("mode", modeVal)
                    .putString("names", names.getText().toString())
                    .putBoolean("groups", groups.isChecked())
                    .putBoolean("tag", tag.isChecked())
                    .putString("from", from.getText().toString().trim())
                    .putString("to", to.getText().toString().trim())
                    .putInt("max_hour", maxVal)
                    .apply();
        };

        LinearLayout row = new LinearLayout(this);
        row.addView(button("Save", v -> { save.run(); toast("Saved"); }), weight());
        row.addView(button("Test the AI", v -> { save.run(); testAi(); }), weight());
        content.addView(row);

        content.addView(note("Get an API key at console.anthropic.com → API Keys. Replies are sent through WhatsApp's "
                + "notification Reply button, so keep WhatsApp notifications switched on."));
    }

    private void testAi() {
        toast("Asking the AI…");
        new Thread(() -> {
            String result;
            try {
                List<String[]> h = new ArrayList<>();
                h.add(new String[]{"user", "Hey! Are you free for a quick call later?"});
                result = "Test message: \"Hey! Are you free for a quick call later?\"\n\nAI reply:\n"
                        + Claude.reply(getApplicationContext(), "Test contact", false, h);
            } catch (Exception e) {
                result = "Problem: " + e.getMessage();
            }
            String r = result;
            ui.post(() -> new AlertDialog.Builder(this).setTitle("AI test").setMessage(r)
                    .setPositiveButton("OK", null).show());
        }).start();
    }

    private boolean notificationAccessOn() {
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(new ComponentName(this, ReplyListener.class).flattenToString());
    }

    // -------------------------------------------------------------- schedule

    private void buildScheduleTab() {
        boolean a11yOn = SendService.instance != null || accessibilityOn();
        content.addView(status(a11yOn
                ? "✅ Accessibility is on: scheduled messages can be sent"
                : "⚠️ Accessibility is off. Scheduled messages can't press Send until you turn on WA Assistant in Accessibility settings.", a11yOn));
        if (!a11yOn) {
            content.addView(button("Open Accessibility settings", v ->
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        }
        if (!Scheduler.canExact(this) && Build.VERSION.SDK_INT >= 31) {
            content.addView(button("Allow exact timing (alarms)", v -> {
                Intent i = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
            }));
        }

        content.addView(header("New scheduled message"));

        content.addView(label("To (phone number with country code)"));
        LinearLayout phoneRow = new LinearLayout(this);
        schedPhone = edit("", "e.g. 447700900123");
        schedPhone.setInputType(InputType.TYPE_CLASS_PHONE);
        phoneRow.addView(schedPhone, weight());
        phoneRow.addView(button("Contacts", v -> pickContact()));
        content.addView(phoneRow);
        schedContactLabel = new TextView(this);
        schedContactName = "";
        content.addView(schedContactLabel);

        content.addView(label("Message"));
        EditText msg = edit("", "Type your message");
        msg.setMinLines(3);
        msg.setGravity(Gravity.TOP | Gravity.START);
        msg.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        content.addView(msg);

        Button time = button("Pick time", null);
        schedHour = -1;
        schedMinute = -1;
        time.setOnClickListener(v -> {
            Calendar now = Calendar.getInstance();
            new TimePickerDialog(this, (tp, h, mi) -> {
                schedHour = h;
                schedMinute = mi;
                time.setText(String.format(Locale.UK, "Time: %02d:%02d", h, mi));
            }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), true).show();
        });
        content.addView(time);

        CheckBox daily = check("Repeat every day", false);
        content.addView(daily);

        RadioGroup app = new RadioGroup(this);
        app.setOrientation(LinearLayout.HORIZONTAL);
        app.addView(radio("WhatsApp", 200));
        app.addView(radio("WhatsApp Business", 201));
        app.check(200);
        content.addView(app);

        content.addView(button("Add to schedule", v -> {
            String phone = normalisePhone(schedPhone.getText().toString());
            String text = msg.getText().toString().trim();
            if (phone.length() < 8) { toast("Enter a phone number with country code"); return; }
            if (text.isEmpty()) { toast("Type a message"); return; }
            if (schedHour < 0) { toast("Pick a time"); return; }
            try {
                JSONObject item = new JSONObject();
                item.put("id", Store.nextId(this));
                item.put("phone", phone);
                item.put("label", schedContactName.isEmpty() ? "+" + phone : schedContactName);
                item.put("text", text);
                item.put("hour", schedHour);
                item.put("minute", schedMinute);
                item.put("daily", daily.isChecked());
                item.put("pkg", app.getCheckedRadioButtonId() == 201 ? Store.WA_BUSINESS : Store.WA);
                item.put("at", Scheduler.nextOccurrence(schedHour, schedMinute));
                Store.putSchedule(this, item);
                Scheduler.schedule(this, item);
                toast("Scheduled");
                showTab(1);
            } catch (Exception e) {
                toast("Couldn't save: " + e.getMessage());
            }
        }));

        content.addView(header("Scheduled"));
        List<JSONObject> items = Store.schedules(this);
        if (items.isEmpty()) content.addView(note("Nothing scheduled yet."));
        for (JSONObject item : items) content.addView(scheduleRow(item));

        content.addView(note("Tips: the phone must be unlocked (or have no PIN) when a message is due, because WhatsApp "
                + "has to open to send it. UK numbers starting with 0 are converted to +44 automatically."));
    }

    private View scheduleRow(JSONObject item) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(8), 0, dp(8));
        TextView t = new TextView(this);
        String when = String.format(Locale.UK, "%02d:%02d", item.optInt("hour"), item.optInt("minute"))
                + (item.optBoolean("daily") ? " every day" : " (once)");
        String app = Store.WA_BUSINESS.equals(item.optString("pkg")) ? " · Business" : "";
        t.setText(when + " → " + item.optString("label") + app + "\n" + item.optString("text"));
        t.setTextSize(15);
        box.addView(t);
        LinearLayout row = new LinearLayout(this);
        row.addView(button("Send now", v -> {
            if (SendService.instance == null) { toast("Turn on Accessibility first"); return; }
            SendService.instance.enqueue(item);
        }), weight());
        row.addView(button("Delete", v -> {
            Scheduler.cancel(this, item.optInt("id"));
            Store.removeSchedule(this, item.optInt("id"));
            showTab(1);
        }), weight());
        box.addView(row);
        return box;
    }

    private boolean accessibilityOn() {
        String flat = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return flat != null && flat.contains(getPackageName());
    }

    private static String normalisePhone(String raw) {
        String d = raw.replaceAll("[^0-9]", "");
        if (d.startsWith("00")) d = d.substring(2);
        else if (d.startsWith("0")) d = "44" + d.substring(1);
        return d;
    }

    private void pickContact() {
        Intent i = new Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI);
        pickingContact = true;
        try { startActivityForResult(i, REQ_CONTACT); } catch (Exception e) { toast("No contacts app found"); }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CONTACT || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        String[] cols = {ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME};
        try (Cursor c = getContentResolver().query(data.getData(), cols, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String number = c.getString(0);
                String name = c.getString(1);
                // onResume rebuilds the tab, so apply after it.
                ui.post(() -> {
                    if (schedPhone != null) schedPhone.setText(number);
                    schedContactName = name == null ? "" : name;
                    if (schedContactLabel != null) schedContactLabel.setText("Contact: " + schedContactName);
                });
            }
        } catch (Exception e) {
            toast("Couldn't read that contact");
        }
    }

    // ------------------------------------------------------------------- log

    private void buildLogTab() {
        LinearLayout row = new LinearLayout(this);
        row.addView(button("Refresh", v -> showTab(2)), weight());
        row.addView(button("Clear", v -> { Store.clearLog(this); showTab(2); }), weight());
        content.addView(row);
        TextView log = new TextView(this);
        String text = Store.logText(this);
        log.setText(text.isEmpty() ? "Nothing yet. Auto-replies and scheduled sends will show up here." : text);
        log.setTextIsSelectable(true);
        log.setPadding(0, dp(8), 0, 0);
        content.addView(log);
    }

    // --------------------------------------------------------------- helpers

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(15);
        t.setPadding(0, dp(12), 0, dp(2));
        return t;
    }

    private TextView header(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(20);
        t.setTypeface(null, Typeface.BOLD);
        t.setPadding(0, dp(20), 0, dp(4));
        return t;
    }

    private TextView note(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(13);
        t.setTextColor(Color.GRAY);
        t.setPadding(0, dp(12), 0, 0);
        return t;
    }

    private TextView status(String s, boolean ok) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(15);
        t.setPadding(dp(10), dp(10), dp(10), dp(10));
        t.setBackgroundColor(ok ? Color.rgb(220, 245, 225) : Color.rgb(255, 236, 210));
        return t;
    }

    private EditText edit(String value, String hint) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setHint(hint);
        return e;
    }

    private CheckBox check(String s, boolean checked) {
        CheckBox c = new CheckBox(this);
        c.setText(s);
        c.setChecked(checked);
        return c;
    }

    private RadioButton radio(String s, int id) {
        RadioButton r = new RadioButton(this);
        r.setText(s);
        r.setId(id);
        return r;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        if (l != null) b.setOnClickListener(l);
        return b;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
