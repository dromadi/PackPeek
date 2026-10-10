package com.packpeek.app.engine.risk

import com.packpeek.app.engine.ManifestRoot
import com.reandroid.arsc.chunk.TableBlock
import java.io.File
import java.util.zip.ZipFile

data class IntentFilterFacts(
    val actions: Set<String>,
    val categories: Set<String>,
    val priority: Int,
)

data class ComponentFacts(
    /** activity, activity-alias, service, receiver or provider. */
    val kind: String,
    val name: String,
    val exported: Boolean,
    val permission: String,
    val enabled: Boolean,
    val filters: List<IntentFilterFacts>,
    /** meta-data name to value; references to res/xml are resolved into [configs]. */
    val metaData: Map<String, String>,
    val foregroundServiceType: Int,
    /** APK the component was declared in, e.g. base.apk or split_feature.apk. */
    val apk: String,
) {
    val actions
        get() = filters.flatMap { it.actions }.toSet()

    fun maxPriority(action: String) =
        filters.filter { action in it.actions }.maxOfOrNull { it.priority } ?: 0
}

/** Settings from an accessibility service's res/xml configuration. */
data class AccessibilityConfig(
    val canRetrieveWindowContent: Boolean,
    val canPerformGestures: Boolean,
    /** accessibilityEventTypes bit mask; -1 (typeAllMask) means every event. */
    val eventTypes: Int,
)

/** Everything PackPeek reads from the manifests of every APK of one app (base and splits). */
data class ManifestFacts(
    val packageName: String,
    val apks: List<String>,
    val permissions: Map<String, String>,
    val components: List<ComponentFacts>,
    val usesCleartextTraffic: Boolean?,
    val hasNetworkSecurityConfig: Boolean,
    val debuggable: Boolean,
    val hasLauncher: Boolean,
    val accessibility: Map<String, AccessibilityConfig>,
    val deviceAdminPolicies: Map<String, List<String>>,
    /** Referenced res/xml configurations that could not be read. */
    val unresolvedConfigs: Int,
    /** Manifests that could not be parsed. */
    val unreadable: List<String>,
) {
    fun has(permission: String) = permission in permissions

    fun servicesBoundBy(permission: String) =
        components.filter { it.kind == "service" && it.permission == permission }

    fun receiversFor(action: String) =
        components.filter { it.kind == "receiver" && action in it.actions }
}

/** Reads and merges the manifests of [paths] (base first). Never executes APK code. */
object ManifestScanner {
    const val ACCESSIBILITY_META = "android.accessibilityservice"
    const val DEVICE_ADMIN_META = "android.app.device_admin"
    private val launcherCategories =
        setOf("android.intent.category.LAUNCHER", "android.intent.category.LEANBACK_LAUNCHER")

    fun scan(paths: List<File>): ManifestFacts {
        var pkg = ""
        val permissions = LinkedHashMap<String, String>()
        val components = mutableListOf<ComponentFacts>()
        val unreadable = mutableListOf<String>()
        var cleartext: Boolean? = null
        var nsc = false
        var debuggable = false
        var launcher = false
        paths.forEachIndexed { index, file ->
            val apkName = if (index == 0) "base.apk" else file.name
            val tree =
                runCatching { ManifestRoot.readTree(file) }
                    .getOrElse {
                        unreadable += apkName
                        return@forEachIndexed
                    }
            if (index == 0) pkg = tree["package"]?.asString().orEmpty()
            tree.child("uses-permission").plus(tree.child("uses-permission-sdk-23")).forEach {
                it["name"]?.asString()?.let { name -> permissions.putIfAbsent(name, apkName) }
            }
            tree.child("application").forEach { app ->
                app["usesCleartextTraffic"]?.asBoolean()?.let { cleartext = it }
                if (app["networkSecurityConfig"] != null) nsc = true
                if (app["debuggable"]?.asBoolean() == true) debuggable = true
                app.children
                    .filter {
                        it.name in
                            setOf("activity", "activity-alias", "service", "receiver", "provider")
                    }
                    .forEach { node ->
                        val component = component(node, pkg, apkName)
                        components += component
                        if (
                            component.kind.startsWith("activity") &&
                                component.enabled &&
                                component.filters.any { f ->
                                    f.categories.any { it in launcherCategories }
                                }
                        )
                            launcher = true
                    }
            }
        }
        val configs = resolveConfigs(paths.firstOrNull(), components)
        return ManifestFacts(
            packageName = pkg,
            apks = paths.mapIndexed { i, f -> if (i == 0) "base.apk" else f.name },
            permissions = permissions,
            components = components,
            usesCleartextTraffic = cleartext,
            hasNetworkSecurityConfig = nsc,
            debuggable = debuggable,
            hasLauncher = launcher,
            accessibility = configs.accessibility,
            deviceAdminPolicies = configs.adminPolicies,
            unresolvedConfigs = configs.unresolved,
            unreadable = unreadable,
        )
    }

    private fun component(node: XmlNode, pkg: String, apk: String): ComponentFacts {
        val filters =
            node.child("intent-filter").map { f ->
                IntentFilterFacts(
                    f.child("action").mapNotNull { it["name"]?.asString() }.toSet(),
                    f.child("category").mapNotNull { it["name"]?.asString() }.toSet(),
                    f["priority"]?.asInt() ?: 0,
                )
            }
        val name = ManifestRoot.qualify(pkg, node["name"]?.asString().orEmpty())
        return ComponentFacts(
            kind = node.name,
            name = name,
            // Before Android 12 the default was "exported when it has an intent filter".
            exported = node["exported"]?.asBoolean() ?: filters.isNotEmpty(),
            permission = node["permission"]?.asString().orEmpty(),
            enabled = node["enabled"]?.asBoolean() ?: true,
            filters = filters,
            metaData =
                node.child("meta-data").associate {
                    it["name"]?.asString().orEmpty() to
                        (it["resource"] ?: it["value"])?.asString().orEmpty()
                },
            foregroundServiceType = node["foregroundServiceType"]?.asInt() ?: 0,
            apk = apk,
        )
    }

    private class Configs(
        val accessibility: Map<String, AccessibilityConfig>,
        val adminPolicies: Map<String, List<String>>,
        val unresolved: Int,
    )

    /** Resolves res/xml references through resources.arsc of the base APK. */
    private fun resolveConfigs(base: File?, components: List<ComponentFacts>): Configs {
        val wanted =
            components.mapNotNull { c ->
                listOf(ACCESSIBILITY_META, DEVICE_ADMIN_META)
                    .firstOrNull { c.metaData[it]?.startsWith("@0x") == true }
                    ?.let { Triple(c.name, it, c.metaData.getValue(it)) }
            }
        if (wanted.isEmpty() || base == null) return Configs(emptyMap(), emptyMap(), 0)
        val accessibility = mutableMapOf<String, AccessibilityConfig>()
        val admin = mutableMapOf<String, List<String>>()
        var unresolved = 0
        runCatching {
                ZipFile(base).use { zip ->
                    val table =
                        zip.getEntry("resources.arsc")?.let { e ->
                            zip.getInputStream(e).use { TableBlock.load(it) }
                        }
                    for ((component, kind, ref) in wanted) {
                        val tree =
                            runCatching {
                                    val id = ref.removePrefix("@0x").toLong(16).toInt()
                                    val path =
                                        table
                                            ?.getResource(id)
                                            ?.iterator(true)
                                            ?.asSequence()
                                            ?.mapNotNull { it.valueAsString }
                                            ?.firstOrNull { it.endsWith(".xml") }
                                    path?.let { BinaryXml.readEntry(zip, it) }?.let(BinaryXml::parse)
                                }
                                .getOrNull()
                        if (tree == null) {
                            unresolved++
                            continue
                        }
                        if (kind == ACCESSIBILITY_META) accessibility[component] = accessibilityConfig(tree)
                        else
                            admin[component] =
                                tree.walk()
                                    .firstOrNull { it.name == "uses-policies" }
                                    ?.children
                                    ?.map { it.name }
                                    .orEmpty()
                    }
                }
            }
            .onFailure { unresolved = wanted.size }
        return Configs(accessibility, admin, unresolved)
    }

    fun accessibilityConfig(tree: XmlNode) =
        AccessibilityConfig(
            canRetrieveWindowContent = tree["canRetrieveWindowContent"]?.asBoolean() ?: false,
            canPerformGestures = tree["canPerformGestures"]?.asBoolean() ?: false,
            eventTypes = tree["accessibilityEventTypes"]?.asInt() ?: 0,
        )
}
