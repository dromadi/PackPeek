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

    fun evaluate(facts: ManifestFacts, context: RiskContext, device: DeviceState?): RiskReport {
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
        val system = context.system
        fun severity(normal: Severity) = if (system) Severity.INFO else normal
        val systemNote = if (system) ContextNote.SYSTEM_APP else ContextNote.NONE

        // Signals that the app or file did not come from a trusted, visible place.
        val untrusted = buildList {
            if (!facts.hasLauncher && !system)
                add(Evidence(EvidenceLevel.DECLARED, EvidenceKind.FLAG, "", manifest, StateKey.NO_LAUNCHER))
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
        val internet =
            if (facts.has(INTERNET))
                listOf(Evidence(EvidenceLevel.DECLARED, EvidenceKind.PERMISSION, INTERNET, "$manifest · ${facts.permissions[INTERNET]}"))
            else emptyList()

        // SMS / OTP
        val defaultSms = device?.defaultSmsApp == true
        val smsReceivers = facts.receiversFor(SMS_RECEIVED)
        val sms =
            declaredWithGrant(smsPermissions) +
                smsReceivers.map { component(it, " · priority ${it.maxPriority(SMS_RECEIVED)}") } +
                (if (defaultSms) listOf(observed(StateKey.DEFAULT_SMS_APP)) else emptyList())
        if (sms.isNotEmpty())
            findings +=
                RiskFinding(
                    RiskId.SMS,
                    if (defaultSms) Severity.INFO else severity(Severity.ATTENTION),
                    sms,
                    if (defaultSms) ContextNote.DEFAULT_SMS_APP else systemNote,
                )
        if (sms.isNotEmpty() && internet.isNotEmpty() && untrusted.isNotEmpty() && !defaultSms && !system)
            findings += RiskFinding(RiskId.OTP_THEFT, Severity.STRONG, sms + internet + untrusted)
        declaredWithGrant(listOf("android.permission.SEND_SMS")).takeIf { it.isNotEmpty() }?.let {
            findings +=
                RiskFinding(
                    RiskId.SMS_SEND,
                    if (defaultSms) Severity.INFO else severity(Severity.ATTENTION),
                    it,
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
            }
        if (accessibility.isNotEmpty())
            findings += RiskFinding(RiskId.ACCESSIBILITY, severity(Severity.ATTENTION), accessibility, systemNote)

        // Notifications, device admin, overlay
        val notifications =
            facts.servicesBoundBy(BIND_NOTIFICATIONS).map { component(it) } +
                (if (device?.notificationListenerEnabled == true)
                    listOf(observed(StateKey.NOTIFICATION_LISTENER_ENABLED))
                else emptyList())
        if (notifications.isNotEmpty())
            findings += RiskFinding(RiskId.NOTIFICATIONS, severity(Severity.ATTENTION), notifications, systemNote)
        val admins = facts.components.filter { it.kind == "receiver" && it.permission == BIND_DEVICE_ADMIN }
        val admin =
            admins.map { a ->
                val policies = facts.deviceAdminPolicies[a.name].orEmpty()
                component(a, if (policies.isEmpty()) "" else " · ${policies.joinToString()}")
            } + (if (device?.deviceAdminActive == true) listOf(observed(StateKey.DEVICE_ADMIN_ACTIVE)) else emptyList())
        if (admin.isNotEmpty())
            findings += RiskFinding(RiskId.DEVICE_ADMIN, severity(Severity.ATTENTION), admin, systemNote)
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
        val amplifiers =
            untrusted + overlay.filter { it.kind == EvidenceKind.PERMISSION } + admin + notifications
        if (accessibilityServices.isNotEmpty() && readsScreen && internet.isNotEmpty() && amplifiers.isNotEmpty() && !system)
            findings += RiskFinding(RiskId.SCREEN_SPYING, Severity.STRONG, accessibility + internet + amplifiers)

        // Other personal data and capabilities
        declaredWithGrant(contactPermissions).takeIf { it.isNotEmpty() }?.let {
            findings += RiskFinding(RiskId.CONTACTS_CALLS, severity(Severity.ATTENTION), it, systemNote)
        }
        declaredWithGrant(identityPermissions).takeIf { it.isNotEmpty() }?.let {
            findings += RiskFinding(RiskId.PHONE_IDENTITY, Severity.INFO, it, systemNote)
        }
        declaredWithGrant(cameraMicPermissions).takeIf { it.isNotEmpty() }?.let {
            findings += RiskFinding(RiskId.CAMERA_MIC, Severity.INFO, it, systemNote)
        }
        declaredWithGrant(locationPermissions).takeIf { it.isNotEmpty() }?.let {
            val background = facts.has("android.permission.ACCESS_BACKGROUND_LOCATION")
            findings +=
                RiskFinding(
                    RiskId.LOCATION,
                    if (background) severity(Severity.ATTENTION) else Severity.INFO,
                    it,
                    systemNote,
                )
        }
        val capture =
            declaredWithGrant(listOf("android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION")) +
                facts.components
                    .filter { it.kind == "service" && it.foregroundServiceType and MEDIA_PROJECTION_TYPE != 0 }
                    .map { component(it, " · mediaProjection") }
        if (capture.isNotEmpty())
            findings += RiskFinding(RiskId.SCREEN_CAPTURE, severity(Severity.ATTENTION), capture, systemNote)
        val persistence =
            facts.receiversFor(BOOT_COMPLETED).map { component(it, " · BOOT_COMPLETED") } +
                declaredWithGrant(listOf("android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS")) +
                (if (device?.batteryExempt == true) listOf(observed(StateKey.BATTERY_EXEMPT)) else emptyList())
        if (persistence.isNotEmpty())
            findings += RiskFinding(RiskId.PERSISTENCE, Severity.INFO, persistence, systemNote)
        if (!facts.hasLauncher && !system)
            findings +=
                RiskFinding(
                    RiskId.HIDDEN_ICON,
                    Severity.ATTENTION,
                    untrusted.filter { it.stateKey == StateKey.NO_LAUNCHER },
                )
        declaredWithGrant(listOf("android.permission.REQUEST_INSTALL_PACKAGES")).takeIf { it.isNotEmpty() }?.let {
            findings += RiskFinding(RiskId.INSTALL_APPS, severity(Severity.ATTENTION), it, systemNote)
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
                codeAnalyzed = false,
                deviceStateRead = device != null,
                usageAccess = if (device == null) null else device.usageAccess,
            ),
        )
    }
}
