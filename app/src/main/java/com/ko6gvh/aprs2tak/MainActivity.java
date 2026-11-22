package com.ko6gvh.aprs2tak;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.text.DateFormat;
import java.util.Date;

public class MainActivity extends AppCompatActivity {

    static final String PREFS = "aprs2tak";
    static final String KEY_CALL = "mycall";
    static final String KEY_STALE = "staleMinutes";
    static final int DEFAULT_STALE = 30;
    static final int MAX_STALE = 1440;   // 24 hours

    private TextView status;
    private EditText myCall;
    private EditText staleBox;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            updateStatus();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(pad, pad * 2, pad, pad);

        TextView title = new TextView(this);
        title.setText("APRSdroid → ATAK");
        title.setTextSize(24);
        layout.addView(title);

        TextView info = new TextView(this);
        info.setText("\nStations heard by APRSdroid are sent to ATAK on this phone "
                + "(127.0.0.1:4242 UDP, ATAK's \"Default\" input).");
        layout.addView(info);

        TextView setupTitle = new TextView(this);
        setupTitle.setText("\nFirst-time setup: APRS symbols");
        setupTitle.setTextSize(18);
        layout.addView(setupTitle);

        TextView setup = new TextView(this);
        setup.setText("To show real APRS symbols instead of plain markers, import the "
                + "APRS iconset into ATAK once:\n\n"
                + "1. Download aprs-iconset.zip from the APRS2TAK GitHub release page "
                + "to this phone (it usually lands in the Download folder).\n"
                + "2. In ATAK, tap \u2630 (menu) \u2192 Import \u2192 Local SD.\n"
                + "3. Open the Download folder and tap aprs-iconset.zip.\n"
                + "4. Restart ATAK.\n\n"
                + "To check: open Point Dropper in ATAK; an iconset named \"APRS\" "
                + "should be in its list.");
        layout.addView(setup);

        TextView callTitle = new TextView(this);
        callTitle.setText("\nSettings");
        callTitle.setTextSize(18);
        layout.addView(callTitle);

        TextView callInfo = new TextView(this);
        callInfo.setText("My callsign (optional): fill this in to hide your own APRS "
                + "beacons, since ATAK already shows your position. Leave it blank to "
                + "show everything. Without an SSID (N0CALL) it hides all your SSIDs; "
                + "with one (N0CALL-7) it hides only that one.");
        layout.addView(callInfo);

        myCall = new EditText(this);
        myCall.setSingleLine(true);
        myCall.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        myCall.setHint("Optional, e.g. N0CALL");
        myCall.setText(prefs.getString(KEY_CALL, ""));
        layout.addView(myCall);

        TextView staleLabel = new TextView(this);
        staleLabel.setText("\nKeep stations on the map for (minutes after last beacon):");
        layout.addView(staleLabel);

        staleBox = new EditText(this);
        staleBox.setSingleLine(true);
        staleBox.setInputType(InputType.TYPE_CLASS_NUMBER);
        staleBox.setText(String.valueOf(prefs.getInt(KEY_STALE, DEFAULT_STALE)));
        layout.addView(staleBox);

        Button save = new Button(this);
        save.setText("Save settings");
        save.setOnClickListener(v -> saveSettings(prefs, true));
        layout.addView(save);

        Button start = new Button(this);
        start.setText("Start bridge");
        start.setOnClickListener(v -> {
            saveSettings(prefs, false);
            ContextCompat.startForegroundService(this, new Intent(this, BridgeService.class));
        });
        layout.addView(start);

        Button stop = new Button(this);
        stop.setText("Stop bridge");
        stop.setOnClickListener(v -> stopService(new Intent(this, BridgeService.class)));
        layout.addView(stop);

        TextView credit = new TextView(this);
        credit.setText("\nAPRS symbols: aprs.fi symbol set, github.com/hessu/aprs-symbols");
        credit.setTextSize(12);
        layout.addView(credit);

        status = new TextView(this);
        status.setTextSize(16);
        status.setPadding(0, pad, 0, 0);
        layout.addView(status);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(layout);
        setContentView(scroll);
    }

    /** Saves callsign and stale time; the bridge picks them up on the next packet. */
    private void saveSettings(SharedPreferences prefs, boolean showToast) {
        int minutes;
        try {
            minutes = Integer.parseInt(staleBox.getText().toString().trim());
        } catch (NumberFormatException e) {
            minutes = DEFAULT_STALE;
        }
        minutes = Math.max(1, Math.min(MAX_STALE, minutes));
        staleBox.setText(String.valueOf(minutes));
        prefs.edit()
                .putString(KEY_CALL, myCall.getText().toString().trim().toUpperCase())
                .putInt(KEY_STALE, minutes)
                .apply();
        if (showToast) {
            Toast.makeText(this, "Saved: stations stay " + minutes + " min", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    private void updateStatus() {
        if (!BridgeService.running) {
            status.setText("Status: stopped");
            return;
        }
        StringBuilder sb = new StringBuilder("Status: running\nStations sent to ATAK: ")
                .append(BridgeService.forwarded);
        if (BridgeService.lastTime > 0) {
            sb.append("\nLast: ").append(BridgeService.lastCall).append(" at ")
                    .append(DateFormat.getTimeInstance().format(new Date(BridgeService.lastTime)));
        }
        status.setText(sb.toString());
    }
}
