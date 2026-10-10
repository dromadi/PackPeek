package com.packpeek.app.engine.risk

import java.util.Locale

/**
 * Turns manifest facts, app context and device state into findings. Pure Kotlin so every rule is
 * unit-tested. A single sensitive capability never becomes a strong indicator on its own; only
 * the combined patterns ([RiskId.OTP_THEFT], [RiskId.SCREEN_SPYING]) do.
 */
object RiskRules {
    const val INTERNET = "android.permission.INTERNET"
    const val SMS_RECEIVED = "android.provider.Telephony.SMS_RECEIVED"
    const val SMS_DELIVER = "android.provider.Telephony.SMS_DELIVER"
    const val BIND_ACCESSIBILITY = "android.permission.BIND_ACCESSIBILITY_SERVICE"
    const val BIND_NOTIFICATIONS = "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"
    const val BIND_DEVICE_ADMIN = "android.permission.BIND_DEVICE_ADMIN"
    const val BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED"
    private const val MEDIA_PROJECTION_TYPE = 0x20
    private const val NEW_CERTIFICATE_DAYS = 30
    private const val HEAVY_BACKGROUND_BYTES = 100L * 1000 * 1000

    private val smsPermissions =
        listOf(
            "android.permission.RECEIVE_SMS",
            "android.permission.READ_SMS",
            "android.permission.RECEIVE_MMS",
            "android.permission.RECEIVE_WAP_PUSH",
        )
    private val contactPermissions =
        listOf(
            "android.permission.READ_CONTACTS",
            "android.permission.READ_CALL_LOG",
            "android.permission.WRITE_CALL_LOG",
            "android.permission.PROCESS_OUTGOING_CALLS",
        )
    private val identityPermissions =
        listOf(
            "android.permission.READ_PHONE_STATE",
            "android.permission.READ_PHONE_NUMBERS",
            "android.permission.GET_ACCOUNTS",
        )
    private val cameraMicPermissions =
        listOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO")
    private val locationPermissions =
        listOf(
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_BACKGROUND_LOCATION",
        )

    /** Names used by fake invitation, ticket or parcel APKs to look harmless. */
    private val lureWords =
        listOf(
            "undangan",
            "pernikahan",
            "surat",
            "tilang",
            "resi",
            "kurir",
            "paket",
            "tagihan",
            "dokumen",
            "invoice",
            "wedding",
            "invitation",
            "pdf",
            "bpjs",
            "pajak",
        )

    /** Personal data that should never go to a hidden destination. */
    private val personalSources =
        listOf(
            Capability.SMS_READ,
            Capability.CONTACTS,
            Capability.CALL_LOG,
            Capability.NOTIFICATION_READ,
            Capability.SCREEN_READ,
            Capability.CLIPBOARD,
            Capability.COOKIES,
            Capability.PHONE_ID,
            Capability.ACCOUNTS,
            Capability.LOCATION,
        )

    fun evaluate(
        facts: ManifestFacts,
        context: RiskContext,
        device: DeviceState?,
        code: CodeFacts? = null,
    ): RiskReport {
        val findings = mutableListOf<RiskFinding>()
        val manifest = "AndroidManifest.xml"
        fun permission(name: String) =
            Evidence(
                EvidenceLevel.DECLARED,
                EvidenceKind.PERMISSION,
                name,
                "$manifest · ${facts.permissions[name]}",
            )
        fun component(c: ComponentFacts, extra: String = "") =
            Evidence(
                EvidenceLevel.DECLARED,
                EvidenceKind.COMPONENT,
                c.name + extra,
                "$manifest · ${c.apk}",
            )
        fun observed(key: StateKey, detail: String = "") =
            Evidence(EvidenceLevel.OBSERVED, EvidenceKind.STATE, detail, "", key)
        fun granted(name: String) =
            if (device != null && name in device.granted)
                listOf(observed(StateKey.PERMISSION_GRANTED, name))
            else emptyList()
        fun declaredWithGrant(names: List<String>) =
            names.filter(facts::has).flatMap { listOf(permission(it)) + granted(it) }
        // Code evidence (phase 2/3). Only the app's own code counts for patterns; known libraries
        // are listed for context on capability findings.
        fun calls(vararg caps: Capability, libraries: Boolean = false): List<Evidence> =
            caps.flatMap { cap ->
                code?.hits?.get(cap).orEmpty()
                    .filter { libraries || it.library == null }
                    .map {
                        Evidence(
                            EvidenceLevel.CODE,
                            if (it.string) EvidenceKind.STRING else EvidenceKind.CODE,
                            it.api,
                            it.method,
                            library = it.library,
                        )
                    }
            }
        fun flowsFrom(vararg sources: Capability, sinks: Set<Capability>? = null): List<Evidence> =
            code?.flows.orEmpty()
                .filter { it.source in sources && (sinks == null || it.sink in sinks) }
                .map {
                    Evidence(
                        EvidenceLevel.FLOW,
                        EvidenceKind.FLOW,
                        "${it.source}>${it.sink}|${it.sourceApi}|${it.sinkApi}",
                        it.method,
                    )
                }
        fun state(evidence: List<Evidence>): CodeState =
            when {
                code == null -> CodeState.NOT_ANALYZED
                evidence.any { it.level == EvidenceLevel.FLOW } -> CodeState.FLOW
                evidence.any { it.level == EvidenceLevel.CODE && it.library == null } -> CodeState.CALLS
                else -> CodeState.NOT_FOUND
            }
        fun finding(id: RiskId, severity: Severity, evidence: List<Evidence>, note: ContextNote = ContextNote.NONE) =
            RiskFinding(id, severity, evidence, note, code = state(evidence))
        val hiddenDestinations = calls(Capability.TELEGRAM, Capability.DISCORD)
        val ipDestinations =
            code?.destinations.orEmpty().filter { it.ipAddress }.map {
                Evidence(EvidenceLevel.CODE, EvidenceKind.DESTINATION, "${it.host}|${it.cleartext}|true", "")
            }
        val forwarding = calls(Capability.CALL_FORWARDING)

        val system = context.system
        // What the launcher shows on this device wins over the manifest: an app can disable its
        // launcher activity after the first start.
        val hidden = device?.launcherVisible?.not() ?: !facts.hasLauncher
        fun severity(normal: Severity) = if (system) Severity.INFO else normal
        val systemNote = if (system) ContextNote.SYSTEM_APP else ContextNote.NONE

        // Signals that the app or file did not come from a trusted, visible place.
        val untrusted = buildList {
            if (hidden && !system)
                add(
                    if (device?.launcherVisible == false) observed(StateKey.NO_LAUNCHER)
                    else Evidence(EvidenceLevel.DECLARED, EvidenceKind.FLAG, "", manifest, StateKey.NO_LAUNCHER)
                )
            if (context.fromFile)
                add(Evidence(EvidenceLevel.DECLARED, EvidenceKind.SOURCE, "", "", StateKey.FILE_NOT_INSTALLED))
            else if (context.source == "SIDE") add(observed(StateKey.SIDELOADED))
            if (context.debugCertificate)
                add(Evidence(EvidenceLevel.DECLARED, EvidenceKind.CERTIFICATE, "", "META-INF", StateKey.DEBUG_CERTIFICATE))
            else if ((context.certificateAgeDays ?: Long.MAX_VALUE) < NEW_CERTIFICATE_DAYS)
                add(
                    Evidence(
                        EvidenceLevel.DECLARED,
                        EvidenceKind.CERTIFICATE,
                        context.certificateAgeDays.toString(),
                        "META-INF",
                        StateKey.NEW_CERTIFICATE,
                    )
                )
            val label = context.label.lowercase(Locale.ROOT)
            lureWords
                .firstOrNull { it in label }
                ?.let {
                    add(Evidence(EvidenceLevel.DECLARED, EvidenceKind.LABEL, context.label, manifest, StateKey.LURE_LABEL))
                }
        }
        // Installing outside a store is common for ordinary apps. On its own it only counts when
        // the code could not be checked fully; hidden icons, lures and test certificates always count.
        val concealment = untrusted.filter { it.stateKey != StateKey.SIDELOADED }
        val sideloaded = untrusted.any { it.stateKey == StateKey.SIDELOADED }
        // The app's main code could not be read: no analysis, an encrypting packer, or a limit.
        // Embedded files are reported separately (DYNAMIC_CODE) and do not hide the main code.
        val codeIncomplete =
            code == null || code.gaps.any { it.kind in setOf(GapKind.PACKER, GapKind.UNREADABLE, GapKind.LIMIT) }
        val internet =
            if (facts.has(INTERNET))
                listOf(Evidence(EvidenceLevel.DECLARED, EvidenceKind.PERMISSION, INTERNET, "$manifest · ${facts.permissions[INTERNET]}"))
            else emptyList()

        // SMS / OTP
        val defaultSms = device?.defaultSmsApp == true
        val smsReceivers = facts.receiversFor(SMS_RECEIVED)
        val smsDeclared =
            declaredWithGrant(smsPermissions) +
                smsReceivers.map { component(it, " · priority ${it.maxPriority(SMS_RECEIVED)}") } +
                (if (defaultSms) listOf(observed(StateKey.DEFAULT_SMS_APP)) else emptyList())
        val smsCode = if (smsDeclared.isEmpty()) emptyList() else calls(Capability.SMS_READ) + flowsFrom(Capability.SMS_READ)
        val sms = smsDeclared + smsCode
        if (sms.isNotEmpty())
            findings +=
                finding(
                    RiskId.SMS,
                    if (defaultSms) Severity.INFO else severity(Severity.ATTENTION),
                    sms,
                    if (defaultSms) ContextNote.DEFAULT_SMS_APP else systemNote,
                )
        val sendSms = declaredWithGrant(listOf("android.permission.SEND_SMS"))
        // OTP theft: SMS access plus a way out plus at least one sign that it is meant to be hidden
        // or that code actually sends SMS content somewhere.
        val smsOut = flowsFrom(Capability.SMS_READ)
        val otpSigns = concealment + smsOut + hiddenDestinations + forwarding
        if (
            smsDeclared.isNotEmpty() &&
                (internet.isNotEmpty() || sendSms.isNotEmpty()) &&
                (otpSigns.isNotEmpty() || (sideloaded && codeIncomplete)) &&
                !defaultSms &&
                !system
        )
            findings +=
                finding(
                    RiskId.OTP_THEFT,
                    Severity.STRONG,
                    sms + internet + untrusted + hiddenDestinations + forwarding,
                )
        if (sendSms.isNotEmpty()) {
            val evidence = sendSms + calls(Capability.SMS_SEND) + flowsFrom(*personalSources.toTypedArray(), sinks = setOf(Capability.SMS_SEND))
            findings +=
                finding(
                    RiskId.SMS_SEND,
                    if (defaultSms) Severity.INFO else severity(Severity.ATTENTION),
                    evidence.distinct(),
                    if (defaultSms) ContextNote.DEFAULT_SMS_APP else systemNote,
                )
        }

        // Accessibility
        val accessibilityServices = facts.servicesBoundBy(BIND_ACCESSIBILITY)
        val accessibility =
            accessibilityServices.flatMap { service ->
                val config = facts.accessibility[service.name]
                buildList {
                    add(component(service))
                    if (config != null) {
                        if (config.canRetrieveWindowContent)
                            add(Evidence(EvidenceLevel.DECLARED, EvidenceKind.CONFIG, "canRetrieveWindowContent", service.name))
                        if (config.canPerformGestures)
                            add(Evidence(EvidenceLevel.DECLARED, EvidenceKind.CONFIG, "canPerformGestures", service.name))
                        if (config.eventTypes == -1)
                            add(Evidence(EvidenceLevel.DECLARED, EvidenceKind.CONFIG, "typeAllMask", service.name))
                    }
                    if (device != null && service.name in device.accessibilityEnabled)
                        add(observed(StateKey.ACCESSIBILITY_ENABLED, service.name))
                }
            } +
                (if (accessibilityServices.isEmpty()) emptyList()
                else calls(Capability.SCREEN_READ, Capability.GESTURES) + flowsFrom(Capability.SCREEN_READ))
        if (accessibility.isNotEmpty())
            findings += finding(RiskId.ACCESSIBILITY, severity(Severity.ATTENTION), accessibility, systemNote)

        // Notifications, device admin, overlay
        val listeners = facts.servicesBoundBy(BIND_NOTIFICATIONS)
        val notifications =
            listeners.map { component(it) } +
                (if (device?.notificationListenerEnabled == true)
                    listOf(observed(StateKey.NOTIFICATION_LISTENER_ENABLED))
                else emptyList()) +
                (if (listeners.isEmpty()) emptyList()
                else calls(Capability.NOTIFICATION_READ) + flowsFrom(Capability.NOTIFICATION_READ))
        if (notifications.isNotEmpty())
            findings += finding(RiskId.NOTIFICATIONS, severity(Severity.ATTENTION), notifications, systemNote)
        val admins = facts.components.filter { it.kind == "receiver" && it.permission == BIND_DEVICE_ADMIN }
        val admin =
            admins.map { a ->
                val policies = facts.deviceAdminPolicies[a.name].orEmpty()
                component(a, if (policies.isEmpty()) "" else " · ${policies.joinToString()}")
            } + (if (device?.deviceAdminActive == true) listOf(observed(StateKey.DEVICE_ADMIN_ACTIVE)) else emptyList()) +
                (if (admins.isEmpty()) emptyList() else calls(Capability.DEVICE_ADMIN_ACTION))
        if (admin.isNotEmpty())
            findings += finding(RiskId.DEVICE_ADMIN, severity(Severity.ATTENTION), admin, systemNote)
        val overlay =
            declaredWithGrant(listOf("android.permission.SYSTEM_ALERT_WINDOW")).filter {
                it.kind == EvidenceKind.PERMISSION
            } + (if (device?.overlayAllowed == true) listOf(observed(StateKey.OVERLAY_ALLOWED)) else emptyList())
        if (overlay.any { it.kind == EvidenceKind.PERMISSION })
            findings += RiskFinding(RiskId.OVERLAY, severity(Severity.ATTENTION), overlay, systemNote)

        // Screen spying: reads other apps' screens, can send data out, plus a concealment signal.
        val readsScreen =
            accessibilityServices.any { s ->
                facts.accessibility[s.name]?.canRetrieveWindowContent ?: true
            }
        // Overlay, device admin and notification access amplify the pattern only when the code
        // could not be checked; with readable code, the code itself must show it (or concealment).
        val amplifiers =
            concealment +
                if (codeIncomplete)
                    untrusted.filter { it.stateKey == StateKey.SIDELOADED } +
                        overlay.filter { it.kind == EvidenceKind.PERMISSION } +
                        admin +
                        notifications
                else emptyList()
        val screenOut = flowsFrom(Capability.SCREEN_READ)
        val spying = accessibilityServices.isNotEmpty() && readsScreen && internet.isNotEmpty() && !system
        if (spying && (amplifiers.isNotEmpty() || screenOut.isNotEmpty() || hiddenDestinations.isNotEmpty()))
            findings +=
                finding(
                    RiskId.SCREEN_SPYING,
                    Severity.STRONG,
                    (accessibility + internet + amplifiers + hiddenDestinations).distinct(),
                )

        // Other personal data and capabilities
        declaredWithGrant(contactPermissions).takeIf { it.isNotEmpty() }?.let {
            val evidence =
                it + calls(Capability.CONTACTS, Capability.CALL_LOG) +
                    flowsFrom(Capability.CONTACTS, Capability.CALL_LOG)
            findings += finding(RiskId.CONTACTS_CALLS, severity(Severity.ATTENTION), evidence, systemNote)
        }
        declaredWithGrant(identityPermissions).takeIf { it.isNotEmpty() }?.let {
            val evidence =
                it + calls(Capability.PHONE_ID, Capability.ACCOUNTS) +
                    flowsFrom(Capability.PHONE_ID, Capability.ACCOUNTS)
            findings += finding(RiskId.PHONE_IDENTITY, Severity.INFO, evidence, systemNote)
        }
        declaredWithGrant(cameraMicPermissions).takeIf { it.isNotEmpty() }?.let {
            findings +=
                finding(RiskId.CAMERA_MIC, Severity.INFO, it + calls(Capability.CAMERA_MIC, libraries = true), systemNote)
        }
        declaredWithGrant(locationPermissions).takeIf { it.isNotEmpty() }?.let {
            val background = facts.has("android.permission.ACCESS_BACKGROUND_LOCATION")
            findings +=
                finding(
                    RiskId.LOCATION,
                    if (background) severity(Severity.ATTENTION) else Severity.INFO,
                    it + calls(Capability.LOCATION, libraries = true) + flowsFrom(Capability.LOCATION),
                    systemNote,
                )
        }
        val capture =
            declaredWithGrant(listOf("android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION")) +
                facts.components
                    .filter { it.kind == "service" && it.foregroundServiceType and MEDIA_PROJECTION_TYPE != 0 }
                    .map { component(it, " · mediaProjection") }
        if (capture.isNotEmpty())
            findings +=
                finding(RiskId.SCREEN_CAPTURE, severity(Severity.ATTENTION), capture + calls(Capability.SCREEN_CAPTURE), systemNote)
        val persistence =
            facts.receiversFor(BOOT_COMPLETED).map { component(it, " · BOOT_COMPLETED") } +
                declaredWithGrant(listOf("android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS")) +
                (if (device?.batteryExempt == true) listOf(observed(StateKey.BATTERY_EXEMPT)) else emptyList())
        if (persistence.isNotEmpty())
            findings += RiskFinding(RiskId.PERSISTENCE, Severity.INFO, persistence, systemNote)
        if (hidden && !system)
            findings +=
                RiskFinding(
                    RiskId.HIDDEN_ICON,
                    Severity.ATTENTION,
                    untrusted.filter { it.stateKey == StateKey.NO_LAUNCHER } + calls(Capability.HIDE_ICON),
                )
        declaredWithGrant(listOf("android.permission.REQUEST_INSTALL_PACKAGES")).takeIf { it.isNotEmpty() }?.let {
            findings +=
                finding(RiskId.INSTALL_APPS, severity(Severity.ATTENTION), it + calls(Capability.INSTALL_PACKAGE), systemNote)
        }
        declaredWithGrant(listOf("android.permission.MANAGE_EXTERNAL_STORAGE")).takeIf { it.isNotEmpty() }?.let {
            findings += RiskFinding(RiskId.ALL_FILES, severity(Severity.ATTENTION), it, systemNote)
        }
        if (facts.usesCleartextTraffic == true && internet.isNotEmpty())
            findings +=
                RiskFinding(
                    RiskId.CLEARTEXT,
                    Severity.INFO,
                    listOf(Evidence(EvidenceLevel.DECLARED, EvidenceKind.FLAG, "usesCleartextTraffic", manifest, StateKey.CLEARTEXT)),
                )
        val signer = untrusted.filter { it.kind == EvidenceKind.CERTIFICATE }
        if (signer.isNotEmpty() && !system)
            findings += RiskFinding(RiskId.UNTRUSTED_SIGNER, Severity.ATTENTION, signer)
        if (code != null) {
            // Personal data sent to a hidden destination (bot, webhook): the clearest code evidence.
            val alreadyCovered = findings.map { it.id }.toSet()
            val theftSources =
                personalSources.filterNot {
                    (it == Capability.SMS_READ && RiskId.OTP_THEFT in alreadyCovered) ||
                        (it == Capability.SCREEN_READ && RiskId.SCREEN_SPYING in alreadyCovered)
                }
            val theft = flowsFrom(*theftSources.toTypedArray(), sinks = Capability.hiddenDestinations)
            if (theft.isNotEmpty() && !system)
                findings += finding(RiskId.DATA_THEFT, Severity.STRONG, theft + hiddenDestinations + untrusted)
            if (hiddenDestinations.isNotEmpty() || ipDestinations.isNotEmpty())
                findings += finding(RiskId.HIDDEN_DESTINATION, severity(Severity.ATTENTION), hiddenDestinations + ipDestinations)
            if (forwarding.isNotEmpty())
                findings += finding(RiskId.CALL_FORWARDING, severity(Severity.ATTENTION), forwarding)
            val dynamic =
                calls(Capability.DYNAMIC_CODE, Capability.SHELL) +
                    code.gaps
                        .filter { it.kind == GapKind.HIDDEN_CODE || it.kind == GapKind.PACKER }
                        .map { Evidence(EvidenceLevel.CODE, EvidenceKind.STRING, it.detail, it.kind.name) }
            if (dynamic.isNotEmpty())
                findings += finding(RiskId.DYNAMIC_CODE, severity(Severity.ATTENTION), dynamic, systemNote)
            val clipboard = calls(Capability.CLIPBOARD) + flowsFrom(Capability.CLIPBOARD)
            if (clipboard.isNotEmpty())
                findings +=
                    finding(
                        RiskId.CLIPBOARD,
                        if (clipboard.any { it.level == EvidenceLevel.FLOW }) severity(Severity.ATTENTION) else Severity.INFO,
                        clipboard,
                        systemNote,
                    )
            val cookies = calls(Capability.COOKIES)
            if (cookies.isNotEmpty())
                findings +=
                    finding(
                        RiskId.WEB_LOGIN,
                        severity(Severity.ATTENTION),
                        cookies + calls(Capability.JS_BRIDGE) + flowsFrom(Capability.COOKIES),
                        systemNote,
                    )
            if (code.sdks.isNotEmpty())
                findings +=
                    RiskFinding(
                        RiskId.AD_SDKS,
                        Severity.INFO,
                        code.sdks.map { Evidence(EvidenceLevel.CODE, EvidenceKind.SDK, it, "") },
                    )
            if (internet.isNotEmpty() && code.destinations.isNotEmpty())
                findings +=
                    RiskFinding(
                        RiskId.DESTINATIONS,
                        Severity.INFO,
                        code.destinations.take(12).map {
                            Evidence(EvidenceLevel.CODE, EvidenceKind.DESTINATION, "${it.host}|${it.cleartext}|${it.ipAddress}", "")
                        },
                    )
        }
        device?.network?.let { usage ->
            findings +=
                RiskFinding(
                    RiskId.NETWORK_USAGE,
                    if (usage.backgroundBytes >= HEAVY_BACKGROUND_BYTES && !system) Severity.ATTENTION
                    else Severity.INFO,
                    listOf(
                        Evidence(
                            EvidenceLevel.OBSERVED,
                            EvidenceKind.NETWORK,
                            "${usage.foregroundBytes}/${usage.backgroundBytes}/${usage.days}",
                            "",
                        )
                    ),
                    amount = usage.backgroundBytes,
                )
        }

        val ordered =
            findings.sortedWith(
                compareBy<RiskFinding>({ it.severity }, { -it.strongestLevel.ordinal }, { it.id })
            )
        return RiskReport(
            ordered,
            Coverage(
                apks = facts.apks.size,
                unreadable = facts.unreadable,
                unresolvedConfigs = facts.unresolvedConfigs,
                code = code?.let { CodeCoverage(it.dexFiles, it.classes, it.appClasses, it.gaps) },
                deviceStateRead = device != null,
                usageAccess = if (device == null) null else device.usageAccess,
            ),
        )
    }
}
