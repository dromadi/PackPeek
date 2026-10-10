package com.packpeek.app.engine.risk

import com.packpeek.app.engine.risk.Capability.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * Reads the compiled code (DEX) of an APK without running it: which sensitive calls each method
 * makes, which strings it uses, and which of the app's own methods it calls. From that it reports
 * T2 evidence (a call exists) and T3 indications (one method, or the methods it calls directly,
 * both reads personal data and sends data out).
 *
 * Bounded in time and size; anything skipped is reported as a [CodeGap].
 */
object DexScanner {
    const val MAX_DEX_BYTES = 96 * 1024 * 1024
    const val TIME_LIMIT_MS = 30_000L
    const val DEEP_TIME_LIMIT_MS = 90_000L
    /** Call levels followed by the deep analysis. */
    const val DEEP_DEPTH = 3
    private const val MAX_CHAINS = 12
    private const val LISTING_LINES = 60
    private const val MAX_STRINGS_PER_METHOD = 200
    private const val PLACES_PER_CAPABILITY = 4
    private const val MAX_FLOWS = 24
    /** Constructors of small app classes stand for all their methods (lambdas, Runnables). */
    private const val CONSTRUCTOR_EXPANSION_LIMIT = 24

    private class Rule(val capability: Capability, val api: String, val string: Boolean = false)

    private fun rules(vararg entries: Pair<String, Capability>): Map<String, Rule> =
        entries.associate { (key, cap) ->
            val cls = key.substringBefore("->").removePrefix("L").removeSuffix(";").substringAfterLast('/')
            key to Rule(cap, "${cls.replace('$', '.')}.${key.substringAfter("->")}")
        }

    /** "Lclass;->method" for invoked methods. */
    private val methodRules =
        rules(
            "Landroid/telephony/SmsMessage;->createFromPdu" to SMS_READ,
            "Landroid/telephony/SmsMessage;->getMessageBody" to SMS_READ,
            "Landroid/telephony/SmsMessage;->getDisplayMessageBody" to SMS_READ,
            "Landroid/telephony/SmsMessage;->getOriginatingAddress" to SMS_READ,
            "Landroid/telephony/gsm/SmsMessage;->createFromPdu" to SMS_READ,
            "Landroid/provider/Telephony\$Sms\$Intents;->getMessagesFromIntent" to SMS_READ,
            "Landroid/content/ClipboardManager;->getPrimaryClip" to CLIPBOARD,
            "Landroid/content/ClipboardManager;->getText" to CLIPBOARD,
            "Landroid/content/ClipboardManager;->addPrimaryClipChangedListener" to CLIPBOARD,
            "Landroid/service/notification/NotificationListenerService;->getActiveNotifications" to NOTIFICATION_READ,
            "Landroid/service/notification/StatusBarNotification;->getNotification" to NOTIFICATION_READ,
            "Landroid/view/accessibility/AccessibilityNodeInfo;->getText" to SCREEN_READ,
            "Landroid/view/accessibility/AccessibilityNodeInfo;->findAccessibilityNodeInfosByViewId" to SCREEN_READ,
            "Landroid/view/accessibility/AccessibilityNodeInfo;->findAccessibilityNodeInfosByText" to SCREEN_READ,
            "Landroid/view/accessibility/AccessibilityEvent;->getText" to SCREEN_READ,
            "Landroid/view/accessibility/AccessibilityRecord;->getText" to SCREEN_READ,
            "Landroid/accessibilityservice/AccessibilityService;->getRootInActiveWindow" to SCREEN_READ,
            "Landroid/accessibilityservice/AccessibilityService;->getWindows" to SCREEN_READ,
            "Landroid/accessibilityservice/AccessibilityService;->dispatchGesture" to GESTURES,
            "Landroid/accessibilityservice/AccessibilityService;->performGlobalAction" to GESTURES,
            "Landroid/view/accessibility/AccessibilityNodeInfo;->performAction" to GESTURES,
            "Landroid/webkit/CookieManager;->getCookie" to COOKIES,
            "Landroid/webkit/WebView;->addJavascriptInterface" to JS_BRIDGE,
            "Landroid/telephony/TelephonyManager;->getLine1Number" to PHONE_ID,
            "Landroid/telephony/TelephonyManager;->getDeviceId" to PHONE_ID,
            "Landroid/telephony/TelephonyManager;->getImei" to PHONE_ID,
            "Landroid/telephony/TelephonyManager;->getSubscriberId" to PHONE_ID,
            "Landroid/telephony/TelephonyManager;->getSimSerialNumber" to PHONE_ID,
            "Landroid/telephony/SubscriptionInfo;->getNumber" to PHONE_ID,
            "Landroid/accounts/AccountManager;->getAccounts" to ACCOUNTS,
            "Landroid/accounts/AccountManager;->getAccountsByType" to ACCOUNTS,
            "Landroid/location/LocationManager;->getLastKnownLocation" to LOCATION,
            "Landroid/location/LocationManager;->requestLocationUpdates" to LOCATION,
            "Lcom/google/android/gms/location/FusedLocationProviderClient;->getLastLocation" to LOCATION,
            "Lcom/google/android/gms/location/FusedLocationProviderClient;->getCurrentLocation" to LOCATION,
            "Lcom/google/android/gms/location/FusedLocationProviderClient;->requestLocationUpdates" to LOCATION,
            "Landroid/media/AudioRecord;->startRecording" to CAMERA_MIC,
            "Landroid/media/MediaRecorder;->setAudioSource" to CAMERA_MIC,
            "Landroid/media/MediaRecorder;->setVideoSource" to CAMERA_MIC,
            "Landroid/hardware/camera2/CameraManager;->openCamera" to CAMERA_MIC,
            "Landroid/hardware/Camera;->open" to CAMERA_MIC,
            "Landroid/media/projection/MediaProjectionManager;->getMediaProjection" to SCREEN_CAPTURE,
            "Landroid/media/projection/MediaProjection;->createVirtualDisplay" to SCREEN_CAPTURE,
            "Ljava/net/URL;->openConnection" to NETWORK,
            "Ljava/net/URL;->openStream" to NETWORK,
            "Ljava/net/URLConnection;->getOutputStream" to NETWORK,
            "Ljava/net/HttpURLConnection;->getOutputStream" to NETWORK,
            "Ljavax/net/ssl/HttpsURLConnection;->getOutputStream" to NETWORK,
            "Ljava/net/Socket;-><init>" to NETWORK,
            "Lokhttp3/OkHttpClient;->newCall" to NETWORK,
            "Lokhttp3/OkHttpClient;->newWebSocket" to NETWORK,
            "Lokhttp3/Call;->execute" to NETWORK,
            "Lokhttp3/Call;->enqueue" to NETWORK,
            "Lorg/apache/http/client/HttpClient;->execute" to NETWORK,
            "Lorg/apache/http/impl/client/DefaultHttpClient;->execute" to NETWORK,
            "Lcom/android/volley/RequestQueue;->add" to NETWORK,
            "Lretrofit2/Retrofit;->create" to NETWORK,
            "Lcom/google/firebase/database/DatabaseReference;->setValue" to NETWORK,
            "Lcom/google/firebase/database/DatabaseReference;->updateChildren" to NETWORK,
            "Lcom/google/firebase/firestore/CollectionReference;->add" to NETWORK,
            "Landroid/telephony/SmsManager;->sendTextMessage" to SMS_SEND,
            "Landroid/telephony/SmsManager;->sendMultipartTextMessage" to SMS_SEND,
            "Landroid/telephony/SmsManager;->sendDataMessage" to SMS_SEND,
            "Landroid/telephony/gsm/SmsManager;->sendTextMessage" to SMS_SEND,
            "Landroid/content/pm/PackageManager;->setComponentEnabledSetting" to HIDE_ICON,
            "Ldalvik/system/DexClassLoader;-><init>" to DYNAMIC_CODE,
            "Ldalvik/system/InMemoryDexClassLoader;-><init>" to DYNAMIC_CODE,
            "Ldalvik/system/DexFile;->loadDex" to DYNAMIC_CODE,
            "Ljava/lang/Runtime;->exec" to SHELL,
            "Ljava/lang/ProcessBuilder;->start" to SHELL,
            "Landroid/content/pm/PackageInstaller;->createSession" to INSTALL_PACKAGE,
            "Landroid/app/admin/DevicePolicyManager;->lockNow" to DEVICE_ADMIN_ACTION,
            "Landroid/app/admin/DevicePolicyManager;->wipeData" to DEVICE_ADMIN_ACTION,
            "Landroid/app/admin/DevicePolicyManager;->resetPassword" to DEVICE_ADMIN_ACTION,
            "Landroid/telephony/TelephonyManager;->sendUssdRequest" to CALL_FORWARDING,
            "Landroid/content/ContentResolver;->query" to CONTENT_QUERY,
            "Landroid/content/ContentProviderClient;->query" to CONTENT_QUERY,
        )

    /** "Lclass;->FIELD" for static fields read with sget. */
    private val fieldRules =
        rules(
            "Landroid/provider/Telephony\$Sms;->CONTENT_URI" to SMS_READ,
            "Landroid/provider/Telephony\$Sms\$Inbox;->CONTENT_URI" to SMS_READ,
            "Landroid/provider/ContactsContract\$Contacts;->CONTENT_URI" to CONTACTS,
            "Landroid/provider/ContactsContract\$CommonDataKinds\$Phone;->CONTENT_URI" to CONTACTS,
            "Landroid/provider/CallLog\$Calls;->CONTENT_URI" to CALL_LOG,
        )

    private val ruleClasses =
        (methodRules.keys + fieldRules.keys).map { it.substringBefore("->") }.toSet()

    private val telegramToken = Regex("""(?<![0-9])\d{8,10}:[A-Za-z0-9_-]{35}(?![A-Za-z0-9_-])""")
    private val forwardingCode = Regex("""^(\*\*?|#)(21|61|62|67|002|004)\*""")
    private val url = Regex("""(https?)://([A-Za-z0-9.-]+|\[[0-9a-fA-F:]+])(:\d+)?""")
    private val ignoredHosts =
        setOf("schemas.android.com", "www.w3.org", "www.apache.org", "xmlpull.org", "localhost", "127.0.0.1",
            "example.com", "www.example.com", "ns.adobe.com", "purl.org", "xml.org", "www.xml.org")

    /** Labels of letters, digits and hyphens, ending in an alphabetic top-level domain. */
    private val domain = Regex("""^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,24}$""")

    private val base64 = Regex("""^[A-Za-z0-9+/]+={0,2}$""")

    /** Calls that hand an app class to Android to run later (Intent, work request, job). */
    private val launchKeys =
        setOf(
            "Landroid/content/Intent;-><init>",
            "Landroid/content/Intent;->setClass",
            "Landroid/content/ComponentName;-><init>",
            "Landroidx/work/OneTimeWorkRequest\$Builder;-><init>",
            "Landroidx/work/PeriodicWorkRequest\$Builder;-><init>",
            "Landroidx/core/app/JobIntentService;->enqueueWork",
            "Landroid/app/job/JobInfo\$Builder;-><init>",
        )

    /** Methods Android calls on a component started through an Intent or work request. */
    private val entryPoints =
        setOf("onReceive", "onStartCommand", "onHandleIntent", "onHandleWork", "handleWork", "doWork",
            "startWork", "onStartJob", "onCreate", "run", "onBind")

    /** Standard Base64 without padding checks; java.util.Base64 needs API 26. */
    fun decodeBase64(s: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val out = java.io.ByteArrayOutputStream(s.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (c in s) {
            if (c == '=') break
            val v = alphabet.indexOf(c)
            require(v >= 0)
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xff)
            }
        }
        return out.toByteArray()
    }

    private fun printable(s: String) =
        s.isNotEmpty() && s.count { it.code in 32..126 || it.isLetterOrDigit() } >= s.length * 0.9

    private fun interesting(s: String): Rule? = stringRule(s)?.takeIf { it.capability != INSTALL_PACKAGE }

    private fun decodingName(d: Decoding) =
        when (d) {
            Decoding.BASE64 -> "Base64"
            Decoding.JOINED -> "joined"
            Decoding.CHAR_ARRAY -> "char array"
            Decoding.XOR -> "XOR"
            Decoding.REVERSED -> "reversed"
        }

    /** Classifies a string constant; null when uninteresting. */
    private fun stringRule(s: String): Rule? =
        when {
            "api.telegram.org" in s || telegramToken.containsMatchIn(s) -> Rule(TELEGRAM, maskToken(s), true)
            "discord.com/api/webhooks" in s || "discordapp.com/api/webhooks" in s ->
                Rule(DISCORD, s.substringBefore("/webhooks") + "/webhooks/…", true)
            s.startsWith("content://sms") || s.startsWith("content://mms-sms") -> Rule(SMS_READ, s, true)
            s.startsWith("content://com.android.contacts") -> Rule(CONTACTS, s, true)
            s.startsWith("content://call_log") -> Rule(CALL_LOG, s, true)
            forwardingCode.containsMatchIn(s) -> Rule(CALL_FORWARDING, s.take(24), true)
            s == "application/vnd.android.package-archive" -> Rule(INSTALL_PACKAGE, s, true)
            else -> null
        }

    /** Hides most of a bot token so the evidence never republishes a working credential. */
    private fun maskToken(s: String): String =
        telegramToken.replace(s.take(120)) { m ->
            val v = m.value
            v.substringBefore(':') + ":" + v.substringAfter(':').take(4) + "…"
        }

    /** Known SDK and library package prefixes, longest first; [ad] marks advertising/analytics. */
    private class Library(val prefix: String, val name: String, val ad: Boolean = false)

    private val libraries =
        listOf(
                Library("Lcom/google/android/gms/ads/", "Google AdMob", true),
                Library("Lcom/google/android/gms/measurement/", "Google Analytics for Firebase", true),
                Library("Lcom/google/firebase/analytics/", "Google Analytics for Firebase", true),
                Library("Lcom/google/firebase/crashlytics/", "Firebase Crashlytics", true),
                Library("Lcom/google/firebase/", "Firebase"),
                Library("Lcom/google/android/gms/", "Google Play services"),
                Library("Lcom/google/", "Google libraries"),
                Library("Lcom/facebook/ads/", "Meta Audience Network", true),
                Library("Lcom/facebook/appevents/", "Facebook App Events", true),
                Library("Lcom/facebook/react/", "React Native"),
                Library("Lcom/facebook/", "Facebook SDK"),
                Library("Lcom/applovin/", "AppLovin", true),
                Library("Lcom/unity3d/ads/", "Unity Ads", true),
                Library("Lcom/unity3d/", "Unity"),
                Library("Lcom/ironsource/", "ironSource", true),
                Library("Lcom/bytedance/sdk/openadsdk/", "Pangle", true),
                Library("Lcom/bytedance/", "ByteDance SDK", true),
                Library("Lcom/mbridge/", "Mintegral", true),
                Library("Lcom/vungle/", "Vungle", true),
                Library("Lcom/inmobi/", "InMobi", true),
                Library("Lcom/chartboost/", "Chartboost", true),
                Library("Lcom/startapp/", "Start.io", true),
                Library("Lcom/adjust/sdk/", "Adjust", true),
                Library("Lcom/appsflyer/", "AppsFlyer", true),
                Library("Lcom/onesignal/", "OneSignal", true),
                Library("Lcom/clevertap/", "CleverTap", true),
                Library("Lcom/moengage/", "MoEngage", true),
                Library("Lcom/mixpanel/", "Mixpanel", true),
                Library("Lcom/amplitude/", "Amplitude", true),
                Library("Lcom/yandex/mobile/ads/", "Yandex Ads", true),
                Library("Lcom/huawei/hms/ads/", "Huawei Ads", true),
                Library("Lcom/huawei/", "Huawei Mobile Services"),
                Library("Lio/sentry/", "Sentry", true),
                Library("Lio/flutter/", "Flutter"),
                Library("Landroidx/", "AndroidX"),
                Library("Landroid/support/", "Android Support"),
                Library("Lkotlin/", "Kotlin"),
                Library("Lkotlinx/", "Kotlin"),
                Library("Lokhttp3/", "OkHttp"),
                Library("Lokio/", "Okio"),
                Library("Lretrofit2/", "Retrofit"),
                Library("Lcom/squareup/", "Square"),
                Library("Lio/reactivex/", "RxJava"),
                Library("Ldagger/", "Dagger"),
                Library("Ljavax/", "Java"),
                Library("Ljava/", "Java"),
                Library("Lorg/apache/", "Apache"),
                Library("Lorg/json/", "JSON"),
                Library("Lorg/jetbrains/", "JetBrains"),
                Library("Lorg/intellij/", "JetBrains"),
                Library("Lorg/chromium/", "Chromium"),
                Library("Lorg/bouncycastle/", "Bouncy Castle"),
                Library("Lorg/greenrobot/", "greenrobot"),
                Library("Lcom/bumptech/", "Glide"),
                Library("Lcom/airbnb/", "Lottie"),
                Library("Lcom/fasterxml/", "Jackson"),
                Library("Lcom/jakewharton/", "Jake Wharton libraries"),
                Library("Lcoil/", "Coil"),
                Library("Lio/ktor/", "Ktor"),
                Library("Lcom/microsoft/", "Microsoft"),
                Library("Lcom/amazon/", "Amazon"),
                Library("Lcom/tencent/", "Tencent"),
                Library("Lcom/android/volley/", "Volley"),
                Library("Lnet/sqlcipher/", "SQLCipher"),
                Library("Lcom/reandroid/", "ARSCLib"),
                Library("Lcom/android/apksig/", "apksig"),
                Library("Lcom/github/", "GitHub libraries"),
                Library("Lio/grpc/", "gRPC"),
                Library("Lorg/slf4j/", "SLF4J"),
                Library("Lorg/joda/", "Joda-Time"),
                Library("Lorg/jsoup/", "jsoup"),
                Library("Lcom/caverock/", "AndroidSVG"),
                Library("Lio/branch/", "Branch", true),
                Library("Lcom/kochava/", "Kochava", true),
                Library("Lcom/singular/", "Singular", true),
                Library("Lcom/tapjoy/", "Tapjoy", true),
                Library("Lcom/fyber/", "Fyber", true),
                Library("Lcom/smaato/", "Smaato", true),
                Library("Lnet/pubnative/", "PubNative", true),
                Library("Lcom/apm/insight/", "ByteDance APM", true),
                Library("Lcom/pgl/", "Pangle", true),
                Library("Lcom/tradplus/", "TradPlus", true),
                Library("Lcom/taurusx/", "TaurusX", true),
                Library("Lcom/anythink/", "TopOn", true),
                Library("Lcom/my/target/", "myTarget", true),
                Library("Lcom/iab/omid/", "Open Measurement SDK", true),
                Library("Lio/appmetrica/", "AppMetrica", true),
                Library("Lcom/yandex/", "Yandex SDK"),
                Library("Lio/jsonwebtoken/", "JJWT"),
                Library("Lnet/aihelp/", "AIHelp"),
            )
            .sortedByDescending { it.prefix.length }

    private fun library(descriptor: String): Library? = libraries.firstOrNull { descriptor.startsWith(it.prefix) }

    private val packerLibs =
        listOf("libjiagu", "libsecexe", "libsecmain", "libdexhelper", "libshell", "libprotectclass",
            "libnqshield", "libmobisec", "libexecmain", "libtosprotection", "libapkprotect", "libjgdtc",
            "libbaiduprotect", "libddog", "libnesec", "libapssec", "librsprotect")

    /** Everything an app method does that matters for flows. */
    private class MethodInfo {
        /** From calls and fields: always real. */
        var sources = 0
        var sinks = 0
        /** From text constants: only real together with a query or a network call. */
        var stringSources = 0
        var stringSinks = 0
        var markers = 0
        val apis = HashMap<Capability, String>()
        val callees = HashSet<String>()
        /** Deep: app classes named by const-class, started through an Intent or work request. */
        val constClasses = HashSet<String>()
        var launches = false
        val listing = ArrayList<String>()
        /** Value tracking: per sink bit, the source bits whose values reach that call's arguments. */
        val traced = HashMap<Int, Int>()
        /** Sinks reached by the method's own parameters. */
        var paramSinks = 0
        /** Sources whose values the method returns, and app methods whose results it returns. */
        var returnSources = 0
        val returnCallees = HashSet<String>()
        /** Calls to app methods: callee, source bits of the arguments, whether a parameter is passed. */
        val calls = ArrayList<TaintCall>()
        /** Per sink bit, app methods whose results reach that sink here. */
        val sinkCallees = HashMap<Int, MutableSet<String>>()

        fun merge(other: MethodInfo) {
            sources = sources or other.sources
            sinks = sinks or other.sinks
            stringSources = stringSources or other.stringSources
            stringSinks = stringSinks or other.stringSinks
            markers = markers or other.markers
            other.apis.forEach { (k, v) -> apis.putIfAbsent(k, v) }
            callees += other.callees
            constClasses += other.constClasses
            launches = launches || other.launches
            if (listing.isEmpty()) listing += other.listing
            other.traced.forEach { (k, v) -> traced[k] = (traced[k] ?: 0) or v }
            paramSinks = paramSinks or other.paramSinks
            returnSources = returnSources or other.returnSources
            returnCallees += other.returnCallees
            calls += other.calls
            other.sinkCallees.forEach { (k, v) -> sinkCallees.getOrPut(k) { HashSet() } += v }
        }
    }

    /** An app-method call seen by value tracking; [results] are callees whose results are passed. */
    private class TaintCall(val callee: String, val sources: Int, val param: Boolean, val results: Set<String>)

    /** Value-tracking label for "comes from a parameter"; capability bits use the low bits. */
    private const val PARAM = 1 shl 30

    private class State(val deadline: Long, val deep: Boolean = false) {
        val decoded = mutableListOf<DecodedText>()
        val decodedKeys = HashSet<String>()
        val payloads = mutableListOf<PayloadScan>()
        /** Capabilities seen while scanning the current payload, if any. */
        var payloadCaps: MutableSet<Capability>? = null
        var payloadClasses = 0
        val appHits = HashMap<Capability, MutableList<CodeHit>>()
        val libraryHits = HashMap<Capability, MutableList<CodeHit>>()
        val hitKeys = HashSet<String>()
        val methods = HashMap<String, MethodInfo>()
        val classMethods = HashMap<String, MutableList<String>>()
        val names = HashMap<String, String>()
        val destinations = LinkedHashMap<String, Destination>()
        /** Value tracking through fields: source bits stored into each field so far. */
        val fieldTaint = HashMap<String, Int>()
        /** App classes (descriptors) that contain a public IP literal, with the first one found. */
        val classIps = HashMap<String, String>()
        /**
         * Text evidence in app code waiting for confirmation: bot/webhook texts count only in a
         * class that can send, content:// texts only in a class that queries.
         */
        val pendingTexts = mutableListOf<Pair<String, CodeHit>>()
        val sdks = sortedSetOf<String>()
        val gaps = mutableListOf<CodeGap>()
        var dexFiles = 0
        var classes = 0
        var appClasses = 0
        var stopped = false

        /** Keeps a few places per capability: the app's own code, then known libraries. */
        fun hit(rule: Rule, method: String, library: String?) {
            if (library == null) payloadCaps?.add(rule.capability)
            if (!hitKeys.add("${rule.capability}|${rule.api}|$method")) return
            val list = (if (library == null) appHits else libraryHits).getOrPut(rule.capability) { mutableListOf() }
            val limit = if (library == null) PLACES_PER_CAPABILITY else PLACES_PER_CAPABILITY / 2
            if (list.size < limit) list += CodeHit(rule.capability, rule.api, method, library, rule.string)
        }

        fun hits(confirmedTexts: List<CodeHit>): Map<Capability, List<CodeHit>> {
            val all = (appHits - CONTENT_QUERY).toMutableMap<Capability, List<CodeHit>>()
            confirmedTexts.groupBy { it.capability }.forEach { (cap, list) ->
                val texts = list.distinctBy { it.api to it.method }
                all[cap] = (all[cap].orEmpty() + texts).take(PLACES_PER_CAPABILITY)
            }
            return (all.keys + libraryHits.keys - CONTENT_QUERY).associateWith {
                all[it].orEmpty() + libraryHits[it].orEmpty()
            }
        }
    }

    /**
     * @param deep also follows calls [DEEP_DEPTH] levels deep and through Intents, decodes hidden
     *   texts, scans code files hidden in assets and keeps listings of the methods in evidence.
     */
    fun scan(apks: List<File>, timeLimitMs: Long = TIME_LIMIT_MS, deep: Boolean = false): CodeFacts {
        val started = System.currentTimeMillis()
        val state = State(started + timeLimitMs, deep)
        apks.forEach { apk ->
            runCatching {
                    ZipFile(apk).use { zip ->
                        val entries = zip.entries().asSequence().toList()
                        inspectPackaging(zip, entries, apk.name, state)
                        entries
                            .filter { Regex("""classes\d*\.dex""").matches(it.name) }
                            .sortedBy { it.name.length * 100 + (it.name.filter(Char::isDigit).toIntOrNull() ?: 0) }
                            .forEach { entry ->
                                if (state.stopped) return@forEach
                                if (entry.size > MAX_DEX_BYTES) {
                                    state.gaps += CodeGap(GapKind.LIMIT, "${apk.name}!${entry.name}")
                                    return@forEach
                                }
                                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                                try {
                                    Dex(bytes, state).scan()
                                    state.dexFiles++
                                } catch (e: OutOfMemoryError) {
                                    state.gaps += CodeGap(GapKind.LIMIT, "${apk.name}!${entry.name}")
                                } catch (e: Exception) {
                                    state.gaps += CodeGap(GapKind.UNREADABLE, "${apk.name}!${entry.name}")
                                }
                            }
                    }
                }
                .onFailure { state.gaps += CodeGap(GapKind.UNREADABLE, apk.name) }
        }
        if (state.stopped) state.gaps += CodeGap(GapKind.LIMIT, "time")
        val (shallowFlows, classes) = flows(state)
        val chains = if (deep) chains(state, classes) else emptyList()
        // Deep chains also count as flows, so the findings pick them up.
        val flows =
            (shallowFlows +
                    chains.map {
                        CodeFlow(it.source, it.sink, it.toSink.first().method, it.sourceApi, it.sinkApi, it.ipHost)
                    })
                .sortedBy { if (it.ipHost != null) 0 else 1 }
                .distinctBy { Triple(it.source, it.sink, it.method) }
                .sortedBy { if (it.sink in Capability.hiddenDestinations) 0 else 1 }
        val confirmedTexts =
            state.pendingTexts
                .filter { (key, hit) ->
                    val owner = key.substringBefore("->")
                    if (hit.capability.source) owner in classes.querying else owner in classes.sending
                }
                .map { it.second }
        val hits = state.hits(confirmedTexts)
        val deepFacts =
            if (!deep) null
            else {
                val evidence =
                    (chains.flatMap { c -> (c.toSource + c.toSink).map { it.method } } +
                            state.decoded.map { it.method } +
                            hits.values.flatten().filter { it.library == null }.map { it.method })
                        .toSet()
                DeepFacts(
                    chains = chains,
                    decoded = state.decoded.toList(),
                    payloads = state.payloads.toList(),
                    listings =
                        state.methods
                            .filter { (key, info) -> info.listing.isNotEmpty() && (state.names[key] ?: key) in evidence }
                            .entries
                            .take(40)
                            .associate { (key, info) -> (state.names[key] ?: key) to info.listing.toList() },
                    sha256 = apks.firstOrNull()?.let(::sha256).orEmpty(),
                    bytes = apks.sumOf { it.length() },
                    millis = System.currentTimeMillis() - started,
                )
            }
        return CodeFacts(
            dexFiles = state.dexFiles,
            classes = state.classes,
            appClasses = state.appClasses,
            hits = hits,
            flows = flows,
            destinations = state.destinations.values.toList(),
            sdks = state.sdks.toList(),
            gaps = state.gaps.distinct(),
            deep = deepFacts,
        )
    }

    private fun sha256(file: File): String =
        runCatching {
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        digest.update(buffer, 0, n)
                    }
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
            .getOrDefault("")

    /** Code that DEX analysis cannot see: hidden payloads, packers, cross-platform engines. */
    private fun inspectPackaging(
        zip: ZipFile,
        entries: List<java.util.zip.ZipEntry>,
        apk: String,
        state: State,
    ) {
        entries.forEach { e ->
            val name = e.name
            val lower = name.lowercase()
            val file = lower.substringAfterLast('/')
            if (lower.startsWith("lib/") && file.endsWith(".so")) {
                packerLibs.firstOrNull { file.startsWith(it) }?.let {
                    state.gaps += CodeGap(GapKind.PACKER, file)
                }
                when {
                    file == "libflutter.so" || file == "libapp.so" -> state.gaps += CodeGap(GapKind.CROSS_PLATFORM, "Flutter")
                    file == "libil2cpp.so" -> state.gaps += CodeGap(GapKind.CROSS_PLATFORM, "Unity (IL2CPP)")
                    file == "libhermes.so" || file == "libreactnativejni.so" ->
                        state.gaps += CodeGap(GapKind.CROSS_PLATFORM, "React Native")
                }
            } else if (lower == "assets/index.android.bundle")
                state.gaps += CodeGap(GapKind.CROSS_PLATFORM, "React Native")
            else if (
                !e.isDirectory &&
                    (lower.startsWith("assets/") || lower.startsWith("res/") || !lower.contains('/')) &&
                    !Regex("""classes\d*\.dex""").matches(name) &&
                    e.size in 112..MAX_DEX_BYTES.toLong()
            ) {
                // Payloads are often renamed, so check the content, not just the extension.
                val head = runCatching { zip.getInputStream(e).use { magic(it) } }.getOrNull()
                val dex = head != null && head.size == 4 && head[0] == 'd'.code.toByte() &&
                    head[1] == 'e'.code.toByte() && head[2] == 'x'.code.toByte() && head[3] == '\n'.code.toByte()
                val archive = file.endsWith(".jar") || file.endsWith(".apk") || file.endsWith(".dex")
                val zipped = head != null && head.size == 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
                if (dex || (archive && zipped)) {
                    state.gaps += CodeGap(GapKind.HIDDEN_CODE, "$apk!$name")
                    if (state.deep) scanPayload(zip, e, if (dex) PayloadKind.DEX else PayloadKind.ARCHIVE, state)
                } else if (state.deep && lower.startsWith("assets/") && e.size >= 16 * 1024 && file.substringAfterLast('.', "") !in mediaExtensions)
                    entropy(zip, e)?.takeIf { it > 7.6 }?.let {
                        state.payloads += PayloadScan(name, PayloadKind.ENCRYPTED, 0, emptySet(), it)
                    }
            }
        }
    }

    private val mediaExtensions =
        setOf("png", "jpg", "jpeg", "webp", "gif", "mp3", "mp4", "ogg", "wav", "m4a", "ttf", "otf", "woff", "woff2",
            "zip", "gz", "pak", "bundle", "tflite", "bin_compressed", "jar", "apk", "dex")

    /** Shannon entropy of the first 64 KiB, in bits per byte. */
    private fun entropy(zip: ZipFile, entry: java.util.zip.ZipEntry): Double? =
        runCatching {
                val counts = IntArray(256)
                var total = 0
                zip.getInputStream(entry).use { input ->
                    val buffer = ByteArray(8192)
                    while (total < 65536) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        for (i in 0 until n) counts[buffer[i].toInt() and 255]++
                        total += n
                    }
                }
                if (total == 0) return null
                counts.filter { it > 0 }.sumOf { val p = it.toDouble() / total; -p * kotlin.math.ln(p) / kotlin.math.ln(2.0) }
            }
            .getOrNull()

    /** Deep: scans a DEX or an APK/JAR hidden in the package with the same rules. */
    private fun scanPayload(zip: ZipFile, entry: java.util.zip.ZipEntry, kind: PayloadKind, state: State) {
        val caps = mutableSetOf<Capability>()
        val before = state.classes
        state.payloadCaps = caps
        try {
            val bytes = zip.getInputStream(entry).use { it.readBytes() }
            if (kind == PayloadKind.DEX) Dex(bytes, state, entry.name).scan()
            else {
                val temp = File.createTempFile("payload", ".zip")
                try {
                    temp.writeBytes(bytes)
                    ZipFile(temp).use { inner ->
                        inner.entries().asSequence().filter { Regex("""classes\d*\.dex""").matches(it.name) }.forEach { dex ->
                            if (dex.size <= MAX_DEX_BYTES)
                                Dex(inner.getInputStream(dex).use { it.readBytes() }, state, entry.name).scan()
                        }
                    }
                } finally {
                    temp.delete()
                }
            }
        } catch (e: Exception) {
            /* Recorded with no classes below. */
        } finally {
            state.payloadCaps = null
        }
        state.payloads += PayloadScan(entry.name, kind, state.classes - before, caps - CONTENT_QUERY)
    }

    /** First four bytes; InputStream.readNBytes needs API 33. */
    private fun magic(input: java.io.InputStream): ByteArray {
        val out = ByteArray(4)
        var n = 0
        while (n < 4) {
            val read = input.read(out, n, 4 - n)
            if (read < 0) break
            n += read
        }
        return out.copyOf(n)
    }

    private val sourceBits = Capability.entries.filter { it.source }.fold(0) { acc, c -> acc or c.bit }

    private fun methodName(key: String) = key.substringAfter("->").substringBefore('(')

    private fun isConstructor(key: String) = methodName(key) == "<init>"

    private class Classes(val sending: Set<String>, val querying: Set<String>)

    /** Read-then-send paths, and the classes that can actually send or query (for text evidence). */
    private fun flows(state: State): Pair<List<CodeFlow>, Classes> {
        val result = LinkedHashMap<String, CodeFlow>()
        val sending = HashSet<String>()
        val querying = HashSet<String>()
        fun calleeKeys(info: MethodInfo): List<String> =
            info.callees.flatMap { key ->
                if (isConstructor(key)) {
                    val members = state.classMethods[key.substringBefore("->")].orEmpty()
                    if (members.size <= CONSTRUCTOR_EXPANSION_LIMIT) members else listOf(key)
                } else listOf(key)
            }
        val traced = tracedFlows(state)
        val candidates = ArrayList<CodeFlow>()
        for ((key, info) in state.methods) {
            val reachKeys = (calleeKeys(info) + key).filter { it in state.methods }
            val reach = reachKeys.map { state.methods.getValue(it) }
            // The bare IP must sit in the class of code that actually sends.
            val ipHost =
                reachKeys.firstNotNullOfOrNull { k ->
                    if (state.methods.getValue(k).sinks and NETWORK.bit != 0) state.classIps[k.substringBefore("->")]
                    else null
                }
            val all = MethodInfo().apply { reach.forEach(::merge) }
            val queries = all.markers and CONTENT_QUERY.bit != 0
            val network = all.sinks and NETWORK.bit != 0
            // A bot URL is often a static field set in <clinit> and used by another method of the
            // same class, so a class that can send counts for all its texts.
            if (network) sending += key.substringBefore("->")
            if (queries) querying += key.substringBefore("->")
            val sources = all.sources or (if (queries) all.stringSources else 0)
            val sinks = all.sinks or (if (network) all.stringSinks else 0)
            if (sources == 0 || sinks == 0) continue
            val here = traced[key].orEmpty()
            for (source in Capability.entries.filter { it.source && sources and it.bit != 0 })
                for (sink in Capability.entries.filter { it.sink && sinks and it.bit != 0 }) {
                    if (candidates.size >= MAX_FLOWS * 20) break
                    // Traced: the value read from the source reaches the sending call's arguments.
                    // A bot address in the same path makes a traced network send a traced bot send.
                    val valueTraced =
                        (here[sink.bit] ?: 0) and source.bit != 0 ||
                            (sink in Capability.hiddenDestinations && (here[NETWORK.bit] ?: 0) and source.bit != 0)
                    candidates +=
                        CodeFlow(
                            source,
                            sink,
                            state.names[key] ?: key,
                            reach.firstNotNullOf { it.apis[source] },
                            reach.firstNotNullOf { it.apis[sink] },
                            ipHost.takeIf { sink == NETWORK },
                            valueTraced,
                        )
                }
        }
        // Traced paths first, then a few per kind.
        for (flow in candidates.sortedBy { if (it.traced) 0 else 1 }) {
            val id = "${flow.source}>${flow.sink}"
            if (result.keys.count { it.startsWith("$id|") } >= 2 || result.size >= MAX_FLOWS) continue
            result["$id|${flow.method}|${result.size}"] = flow
        }
        // Hidden destinations first: they are the strongest sign.
        return result.values.sortedBy { if (it.sink in Capability.hiddenDestinations) 0 else 1 } to Classes(sending, querying)
    }

    /**
     * Per method, sink bit -> source bits whose values are traced into that sink: in the method
     * itself, through app methods it passes them to (whose parameters reach a sink), and through
     * app methods whose results (holding source values) it sends.
     */
    private fun tracedFlows(state: State): Map<String, Map<Int, Int>> {
        val returns = HashMap<String, Int>()
        val paramSinks = HashMap<String, Int>()
        state.methods.forEach { (k, m) ->
            returns[k] = m.returnSources
            paramSinks[k] = m.paramSinks
        }
        // A few rounds of propagation through callers cover chains of helpers.
        repeat(6) {
            var changed = false
            state.methods.forEach { (k, m) ->
                var r = returns.getValue(k)
                m.returnCallees.forEach { r = r or (returns[it] ?: 0) }
                var p = paramSinks.getValue(k)
                m.calls.forEach { if (it.param) p = p or (paramSinks[it.callee] ?: 0) }
                if (r != returns[k] || p != paramSinks[k]) changed = true
                returns[k] = r
                paramSinks[k] = p
            }
            if (!changed) return@repeat
        }
        val out = HashMap<String, Map<Int, Int>>()
        state.methods.forEach { (k, m) ->
            val map = HashMap(m.traced)
            fun add(sinkBits: Int, sources: Int) {
                if (sources == 0) return
                for (c in Capability.entries) if (c.sink && sinkBits and c.bit != 0) map[c.bit] = (map[c.bit] ?: 0) or sources
            }
            m.calls.forEach { call ->
                var sources = call.sources
                call.results.forEach { sources = sources or (returns[it] ?: 0) }
                add(paramSinks[call.callee] ?: 0, sources)
            }
            m.sinkCallees.forEach { (sinkBit, callees) ->
                var sources = 0
                callees.forEach { sources = sources or (returns[it] ?: 0) }
                add(sinkBit, sources)
            }
            if (map.isNotEmpty()) out[k] = map
        }
        return out
    }

    /**
     * Deep: read-then-send paths up to [DEEP_DEPTH] levels, following calls, new objects and
     * Intents. Each chain keeps the shortest path to the reading and to the sending code.
     */
    private fun chains(state: State, classes: Classes): List<CodeChain> {
        fun edges(key: String, info: MethodInfo): List<Pair<String, StepVia>> {
            val out = ArrayList<Pair<String, StepVia>>()
            info.callees.forEach { callee ->
                if (isConstructor(callee)) {
                    val members = state.classMethods[callee.substringBefore("->")].orEmpty()
                    if (members.size <= CONSTRUCTOR_EXPANSION_LIMIT) members.forEach { out += it to StepVia.OBJECT }
                }
                if (callee in state.methods && callee != key) out += callee to StepVia.CALL
            }
            if (info.launches)
                info.constClasses.forEach { cls ->
                    state.classMethods[cls].orEmpty()
                        .filter { methodName(it) in entryPoints }
                        .forEach { out += it to StepVia.INTENT }
                }
            return out
        }
        fun sources(info: MethodInfo, owner: String) =
            info.sources or (if (owner in classes.querying) info.stringSources else 0)
        fun sinks(info: MethodInfo, owner: String) =
            info.sinks or (if (owner in classes.sending) info.stringSinks else 0)
        val result = LinkedHashMap<String, CodeChain>()
        for ((start, startInfo) in state.methods) {
            if (result.size >= MAX_CHAINS * 3) break
            // Breadth-first so each node keeps its shortest path from the start.
            val parent = LinkedHashMap<String, Pair<String?, StepVia>>()
            parent[start] = null to StepVia.START
            var frontier = listOf(start)
            repeat(DEEP_DEPTH) {
                val next = ArrayList<String>()
                for (node in frontier) {
                    val info = state.methods[node] ?: continue
                    for ((target, via) in edges(node, info)) {
                        if (target !in parent && target in state.methods) {
                            parent[target] = node to via
                            next += target
                        }
                    }
                }
                frontier = next
            }
            fun path(to: String): List<ChainStep> {
                val steps = ArrayList<ChainStep>()
                var cursor: String? = to
                while (cursor != null) {
                    val (from, via) = parent.getValue(cursor)
                    steps += ChainStep(state.names[cursor] ?: cursor, via)
                    cursor = from
                }
                return steps.reversed()
            }
            for (source in Capability.entries.filter { it.source }) {
                val sourceNode = parent.keys.firstOrNull { sources(state.methods.getValue(it), it.substringBefore("->")) and source.bit != 0 } ?: continue
                for (sink in Capability.entries.filter { it.sink }) {
                    val sending = parent.keys.filter { sinks(state.methods.getValue(it), it.substringBefore("->")) and sink.bit != 0 }
                    // A network sink in a class holding a bare IP is the one that matters.
                    val sinkNode =
                        (if (sink == NETWORK) sending.firstOrNull { it.substringBefore("->") in state.classIps } else null)
                            ?: sending.firstOrNull()
                            ?: continue
                    val id = "$source>$sink"
                    val toSource = path(sourceNode)
                    val toSink = path(sinkNode)
                    // Only chains the one-level analysis cannot see are new; keep a few per kind.
                    if (toSource.size <= 2 && toSink.size <= 2 && toSource.none { it.via == StepVia.INTENT } && toSink.none { it.via == StepVia.INTENT }) continue
                    if (result.keys.count { it.startsWith(id) } >= 2) continue
                    result["$id|$start"] =
                        CodeChain(
                            source,
                            sink,
                            state.methods.getValue(sourceNode).apis[source].orEmpty(),
                            state.methods.getValue(sinkNode).apis[sink].orEmpty(),
                            toSource,
                            toSink,
                            state.classIps[sinkNode.substringBefore("->")].takeIf { sink == NETWORK },
                        )
                }
            }
        }
        return result.values
            .sortedWith(compareBy({ if (it.sink in Capability.hiddenDestinations) 0 else 1 }, { it.toSource.size + it.toSink.size }))
            .take(MAX_CHAINS)
    }

    /** One DEX file. Offsets follow the format at source.android.com/docs/core/runtime/dex-format. */
    private class Dex(val bytes: ByteArray, val state: State, val origin: String = "") {
        val b: ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val strings: Array<String?>
        val stringIdsOff: Int
        val typeIdsOff: Int
        val typeCount: Int
        val fieldIdsOff: Int
        val fieldCount: Int
        val methodIdsOff: Int
        val methodCount: Int
        val protoIdsOff: Int
        val protoCount: Int
        val classDefsOff: Int
        val classCount: Int
        /** Per method id: rule, or null; computed only for classes that have rules. */
        val methodRule: Array<Rule?>
        val fieldRule: Array<Rule?>
        val stringRules = HashMap<Int, Rule?>()

        init {
            require(bytes.size >= 0x70 && bytes[0] == 'd'.code.toByte() && bytes[1] == 'e'.code.toByte())
            strings = arrayOfNulls(count(56))
            stringIdsOff = offset(60, strings.size, 4)
            typeCount = count(64)
            typeIdsOff = offset(68, typeCount, 4)
            fieldCount = count(80)
            fieldIdsOff = offset(84, fieldCount, 8)
            protoCount = count(72)
            protoIdsOff = offset(76, protoCount, 12)
            methodCount = count(88)
            methodIdsOff = offset(92, methodCount, 8)
            classCount = count(96)
            classDefsOff = offset(100, classCount, 32)
            methodRule = arrayOfNulls(methodCount)
            fieldRule = arrayOfNulls(fieldCount)
            val ruleTypes = HashSet<Int>()
            for (t in 0 until typeCount) if (type(t) in ruleClasses) ruleTypes += t
            for (m in 0 until methodCount) {
                val p = methodIdsOff + 8 * m
                if (u16(p) in ruleTypes) methodRule[m] = methodRules[type(u16(p)) + "->" + string(b.getInt(p + 4))]
            }
            for (f in 0 until fieldCount) {
                val p = fieldIdsOff + 8 * f
                if (u16(p) in ruleTypes) fieldRule[f] = fieldRules[type(u16(p)) + "->" + string(b.getInt(p + 4))]
            }
        }

        fun count(at: Int): Int = b.getInt(at).also { require(it in 0..0x1000000) }

        fun offset(at: Int, count: Int, size: Int): Int {
            val off = b.getInt(at)
            require(count == 0 || (off >= 0x70 && off.toLong() + count.toLong() * size <= bytes.size))
            return off
        }

        fun u16(p: Int) = b.getShort(p).toInt() and 0xffff

        fun uleb(cursor: IntArray): Int {
            var result = 0
            var shift = 0
            while (true) {
                val v = bytes[cursor[0]++].toInt()
                result = result or ((v and 0x7f) shl shift)
                if (v and 0x80 == 0 || shift >= 28) return result
                shift += 7
            }
        }

        fun string(index: Int): String {
            require(index in strings.indices)
            strings[index]?.let { return it }
            val cursor = intArrayOf(b.getInt(stringIdsOff + 4 * index))
            require(cursor[0] in 0 until bytes.size)
            uleb(cursor)
            var p = cursor[0]
            val out = StringBuilder()
            // Modified UTF-8, bounded by the file and a sane length.
            while (p < bytes.size && out.length < 4096) {
                val c = bytes[p].toInt() and 0xff
                if (c == 0) break
                when {
                    c < 0x80 -> { out.append(c.toChar()); p += 1 }
                    c and 0xe0 == 0xc0 && p + 1 < bytes.size -> {
                        out.append((((c and 0x1f) shl 6) or (bytes[p + 1].toInt() and 0x3f)).toChar()); p += 2
                    }
                    c and 0xf0 == 0xe0 && p + 2 < bytes.size -> {
                        out.append(
                            (((c and 0x0f) shl 12) or ((bytes[p + 1].toInt() and 0x3f) shl 6) or
                                (bytes[p + 2].toInt() and 0x3f)).toChar()
                        )
                        p += 3
                    }
                    else -> p += 1
                }
            }
            return out.toString().also { strings[index] = it }
        }

        fun type(index: Int): String {
            require(index in 0 until typeCount)
            return string(b.getInt(typeIdsOff + 4 * index))
        }

        private val signatures = HashMap<Int, String>()

        /**
         * Full identity of a method: class, name and signature, e.g. `La/B;->a(Ljava/lang/String;)V`.
         * Overloads such as a(String) and a(int) are different methods and must not be merged.
         */
        fun methodKey(m: Int): String {
            val p = methodIdsOff + 8 * m
            return type(u16(p)) + "->" + string(b.getInt(p + 4)) + signature(u16(p + 2))
        }

        fun signature(proto: Int): String =
            signatures.getOrPut(proto) {
                require(proto in 0 until protoCount)
                val p = protoIdsOff + 12 * proto
                val returns = type(b.getInt(p + 4))
                val params = b.getInt(p + 8)
                val out = StringBuilder("(")
                if (params != 0) {
                    require(params in 0x70 until bytes.size - 4)
                    val n = b.getInt(params)
                    require(n in 0..255 && params + 4L + 2L * n <= bytes.size)
                    for (k in 0 until n) out.append(type(u16(params + 4 + 2 * k)))
                }
                out.append(')').append(returns).toString()
            }

        fun fieldKey(f: Int): String {
            val p = fieldIdsOff + 8 * f
            return type(u16(p)) + "->" + string(b.getInt(p + 4))
        }

        /** Source bits of a field: a source field itself, or values stored into it earlier. */
        fun fieldBits(f: Int): Int {
            if (f >= fieldCount) return 0
            val rule = fieldRule.getOrNull(f)
            val own = if (rule != null && rule.capability.source) rule.capability.bit else 0
            return own or (state.fieldTaint[fieldKey(f)] ?: 0)
        }

        fun storeField(f: Int, bits: Int) {
            val sources = bits and PARAM.inv()
            if (sources == 0 || f >= fieldCount || state.fieldTaint.size > 20000) return
            val key = fieldKey(f)
            state.fieldTaint[key] = (state.fieldTaint[key] ?: 0) or sources
        }

        fun stringRule(index: Int): Rule? = stringRules.getOrPut(index) { DexScanner.stringRule(string(index)) }

        fun scan() {
            for (c in 0 until classCount) {
                if (System.currentTimeMillis() > state.deadline) {
                    state.stopped = true
                    return
                }
                val def = classDefsOff + 32 * c
                val descriptor = type(b.getInt(def))
                val lib = library(descriptor)
                state.classes++
                if (lib == null) state.appClasses++ else if (lib.ad) state.sdks += lib.name
                val dataOff = b.getInt(def + 24)
                if (dataOff == 0) continue
                require(dataOff in 0 until bytes.size)
                val className = descriptor.removePrefix("L").removeSuffix(";").replace('/', '.')
                val cursor = intArrayOf(dataOff)
                val staticFields = uleb(cursor)
                val instanceFields = uleb(cursor)
                val direct = uleb(cursor)
                val virtual = uleb(cursor)
                repeat(staticFields + instanceFields) {
                    uleb(cursor)
                    uleb(cursor)
                }
                for (group in listOf(direct, virtual)) {
                    var methodIndex = 0
                    repeat(group) {
                        methodIndex += uleb(cursor)
                        val access = uleb(cursor)
                        val codeOff = uleb(cursor)
                        if (codeOff != 0 && methodIndex in 0 until methodCount)
                            method(methodIndex, codeOff, className, descriptor, lib, access and 0x8 != 0)
                    }
                }
            }
        }

        fun method(index: Int, codeOff: Int, className: String, descriptor: String, lib: Library?, static: Boolean) {
            require(codeOff in 0 until bytes.size - 16)
            val size = b.getInt(codeOff + 12)
            val start = codeOff + 16
            require(size >= 0 && start.toLong() + 2L * size <= bytes.size)
            // Value tracking (app code only): register -> source bits, and register -> app methods
            // whose results it holds. Straight-line and flow-insensitive: an approximation that
            // only claims a link when a value read from a source is passed to the sending call.
            val taint = HashMap<Int, Int>()
            val results = HashMap<Int, Set<String>>()
            var pending = 0
            var pendingResults: Set<String> = emptySet()
            run {
                val registers = u16(codeOff)
                val ins = u16(codeOff + 2)
                // Parameters sit in the last registers; `this` is not a parameter.
                val first = registers - ins + (if (static) 0 else 1)
                for (r in first until registers) taint[r] = PARAM
            }
            fun set(reg: Int, bits: Int, from: Set<String> = emptySet()) {
                if (bits == 0) taint.remove(reg) else taint[reg] = bits
                if (from.isEmpty()) results.remove(reg) else results[reg] = from
            }
            val name = string(b.getInt(methodIdsOff + 8 * index + 4))
            val key = methodKey(index)
            val readable = if (origin.isEmpty()) "$className.$name" else "$className.$name [$origin]"
            val info = if (lib == null) MethodInfo() else null
            val deep = state.deep && info != null
            val strings = if (deep) ArrayList<String>() else null
            fun line(text: String) {
                if (deep && info!!.listing.size < LISTING_LINES) info.listing += text
            }
            // Deep: constants in registers and the arrays filled from them one element at a time,
            // the way d8 compiles short array initialisers.
            val constants = if (deep) HashMap<Int, Int>() else null
            val arrays = if (deep) HashMap<Int, java.util.TreeMap<Int, Int>>() else null
            val arrayWidth = if (deep) HashMap<Int, Int>() else null
            fun flushArray(reg: Int) {
                val values = arrays!!.remove(reg) ?: return
                val width = arrayWidth!!.remove(reg) ?: 1
                if (values.size in 6..4096 && values.keys.first() == 0 && values.keys.last() == values.size - 1) {
                    val data =
                        if (width == 2) ByteArray(values.size * 2) { if (it % 2 == 0) values.getValue(it / 2).toByte() else (values.getValue(it / 2) shr 8).toByte() }
                        else ByteArray(values.size) { values.getValue(it).toByte() }
                    decodeArray(data, width, readable, key)
                }
            }
            fun reg2(i: Int) = if (i + 2 < size) u16(start + 2 * (i + 2)) else 0

            /** Arguments of an invoke at [i]: 35c lists up to five registers, 3rc a range. */
            fun args(op: Int, unit: Int, i: Int): IntArray =
                if (op in 0x6e..0x72 || op == 0xfa || op == 0xfc) {
                    val count = (unit shr 12) and 0xf
                    val w = reg2(i)
                    intArrayOf(w and 0xf, (w shr 4) and 0xf, (w shr 8) and 0xf, (w shr 12) and 0xf, (unit shr 8) and 0xf)
                        .copyOf(count.coerceAtMost(5))
                } else {
                    val count = (unit shr 8) and 0xff
                    val first = reg2(i)
                    IntArray(count) { first + it }
                }

            fun track(op: Int, unit: Int, next: Int, i: Int) {
                val mi = info!!
                val a8 = (unit shr 8) and 0xff
                val a4 = (unit shr 8) and 0xf
                val b4 = (unit shr 12) and 0xf
                if (op !in 0x0a..0x0c && op !in 0x6e..0x78 && op !in 0xfa..0xfd) pending = 0
                when (op) {
                    0x01, 0x04, 0x07 -> set(a4, taint[b4] ?: 0, results[b4].orEmpty())
                    0x02, 0x05, 0x08 -> set(a8, taint[next] ?: 0, results[next].orEmpty())
                    0x03, 0x06, 0x09 -> set(next, taint[reg2(i)] ?: 0, results[reg2(i)].orEmpty())
                    0x0a, 0x0b, 0x0c -> {
                        set(a8, pending, pendingResults)
                        pending = 0
                        pendingResults = emptySet()
                    }
                    0x0f, 0x10, 0x11 -> {
                        mi.returnSources = mi.returnSources or ((taint[a8] ?: 0) and PARAM.inv())
                        results[a8]?.let { mi.returnCallees += it }
                    }
                    in 0x12..0x19 -> set(if (op == 0x12) a4 else a8, 0)
                    0x1a, 0x1b -> {
                        val idx = if (op == 0x1a) next else next or (reg2(i) shl 16)
                        val rule = stringRule(idx)
                        // Content URIs carry their source (read once queried); bot URLs mark the destination.
                        val bits = rule?.capability?.let { if (it.source || it in Capability.hiddenDestinations) it.bit else 0 } ?: 0
                        set(a8, bits)
                    }
                    0x1c, 0x22 -> set(a8, 0)
                    0x1f -> Unit // check-cast keeps the value
                    in 0x44..0x4a -> set(a8, taint[next and 0xff] ?: 0, results[next and 0xff].orEmpty())
                    in 0x4b..0x51 -> {
                        val arr = next and 0xff
                        val value = taint[a8] ?: 0
                        if (value != 0) set(arr, (taint[arr] ?: 0) or value, results[arr].orEmpty() + results[a8].orEmpty())
                    }
                    // A field of an object read from a source holds that source: notification.extras.
                    in 0x52..0x58 -> set(a4, fieldBits(next) or ((taint[b4] ?: 0) and PARAM.inv()), results[b4].orEmpty())
                    in 0x59..0x5f -> storeField(next, taint[a4] ?: 0)
                    in 0x60..0x66 -> set(a8, fieldBits(next))
                    in 0x67..0x6d -> storeField(next, taint[a8] ?: 0)
                    in 0x6e..0x72, in 0x74..0x78, 0xfa, 0xfb, 0xfc, 0xfd -> {
                        val regs = args(op, unit, i)
                        var mask = 0
                        val from = HashSet<String>()
                        regs.forEach { r ->
                            mask = mask or (taint[r] ?: 0)
                            results[r]?.let { from += it }
                        }
                        val sources = mask and PARAM.inv()
                        val rule = if (op == 0xfc || op == 0xfd) null else methodRule.getOrNull(next)
                        val cap = rule?.capability
                        if (cap != null && cap.sink) {
                            val reached = sources and sourceBits
                            if (reached != 0) mi.traced[cap.bit] = (mi.traced[cap.bit] ?: 0) or reached
                            // Data sent together with a bot or webhook address goes to that destination.
                            for (d in Capability.hiddenDestinations)
                                if (sources and d.bit != 0 && reached != 0)
                                    mi.traced[d.bit] = (mi.traced[d.bit] ?: 0) or reached
                            if (mask and PARAM != 0) {
                                mi.paramSinks = mi.paramSinks or cap.bit
                                for (d in Capability.hiddenDestinations)
                                    if (sources and d.bit != 0) mi.paramSinks = mi.paramSinks or d.bit
                            }
                            if (from.isNotEmpty()) mi.sinkCallees.getOrPut(cap.bit) { HashSet() } += from
                        }
                        var result = mask
                        var resultFrom: Set<String> = from
                        if (cap != null && cap.source) result = result or cap.bit
                        val called = if (op == 0xfc || op == 0xfd || next >= methodCount) null else methodKey(next)
                        if (called != null && library(called.substringBefore("->")) == null && called.startsWith("L")) {
                            mi.calls += TaintCall(called, sources and sourceBits, mask and PARAM != 0, from)
                            resultFrom = from + called
                        }
                        // A builder or collection that receives a value carries it: url(x), append(x), put(k, x).
                        val instance = op != 0x71 && op != 0x77 && op != 0xfc && op != 0xfd && regs.isNotEmpty()
                        if (instance && regs.size > 1) {
                            var rest = 0
                            for (k in 1 until regs.size) rest = rest or (taint[regs[k]] ?: 0)
                            if (rest != 0) set(regs[0], (taint[regs[0]] ?: 0) or rest, results[regs[0]].orEmpty() + from)
                        }
                        pending = result
                        pendingResults = resultFrom
                    }
                }
            }

            fun record(rule: Rule) {
                val cap = rule.capability
                val needsConfirmation = rule.string && (cap in Capability.hiddenDestinations || cap.source)
                if (needsConfirmation) {
                    if (info != null) state.pendingTexts += key to CodeHit(cap, rule.api, readable, null, true)
                } else state.hit(rule, readable, lib?.name)
                if (info != null) {
                    when {
                        cap == CONTENT_QUERY -> info.markers = info.markers or cap.bit
                        cap.source && rule.string -> info.stringSources = info.stringSources or cap.bit
                        cap.source -> info.sources = info.sources or cap.bit
                        cap.sink && rule.string -> info.stringSinks = info.stringSinks or cap.bit
                        cap.sink -> info.sinks = info.sinks or cap.bit
                    }
                    info.apis.putIfAbsent(cap, rule.api)
                }
            }
            var i = 0
            while (i < size) {
                val unit = u16(start + 2 * i)
                val op = unit and 0xff
                val next = if (i + 1 < size) u16(start + 2 * (i + 1)) else 0
                if (info != null) track(op, unit, next, i)
                when (op) {
                    0x1a ->
                        if (info != null) {
                            stringRule(next)?.let(::record)
                            url(string(next), descriptor)
                            if (deep) {
                                if (strings!!.size < MAX_STRINGS_PER_METHOD) strings += string(next)
                                line("text \"${shown(string(next))}\"")
                            }
                        }
                    0x1b ->
                        if (info != null && i + 2 < size) {
                            val idx = next or (u16(start + 2 * (i + 2)) shl 16)
                            stringRule(idx)?.let(::record)
                            url(string(idx), descriptor)
                            if (deep) {
                                if (strings!!.size < MAX_STRINGS_PER_METHOD) strings += string(idx)
                                line("text \"${shown(string(idx))}\"")
                            }
                        }
                    0x1c ->
                        if (deep && next < typeCount) {
                            val target = type(next)
                            if (library(target) == null && target.startsWith("L")) info!!.constClasses += target
                            line("class ${simple(target)}")
                        }
                    0x22 -> if (deep && next < typeCount) line("new ${simple(type(next))}")
                    0x12 -> constants?.put((unit shr 8) and 0xf, (unit shl 16) shr 28)
                    0x13 -> constants?.put((unit shr 8) and 0xff, next.toShort().toInt())
                    0x14 -> if (constants != null && i + 2 < size) constants[(unit shr 8) and 0xff] = next or (u16(start + 2 * (i + 2)) shl 16)
                    0x23 ->
                        if (arrays != null) {
                            val reg = (unit shr 8) and 0xf
                            flushArray(reg)
                            val elementType = if (next < typeCount) type(next) else ""
                            if (elementType == "[B" || elementType == "[C") {
                                arrays[reg] = java.util.TreeMap()
                                arrayWidth!![reg] = if (elementType == "[C") 2 else 1
                            }
                        }
                    0x4f, 0x50 ->
                        if (arrays != null) {
                            val value = constants!![(unit shr 8) and 0xff]
                            val array = arrays[next and 0xff]
                            val index = constants[(next shr 8) and 0xff]
                            // First write wins: later loops over the array (e.g. the XOR decoder itself)
                            // reuse registers whose tracked constant is stale.
                            if (value != null && array != null && index != null && index in 0..4095) array.putIfAbsent(index, value)
                        }
                    in 0x60..0x6d -> {
                        val rule = fieldRule.getOrNull(next)
                        rule?.let(::record)
                        if (deep && rule != null) line("read ${rule.api}")
                    }
                    in 0x6e..0x72, in 0x74..0x78, 0xfa, 0xfb -> {
                        val rule = methodRule.getOrNull(next)
                        rule?.let(::record)
                        if (info != null && next < methodCount) {
                            val target = methodIdsOff + 8 * next
                            val owner = type(u16(target))
                            if (library(owner) == null && owner.startsWith("L")) info.callees += methodKey(next)
                            if (deep) {
                                val called = methodKey(next)
                                if (called.substringBefore('(') in launchKeys) info.launches = true
                                line((if (rule != null) "⚠ " else "") + "call ${simple(owner)}.${methodName(called)}")
                            }
                        }
                    }
                    0x00 ->
                        // fill-array-data payload: arrays of bytes or chars can hide texts.
                        if (deep && unit == 0x0300 && i + 3 < size) {
                            val width = next
                            val count = b.getInt(start + 2 * (i + 2))
                            val dataStart = start + 2 * (i + 4)
                            if ((width == 1 || width == 2) && count in 6..4096 && dataStart + width.toLong() * count <= bytes.size)
                                decodeArray(ByteArray(width * count) { bytes[dataStart + it] }, width, readable, key)
                        }
                }
                i += width(op, unit, start, i, size)
            }
            if (deep) {
                arrays!!.keys.toList().forEach(::flushArray)
                decodeStrings(strings!!, readable, key)
            }
            if (info != null) {
                state.names.putIfAbsent(key, readable)
                state.classMethods.getOrPut(descriptor) { mutableListOf() }.let { if (key !in it) it += key }
                state.methods.getOrPut(key) { MethodInfo() }.merge(info)
            }
        }

        fun simple(descriptor: String) = descriptor.removePrefix("L").removeSuffix(";").substringAfterLast('/')

        /** A text for a listing: short, single line, bot tokens masked. */
        fun shown(s: String) = maskToken(s.replace('\n', ' ')).take(80)

        /** Deep: texts hidden with Base64, reversal or by splitting them into pieces. */
        fun decodeStrings(strings: List<String>, readable: String, key: String) {
            for (s in strings) {
                if (s.length in 12..4096 && s.length % 4 == 0 && base64.matches(s))
                    runCatching { String(decodeBase64(s), Charsets.UTF_8) }
                        .getOrNull()
                        ?.takeIf { printable(it) }
                        ?.let { recordDecoded(Decoding.BASE64, it, readable, key) }
                if (s.length >= 10) recordDecoded(Decoding.REVERSED, s.reversed(), readable, key, quiet = true)
            }
            if (strings.size >= 2) {
                val joined = strings.joinToString("")
                // Only when no single piece already shows what the whole does.
                if (strings.none { interesting(it) != null }) recordDecoded(Decoding.JOINED, joined, readable, key, quiet = true)
            }
        }

        /** Deep: byte or char arrays that spell a text directly or after a one-byte XOR. */
        fun decodeArray(data: ByteArray, width: Int, readable: String, key: String) {
            val text =
                if (width == 2) String(CharArray(data.size / 2) { ((data[2 * it].toInt() and 255) or ((data[2 * it + 1].toInt() and 255) shl 8)).toChar() })
                else String(data, Charsets.ISO_8859_1)
            if (printable(text) && recordDecoded(Decoding.CHAR_ARRAY, text, readable, key)) return
            if (width == 1 && data.size <= 512)
                for (xor in 1..255) {
                    val candidate = String(ByteArray(data.size) { (data[it].toInt() xor xor).toByte() }, Charsets.ISO_8859_1)
                    if (printable(candidate) && recordDecoded(Decoding.XOR, candidate, readable, key, detail = "0x%02x".format(xor)))
                        return
                }
        }

        /**
         * Records a decoded text when it is a bot, webhook, content URI, URL or forwarding code.
         * Returns whether it was interesting. [quiet] skips texts that only look like URLs.
         */
        fun recordDecoded(technique: Decoding, text: String, readable: String, key: String, detail: String = "", quiet: Boolean = false): Boolean {
            val rule = interesting(text)
            val hasUrl = url.containsMatchIn(text)
            if (rule == null && (quiet || !hasUrl)) return false
            if (!state.decodedKeys.add("$technique|$text|$readable")) return true
            val shownText = rule?.api ?: shown(text)
            state.decoded += DecodedText(technique, shownText, readable, rule?.capability, detail)
            if (rule != null) {
                val info = state.methods.getOrPut(key) { MethodInfo() }
                val hit = CodeHit(rule.capability, "${decodingName(technique)}: $shownText", readable, null, true)
                if (rule.capability in Capability.hiddenDestinations || rule.capability.source) {
                    state.pendingTexts += key to hit
                    if (rule.capability.source) info.stringSources = info.stringSources or rule.capability.bit
                    else info.stringSinks = info.stringSinks or rule.capability.bit
                } else state.hit(Rule(rule.capability, hit.api, true), readable, null)
                info.apis.putIfAbsent(rule.capability, hit.api)
            }
            // A join of unrelated constants would invent hosts such as "instagram.compriority".
            if (technique != Decoding.JOINED) url(text, key.substringBefore("->"))
            return true
        }

        /** Records hosts of URLs written in the app's own code, in class [owner]. */
        fun url(s: String, owner: String) {
            if (s.length > 2048) return
            url.findAll(s).take(3).forEach { m ->
                val host = m.groupValues[2].lowercase().trim('[', ']')
                if (host.isBlank() || host in ignoredHosts || !(NetAddress.isIp(host) || domain.matches(host))) return@forEach
                val cleartext = m.groupValues[1] == "http"
                val existing = state.destinations[host]
                val public = NetAddress.isPublic(host)
                if (public && state.classIps.size < 200) state.classIps.putIfAbsent(owner, host)
                if (existing == null && state.destinations.size >= 40) return@forEach
                state.destinations[host] =
                    Destination(host, cleartext || existing?.cleartext == true, NetAddress.isIp(host), public)
            }
        }

        /** Instruction width in 16-bit units, including switch and array payloads. */
        fun width(op: Int, unit: Int, start: Int, i: Int, size: Int): Int {
            if (op == 0x00 && i + 1 < size) {
                val n = u16(start + 2 * (i + 1))
                return when (unit) {
                    0x0100 -> n * 2 + 4
                    0x0200 -> n * 4 + 2
                    0x0300 ->
                        if (i + 3 < size) {
                            val count = b.getInt(start + 2 * (i + 2)).toLong()
                            ((count * n + 1) / 2 + 4).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                        } else 1
                    else -> 1
                }.coerceAtLeast(1)
            }
            return WIDTHS[op]
        }
    }

    private val WIDTHS =
        IntArray(256) { op ->
            when (op) {
                0x02, 0x05, 0x08, 0x13, 0x15, 0x16, 0x19, 0x1a, 0x1c, 0x1f, 0x20, 0x22, 0x23, 0x29 -> 2
                in 0x2d..0x3d, in 0x44..0x6d, in 0x90..0xaf, in 0xd0..0xe2, 0xfe, 0xff -> 2
                0x03, 0x06, 0x09, 0x14, 0x17, 0x1b, 0x24, 0x25, 0x26, 0x2a, 0x2b, 0x2c -> 3
                in 0x6e..0x72, in 0x74..0x78, 0xfc, 0xfd -> 3
                0xfa, 0xfb -> 4
                0x18 -> 5
                else -> 1
            }
        }
}
