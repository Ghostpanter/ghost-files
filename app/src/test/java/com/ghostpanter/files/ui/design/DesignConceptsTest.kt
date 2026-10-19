package com.ghostpanter.files.ui.design

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import com.android.resources.ScreenOrientation
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Concept mockups for redesign directions A/B/C — fake data only, no production Theme.
 * Snapshots land under app/src/test/snapshots/images/ as DesignConceptsTest_*.
 */
@RunWith(Parameterized::class)
class DesignConceptsTest(
    private val dir: DesignDir,
    private val variant: String,
    private val device: DeviceConfig,
) {
    companion object {
        init { java.util.Locale.setDefault(java.util.Locale.SIMPLIFIED_CHINESE) }

        @JvmStatic
        @Parameterized.Parameters(name = "{0}_{1}")
        fun data(): List<Array<Any>> {
            val phones = listOf(
                "phone_light" to DeviceConfig.PIXEL_5.copy(locale = "zh-rCN"),
                "phone_oled" to DeviceConfig.PIXEL_5.copy(nightMode = NightMode.NIGHT, locale = "zh-rCN"),
            )
            val tablet = "tablet" to DeviceConfig.PIXEL_C.copy(orientation = ScreenOrientation.LANDSCAPE, locale = "zh-rCN")
            val out = ArrayList<Array<Any>>()
            for (d in DesignDir.entries) {
                for ((v, cfg) in phones + tablet) out += arrayOf(d, v, cfg)
            }
            return out
        }
    }

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = device, showSystemUi = false, maxPercentDifference = 1.0)

    private val dark get() = device.nightMode == NightMode.NIGHT
    private val tablet get() = variant == "tablet"

    private fun snap(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        paparazzi.snapshot(name = "${dir.id}_$name") {
            DesignThemeWrap(dir, dark = dark, oled = dark, content = content)
        }
    }

    @Test fun main() = snap("main_$variant") { ConceptMain(dir, tablet = tablet, sideOpen = tablet) }

    @Test fun drawer() {
        if (tablet) return // drawer is phone chrome
        snap("drawer_$variant") { ConceptDrawer(dir) }
    }

    @Test fun devices() = snap("devices_$variant") { ConceptDevices(dir, tablet = tablet) }

    @Test fun terminal() = snap("terminal_$variant") { ConceptTerminal(dir, dark = dark || dir == DesignDir.C) }
}
