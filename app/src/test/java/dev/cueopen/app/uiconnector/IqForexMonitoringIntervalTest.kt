package dev.cueopen.app.uiconnector

import dev.cueopen.app.uiconnector.iqoption.IqForexMonitoringInterval
import org.junit.Assert.assertEquals
import org.junit.Test

class IqForexMonitoringIntervalTest {
    @Test fun defaultIsThirtySeconds() {
        assertEquals(30_000L, IqForexMonitoringInterval.milliseconds(null))
    }

    @Test fun clampsToFiveThroughThreeHundredSeconds() {
        assertEquals(5_000L, IqForexMonitoringInterval.milliseconds(1.0))
        assertEquals(300_000L, IqForexMonitoringInterval.milliseconds(600.0))
    }

    @Test fun nonFiniteValuesUseDefault() {
        assertEquals(30_000L, IqForexMonitoringInterval.milliseconds(Double.NaN))
    }
}
