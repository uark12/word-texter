package com.rabiah.wordtexter;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;
import android.telephony.SmsManager;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQ_SMS = 1;
    private static final int REQ_CONTACT = 2;

    private EditText phoneInput, messageInput;
    private TextView contactLabel, speedLabel, statusLabel;
    private SeekBar speedBar;
    private ProgressBar progress;
    private Button sendBtn, stopBtn;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private String[] words;
    private int index;
    private boolean running;
    private String target;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = dp(16);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        root.addView(label("Send to"));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        phoneInput = new EditText(this);
        phoneInput.setHint("Phone number");
        phoneInput.setInputType(InputType.TYPE_CLASS_PHONE);
        row.addView(phoneInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button pick = new Button(this);
        pick.setText("Contacts");
        pick.setOnClickListener(v -> pickContact());
        row.addView(pick);
        root.addView(row);
        contactLabel = new TextView(this);
        root.addView(contactLabel);

        root.addView(label("Message (each word is sent as its own text)"));
        messageInput = new EditText(this);
        messageInput.setHint("Type or paste your story here…");
        messageInput.setMinLines(6);
        messageInput.setGravity(Gravity.TOP | Gravity.START);
        messageInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        root.addView(messageInput);

        speedLabel = label("");
        root.addView(speedLabel);
        speedBar = new SeekBar(this);
        speedBar.setMax(30); // 0..30 -> 0.0s..3.0s
        speedBar.setProgress(5);
        speedBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) { updateSpeedLabel(); }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
        root.addView(speedBar);
        updateSpeedLabel();

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        sendBtn = new Button(this);
        sendBtn.setText("Send");
        sendBtn.setOnClickListener(v -> start());
        stopBtn = new Button(this);
        stopBtn.setText("Stop");
        stopBtn.setEnabled(false);
        stopBtn.setOnClickListener(v -> stop("Stopped."));
        buttons.addView(sendBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        buttons.addView(stopBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(buttons);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        root.addView(progress);
        statusLabel = new TextView(this);
        root.addView(statusLabel);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setPadding(0, dp(12), 0, dp(4));
        t.setTextSize(16);
        return t;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private long delayMs() { return speedBar.getProgress() * 100L; }

    private void updateSpeedLabel() {
        speedLabel.setText(String.format("Gap between texts: %.1f seconds", delayMs() / 1000.0));
    }

    private void pickContact() {
        Intent i = new Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI);
        try { startActivityForResult(i, REQ_CONTACT); }
        catch (Exception e) { toast("No contacts app found"); }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CONTACT || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        String[] cols = {ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME};
        try (Cursor c = getContentResolver().query(uri, cols, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                phoneInput.setText(c.getString(0));
                contactLabel.setText("Contact: " + c.getString(1));
            }
        } catch (Exception e) {
            toast("Couldn't read that contact");
        }
    }

    private void start() {
        if (running) return;
        target = phoneInput.getText().toString().replaceAll("[^0-9+]", "");
        if (target.isEmpty()) { toast("Enter a phone number or pick a contact"); return; }
        String text = messageInput.getText().toString().trim();
        if (text.isEmpty()) { toast("Type a message first"); return; }
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.SEND_SMS}, REQ_SMS);
            return;
        }
        words = text.split("\\s+");
        index = 0;
        running = true;
        progress.setMax(words.length);
        progress.setProgress(0);
        sendBtn.setEnabled(false);
        stopBtn.setEnabled(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        handler.post(sendNext);
    }

    private final Runnable sendNext = new Runnable() {
        @Override public void run() {
            if (!running) return;
            if (index >= words.length) { stop("Done! Sent " + words.length + " texts."); return; }
            try {
                sms().sendTextMessage(target, null, words[index], null, null);
            } catch (Exception e) {
                stop("Failed on word " + (index + 1) + ": " + e.getMessage());
                return;
            }
            index++;
            progress.setProgress(index);
            statusLabel.setText("Sent " + index + " of " + words.length + "  (" + words[index - 1] + ")");
            handler.postDelayed(this, Math.max(150, delayMs()));
        }
    };

    @SuppressWarnings("deprecation")
    private SmsManager sms() {
        if (Build.VERSION.SDK_INT >= 31) {
            SmsManager m = getSystemService(SmsManager.class);
            if (m != null) return m;
        }
        return SmsManager.getDefault();
    }

    private void stop(String msg) {
        running = false;
        handler.removeCallbacks(sendNext);
        sendBtn.setEnabled(true);
        stopBtn.setEnabled(false);
        statusLabel.setText(msg);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] perms, int[] results) {
        super.onRequestPermissionsResult(requestCode, perms, results);
        if (requestCode == REQ_SMS) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) start();
            else toast("SMS permission is needed to send texts");
        }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    @Override
    protected void onDestroy() {
        running = false;
        handler.removeCallbacks(sendNext);
        super.onDestroy();
    }
}
