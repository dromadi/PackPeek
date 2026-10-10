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
    private val ipv4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
    private val ignoredHosts =
        setOf("schemas.android.com", "www.w3.org", "www.apache.org", "xmlpull.org", "localhost", "127.0.0.1",
            "example.com", "www.example.com", "ns.adobe.com", "purl.org", "xml.org", "www.xml.org")

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

        fun merge(other: MethodInfo) {
            sources = sources or other.sources
            sinks = sinks or other.sinks
            stringSources = stringSources or other.stringSources
            stringSinks = stringSinks or other.stringSinks
            markers = markers or other.markers
            other.apis.forEach { (k, v) -> apis.putIfAbsent(k, v) }
            callees += other.callees
        }
    }

    private class State(val deadline: Long) {
        val appHits = HashMap<Capability, MutableList<CodeHit>>()
        val libraryHits = HashMap<Capability, MutableList<CodeHit>>()
        val hitKeys = HashSet<String>()
        val methods = HashMap<String, MethodInfo>()
        val classMethods = HashMap<String, MutableList<String>>()
        val names = HashMap<String, String>()
        val destinations = LinkedHashMap<String, Destination>()
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

    fun scan(apks: List<File>, timeLimitMs: Long = TIME_LIMIT_MS): CodeFacts {
        val state = State(System.currentTimeMillis() + timeLimitMs)
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
        val (flows, classes) = flows(state)
        return CodeFacts(
            dexFiles = state.dexFiles,
            classes = state.classes,
            appClasses = state.appClasses,
            hits =
                state.hits(
                    state.pendingTexts
                        .filter { (key, hit) ->
                            val owner = key.substringBefore("->")
                            if (hit.capability.source) owner in classes.querying else owner in classes.sending
                        }
                        .map { it.second }
                ),
            flows = flows,
            destinations = state.destinations.values.toList(),
            sdks = state.sdks.toList(),
            gaps = state.gaps.distinct(),
        )
    }

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
                if (dex || (archive && head != null && head.size == 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()))
                    state.gaps += CodeGap(GapKind.HIDDEN_CODE, "$apk!$name")
            }
        }
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

    private class Classes(val sending: Set<String>, val querying: Set<String>)

    /** Read-then-send paths, and the classes that can actually send or query (for text evidence). */
    private fun flows(state: State): Pair<List<CodeFlow>, Classes> {
        val result = LinkedHashMap<String, CodeFlow>()
        val sending = HashSet<String>()
        val querying = HashSet<String>()
        fun callees(info: MethodInfo): List<MethodInfo> =
            info.callees.flatMap { key ->
                if (key.endsWith("-><init>")) {
                    val members = state.classMethods[key.substringBefore("->")].orEmpty()
                    if (members.size <= CONSTRUCTOR_EXPANSION_LIMIT) members.mapNotNull { state.methods[it] }
                    else listOfNotNull(state.methods[key])
                } else listOfNotNull(state.methods[key])
            }
        for ((key, info) in state.methods) {
            val reach = callees(info) + info
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
            for (source in Capability.entries.filter { it.source && sources and it.bit != 0 })
                for (sink in Capability.entries.filter { it.sink && sinks and it.bit != 0 }) {
                    val id = "$source>$sink"
                    if (result.keys.count { it.startsWith(id) } >= 2 || result.size >= MAX_FLOWS) continue
                    result["$id|$key"] =
                        CodeFlow(
                            source,
                            sink,
                            state.names[key] ?: key,
                            reach.firstNotNullOf { it.apis[source] },
                            reach.firstNotNullOf { it.apis[sink] },
                        )
                }
        }
        // Hidden destinations first: they are the strongest sign.
        return result.values.sortedBy { if (it.sink in Capability.hiddenDestinations) 0 else 1 } to Classes(sending, querying)
    }

    /** One DEX file. Offsets follow the format at source.android.com/docs/core/runtime/dex-format. */
    private class Dex(val bytes: ByteArray, val state: State) {
        val b: ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val strings: Array<String?>
        val stringIdsOff: Int
        val typeIdsOff: Int
        val typeCount: Int
        val fieldIdsOff: Int
        val fieldCount: Int
        val methodIdsOff: Int
        val methodCount: Int
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

        fun methodKey(m: Int): String {
            val p = methodIdsOff + 8 * m
            return type(u16(p)) + "->" + string(b.getInt(p + 4))
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
                        uleb(cursor)
                        val codeOff = uleb(cursor)
                        if (codeOff != 0 && methodIndex in 0 until methodCount)
                            method(methodIndex, codeOff, className, descriptor, lib)
                    }
                }
            }
        }

        fun method(index: Int, codeOff: Int, className: String, descriptor: String, lib: Library?) {
            require(codeOff in 0 until bytes.size - 16)
            val size = b.getInt(codeOff + 12)
            val start = codeOff + 16
            require(size >= 0 && start.toLong() + 2L * size <= bytes.size)
            val name = string(b.getInt(methodIdsOff + 8 * index + 4))
            val readable = "$className.$name"
            val info = if (lib == null) MethodInfo() else null
            val key = "$descriptor->$name"
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
                when (op) {
                    0x1a ->
                        if (info != null) {
                            stringRule(next)?.let(::record)
                            url(string(next))
                        }
                    0x1b ->
                        if (info != null && i + 2 < size) {
                            val idx = next or (u16(start + 2 * (i + 2)) shl 16)
                            stringRule(idx)?.let(::record)
                            url(string(idx))
                        }
                    in 0x60..0x6d -> fieldRule.getOrNull(next)?.let(::record)
                    in 0x6e..0x72, in 0x74..0x78, 0xfa, 0xfb -> {
                        methodRule.getOrNull(next)?.let(::record)
                        if (info != null && next < methodCount) {
                            val target = methodIdsOff + 8 * next
                            val owner = type(u16(target))
                            if (library(owner) == null && owner.startsWith("L")) info.callees += methodKey(next)
                        }
                    }
                }
                i += width(op, unit, start, i, size)
            }
            if (info != null) {
                state.names.putIfAbsent(key, readable)
                state.classMethods.getOrPut(descriptor) { mutableListOf() }.let { if (key !in it) it += key }
                state.methods.getOrPut(key) { MethodInfo() }.merge(info)
            }
        }

        /** Records hosts of URLs written in the app's own code. */
        fun url(s: String) {
            if (state.destinations.size >= 40 || s.length > 2048) return
            url.findAll(s).take(3).forEach { m ->
                val host = m.groupValues[2].lowercase().trim('[', ']')
                if (host.isBlank() || host in ignoredHosts || '.' !in host && ':' !in host) return@forEach
                val cleartext = m.groupValues[1] == "http"
                val existing = state.destinations[host]
                state.destinations[host] =
                    Destination(host, cleartext || existing?.cleartext == true, ipv4.matches(host) || ':' in host)
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
