package com.ko6gvh.aprs2tak;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Listens for APRSdroid's broadcasts (https://aprsdroid.org/api/) and sends
 * each received APRS position to ATAK as a CoT message on 127.0.0.1:4242/udp
 * (ATAK's "Default" input). Everything stays on the phone.
 */
public class BridgeService extends Service {

    private static final String TAG = "APRS2TAK";

    // ---- Settings ----
    private static final String ATAK_HOST = "127.0.0.1";
    private static final int ATAK_PORT = 4242;          // ATAK input "Default" 0.0.0.0:4242:udp
    private static final String COT_TYPE = "a-n-G";     // neutral ground (yellow), used if no APRS icon
    // Must match the uid in the APRS iconset's iconset.xml (made by make_aprs_iconset.py)
    static final String ICONSET_UID = "3a9d2f4e-7b61-4c58-9e02-5d4b8c1a6f71";
    // Alternate symbols that have overlay versions in the iconset (same list as make_aprs_iconset.py)
    static final String OVERLAY_BASES = "!#%&()-0;=>AEW^_acknsuvyh";
    // ------------------

    private static final String PREFIX = "org.aprsdroid.app.";
    private static final String ACTION_POSITION = PREFIX + "POSITION";
    private static final String ACTION_STARTED = PREFIX + "SERVICE_STARTED";
    private static final String CHANNEL_ID = "bridge";
    private static final double UNKNOWN = 9999999.0;

    // status shown in the app screen
    static volatile boolean running = false;
    static volatile int forwarded = 0;
    static volatile String lastCall = "";
    static volatile long lastTime = 0;

    private ExecutorService sender;
    private DatagramSocket socket;
    private String typedCall = "";      // callsign typed in the app
    private String aprsdroidCall = "";  // callsign APRSdroid reports when it starts

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_STARTED.equals(action)) {
                String c = intent.getStringExtra("callsign");
                if (c != null) aprsdroidCall = c.trim();
            } else if (ACTION_POSITION.equals(action)) {
                try {
                    handlePosition(intent);
                } catch (Exception e) {
                    Log.e(TAG, "Could not forward position", e);
                }
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        startAsForeground();

        sender = Executors.newSingleThreadExecutor();
        loadCallsign();

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_POSITION);
        filter.addAction(ACTION_STARTED);
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED);

        running = true;
        Log.d(TAG, "Bridge started, sending to " + ATAK_HOST + ":" + ATAK_PORT);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        loadCallsign();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        try {
            unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignored) {
        }
        if (sender != null) sender.shutdownNow();
        if (socket != null) socket.close();
        Log.d(TAG, "Bridge stopped");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void loadCallsign() {
        typedCall = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
                .getString(MainActivity.KEY_CALL, "").trim();
    }

    private void startAsForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "APRS bridge", NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("APRS2TAK running")
                .setContentText("Sending APRSdroid stations to ATAK")
                .setContentIntent(open)
                .setOngoing(true)
                .build();
        int type = Build.VERSION.SDK_INT >= 34 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE : 0;
        ServiceCompat.startForeground(this, 1, n, type);
    }

    @SuppressWarnings("deprecation")
    private void handlePosition(Intent intent) {
        String source = intent.getStringExtra("source");
        String callsign = intent.getStringExtra("callsign");
        String packet = intent.getStringExtra("packet");
        Location loc = intent.getParcelableExtra("location");
        if (callsign == null || loc == null) return;
        callsign = callsign.trim();
        if (callsign.isEmpty()) return;

        // Skip our own beacons (ATAK already shows us). Objects we send still show.
        if (source != null && callsign.equalsIgnoreCase(source) && isOwnCall(source)) return;

        // read settings each time so changes in the app apply right away
        loadCallsign();
        int staleMinutes = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
                .getInt(MainActivity.KEY_STALE, MainActivity.DEFAULT_STALE);
        String xml = buildCot(callsign, source, packet, loc, staleMinutes);
        final byte[] data = xml.getBytes(StandardCharsets.UTF_8);
        final String call = callsign;

        sender.execute(() -> {
            try {
                if (socket == null) socket = new DatagramSocket();
                socket.send(new DatagramPacket(data, data.length,
                        InetAddress.getByName(ATAK_HOST), ATAK_PORT));
                forwarded++;
                lastCall = call;
                lastTime = System.currentTimeMillis();
                Log.d(TAG, "Sent " + call);
            } catch (Exception e) {
                Log.e(TAG, "UDP send failed", e);
            }
        });
    }

    private boolean isOwnCall(String source) {
        if (!aprsdroidCall.isEmpty() && aprsdroidCall.equalsIgnoreCase(source)) return true;
        if (typedCall.isEmpty()) return false;
        if (typedCall.contains("-")) return typedCall.equalsIgnoreCase(source);
        // typed without SSID: match N0CALL, N0CALL-7, N0CALL-9, ...
        String base = source.contains("-") ? source.substring(0, source.indexOf('-')) : source;
        return typedCall.equalsIgnoreCase(base);
    }

    private static String buildCot(String callsign, String source, String packet, Location loc,
                                   int staleMinutes) {
        long now = System.currentTimeMillis();
        String time = cotTime(now);
        String stale = cotTime(now + staleMinutes * 60_000L);

        double hae = loc.hasAltitude() ? loc.getAltitude() : UNKNOWN;
        double ce = loc.hasAccuracy() ? loc.getAccuracy() : UNKNOWN;

        char[] sym = parseSymbol(packet);           // {table, code} or null
        String icon = sym == null ? null : iconPath(sym[0], sym[1]);

        StringBuilder remarks = new StringBuilder("APRS via APRSdroid");
        if (sym != null) remarks.append(" [symbol ").append(sym[0]).append(sym[1]).append("]");
        if (source != null && !source.equalsIgnoreCase(callsign)) {
            remarks.append(" (object from ").append(source).append(")");
        }
        if (packet != null) remarks.append("\n").append(packet);

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
        sb.append("<event version=\"2.0\"")
                .append(" uid=\"").append(esc("APRS." + callsign.replaceAll("\\s+", "_"))).append("\"")
                // custom-icon markers use "unknown ground", like ATAK's own iconset markers
                .append(" type=\"").append(icon != null ? "a-u-G" : COT_TYPE).append("\"")
                .append(" how=\"m-g\"")
                .append(" time=\"").append(time).append("\"")
                .append(" start=\"").append(time).append("\"")
                .append(" stale=\"").append(stale).append("\">");
        sb.append(String.format(Locale.US,
                "<point lat=\"%.7f\" lon=\"%.7f\" hae=\"%.1f\" ce=\"%.1f\" le=\"%.1f\"/>",
                loc.getLatitude(), loc.getLongitude(), hae, ce, UNKNOWN));
        sb.append("<detail>");
        sb.append("<contact callsign=\"").append(esc(callsign)).append("\"/>");
        if (icon != null) {
            sb.append("<usericon iconsetpath=\"").append(esc(icon)).append("\"/>");
        }
        if (loc.hasBearing() || loc.hasSpeed()) {
            sb.append(String.format(Locale.US, "<track course=\"%.1f\" speed=\"%.2f\"/>",
                    loc.hasBearing() ? loc.getBearing() : 0f,
                    loc.hasSpeed() ? loc.getSpeed() : 0f));
        }
        sb.append("<remarks>").append(esc(remarks.toString())).append("</remarks>");
        sb.append("</detail></event>");
        return sb.toString();
    }

    // ---------------- APRS symbol parsing ----------------

    /**
     * Finds the APRS symbol in a raw packet ("SRC>DST,PATH:info").
     * Returns {tableChar, codeChar}, or null if it can't be found.
     */
    static char[] parseSymbol(String packet) {
        if (packet == null) return null;
        int colon = packet.indexOf(':');
        if (colon < 0 || colon + 1 >= packet.length()) return null;
        String info = packet.substring(colon + 1);
        try {
            char dti = info.charAt(0);
            switch (dti) {
                case '}':                    // third-party: a whole packet inside
                    return parseSymbol(info.substring(1));
                case '!':
                case '=':                    // position, no timestamp
                    return positionSymbol(info, 1);
                case '/':
                case '@':                    // position with 7-char timestamp
                    return positionSymbol(info, 8);
                case ';':                    // object: 9-char name, * or _, timestamp
                    return positionSymbol(info, 18);
                case ')': {                  // item: 3-9 char name ending in ! or _
                    int i = 1;
                    while (i < info.length() && i <= 10
                            && info.charAt(i) != '!' && info.charAt(i) != '_') i++;
                    return positionSymbol(info, i + 1);
                }
                case '`':
                case '\'':
                case 0x1c:
                case 0x1d:                   // Mic-E: code at [7], table at [8]
                    return new char[]{info.charAt(8), info.charAt(7)};
                default:
                    return null;
            }
        } catch (IndexOutOfBoundsException e) {
            return null;
        }
    }

    /** Position data starting at p: uncompressed (digits) or compressed (base-91). */
    private static char[] positionSymbol(String s, int p) {
        char first = s.charAt(p);
        if (Character.isDigit(first) || first == ' ') {
            // DDMM.mmN T DDDMM.mmW C
            return new char[]{s.charAt(p + 8), s.charAt(p + 18)};
        }
        // compressed: T YYYY XXXX C
        return new char[]{first, s.charAt(p + 9)};
    }

    /** Icon path inside the APRS iconset, or null for an unknown symbol. */
    static String iconPath(char table, char code) {
        if (code < '!' || code > '~') return null;
        String c3 = String.format(Locale.US, "%03d", (int) code);
        if (table == '/') {
            return ICONSET_UID + "/Primary/P" + c3 + ".png";
        }
        if (table == '\\') {
            return ICONSET_UID + "/Alternate/A" + c3 + ".png";
        }
        // overlay: the table position holds a character drawn on an alternate symbol
        char overlay;
        if (Character.isDigit(table) || (table >= 'A' && table <= 'Z')) {
            overlay = table;
        } else if (table >= 'a' && table <= 'j') {
            overlay = (char) ('0' + (table - 'a'));   // compressed format: a-j = 0-9
        } else {
            return null;
        }
        if (OVERLAY_BASES.indexOf(code) >= 0) {
            return ICONSET_UID + "/Overlay/O" + c3 + overlay + ".png";
        }
        return ICONSET_UID + "/Alternate/A" + c3 + ".png";   // no overlay version: plain symbol
    }

    private static String cotTime(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ms));
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
