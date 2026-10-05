package org.kalc.ecoroute

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Requires internet and the Tailscale connection for the private Eco Route service. */
@LargeTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class PlanningFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun selectWorldwideAddressesAndCalculateRoundTrip() {
        shell("wm size 945x2100") // 360 dp at the AVD's 420 dpi density.
        shell("settings put system font_scale 1.3")
        shell("settings put global window_animation_scale 0")
        shell("settings put global transition_animation_scale 0")
        shell("settings put global animator_duration_scale 0")
        shell("settings put secure show_ime_with_hard_keyboard 1")
        try {
            Thread.sleep(CONFIGURATION_SETTLE_MS)
            runPlanningFlow()
        } finally {
            shell("settings put system user_rotation 0")
            shell("settings put system accelerometer_rotation 1")
            shell("cmd uimode night no")
            shell("settings put system font_scale 1.0")
            shell("settings put global window_animation_scale 1")
            shell("settings put global transition_animation_scale 1")
            shell("settings put global animator_duration_scale 1")
            shell("settings put secure show_ime_with_hard_keyboard 0")
            shell("wm size reset")
        }
    }

    private fun runPlanningFlow() {
        val setupButtons = compose.onAllNodes(hasText("Save and continue")).fetchSemanticsNodes()
        if (setupButtons.isNotEmpty()) {
            compose.onNodeWithTag("vehicle-catalogue-fuel-diesel").performClick()
            compose.onNodeWithTag("vehicle-catalogue-query").performTextInput("XC90 B5")
            compose.waitUntilExactlyOneExists(hasTestTag("catalogue-result-eea2024-901f4bc05bfda76da5361896"), ADDRESS_TIMEOUT_MS)
            shell("input keyevent 4")
            compose.onNodeWithTag("catalogue-result-eea2024-901f4bc05bfda76da5361896").performScrollTo()
            saveScreenshot("v050-catalogue-xc90-diesel-360")
            compose.onNodeWithTag("catalogue-result-eea2024-901f4bc05bfda76da5361896").performScrollTo().performClick()
            compose.waitUntilExactlyOneExists(hasTestTag("vehicle-body-summary"), 5_000)
            compose.onNodeWithTag("vehicle-body-summary").performScrollTo()
            compose.onNodeWithText("Large SUV / van · generic physical estimate").assertExists()
            compose.onNodeWithTag("vehicle-powertrain-summary").assertExists()
            assertTrue(compose.onAllNodes(hasTestTag("vehicle-body-large_suv")).fetchSemanticsNodes().isEmpty())
            saveScreenshot("v050-catalogue-autofilled-assumptions-360")
            compose.waitForIdle()
            compose.onNodeWithText("Save and continue").performScrollTo().performClick()
            compose.waitUntilExactlyOneExists(hasTestTag("start-destination-search"), 5_000)
            compose.onNodeWithTag("sheet-toggle").performScrollTo().performClick()
            compose.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText("Car for this trip"))
            saveScreenshot("v050-plan-saved-catalogue-360")
            compose.onNodeWithTag("sheet-toggle").performScrollTo().performClick()
        }
        val idleTiles = waitForTiles()
        assertTrue("Expected real map tiles and attribution before idle capture; got $idleTiles", idleTiles.hasLoadedTiles())
        Thread.sleep(1_000)
        saveScreenshot("v060-idle-360-font130")
        compose.onNodeWithTag("planner-sheet").performScrollToIndex(0)
        compose.onNodeWithTag("sheet-toggle").performClick()
        compose.onNodeWithText("Return to start").performClick()
        compose.onNodeWithTag("sheet-toggle").performClick()

        compose.onNodeWithTag("start-destination-search").performClick()
        compose.onNodeWithTag("destination-field").performTextInput("Glavni trg 1, Kranj")
        compose.waitUntilExactlyOneExists(hasText("Glavni trg 1"), ADDRESS_TIMEOUT_MS)
        Thread.sleep(500)
        saveScreenshot("v060-destination-search-keyboard-360-font130")
        compose.onNodeWithText("Glavni trg 1").performClick()
        Thread.sleep(500)
        saveScreenshot("v060-origin-second-360-font130")

        compose.onNodeWithTag("origin-field").performTextInput("Kongresni trg 12, Ljubljana")
        compose.waitUntilExactlyOneExists(hasText("Congress Square 12"), ADDRESS_TIMEOUT_MS)
        compose.onNodeWithText("Congress Square 12").performClick()

        compose.waitUntilExactlyOneExists(hasText("Round-trip total", substring = true), ROUND_TRIP_TIMEOUT_MS)
        val collapsedMap = waitForMap()
        assertTrue("Expected the calculated route and real map tiles before result capture; got $collapsedMap", collapsedMap.isComplete())
        Thread.sleep(500)
        saveScreenshot("v060-results-collapsed-360-font130")
        compose.onNodeWithTag("sheet-toggle").performClick()
        compose.waitUntilExactlyOneExists(hasText("A → B · Outbound"), ROUND_TRIP_TIMEOUT_MS)
        saveScreenshot("v060-results-expanded-top-360-font130")
        compose.onNodeWithTag("planner-sheet").performScrollToNode(hasTestTag("allowance-percent-10"))
        compose.onNodeWithTag("allowance-percent-10").assertExists()
        val outboundAllowance = allowanceLabel()
        Thread.sleep(500)
        saveScreenshot("v060-results-expanded-360-font130")
        saveScreenshot("roundtrip-outbound-results-percent")
        compose.onNodeWithTag("planner-sheet").performScrollToNode(hasText("B → A · Return"))
        compose.onNodeWithText("B → A · Return").performClick()
        compose.onNodeWithText("B → A · Return").assertIsSelected()
        compose.onNodeWithTag("planner-sheet").performScrollToNode(hasTestTag("allowance-percent-10"))
        val returnAllowance = allowanceLabel()
        assertNotEquals("Each direction must expose its independently calculated percentage minutes", outboundAllowance, returnAllowance)
        saveScreenshot("roundtrip-return-results-percent")
        compose.onNodeWithTag("planner-sheet").performScrollToIndex(0)
        compose.onNodeWithTag("sheet-toggle").performClick()
        compose.waitForIdle()

        val light = waitForMap()
        assertTrue("Expected return route, speed inspector, B marker, and loaded tiles; got $light", light.isComplete())
        saveEvidence("roundtrip-return-map-narrow-light", light)

        shell("cmd uimode night yes")
        Thread.sleep(CONFIGURATION_SETTLE_MS)
        val dark = waitForMap()
        assertTrue("Dark map did not recover after configuration change: $dark", dark.isComplete())
        saveEvidence("roundtrip-return-map-narrow-dark", dark)

        shell("settings put system accelerometer_rotation 0")
        shell("settings put system user_rotation 1")
        Thread.sleep(CONFIGURATION_SETTLE_MS)
        compose.onNodeWithTag("landscape-layout").assertIsDisplayed()
        val landscape = waitForMap()
        assertTrue("Landscape map did not recover after rotation: $landscape", landscape.isComplete())
        saveEvidence("roundtrip-return-map-landscape-dark", landscape)

        val webView = waitForWebView()
        val runtime = evaluate(
            webView,
            "location.href+'|'+document.readyState+'|'+typeof L+'|'+typeof renderRoute+'|'+" +
                "document.querySelectorAll('.leaflet-control-zoom a').length+'|'+" +
                "document.querySelectorAll('img.leaflet-tile').length+'|'+" +
                "document.getElementById('map').getBoundingClientRect().width+'x'+" +
                "document.getElementById('map').getBoundingClientRect().height+'|'+" +
                "getComputedStyle(document.getElementById('map')).display+'|'+" +
                "document.getElementById('tile-status').dataset.state",
        )
        val body = evaluate(
            webView,
            "JSON.stringify({mode:window.EcoMapState&&'loaded',body:document.body.innerText.slice(0,200)})",
        )
        val layers = evaluate(
            webView,
            "(()=>{const t=document.querySelector('img.leaflet-tile');" +
                "const p=document.querySelector('.speed-route-hit');" +
                "const z=document.querySelector('.leaflet-control-zoom');" +
                "const a=document.getElementById('osm-attribution');" +
                "const r=x=>x?JSON.stringify({display:getComputedStyle(x).display,visibility:getComputedStyle(x).visibility," +
                "opacity:getComputedStyle(x).opacity,rect:[x.getBoundingClientRect().x,x.getBoundingClientRect().y," +
                "x.getBoundingClientRect().width,x.getBoundingClientRect().height]}):'null';" +
                "return JSON.stringify({tile:r(t),path:r(p),zoom:r(z),attribution:r(a)});})()",
        )
        saveText("v060-webview-diagnostics", "runtime=$runtime\nbody=$body\nlayers=$layers\n")

        compose.onNodeWithTag("edit-destination").performClick()
        Thread.sleep(CONFIGURATION_SETTLE_MS)
        compose.onNodeWithTag("destination-field").assertIsDisplayed().performClick()
            .performTextReplacement("Glavni trg 1, Kranj")
        compose.waitUntilExactlyOneExists(hasText("Glavni trg 1"), ADDRESS_TIMEOUT_MS)
        Thread.sleep(500)
        saveScreenshot("v060-destination-search-keyboard-landscape-font130")
        compose.onNodeWithText("Glavni trg 1").performScrollTo().assertIsDisplayed()
        saveScreenshot("v060-destination-suggestion-keyboard-landscape-font130")
        compose.onNodeWithText("Glavni trg 1").performClick()
    }

    private data class MapEvidence(val route: String, val tiles: String) {
        override fun toString() = "route=$route tiles=$tiles"
    }

    private fun MapEvidence.isComplete() = route.hasRenderedMap() && tiles.hasLoadedTiles()

    private fun waitForMap(): MapEvidence {
        val webView = waitForWebView()
        val deadline = System.currentTimeMillis() + MAP_TIMEOUT_MS
        var evidence = MapEvidence("", "")
        while (System.currentTimeMillis() < deadline && !evidence.isComplete()) {
            val route = evaluate(webView,
                "(()=>{const m=document.getElementById('map').getBoundingClientRect(),p=document.querySelector('.speed-route-hit'),s=p&&p.ownerSVGElement&&p.ownerSVGElement.getBoundingClientRect();return " +
                    "document.querySelectorAll('.speed-route-hit').length+'|' +" +
                    "document.getElementById('speed-value').textContent+'|' +" +
                    "document.getElementById('map-tools').classList.contains('visible')+'|' +" +
                    "document.querySelectorAll('.place-pin').length+'|' +" +
                    "document.querySelectorAll('.alternative-route').length+'|' +" +
                    "m.width+'|'+m.height+'|'+Boolean(s&&s.right>m.left&&s.left<m.right&&s.bottom>m.top&&s.top<m.bottom)})()")
            val tiles = evaluate(webView,
                "(()=>{const i=[...document.querySelectorAll('img.leaflet-tile')],m=document.getElementById('map').getBoundingClientRect();" +
                    "const v=i.filter(x=>{const r=x.getBoundingClientRect();return x.complete&&x.naturalWidth===256&&x.naturalHeight===256&&r.right>m.left&&r.left<m.right&&r.bottom>m.top&&r.top<m.bottom});" +
                    "return i.length+'|'+i.filter(x=>x.complete&&x.naturalWidth===256&&x.naturalHeight===256).length+'|'+v.length+'|' +" +
                    "document.getElementById('tile-status').dataset.state+'|' +" +
                    "getComputedStyle(document.getElementById('osm-attribution')).display+'|' +" +
                    "m.width+'|'+m.height})()")
            evidence = MapEvidence(route, tiles)
            if (!evidence.isComplete()) Thread.sleep(250)
        }
        return evidence
    }

    private fun waitForTiles(): String {
        val webView = waitForWebView()
        val deadline = System.currentTimeMillis() + MAP_TIMEOUT_MS
        var tiles = ""
        while (System.currentTimeMillis() < deadline && !tiles.hasLoadedTiles()) {
            tiles = evaluate(webView,
                "(()=>{const i=[...document.querySelectorAll('img.leaflet-tile')],m=document.getElementById('map').getBoundingClientRect();" +
                    "const v=i.filter(x=>{const r=x.getBoundingClientRect();return x.complete&&x.naturalWidth===256&&x.naturalHeight===256&&r.right>m.left&&r.left<m.right&&r.bottom>m.top&&r.top<m.bottom});" +
                    "return i.length+'|'+i.filter(x=>x.complete&&x.naturalWidth===256&&x.naturalHeight===256).length+'|'+v.length+'|' +" +
                    "document.getElementById('tile-status').dataset.state+'|' +" +
                    "getComputedStyle(document.getElementById('osm-attribution')).display+'|' +" +
                    "m.width+'|'+m.height})()")
            if (!tiles.hasLoadedTiles()) Thread.sleep(250)
        }
        return tiles
    }

    private fun waitForWebView(): WebView {
        val deadline = System.currentTimeMillis() + MAP_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val webView = AtomicReference<WebView?>()
            compose.activityRule.scenario.onActivity { activity ->
                webView.set(findWebView(activity.findViewById(android.R.id.content)))
            }
            webView.get()?.let { return it }
            Thread.sleep(250)
        }
        error("Map WebView was not attached")
    }

    private fun String.hasRenderedMap(): Boolean {
        val parts = removeSurrounding("\"").split('|')
        return (parts.getOrNull(0)?.toIntOrNull() ?: 0) > 0 &&
            parts.getOrNull(1)?.let { it.isNotBlank() && it != "—" } == true &&
            parts.getOrNull(2) == "true" && (parts.getOrNull(3)?.toIntOrNull() ?: 0) >= 2 &&
            (parts.getOrNull(5)?.toDoubleOrNull() ?: 0.0) > 100.0 &&
            (parts.getOrNull(6)?.toDoubleOrNull() ?: 0.0) > 100.0 && parts.getOrNull(7) == "true"
    }

    private fun String.hasLoadedTiles(): Boolean {
        val parts = removeSurrounding("\"").split('|')
        return (parts.getOrNull(0)?.toIntOrNull() ?: 0) > 0 &&
            (parts.getOrNull(1)?.toIntOrNull() ?: 0) > 0 &&
            (parts.getOrNull(2)?.toIntOrNull() ?: 0) > 0 &&
            parts.getOrNull(3) == "loaded" && parts.getOrNull(4) != "none" &&
            (parts.getOrNull(5)?.toDoubleOrNull() ?: 0.0) > 100.0 &&
            (parts.getOrNull(6)?.toDoubleOrNull() ?: 0.0) > 100.0
    }

    private fun evaluate(webView: WebView, script: String): String {
        val result = AtomicReference("")
        val latch = CountDownLatch(1)
        compose.runOnUiThread {
            webView.evaluateJavascript(script) { result.set(it); latch.countDown() }
        }
        check(latch.await(3, TimeUnit.SECONDS)) { "WebView did not answer JavaScript inspection" }
        return result.get()
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findWebView(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun allowanceLabel(): String = compose
        .onNodeWithTag("allowance-percent-10")
        .fetchSemanticsNode()
        .config[SemanticsProperties.Text]
        .joinToString(" ") { it.text }

    private fun saveEvidence(name: String, evidence: MapEvidence) {
        saveScreenshot(name)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = checkNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        File(directory, "$name-evidence.txt").writeText(
            "route paths, speed, markers, alternatives, map size; requested tiles, loaded 256px tiles, status, attribution, map size: $evidence\n")
    }

    private fun saveScreenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = checkNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun saveText(name: String, content: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = checkNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        File(directory, "$name.txt").writeText(content)
    }

    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        }
    }

    companion object {
        private const val ADDRESS_TIMEOUT_MS = 20_000L
        private const val ROUND_TRIP_TIMEOUT_MS = 17 * 60_000L
        private const val MAP_TIMEOUT_MS = 30_000L
        private const val CONFIGURATION_SETTLE_MS = 2_000L
    }
}
