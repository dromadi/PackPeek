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
            // The request is sent when the response is asked for, after headers such as Cookie.
            "Ljava/net/URLConnection;->connect" to NETWORK,
            "Ljava/net/URLConnection;->getInputStream" to NETWORK,
            "Ljava/net/HttpURLConnection;->getResponseCode" to NETWORK,
            "Ljava/net/HttpURLConnection;->getInputStream" to NETWORK,
            "Ljavax/net/ssl/HttpsURLConnection;->getResponseCode" to NETWORK,
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
                // Yandex Mobile Ads ships its internals in the short package "yads".
                Library("Lyads/", "Yandex Ads", true),
                Library("Lio/jsonwebtoken/", "JJWT"),
                Library("Lnet/aihelp/", "AIHelp"),
            )
            .sortedByDescending { it.prefix.length }

    private fun library(descriptor: String): Library? = libraries.firstOrNull { descriptor.startsWith(it.prefix) }

    private val packerLibs =
        listOf("libjiagu", "libsecexe", "libsecmain", "libdexhelper", "libshell", "libprotectclass",
            "libnqshield", "libmobisec", "libexecmain", "libtosprotection", "libapkprotect", "libjgdtc",
            "libbaiduprotect", "libddog", "libnesec", "libapssec", "librsprotect")

    /**
     * Everything an app method does that matters for flows. Large apps have a hundred thousand
     * methods or more, most with nothing to track, so collections are created on first write:
     * each property reads as empty until then, and the function of the same name makes it.
     */
    private class MethodInfo {
        /** From calls and fields: always real. */
        var sources = 0
        var sinks = 0
        /** From text constants: only real together with a query or a network call. */
        var stringSources = 0
        var stringSinks = 0
        var markers = 0
        private var apisM: HashMap<Capability, String>? = null
        val apis: Map<Capability, String> get() = apisM ?: emptyMap()
        fun apis() = apisM ?: HashMap<Capability, String>(4).also { apisM = it }
        private var calleesM: HashSet<String>? = null
        val callees: Set<String> get() = calleesM ?: emptySet()
        fun callees() = calleesM ?: HashSet<String>().also { calleesM = it }
        /** Deep: app classes named by const-class, started through an Intent or work request. */
        private var constClassesM: HashSet<String>? = null
        val constClasses: Set<String> get() = constClassesM ?: emptySet()
        fun constClasses() = constClassesM ?: HashSet<String>().also { constClassesM = it }
        var launches = false
        private var listingM: ArrayList<String>? = null
        val listing: List<String> get() = listingM ?: emptyList()
        fun listing() = listingM ?: ArrayList<String>().also { listingM = it }
        /** Value tracking: every sending call with what reaches its arguments. */
        private var sinkCallsM: LinkedHashMap<Int, SinkCall>? = null
        val sinkCalls: Map<Int, SinkCall> get() = sinkCallsM ?: emptyMap()
        fun sinkCalls() = sinkCallsM ?: LinkedHashMap<Int, SinkCall>().also { sinkCallsM = it }
        /** Sources whose values the method returns, and app methods whose results it returns. */
        var returnSources = 0
        private var returnCalleesM: HashSet<String>? = null
        val returnCallees: Set<String> get() = returnCalleesM ?: emptySet()
        fun returnCallees() = returnCalleesM ?: HashSet<String>().also { returnCalleesM = it }
        /** Calls to app methods: callee, source bits of the arguments, whether a parameter is passed. */
        private var callsM: LinkedHashMap<Int, TaintCall>? = null
        val calls: Map<Int, TaintCall> get() = callsM ?: emptyMap()
        fun calls() = callsM ?: LinkedHashMap<Int, TaintCall>().also { callsM = it }
        /** Stores into fields of `this`: labels and source bits per field, and parameters stored. */
        private var thisFieldFromM: HashMap<String, MutableSet<String>>? = null
        val thisFieldFrom: Map<String, Set<String>> get() = thisFieldFromM ?: emptyMap()
        fun thisFieldFrom() = thisFieldFromM ?: HashMap<String, MutableSet<String>>().also { thisFieldFromM = it }
        private var thisFieldBitsM: HashMap<String, Int>? = null
        val thisFieldBits: Map<String, Int> get() = thisFieldBitsM ?: emptyMap()
        fun thisFieldBits() = thisFieldBitsM ?: HashMap<String, Int>().also { thisFieldBitsM = it }
        private var thisParamFieldsM: Array<HashSet<String>>? = null
        val thisParamFields: Array<out Set<String>> get() = thisParamFieldsM ?: NO_SLOTS
        fun thisParamFields() = thisParamFieldsM ?: Array(PARAM_SLOTS) { HashSet<String>() }.also { thisParamFieldsM = it }
        /** Stores into fields of other objects whose labels are known (object allocated or fetched here). */
        private var objectStoresM: ArrayList<ObjectStore>? = null
        val objectStores: List<ObjectStore> get() = objectStoresM ?: emptyList()
        fun objectStores() = objectStoresM ?: ArrayList<ObjectStore>().also { objectStoresM = it }
        /** Per parameter slot, fields it is stored into (a lambda capturing it, a spilled local). */
        private var paramFieldsM: Array<HashSet<String>>? = null
        val paramFields: Array<out Set<String>> get() = paramFieldsM ?: NO_SLOTS
        fun paramFields() = paramFieldsM ?: Array(PARAM_SLOTS) { HashSet<String>() }.also { paramFieldsM = it }
        /** Per field, labels of the values this method stores into it. */
        private var fieldFromM: HashMap<String, MutableSet<String>>? = null
        val fieldFrom: Map<String, Set<String>> get() = fieldFromM ?: emptyMap()
        fun fieldFrom() = fieldFromM ?: HashMap<String, MutableSet<String>>().also { fieldFromM = it }

        fun merge(other: MethodInfo) {
            sources = sources or other.sources
            sinks = sinks or other.sinks
            stringSources = stringSources or other.stringSources
            stringSinks = stringSinks or other.stringSinks
            markers = markers or other.markers
            other.apis.forEach { (k, v) -> apis().putIfAbsent(k, v) }
            if (other.callees.isNotEmpty()) callees() += other.callees
            if (other.constClasses.isNotEmpty()) constClasses() += other.constClasses
            launches = launches || other.launches
            if (listing.isEmpty() && other.listing.isNotEmpty()) listing() += other.listing
            other.sinkCalls.forEach { (pc, c) -> sinkCalls().putIfAbsent(pc, c) }
            returnSources = returnSources or other.returnSources
            if (other.returnCallees.isNotEmpty()) returnCallees() += other.returnCallees
            other.calls.forEach { (pc, c) -> calls().putIfAbsent(pc, c) }
            other.thisFieldFrom.forEach { (k, v) -> thisFieldFrom().getOrPut(k) { HashSet() } += v }
            other.thisFieldBits.forEach { (k, v) -> thisFieldBits()[k] = (thisFieldBits[k] ?: 0) or v }
            for (k in 0 until PARAM_SLOTS) if (other.thisParamFields[k].isNotEmpty()) thisParamFields()[k] += other.thisParamFields[k]
            if (other.objectStores.isNotEmpty()) objectStores() += other.objectStores
            for (k in 0 until PARAM_SLOTS) if (other.paramFields[k].isNotEmpty()) paramFields()[k] += other.paramFields[k]
            other.fieldFrom.forEach { (k, v) -> fieldFrom().getOrPut(k) { HashSet() } += v }
        }
    }

    private val NO_SLOTS: Array<Set<String>> = Array(PARAM_SLOTS) { emptySet() }

    /** An app-method call seen by value tracking; [results] are callees whose results are passed. */
    /**
     * An app-method call seen by value tracking. [results] are the labels of its arguments: app
     * methods whose results they hold, "F:<field>" for values read from a field, or [RESUMED].
     */
    private class TaintCall(val callee: String) {
        /** Per callee parameter slot. */
        private var argsM: HashMap<Int, TaintArg>? = null
        val args: Map<Int, TaintArg> get() = argsM ?: emptyMap()
        fun arg(slot: Int) = (argsM ?: HashMap<Int, TaintArg>(4).also { argsM = it }).getOrPut(slot) { TaintArg(slot, 0, 0) }
        /** Labels of the receiver (instance calls): which object the call is made on. */
        private var receiverM: HashSet<String>? = null
        val receiver: Set<String> get() = receiverM ?: emptySet()
        fun receiver() = receiverM ?: HashSet<String>().also { receiverM = it }
    }

    /**
     * One argument of an app-method call: the callee parameter [slot] it lands in, the source bits
     * and caller-parameter bits it carries, and its labels.
     */
    private class TaintArg(val slot: Int, var sources: Int, var params: Int) {
        var labels: Set<String> = emptySet()
    }

    /** A store into a field of an object other than `this`: the object's labels, field, value. */
    private class ObjectStore(val target: Set<String>, val field: String, val sources: Int, val labels: Set<String>)

    /** Register state of value tracking at one instruction; joined where branches meet. */
    private class RegState(withTexts: Boolean) {
        val taint = HashMap<Int, Int>()
        val labels = HashMap<Int, Set<String>>()
        val texts: HashMap<Int, String>? = if (withTexts) HashMap() else null
        val pieces: HashMap<Int, Int>? = if (withTexts) HashMap() else null
        var pending = 0
        var pendingLabels: Set<String> = emptySet()
        var pendingText: String? = null
        var pendingPieces = 0

        fun copy(): RegState =
            RegState(texts != null).also {
                it.taint.putAll(taint)
                it.labels.putAll(labels)
                texts?.let { t -> it.texts!!.putAll(t) }
                pieces?.let { t -> it.pieces!!.putAll(t) }
                it.pending = pending
                it.pendingLabels = pendingLabels
                it.pendingText = pendingText
                it.pendingPieces = pendingPieces
            }

        /** Merges [o] into this state; true when something new arrived. */
        fun join(o: RegState): Boolean {
            var changed = false
            o.taint.forEach { (r, bits) ->
                val old = taint[r] ?: 0
                if (bits and old.inv() != 0) {
                    taint[r] = old or bits
                    changed = true
                }
            }
            o.labels.forEach { (r, set) ->
                val old = labels[r].orEmpty()
                if (!old.containsAll(set) && old.size < MAX_LABELS) {
                    labels[r] = old + set
                    changed = true
                }
            }
            // A text is only known when every path built the same one.
            texts?.keys?.retainAll { o.texts?.get(it) == texts[it] }
            if (o.pending and pending.inv() != 0) {
                pending = pending or o.pending
                changed = true
            }
            if (!pendingLabels.containsAll(o.pendingLabels)) {
                pendingLabels = pendingLabels + o.pendingLabels
                changed = true
            }
            if (pendingText != o.pendingText) pendingText = null
            return changed
        }
    }

    /**
     * A sending call: [sinks] (the sink and any bot/webhook address among its arguments), source
     * and caller-parameter bits reaching its arguments, and their labels (including "H:<host>").
     */
    private class SinkCall(var sinks: Int, var sources: Int, var params: Int, val labels: MutableSet<String>)

    /** Label of `this` in an instance method. */
    private const val THIS = "T"

    /** Label of a value delivered when a coroutine resumes (invokeSuspend's argument). */
    private const val RESUMED = "R"

    private const val MAX_LABELS = 32

    /** Value-tracking label for "comes from a parameter"; capability bits use the low bits. */
    /** Parameter labels: one bit per parameter slot (the last slot is shared by the rest). */
    private const val PARAM_SHIFT = 25
    private const val PARAM_SLOTS = 5
    private const val PARAMS = ((1 shl PARAM_SLOTS) - 1) shl PARAM_SHIFT
    private const val MAX_CALLS = 400
    private val DEX_NAME = Regex("""classes\d*\.dex""")

    private fun paramBit(slot: Int) = 1 shl (PARAM_SHIFT + slot.coerceIn(0, PARAM_SLOTS - 1))

    private inline fun forSlots(bits: Int, action: (Int) -> Unit) {
        for (slot in 0 until PARAM_SLOTS) if (bits and (1 shl (PARAM_SHIFT + slot)) != 0) action(slot)
    }

    private class State(val deadline: Long, val deep: Boolean = false, val trackDeadline: Long = deadline, val track: Boolean = true) {
        /** Value tracking was skipped or stopped somewhere to keep the scan within its limit. */
        var trackingCut = false
        var progress: ((ScanProgress) -> Unit)? = null
        var dexTotal = 1
        var dexDone = 0
        private var shown = -1

        /** Reports [part] of the current step: reading DEX file [dexDone], or resolving flows. */
        fun report(part: Float, resolving: Boolean = false) {
            val listener = progress ?: return
            val reading = if (track) 0.85f else 1f
            val fraction =
                if (resolving) reading + (1 - reading) * part
                else reading * ((dexDone + part.coerceIn(0f, 1f)) / dexTotal).coerceAtMost(1f)
            val percent = (fraction * 100).toInt()
            if (percent <= shown) return
            shown = percent
            listener(ScanProgress(fraction, (dexDone + 1).coerceAtMost(dexTotal), dexTotal, resolving))
        }
        private var trackChecks = 0
        private var lastLive = 0L

        /**
         * Whether value tracking may go on: within its time share, and while the heap has room.
         * Tracking data grows with app size; the plain scan must never run out of memory for it.
         */
        fun mayTrack(): Boolean {
            if (!track || trackingCut) return false
            if (System.currentTimeMillis() >= trackDeadline || (++trackChecks and 255 == 0 && memoryTight(0.55))) trackingCut = true
            return !trackingCut
        }

        /** Live heap above [fraction] of the maximum. Collects first, but only after real growth. */
        fun memoryTight(fraction: Double): Boolean {
            val rt = Runtime.getRuntime()
            val limit = (rt.maxMemory() * fraction).toLong()
            val used = rt.totalMemory() - rt.freeMemory()
            if (used < limit || used < lastLive + rt.maxMemory() / 16) return false
            System.gc()
            lastLive = rt.totalMemory() - rt.freeMemory()
            return lastLive >= limit
        }
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
        private val keys = HashMap<String, String>()

        /** One copy of each method key, however many calls name it. */
        fun intern(key: String): String = keys.getOrPut(key) { key }
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
    /**
     * [tracedBy], for tests: receives, per method (readable name), sink -> source -> hosts the
     * traced value is sent to.
     */
    fun scan(
        apks: List<File>,
        timeLimitMs: Long = TIME_LIMIT_MS,
        deep: Boolean = false,
        /**
         * Value tracking: which value reaches which send, and its address. Off for quick checks of
         * many apps, where only the findings and their levels matter; those do not depend on it.
         */
        track: Boolean = true,
        progress: ((ScanProgress) -> Unit)? = null,
        tracedBy: ((Map<String, Map<Capability, Map<Capability, Set<String>>>>) -> Unit)? = null,
    ): CodeFacts {
        val started = System.currentTimeMillis()
        // Value tracking gets part of the time: the plain scan (capabilities, SDKs, addresses,
        // code paths) must always finish, even for very large apps on a slow phone.
        val state = State(started + timeLimitMs, deep, trackDeadline = started + timeLimitMs * 6 / 10, track = track)
        state.progress = progress
        state.dexTotal =
            apks.sumOf { apk -> runCatching { ZipFile(apk).use { z -> z.entries().asSequence().count { DEX_NAME.matches(it.name) } } }.getOrDefault(0) }
                .coerceAtLeast(1)
        state.report(0f)
        apks.forEach { apk ->
            runCatching {
                    ZipFile(apk).use { zip ->
                        val entries = zip.entries().asSequence().toList()
                        inspectPackaging(zip, entries, apk.name, state)
                        entries
                            .filter { DEX_NAME.matches(it.name) }
                            .sortedBy { it.name.length * 100 + (it.name.filter(Char::isDigit).toIntOrNull() ?: 0) }
                            .forEach { entry ->
                                if (state.stopped) return@forEach
                                state.report(0f)
                                if (entry.size > MAX_DEX_BYTES) {
                                    state.gaps += CodeGap(GapKind.LIMIT, "${apk.name}!${entry.name}")
                                    state.dexDone++
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
                                state.dexDone++
                            }
                    }
                }
                .onFailure { state.gaps += CodeGap(GapKind.UNREADABLE, apk.name) }
        }
        if (state.stopped) state.gaps += CodeGap(GapKind.LIMIT, "time")
        val traced =
            if (!track) emptyMap()
            else try {
                tracedFlows(state, started + timeLimitMs * 9 / 10)
            } catch (e: OutOfMemoryError) {
                // Last resort: everything tracking allocated is garbage once it unwinds.
                state.trackingCut = true
                emptyMap()
            }
        if (state.trackingCut) state.gaps += CodeGap(GapKind.TRACKING, "")
        state.report(1f, resolving = track)
        tracedBy?.invoke(
            traced.entries.associate { (k, map) ->
                (state.names[k] ?: k) to
                    map.entries.associate { (bit, reached) ->
                        Capability.entries.first { it.bit == bit } to
                            Capability.entries.filter { reached.sources and it.bit != 0 }.associateWith { reached.hosts[it.bit].orEmpty().toSet() }
                    }
            }
        )
        val (shallowFlows, classes) = flows(state, traced)
        val chains = if (deep) chains(state, classes, traced) else emptyList()
        // Deep chains also count as flows, so the findings pick them up.
        val flows =
            (shallowFlows +
                    chains.map {
                        CodeFlow(it.source, it.sink, it.toSink.first().method, it.sourceApi, it.sinkApi, it.ipHost, it.traced, it.destinations)
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
    private val destinationBits = Capability.hiddenDestinations.fold(0) { acc, c -> acc or c.bit }

    /** "H:<host>" labels for the URLs in a constant. */
    private fun hostLabels(s: String): Set<String> {
        if (s.length > 2048 || "://" !in s) return emptySet()
        return url.findAll(s).take(3).mapNotNull { m ->
            val host = m.groupValues[2].lowercase().trim('[', ']')
            host.takeIf { it.isNotBlank() && it !in ignoredHosts && (NetAddress.isIp(it) || domain.matches(it)) }?.let { "H:$it" }
        }.toSet()
    }

    private val platformPrefixes = listOf("Landroid/", "Ljava/", "Ljavax/", "Ldalvik/", "Lorg/w3c/", "Lorg/xml/", "Lsun/", "Llibcore/", "Lcom/android/internal/")

    /** A class of the app itself: not the platform and not a known library. */
    private fun isAppClass(descriptor: String) =
        descriptor.startsWith("L") && platformPrefixes.none { descriptor.startsWith(it) } && library(descriptor) == null

    private fun methodName(key: String) = key.substringAfter("->").substringBefore('(')

    private fun isConstructor(key: String) = methodName(key) == "<init>"

    private class Classes(val sending: Set<String>, val querying: Set<String>)

    /** Read-then-send paths, and the classes that can actually send or query (for text evidence). */
    private fun flows(state: State, traced: Map<String, Map<Int, Reached>>): Pair<List<CodeFlow>, Classes> {
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
            // A traced flow in this method or a callee it reaches is a traced flow of this path.
            val here = HashMap<Int, Reached>()
            reachKeys.forEach { k -> traced[k]?.forEach { (bit, r) -> merge(here, bit, r) } }
            for (source in Capability.entries.filter { it.source && sources and it.bit != 0 })
                for (sink in Capability.entries.filter { it.sink && sinks and it.bit != 0 }) {
                    if (candidates.size >= MAX_FLOWS * 20) break
                    // Traced: the value read from the source reaches the sending call's arguments.
                    // A bot address in the same path makes a traced network send a traced bot send.
                    val (valueTraced, destinations) = tracedTo(here, source, sink)
                    candidates +=
                        CodeFlow(
                            source,
                            sink,
                            state.names[key] ?: key,
                            reach.firstNotNullOf { it.apis[source] },
                            reach.firstNotNullOf { it.apis[sink] },
                            ipHost.takeIf { sink == NETWORK },
                            valueTraced,
                            destinations,
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
    /** What reaches one sink in a method: source bits and, per source bit, the hosts it is sent to. */
    private class Reached(var sources: Int = 0, val hosts: HashMap<Int, MutableSet<String>> = HashMap())

    private fun merge(into: HashMap<Int, Reached>, bit: Int, r: Reached) {
        val t = into.getOrPut(bit) { Reached() }
        t.sources = t.sources or r.sources
        r.hosts.forEach { (src, hosts) -> t.hosts.getOrPut(src) { HashSet() } += hosts }
    }

    /**
     * Whether [source]'s value reaches [sink] in [reached], and the hosts it goes to. A bot address
     * in the same send makes a traced network send a traced bot send.
     */
    private fun tracedTo(reached: Map<Int, Reached>, source: Capability, sink: Capability): Pair<Boolean, List<String>> {
        val direct = reached[sink.bit]?.takeIf { it.sources and source.bit != 0 }
        val viaNetwork =
            if (sink in Capability.hiddenDestinations) reached[NETWORK.bit]?.takeIf { it.sources and source.bit != 0 } else null
        val hit = direct ?: viaNetwork ?: return false to emptyList()
        // One more host than is shown is kept, so a capped list says that there are more.
        val hosts = hit.hosts[source.bit].orEmpty().sorted()
        return true to (if (hosts.size > SHOWN_HOSTS) hosts.take(SHOWN_HOSTS) + MORE_HOSTS else hosts)
    }

    /** Summary of sends a method makes: sources and hosts already known, and its parameters involved. */
    private class Acc(var sources: Int = 0, val hosts: MutableSet<String> = HashSet(), val open: MutableSet<String> = HashSet())

    private const val MAX_HOSTS = 9
    private const val SHOWN_HOSTS = 8

    /** Last entry of a destination list that was cut short. */
    const val MORE_HOSTS = "…"
    private const val MAX_SUMMARIES = 32

    /**
     * Per method, per sink: which sources reach it and to which hosts. Each send is summarised
     * per method as (sink, parameters involved) -> (sources, hosts) and propagated to callers:
     * through arguments, fields (captured lambda variables, continuation spills, base URLs),
     * results of app methods and resumed coroutines. Bounded rounds until nothing changes.
     */
    /** A resolved value: sources, hosts, objects (allocation sites) and this-fields still open. */
    private class Val {
        var sources = 0
        val hosts = HashSet<String>()
        val sites = HashSet<String>()
        val open = HashSet<String>()

        fun add(o: Val?) {
            if (o == null) return
            sources = sources or o.sources
            if (hosts.size < MAX_HOSTS) hosts += o.hosts
            if (sites.size < MAX_LABELS) sites += o.sites
            open += o.open
        }
    }

    /**
     * Per method, per sink: which sources reach it and to which hosts. Each send is summarised
     * per method as (sink, parameters involved) -> (sources, hosts, this-fields still open) and
     * composed into callers: through arguments, results, fields, coroutines, and fields of the
     * object a call is made on (allocation sites), so two instances of one client keep their own
     * base address. Without a known object, fields fall back to one value per field name.
     */
    private fun tracedFlows(state: State, until: Long): Map<String, Map<Int, Reached>> {
        val all = state.methods
        // Only methods whose values can reach a send matter: those with a send or storing a value
        // somewhere a send can read it (fields, returns, constructor arguments), plus their callers,
        // transitively, since callers supply the arguments. Everything else is skipped, which keeps
        // large apps within time and memory.
        val callersOf = HashMap<String, MutableList<String>>()
        all.forEach { (k, m) -> m.calls.values.forEach { callersOf.getOrPut(it.callee) { ArrayList() } += k } }
        val relevant = HashSet<String>()
        val queue = ArrayDeque<String>()
        all.forEach { (k, m) ->
            if (m.sinkCalls.isNotEmpty() || m.thisFieldFrom.isNotEmpty() || m.thisFieldBits.isNotEmpty() ||
                m.objectStores.isNotEmpty() || m.fieldFrom.isNotEmpty() || m.returnSources != 0 ||
                m.returnCallees.isNotEmpty() || m.paramFields.any { it.isNotEmpty() } ||
                m.thisParamFields.any { it.isNotEmpty() })
                if (relevant.add(k)) queue += k
        }
        while (queue.isNotEmpty()) {
            val k = queue.removeFirst()
            callersOf[k]?.forEach { if (relevant.add(it)) queue += it }
        }
        val methods = all.filterKeys { it in relevant }
        val returns = HashMap<String, Val>()
        val fieldVal = HashMap<String, Val>()
        state.fieldTaint.forEach { (f, bits) -> fieldVal.getOrPut(f) { Val() }.sources = bits }
        val objField = HashMap<String, Val>()
        val fieldSinks = HashMap<String, HashMap<Int, MutableSet<String>>>()
        val summaries = HashMap<String, HashMap<Long, Acc>>()
        val callers = HashMap<String, MutableList<Pair<MethodInfo, TaintCall>>>()
        methods.forEach { (_, m) -> m.calls.values.forEach { callers.getOrPut(it.callee) { ArrayList() } += m to it } }
        var changed = false
        fun grow(target: Val, more: Val) {
            if (more.sources and target.sources.inv() != 0) { target.sources = target.sources or more.sources; changed = true }
            for (h in more.hosts) if (target.hosts.size < MAX_HOSTS && target.hosts.add(h)) changed = true
            for (x in more.sites) if (target.sites.size < MAX_LABELS && target.sites.add(x)) changed = true
            for (x in more.open) if (target.open.size < MAX_LABELS && target.open.add(x)) changed = true
        }
        fun global(fields: Collection<String>): Val = Val().also { v -> fields.forEach { v.add(fieldVal[it]) } }
        fun ofObjects(sites: Collection<String>, field: String): Val = Val().also { v -> sites.forEach { v.add(objField["$it|$field"]) } }

        // Declared ahead: resolving a call result needs the call's receiver, and vice versa.
        var receiverOf: (MethodInfo, TaintCall, Set<String>?, Int) -> Pair<Set<String>, Boolean> = { _, _, _, _ -> emptySet<String>() to false }

        /**
         * Labels resolved in method [m]. [recv]: objects `this` stands for (null: unknown, this-fields
         * stay open). Depth-bounded through calls on objects.
         */
        fun resolve(m: MethodInfo, labels: Collection<String>, recv: Set<String>?, depth: Int = 0): Val {
            val v = Val()
            for (l in labels)
                when {
                    l.startsWith("H:") -> if (v.hosts.size < MAX_HOSTS) v.hosts += l.substring(2)
                    l.startsWith("O:") -> v.sites += l
                    l.startsWith("TF:") -> {
                        val f = l.substring(3)
                        if (recv != null && recv.isNotEmpty()) v.add(ofObjects(recv, f)) else v.open += f
                    }
                    l.startsWith("F:") -> v.add(fieldVal[l.substring(2)])
                    l == RESUMED -> m.calls.values.forEach { c -> returns[c.callee]?.let { r -> v.sources = v.sources or r.sources; v.hosts += r.hosts } }
                    l.startsWith("C:") -> {
                        val call = m.calls[l.substring(2).toIntOrNull() ?: continue] ?: continue
                        val r = returns[call.callee] ?: continue
                        v.sources = v.sources or r.sources
                        v.hosts += r.hosts
                        v.sites += r.sites
                        // What the callee returns from its own fields depends on the object it was called on.
                        if (r.open.isNotEmpty()) {
                            val on = receiverOf(m, call, recv, depth)
                            when {
                                on.first.isNotEmpty() -> r.open.forEach { v.add(ofObjects(on.first, it)) }
                                on.second -> v.open += r.open
                                else -> v.add(global(r.open))
                            }
                        }
                    }
                }
            return v
        }

        /** Objects a call is made on, and whether it is made on `this` of the caller. */
        receiverOf = { m, call, recv, depth ->
            when {
                THIS in call.receiver -> (recv ?: emptySet()) to (recv == null)
                depth >= 3 || call.receiver.isEmpty() -> emptySet<String>() to false
                else -> resolve(m, call.receiver, recv, depth + 1).sites to false
            }
        }

        /** Open this-fields resolved against a call's objects, passed up when the call is on `this`. */
        fun close(m: MethodInfo, call: TaintCall, open: Collection<String>, into: Val) {
            if (open.isEmpty()) return
            val (sites, onThis) = receiverOf(m, call, null, 0)
            when {
                sites.isNotEmpty() -> open.forEach { into.add(ofObjects(sites, it)) }
                onThis -> into.open += open
                else -> into.add(global(open))
            }
        }

        fun summarize(k: String, sinks: Int, params: Int, v: Val) {
            if (v.sources == 0 && params == 0 && v.hosts.isEmpty() && v.open.isEmpty()) return
            val map = summaries.getOrPut(k) { HashMap() }
            val key = (sinks.toLong() shl 32) or (params.toLong() and 0xffffffffL)
            val acc = map[key] ?: (if (map.size >= MAX_SUMMARIES) return else Acc().also { map[key] = it; changed = true })
            if (v.sources and acc.sources.inv() != 0) { acc.sources = acc.sources or v.sources; changed = true }
            for (h in v.hosts) if (acc.hosts.size < MAX_HOSTS && acc.hosts.add(h)) changed = true
            for (f in v.open) if (acc.open.size < MAX_LABELS && acc.open.add(f)) changed = true
        }
        fun fieldSink(f: String, sinks: Int, hosts: Collection<String>) {
            val map = fieldSinks.getOrPut(f) { HashMap() }
            val set = map[sinks] ?: HashSet<String>().also { map[sinks] = it; changed = true }
            for (h in hosts) if (set.size < MAX_HOSTS && set.add(h)) changed = true
        }
        /** The field a label reads, of any object (fallback when the object is not known). */
        fun fieldOf(l: String): String? =
            when {
                l.startsWith("F:") -> l.substring(2)
                l.startsWith("TF:") -> l.substring(3)
                else -> null
            }

        /** A resolved value for global use: this-fields still open fall back to all objects. */
        fun closed(v: Val): Val = if (v.open.isEmpty()) v else Val().also { it.add(v); it.open.clear(); it.add(global(v.open)) }

        var steps = 0
        rounds@ for (round in 0 until 12) {
            state.report(round / 12f, resolving = true)
            changed = false
            for ((k, m) in methods) {
                if (++steps and 255 == 0 && (System.currentTimeMillis() > until || state.memoryTight(0.8))) {
                    state.trackingCut = true
                    break@rounds
                }
                grow(returns.getOrPut(k) { Val() }, resolve(m, m.returnCallees, null).also { it.sources = it.sources or m.returnSources })
                m.fieldFrom.forEach { (f, labels) -> grow(fieldVal.getOrPut(f) { Val() }, closed(resolve(m, labels, null))) }
                // Global fallback: callers passing values to parameters stored in fields.
                if (m.paramFields.any { it.isNotEmpty() })
                    callers[k]?.forEach { (caller, call) ->
                        call.args.values.forEach { arg ->
                            val fields = m.paramFields.getOrNull(arg.slot) ?: return@forEach
                            if (fields.isEmpty()) return@forEach
                            val v = closed(resolve(caller, arg.labels, null)).also { it.sources = it.sources or arg.sources }
                            fields.forEach { f -> grow(fieldVal.getOrPut(f) { Val() }, v) }
                        }
                    }
                // Fields of the objects this method's calls are made on: what the callee stores in this.f.
                m.calls.values.forEach { call ->
                    val callee = methods[call.callee] ?: return@forEach
                    if (callee.thisFieldFrom.isEmpty() && callee.thisFieldBits.isEmpty() && callee.thisParamFields.all { it.isEmpty() }) return@forEach
                    val (sites, _) = receiverOf(m, call, null, 0)
                    if (sites.isEmpty()) return@forEach
                    callee.thisFieldFrom.forEach { (f, labels) ->
                        val v = closed(resolve(callee, labels, sites))
                        sites.forEach { s -> grow(objField.getOrPut("$s|$f") { Val() }, v) }
                    }
                    callee.thisFieldBits.forEach { (f, bits) ->
                        sites.forEach { s -> grow(objField.getOrPut("$s|$f") { Val() }, Val().also { it.sources = bits }) }
                    }
                    call.args.values.forEach { arg ->
                        val fields = callee.thisParamFields.getOrNull(arg.slot) ?: return@forEach
                        if (fields.isEmpty()) return@forEach
                        val v = closed(resolve(m, arg.labels, null)).also { it.sources = it.sources or arg.sources }
                        fields.forEach { f -> sites.forEach { s -> grow(objField.getOrPut("$s|$f") { Val() }, v) } }
                    }
                }
                m.objectStores.forEach { store ->
                    val sites = resolve(m, store.target, null).sites
                    if (sites.isEmpty()) return@forEach
                    val v = closed(resolve(m, store.labels, null)).also { it.sources = it.sources or store.sources }
                    sites.forEach { s -> grow(objField.getOrPut("$s|${store.field}") { Val() }, v) }
                }
                // Its own sends.
                m.sinkCalls.values.forEach { call ->
                    val v = resolve(m, call.labels, null).also { it.sources = it.sources or call.sources }
                    summarize(k, call.sinks, call.params, v)
                    // A field reaching a send: the hosts that come with it, not the field's own values.
                    call.labels.forEach { l ->
                        fieldOf(l)?.let { fieldSink(it, call.sinks, closed(resolve(m, call.labels - l, null)).hosts) }
                    }
                }
                // Sends made by app methods it calls, with what it passes them and the object they run on.
                m.calls.values.forEach { call ->
                    val theirs = summaries[call.callee] ?: return@forEach
                    for ((key, acc) in theirs.entries.toList()) {
                        val sinks = (key ushr 32).toInt()
                        val slots = key.toInt()
                        val v = Val()
                        v.sources = acc.sources
                        v.hosts += acc.hosts
                        close(m, call, acc.open, v)
                        var params = 0
                        val fields = ArrayList<Pair<String, TaintArg>>()
                        val involved = call.args.values.filter { slots and paramBit(it.slot) != 0 }
                        involved.forEach { arg ->
                            v.add(resolve(m, arg.labels, null))
                            v.sources = v.sources or arg.sources
                            params = params or arg.params
                            arg.labels.forEach { l -> fieldOf(l)?.let { fields += it to arg } }
                        }
                        summarize(k, sinks or (v.sources and destinationBits), params, v.also { it.sources = it.sources and sourceBits })
                        // A field passed into a send: the hosts of the send and the other arguments.
                        fields.forEach { (f, from) ->
                            val others = Val().also { o ->
                                o.hosts += acc.hosts
                                close(m, call, acc.open, o)
                                involved.filter { it !== from }.forEach { o.add(resolve(m, it.labels, null)) }
                            }
                            fieldSink(f, sinks, closed(others).hosts)
                        }
                    }
                }
                // Parameters stored in fields reach whatever reading those fields reaches.
                for (slot in 0 until PARAM_SLOTS)
                    m.paramFields[slot].forEach { f ->
                        fieldSinks[f]?.forEach { (sinks, hosts) -> summarize(k, sinks, paramBit(slot), Val().also { it.hosts += hosts }) }
                    }
            }
            if (!changed) break
        }
        val out = HashMap<String, Map<Int, Reached>>()
        summaries.forEach { (k, map) ->
            val reached = HashMap<Int, Reached>()
            map.forEach { (key, acc) ->
                // This-fields never tied to an object: all objects' values, as before.
                val fallback = global(acc.open)
                val sources = (acc.sources or fallback.sources) and sourceBits
                if (sources == 0) return@forEach
                val hosts = acc.hosts + fallback.hosts
                val sinks = (key ushr 32).toInt()
                for (c in Capability.entries)
                    if (c.sink && sinks and c.bit != 0) {
                        val r = reached.getOrPut(c.bit) { Reached() }
                        r.sources = r.sources or sources
                        for (src in Capability.entries)
                            if (sources and src.bit != 0) r.hosts.getOrPut(src.bit) { HashSet() } += hosts
                    }
            }
            if (reached.isNotEmpty()) out[k] = reached
        }
        return out
    }

    /**
     * Deep: read-then-send paths up to [DEEP_DEPTH] levels, following calls, new objects and
     * Intents. Each chain keeps the shortest path to the reading and to the sending code.
     */
    private fun chains(state: State, classes: Classes, traced: Map<String, Map<Int, Reached>>): List<CodeChain> {
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
            fun pathKeys(to: String): List<String> {
                val keys = ArrayList<String>()
                var cursor: String? = to
                while (cursor != null) {
                    keys += cursor
                    cursor = parent.getValue(cursor).first
                }
                return keys
            }
            fun tracedOn(keys: List<String>, source: Capability, sink: Capability): Pair<Boolean, List<String>> {
                val merged = HashMap<Int, Reached>()
                keys.forEach { k -> traced[k]?.forEach { (bit, r) -> merge(merged, bit, r) } }
                return tracedTo(merged, source, sink)
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
                    if (result.keys.count { it.startsWith("$id|") } >= 8) continue
                    val (isTraced, destinations) = tracedOn(pathKeys(sourceNode) + pathKeys(sinkNode), source, sink)
                    result["$id|$start"] =
                        CodeChain(
                            source,
                            sink,
                            state.methods.getValue(sourceNode).apis[source].orEmpty(),
                            state.methods.getValue(sinkNode).apis[sink].orEmpty(),
                            toSource,
                            toSink,
                            state.classIps[sinkNode.substringBefore("->")].takeIf { sink == NETWORK },
                            // A traced value anywhere on the path: calls, lambdas and coroutines included.
                            isTraced,
                            destinations,
                        )
                }
            }
        }
        // Traced chains first, then a couple per kind.
        val kept = HashMap<String, Int>()
        return result.values
            .sortedWith(
                compareBy(
                    { if (it.traced) 0 else 1 },
                    { if (it.sink in Capability.hiddenDestinations) 0 else 1 },
                    { it.toSource.size + it.toSink.size },
                )
            )
            .filter { c -> kept.merge("${c.source}>${c.sink}", 1, Int::plus)!! <= 2 }
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

        fun fieldLabels(f: Int, base: Set<String>): Set<String> {
            if (f >= fieldCount) return base
            val key = fieldKey(f)
            val out = HashSet(base)
            out += "F:$key"
            // The continuation's `result` field holds what the awaited suspend call returned.
            if (key.substringAfter("->") == "result" && '$' in key.substringBefore("->")) out += RESUMED
            return if (out.size > MAX_LABELS) base else out
        }

        private fun storeLabels(mi: MethodInfo, f: Int, bits: Int, labels: Set<String>) {
            storeField(f, bits)
            if (f >= fieldCount) return
            val key = fieldKey(f)
            forSlots(bits) { slot -> mi.paramFields()[slot] += key }
            if (labels.isNotEmpty() && mi.fieldFrom.size < 200) mi.fieldFrom().getOrPut(key) { HashSet() } += labels.take(MAX_LABELS)
        }

        fun storeField(f: Int, bits: Int) {
            val sources = bits and PARAMS.inv()
            if (sources == 0 || f >= fieldCount || state.fieldTaint.size > 20000) return
            val key = fieldKey(f)
            state.fieldTaint[key] = (state.fieldTaint[key] ?: 0) or sources
        }

        fun stringRule(index: Int): Rule? = stringRules.getOrPut(index) { DexScanner.stringRule(string(index)) }

        fun scan() {
            for (c in 0 until classCount) {
                if (c and 127 == 0) state.report(c.toFloat() / classCount)
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
            // Value tracking (app code only): register -> source bits (with one label per parameter),
            // and register -> labels (app methods whose results it holds, fields it was read
            // from, a resumed coroutine). Run as dataflow over the method's branches below.
            val tracking = lib == null
            val registerCount = u16(codeOff)
            val entry = RegState(state.deep && tracking)
            run {
                val ins = u16(codeOff + 2)
                // Parameters sit in the last registers; `this` is not a parameter.
                val first = registerCount - ins + (if (static) 0 else 1)
                for (r in first until registerCount) entry.taint[r] = paramBit(r - first)
                if (!static && ins > 0) entry.labels[registerCount - ins] = setOf(THIS)
                // A coroutine resumes with the result of the suspend call it was waiting for.
                if (!static && ins == 2 && string(b.getInt(methodIdsOff + 8 * index + 4)) == "invokeSuspend")
                    entry.labels[registerCount - 1] = setOf(RESUMED)
            }
            var st = entry
            fun set(reg: Int, bits: Int, from: Set<String> = emptySet()) {
                if (bits == 0) st.taint.remove(reg) else st.taint[reg] = bits
                if (from.isEmpty()) st.labels.remove(reg) else st.labels[reg] = from
                st.texts?.remove(reg)
                st.pieces?.remove(reg)
            }
            fun text(reg: Int, value: String?, count: Int) {
                if (st.texts == null || value == null || value.length > 4096) return
                st.texts!![reg] = value
                st.pieces!![reg] = count
            }
            fun copyText(to: Int, from: Int) = text(to, st.texts?.get(from), st.pieces?.get(from) ?: 0)
            val name = string(b.getInt(methodIdsOff + 8 * index + 4))
            val key = methodKey(index)
            val readable = if (origin.isEmpty()) "$className.$name" else "$className.$name [$origin]"
            val info = if (lib == null) MethodInfo() else null
            val deep = state.deep && info != null
            val strings = if (deep) ArrayList<String>() else null
            fun line(text: String) {
                if (deep && info!!.listing.size < LISTING_LINES) info.listing() += text
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

            /** A text the code joined from constants; unknown st.pieces split it. */
            fun joined(value: String) {
                value.split('\u0000').filter { it.length >= 8 }.forEach {
                    recordDecoded(Decoding.JOINED, it, readable, key, quiet = true)
                }
            }

            /** Follows StringBuilder/StringBuffer and String.concat; reports a finished join. */
            fun built(called: String, regs: IntArray) {
                val owner = called.substringBefore("->")
                val name = methodName(called)
                val receiver = regs.firstOrNull() ?: return
                fun known(r: Int) = st.texts!![r]
                if (owner == "Ljava/lang/StringBuilder;" || owner == "Ljava/lang/StringBuffer;") {
                    when (name) {
                        "<init>" -> {
                            val initial = regs.getOrNull(1)?.let { r -> known(r) ?: if (called.contains("(Ljava/lang/String;)")) "\u0000" else "" } ?: ""
                            text(receiver, initial, if (initial.isEmpty() || initial == "\u0000") 0 else st.pieces!![regs[1]] ?: 1)
                        }
                        "append" -> {
                            val current = known(receiver) ?: return
                            val arg = regs.getOrNull(1)?.let(::known)
                            val count = (st.pieces!![receiver] ?: 0) + (arg?.let { st.pieces!![regs[1]] ?: 1 } ?: 0)
                            text(receiver, current + (arg ?: "\u0000"), count)
                            st.pendingText = st.texts!![receiver]
                            st.pendingPieces = count
                        }
                        "toString" -> {
                            val value = known(receiver) ?: return
                            val count = st.pieces!![receiver] ?: 0
                            st.pendingText = value
                            st.pendingPieces = count
                            if (count >= 2) joined(value)
                        }
                    }
                } else if (owner == "Ljava/lang/String;" && name == "concat" && regs.size == 2) {
                    val a = known(regs[0]) ?: return
                    val b = known(regs[1]) ?: return
                    st.pendingText = a + b
                    st.pendingPieces = (st.pieces!![regs[0]] ?: 1) + (st.pieces!![regs[1]] ?: 1)
                    joined(a + b)
                }
            }

            fun track(op: Int, unit: Int, next: Int, i: Int) {
                val mi = info!!
                val a8 = (unit shr 8) and 0xff
                val a4 = (unit shr 8) and 0xf
                val b4 = (unit shr 12) and 0xf
                if (op !in 0x0a..0x0c && op !in 0x6e..0x78 && op !in 0xfa..0xfd) {
                    st.pending = 0
                    st.pendingText = null
                }
                when (op) {
                    0x01, 0x04, 0x07 -> {
                        set(a4, st.taint[b4] ?: 0, st.labels[b4].orEmpty())
                        copyText(a4, b4)
                    }
                    0x02, 0x05, 0x08 -> {
                        set(a8, st.taint[next] ?: 0, st.labels[next].orEmpty())
                        copyText(a8, next)
                    }
                    0x03, 0x06, 0x09 -> {
                        set(next, st.taint[reg2(i)] ?: 0, st.labels[reg2(i)].orEmpty())
                        copyText(next, reg2(i))
                    }
                    0x0a, 0x0b, 0x0c -> {
                        set(a8, st.pending, st.pendingLabels)
                        text(a8, st.pendingText, st.pendingPieces)
                        st.pendingText = null
                        st.pending = 0
                        st.pendingLabels = emptySet()
                    }
                    0x0f, 0x10, 0x11 -> {
                        mi.returnSources = mi.returnSources or ((st.taint[a8] ?: 0) and PARAMS.inv())
                        st.labels[a8]?.let { if (it.isNotEmpty()) mi.returnCallees() += it }
                    }
                    in 0x12..0x19 -> set(if (op == 0x12) a4 else a8, 0)
                    0x1a, 0x1b -> {
                        val idx = if (op == 0x1a) next else next or (reg2(i) shl 16)
                        val rule = stringRule(idx)
                        // Content URIs carry their source (read once queried); bot URLs mark the destination.
                        val bits = rule?.capability?.let { if (it.source || it in Capability.hiddenDestinations) it.bit else 0 } ?: 0
                        // A URL constant carries its host, so the send it ends up in names its destination.
                        set(a8, bits, hostLabels(string(idx)))
                        text(a8, string(idx), 1)
                    }
                    0x1c -> set(a8, 0)
                    // Each allocation site is one object, so its fields are told apart from others'.
                    0x22 -> set(a8, 0, setOf("O:$key@$i"))
                    0x1f -> Unit // check-cast keeps the value
                    in 0x44..0x4a -> set(a8, st.taint[next and 0xff] ?: 0, st.labels[next and 0xff].orEmpty())
                    in 0x4b..0x51 -> {
                        val arr = next and 0xff
                        val value = st.taint[a8] ?: 0
                        if (value != 0) set(arr, (st.taint[arr] ?: 0) or value, st.labels[arr].orEmpty() + st.labels[a8].orEmpty())
                    }
                    // A field of an object read from a source holds that source: notification.extras.
                    // Field reads carry the field as a label, resolved app-wide later: a captured
                    // lambda variable ($c), a spilled local (L$0) or a continuation's result.
                    in 0x52..0x58 -> {
                        val obj = st.labels[b4].orEmpty()
                        val labels =
                            if (THIS in obj && next < fieldCount) {
                                // this.f: resolved against the object the method is called on.
                                val f = fieldKey(next)
                                fieldLabels(next, emptySet()).filterTo(HashSet()) { !it.startsWith("F:") }.also { it += "TF:$f" }
                            } else fieldLabels(next, obj.filterTo(HashSet()) { it != THIS })
                        set(a4, fieldBits(next) or ((st.taint[b4] ?: 0) and PARAMS.inv()), labels)
                    }
                    in 0x59..0x5f -> {
                        val value = st.taint[a4] ?: 0
                        val valueLabels = st.labels[a4].orEmpty()
                        storeLabels(mi, next, value, valueLabels)
                        val obj = st.labels[b4].orEmpty()
                        if (next < fieldCount && obj.isNotEmpty()) {
                            val f = fieldKey(next)
                            if (THIS in obj) {
                                if (valueLabels.isNotEmpty() && mi.thisFieldFrom.size < 200)
                                    mi.thisFieldFrom().getOrPut(f) { HashSet() } += valueLabels.take(MAX_LABELS)
                                val src = value and (sourceBits or destinationBits)
                                if (src != 0) mi.thisFieldBits()[f] = (mi.thisFieldBits[f] ?: 0) or src
                                forSlots(value) { slot -> mi.thisParamFields()[slot] += f }
                            } else if (mi.objectStores.size < 200 && (valueLabels.isNotEmpty() || value and sourceBits != 0))
                                mi.objectStores() += ObjectStore(obj, f, value and (sourceBits or destinationBits), valueLabels)
                        }
                    }
                    in 0x60..0x66 -> set(a8, fieldBits(next), fieldLabels(next, emptySet()))
                    in 0x67..0x6d -> storeLabels(mi, next, st.taint[a8] ?: 0, st.labels[a8].orEmpty())
                    in 0x6e..0x72, in 0x74..0x78, 0xfa, 0xfb, 0xfc, 0xfd -> {
                        val regs = args(op, unit, i)
                        var mask = 0
                        val from = HashSet<String>()
                        regs.forEach { r ->
                            mask = mask or (st.taint[r] ?: 0)
                            st.labels[r]?.let { from += it }
                        }
                        // Object identities say which object a call is on; they are not data.
                        from.removeAll { it == THIS || it.startsWith("O:") }
                        val sources = mask and PARAMS.inv()
                        val rule = if (op == 0xfc || op == 0xfd) null else methodRule.getOrNull(next)
                        val cap = rule?.capability
                        if (cap != null && cap.sink && mi.sinkCalls.size < MAX_CALLS) {
                            // Data sent together with a bot or webhook address goes to that destination.
                            val sinkBits = cap.bit or (sources and destinationBits)
                            if (sources != 0 || mask and PARAMS != 0 || from.isNotEmpty()) {
                                val call = mi.sinkCalls().getOrPut(i) { SinkCall(0, 0, 0, HashSet()) }
                                call.sinks = call.sinks or sinkBits
                                call.sources = call.sources or (sources and sourceBits)
                                call.params = call.params or (mask and PARAMS)
                                if (call.labels.size < MAX_LABELS) call.labels += from
                            }
                        }
                        var result = mask
                        var resultFrom: Set<String> = from
                        if (cap != null && cap.source) result = result or cap.bit
                        val called = if (op == 0xfc || op == 0xfd || next >= methodCount) null else methodKey(next)
                        if (called != null && isAppClass(called.substringBefore("->"))) {
                            // Per argument, so a callee parameter only gets what is passed to it; the
                            // receiver tells which object the call is made on.
                            val static = op == 0x71 || op == 0x77
                            val call =
                                mi.calls[i] ?: (if (mi.calls.size >= MAX_CALLS) null else TaintCall(state.intern(called)).also { mi.calls()[i] = it })
                            if (call != null) {
                                regs.forEachIndexed { p, r ->
                                    val labels = st.labels[r].orEmpty()
                                    val slot = if (static) p else p - 1
                                    if (slot < 0) {
                                        if (labels.isNotEmpty()) call.receiver() += labels.take(MAX_LABELS)
                                        return@forEachIndexed
                                    }
                                    val bits = st.taint[r] ?: 0
                                    if (bits == 0 && labels.isEmpty()) return@forEachIndexed
                                    val s0 = slot.coerceAtMost(PARAM_SLOTS - 1)
                                    val arg = call.arg(s0)
                                    arg.sources = arg.sources or (bits and (sourceBits or destinationBits))
                                    arg.params = arg.params or (bits and PARAMS)
                                    if (arg.labels.size < MAX_LABELS && !arg.labels.containsAll(labels)) arg.labels = arg.labels + labels
                                }
                                // The result is labelled with this call, resolved with its receiver later.
                                resultFrom = if (from.size < MAX_LABELS) from + "C:$i" else from
                            }
                        }
                        // Data read from a source is not sent to the address it was read for
                        // (getCookie("https://www.instagram.com") returns the cookie, not a request).
                        if (cap != null && cap.source) resultFrom = resultFrom.filterTo(HashSet()) { !it.startsWith("H:") }
                        // A builder or collection that receives a value carries it: url(x), append(x), put(k, x).
                        val instance = op != 0x71 && op != 0x77 && op != 0xfc && op != 0xfd && regs.isNotEmpty()
                        if (instance && regs.size > 1) {
                            var rest = 0
                            val restLabels = HashSet<String>()
                            for (k in 1 until regs.size) {
                                rest = rest or (st.taint[regs[k]] ?: 0)
                                st.labels[regs[k]]?.let { restLabels += it }
                            }
                            // Only the value labels change; the builder keeps any text it is building.
                            if (rest != 0) st.taint[regs[0]] = (st.taint[regs[0]] ?: 0) or rest
                            if (restLabels.isNotEmpty()) {
                                val merged = st.labels[regs[0]].orEmpty() + restLabels
                                if (merged.size <= MAX_LABELS) st.labels[regs[0]] = merged
                            }
                        }
                        st.pending = result
                        st.pendingLabels = resultFrom
                        if (st.texts != null && called != null) built(called, regs)
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
                    info.apis().putIfAbsent(cap, rule.api)
                }
            }
            val starts = BooleanArray(if (info != null) size else 0)
            var i = 0
            while (i < size) {
                val unit = u16(start + 2 * i)
                val op = unit and 0xff
                val next = if (i + 1 < size) u16(start + 2 * (i + 1)) else 0
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
                            if (isAppClass(target)) info!!.constClasses() += target
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
                            if (isAppClass(owner)) info.callees() += state.intern(methodKey(next))
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
                if (info != null) starts[i] = true
                i += width(op, unit, start, i, size)
            }
            if (info != null) {
                if (state.mayTrack()) dataflow(start, size, starts, entry, { st = it }, ::track)
            }
            if (deep) {
                arrays!!.keys.toList().forEach(::flushArray)
                decodeStrings(strings!!, readable, key)
            }
            if (info != null) {
                val stored = state.intern(key)
                state.names.putIfAbsent(stored, readable)
                state.classMethods.getOrPut(descriptor) { mutableListOf() }.let { if (stored !in it) it += stored }
                state.methods[stored]?.merge(info) ?: state.methods.put(stored, info)
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
                info.apis().putIfAbsent(rule.capability, hit.api)
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
        /**
         * Runs [step] over a method's instructions along its branches, joining register states
         * where paths meet, until nothing changes or the budget is spent. Code reached only
         * through exception handlers starts from the entry state.
         */
        fun dataflow(
            start: Int,
            size: Int,
            starts: BooleanArray,
            entry: RegState,
            use: (RegState) -> Unit,
            step: (Int, Int, Int, Int) -> Unit,
        ) {
            if (size == 0) return
            // Basic blocks: a block starts at the entry, at every branch target and after every
            // branch. State is copied and joined only at block starts.
            val leader = BooleanArray(size)
            leader[0] = true
            var branches = false
            run {
                var pc = 0
                while (pc < size) {
                    if (!starts[pc]) { pc++; continue }
                    val unit = u16(start + 2 * pc)
                    val op = unit and 0xff
                    val next = if (pc + 1 < size) u16(start + 2 * (pc + 1)) else 0
                    val w = width(op, unit, start, pc, size)
                    if (op in 0x27..0x2c || op in 0x32..0x3d || op in 0x0e..0x11) {
                        branches = true
                        successors(op, unit, next, start, pc, size, w) { if (it in 0 until size) leader[it] = true }
                        if (pc + w < size) leader[pc + w] = true
                    }
                    pc += w
                }
            }
            // Straight-line code: one pass in order is already exact.
            if (!branches) {
                use(entry)
                var pc = 0
                while (pc < size) {
                    if (!starts[pc]) { pc++; continue }
                    val unit = u16(start + 2 * pc)
                    val op = unit and 0xff
                    step(op, unit, if (pc + 1 < size) u16(start + 2 * (pc + 1)) else 0, pc)
                    pc += width(op, unit, start, pc, size)
                }
                return
            }
            val states = HashMap<Int, RegState>()
            val queued = BooleanArray(size)
            val seen = BooleanArray(size)
            val queue = ArrayDeque<Int>()
            fun reach(pc: Int, out: RegState) {
                if (pc !in 0 until size || !starts[pc]) return
                val cur = states[pc]
                val grew = if (cur == null) { states[pc] = out.copy(); true } else cur.join(out)
                if (grew && !queued[pc]) {
                    queued[pc] = true
                    queue += pc
                }
            }
            reach(0, entry)
            var budget = size * 4 + 1000
            var seed = 0
            while (budget > 0) {
                while (queue.isNotEmpty() && budget > 0) {
                    val blockStart = queue.removeFirst()
                    queued[blockStart] = false
                    val current = states.getValue(blockStart).copy()
                    use(current)
                    var pc = blockStart
                    while (pc < size && budget-- > 0) {
                        seen[pc] = true
                        val unit = u16(start + 2 * pc)
                        val op = unit and 0xff
                        val next = if (pc + 1 < size) u16(start + 2 * (pc + 1)) else 0
                        step(op, unit, next, pc)
                        val w = width(op, unit, start, pc, size)
                        val ends = op in 0x27..0x2c || op in 0x32..0x3d || op in 0x0e..0x11
                        if (ends) {
                            successors(op, unit, next, start, pc, size, w) { reach(it, current) }
                            break
                        }
                        pc += w
                        if (pc < size && leader[pc]) {
                            reach(pc, current)
                            break
                        }
                    }
                }
                while (seed < size && (!starts[seed] || seen[seed])) seed++
                if (seed >= size) break
                reach(seed, entry)
                seen[seed] = true
            }
        }

        /** Where control goes after the instruction at [pc]. */
        fun successors(op: Int, unit: Int, next: Int, start: Int, pc: Int, size: Int, w: Int, to: (Int) -> Unit) {
            fun int32(at: Int) = if (at + 1 < size) u16(start + 2 * at) or (u16(start + 2 * (at + 1)) shl 16) else 0
            when (op) {
                0x0e, 0x0f, 0x10, 0x11, 0x27 -> Unit
                0x28 -> to(pc + (unit shr 8).toByte())
                0x29 -> to(pc + next.toShort())
                0x2a -> to(pc + int32(pc + 1))
                in 0x32..0x3d -> {
                    to(pc + w)
                    to(pc + next.toShort())
                }
                0x2b, 0x2c -> {
                    to(pc + w)
                    val payload = pc + int32(pc + 1)
                    if (payload in 0 until size - 2) {
                        val count = u16(start + 2 * (payload + 1))
                        val targets = if (op == 0x2b) payload + 4 else payload + 2 + 2 * count
                        if (count <= 4096 && targets + 2 * count <= size)
                            for (k in 0 until count) to(pc + int32(targets + 2 * k))
                    }
                }
                else -> to(pc + w)
            }
        }

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
