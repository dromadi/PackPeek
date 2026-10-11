package com.packpeek.app

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.packpeek.app.data.Settings
import com.packpeek.app.data.Signer
import com.packpeek.app.engine.risk.*
import com.packpeek.app.model.AppRecord
import com.packpeek.app.ui.*
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders screens to PNG on the JVM, so layouts can be checked in seconds without an emulator.
 * Output: app/build/screenshots/<name>.png. Each screen is rendered in light and dark, in
 * Indonesian, and with a large font, the setups where layouts break first.
 *
 * Run: ./gradlew :app:testDebugUnitTest --tests '*ScreenRenderTest*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h2400dp-xhdpi")
class ScreenRenderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val out = File("build/screenshots").apply { mkdirs() }

    private fun app(pkg: String, label: String, system: Boolean = false, installer: String = "com.android.vending", size: Long = 30_000_000) =
        AppRecord(pkg, label, "1.0", 1, 24, 34, system, "/data/app/$pkg/base.apk", emptyList(), size, 0, 0, installer, 10_100)

    private val apps =
        listOf(
            app("com.whatsapp", "WhatsApp", size = 120_000_000),
            app("com.insaver", "InSaver", size = 40_000_000),
            app("sim.undangan", "Undangan Pernikahan", installer = "", size = 2_000_000),
            app("com.bank", "Mobile Banking", size = 90_000_000),
            app("com.android.settings", "Settings", system = true, installer = ""),
            app("com.android.phone", "Phone", system = true, installer = ""),
        )

    private val undanganReport: RiskReport by lazy {
        val apk = File(requireNotNull(javaClass.getResource("/undangan-sim.apk")).toURI())
        RiskRules.evaluate(
            ManifestScanner.scan(listOf(apk)),
            RiskContext("Undangan Pernikahan", true, false, "SIDE", true, 1),
            null,
            DexScanner.scan(listOf(apk)),
        )
    }

    private fun render(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent {
            PackPeekTheme(Settings(theme = if (dark) "DARK" else "LIGHT", accent = "TEAL")) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        content()
                    }
                }
            }
        }
        compose.waitForIdle()
        // Draw the content view directly; captureToImage times out under Robolectric. The screen
        // is tall (h2400dp) so long content fits; the image is cropped to the drawn content.
        val view = compose.activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
        val full = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(full))
        val composeView = view as android.view.ViewGroup
        val content = composeView.getChildAt(0)?.height?.takeIf { it in 1..view.height } ?: view.height
        val bitmap = Bitmap.createBitmap(full, 0, 0, full.width, content)
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Composable
    private fun Overview() {
        SecurityCard(total = 4, checked = 4, strong = 1, attention = 1, checking = false, current = null, start = {}, again = {}, cancel = {})
        RowGroup(title = "Needs review") {
            ReviewLine(apps[2], RiskBrief.of(undanganReport)) {}
            GroupDivider()
            ReviewLine(apps[1], RiskBrief(0, 2, RiskId.SMS, EvidenceLevel.CODE, true)) {}
        }
        ActiveSection(ActiveServices(setOf("sim.undangan"), emptySet(), emptySet(), "com.android.phone"), apps.associateBy { it.packageName }) {}
        StatsRow(apps)
        AnalysisCard(
            apps,
            apps.associate { it.packageName to AppExtras(Signer(it.packageName.hashCode().toString(16), "Developer ${it.label.first()}"), setOf("arm64-v8a")) },
            emptyMap(),
        ) {}
    }

    @Composable
    private fun Findings() {
        RiskSummary(undanganReport)
        undanganReport.findings.take(3).forEach { FindingCard(it, codeGaps = true) }
        CoverageCard(undanganReport.coverage) {}
    }

    @Test fun overviewLight() = render("overview-light") { Overview() }

    @Test fun overviewDark() = render("overview-dark", dark = true) { Overview() }

    @Test
    @Config(qualifiers = "in-w411dp-h2400dp-xhdpi")
    fun overviewIndonesian() = render("overview-id") { Overview() }

    @Test fun findingsLight() = render("findings-light") { Findings() }

    @Test fun findingsDark() = render("findings-dark", dark = true) { Findings() }

    @Test
    @Config(qualifiers = "in-w411dp-h2400dp-xhdpi", fontScale = 1.3f)
    fun findingsIndonesianLargeFont() = render("findings-id-large") { Findings() }

    private val deepState: DeepState by lazy {
        val apk = File(requireNotNull(javaClass.getResource("/undangan-sim.apk")).toURI())
        val code = DexScanner.scan(listOf(apk), DexScanner.DEEP_TIME_LIMIT_MS, deep = true)
        val report =
            RiskRules.evaluate(
                ManifestScanner.scan(listOf(apk)),
                RiskContext("Undangan Pernikahan", true, false, "SIDE", true, 1),
                null,
                code,
            )
        DeepState(
            app = apps[2].copy(fromFile = true),
            report = report,
            code = code,
            lookup = com.packpeek.app.engine.HashLookup.Found(41, 3, 66, "trojan.smsthief/spymax"),
        )
    }

    @Composable
    private fun Deep() {
        // The screen is a LazyColumn; render its sections in a plain column for one tall image.
        Box(Modifier.height(5200.dp)) { DeepContent(deepState, deepState.app!!, {}, {}) }
    }

    @Test
    @Config(qualifiers = "w411dp-h5400dp-xhdpi")
    fun deepLight() = render("deep-light") { Deep() }

    @Test
    @Config(qualifiers = "in-w411dp-h5400dp-xhdpi")
    fun deepIndonesianDark() = render("deep-id-dark", dark = true) { Deep() }

    @Test
    fun deepButton() = render("deep-button") { DeepAnalysisButton {} }

    /** A whole phone screen (411 × 891 dp), for full-screen layouts such as the introduction. */
    private fun screen(name: String, dark: Boolean = false, content: @Composable () -> Unit) =
        render(name, dark) { Box(Modifier.requiredSize(411.dp, 891.dp)) { content() } }

    @Test
    @Config(qualifiers = "in-w411dp-h2400dp-xhdpi", fontScale = 1.15f)
    fun statsIndonesianLargeFont() =
        render("stats-id-large") {
            StatsRow(List(758) { app("p$it", "App $it", system = it >= 258, size = 50_000_000).let { a -> if (it < 200) a.copy(splitPaths = listOf("split.apk")) else a } })
        }

    private fun intro(step: Int, dark: Boolean = false) =
        screen("intro-id-$step${if (dark) "-dark" else ""}", dark) { Onboarding({}, {}, "Pilih folder", firstStep = step) }

    @Test @Config(qualifiers = "in-w411dp-h2400dp-xhdpi", fontScale = 1.15f) fun introOne() = intro(0)

    @Test @Config(qualifiers = "in-w411dp-h2400dp-xhdpi", fontScale = 1.15f) fun introTwo() = intro(1)

    @Test @Config(qualifiers = "in-w411dp-h2400dp-xhdpi", fontScale = 1.15f) fun introThree() = intro(2)

    @Test @Config(qualifiers = "in-w411dp-h2400dp-xhdpi", fontScale = 1.15f) fun introOneDark() = intro(0, dark = true)

    @Test
    @Config(qualifiers = "in-w411dp-h2400dp-xhdpi", fontScale = 1.15f)
    fun checkResultsIndonesian() =
        render("results-id") {
            SecurityCard(total = 258, checked = 120, strong = 1, attention = 9, checking = false, current = null, start = {}, again = {}, cancel = {})
            ResultChart(strong = 1, attention = 9, clear = 110, unchecked = 138)
            AnalysisProgress(ScanProgress(0.45f, 3, 8, false))
            AnalysisProgress(null)
        }

    @Test
    fun checkResultsDark() =
        render("results-dark", dark = true) {
            ResultChart(strong = 3, attention = 20, clear = 235, unchecked = 0)
            AnalysisProgress(ScanProgress(0.92f, 8, 8, true))
        }
}
