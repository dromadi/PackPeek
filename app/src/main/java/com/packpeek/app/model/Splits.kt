package com.packpeek.app.model

import java.util.Locale

enum class SplitKind {
    BASE,
    ABI,
    DENSITY,
    LOCALE,
    FEATURE,
    OTHER,
}

/** One APK of a split set; [module] is "" for the base module. */
data class SplitPart(
    val name: String,
    val kind: SplitKind,
    val module: String,
    val value: String,
    val recommended: Boolean,
    val bytes: Long = 0,
)

object SplitPlanner {
    private val abis = setOf("arm64_v8a", "armeabi_v7a", "armeabi", "x86_64", "x86", "mips", "mips64")
    private val densities =
        mapOf(
            "ldpi" to 120,
            "mdpi" to 160,
            "tvdpi" to 213,
            "hdpi" to 240,
            "xhdpi" to 320,
            "xxhdpi" to 480,
            "xxxhdpi" to 640,
        )
    private val localePattern = Regex("^[a-z]{2,3}([_-][A-Za-z0-9]{2,8})*$")

    fun parse(name: String): Triple<SplitKind, String, String> {
        if (name.isBlank()) return Triple(SplitKind.BASE, "", "")
        val (module, value) =
            when {
                name.startsWith("config.") -> "" to name.removePrefix("config.")
                ".config." in name -> name.substringBefore(".config.") to name.substringAfter(".config.")
                else -> return Triple(SplitKind.FEATURE, name, "")
            }
        val kind =
            when {
                value in abis -> SplitKind.ABI
                value in densities -> SplitKind.DENSITY
                localePattern.matches(value) -> SplitKind.LOCALE
                else -> SplitKind.OTHER
            }
        return Triple(kind, module, value)
    }

    /** Android still reports some languages under legacy codes; compare on one spelling. */
    private fun language(tag: String): String =
        when (val l = tag.substringBefore('_').substringBefore('-').lowercase(Locale.ROOT)) {
            "in" -> "id"
            "iw" -> "he"
            "ji" -> "yi"
            else -> l
        }

    /** Picks what bundletool would serve this device: best ABI, nearest density, own languages. */
    fun plan(
        names: List<String>,
        deviceAbis: List<String>,
        densityDpi: Int,
        languages: List<String>,
    ): List<SplitPart> {
        val parsed = names.map { it to parse(it) }
        val wanted = languages.map(::language).toSet()
        val chosen = mutableSetOf<String>()
        parsed.groupBy { it.second.second }.forEach { (_, parts) ->
            fun of(kind: SplitKind) = parts.filter { it.second.first == kind }
            val abi = of(SplitKind.ABI)
            runCatching { AbiSelector.choose(abi.map { it.second.third }.toSet(), deviceAbis) }
                .getOrNull()
                ?.let { best -> abi.filter { it.second.third == best }.forEach { chosen += it.first } }
            of(SplitKind.DENSITY)
                .minWithOrNull(
                    compareBy<Pair<String, Triple<SplitKind, String, String>>> {
                            kotlin.math.abs(densities.getValue(it.second.third) - densityDpi)
                        }
                        .thenByDescending { densities.getValue(it.second.third) }
                )
                ?.let { chosen += it.first }
            val locales = of(SplitKind.LOCALE)
            val own = locales.filter { language(it.second.third) in wanted }
            (own.ifEmpty { locales.filter { language(it.second.third) == "en" } }).forEach {
                chosen += it.first
            }
            parts
                .filter { it.second.first in setOf(SplitKind.BASE, SplitKind.FEATURE, SplitKind.OTHER) }
                .forEach { chosen += it.first }
        }
        return parsed.map { (name, p) -> SplitPart(name, p.first, p.second, p.third, name in chosen) }
    }

    /** Base is always kept; config splits of a deselected feature are dropped with it. */
    fun resolve(parts: List<SplitPart>, selected: Set<String>): Set<String> {
        val features =
            parts.filter { it.kind == SplitKind.FEATURE && it.name in selected }.map { it.module }.toSet()
        return parts
            .filter {
                it.kind == SplitKind.BASE ||
                    (it.name in selected &&
                        (it.kind == SplitKind.FEATURE || it.module.isEmpty() || it.module in features))
            }
            .map { it.name }
            .toSet()
    }

    /** True when a selected module ships native code only in ABI splits and none is selected. */
    fun missingAbi(parts: List<SplitPart>, selected: Set<String>): Boolean {
        val chosen = resolve(parts, selected)
        val modules = parts.filter { it.name in chosen }.map { it.module }.toSet()
        return modules.any { module ->
            val abi = parts.filter { it.module == module && it.kind == SplitKind.ABI }
            abi.isNotEmpty() && abi.none { it.name in chosen }
        }
    }
}

/**
 * APK entries to import from a ZIP. Bundletool `.apks` archives also carry standalone APKs for
 * pre-Lollipop devices next to `splits/`; those are alternative full builds, not parts.
 */
fun archiveApkEntries(names: List<String>): List<String> {
    val apks = names.filter { it.endsWith(".apk", true) && !it.endsWith("/") }
    return if (apks.any { it.startsWith("splits/") }) apks.filterNot { it.startsWith("standalones/") }
    else apks
}
