package com.speedy.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryAlertsTest {

    @Test
    fun warnsOncePerThreshold() {
        val alerts = BatteryAlerts()
        assertNull(alerts.update(50, charging = false))
        assertNull(alerts.update(11, charging = false))
        assertEquals(10, alerts.update(10, charging = false))
        assertNull(alerts.update(9, charging = false))
        assertNull(alerts.update(6, charging = false))
        assertEquals(5, alerts.update(5, charging = false))
        assertNull(alerts.update(4, charging = false))
        assertEquals(2, alerts.update(2, charging = false))
        assertNull(alerts.update(1, charging = false))
    }

    @Test
    fun startingBelowThresholdWarnsImmediately() {
        assertEquals(5, BatteryAlerts().update(4, charging = false))
    }

    @Test
    fun chargingResets() {
        val alerts = BatteryAlerts()
        assertEquals(10, alerts.update(8, charging = false))
        assertNull(alerts.update(8, charging = true))
        assertNull(alerts.update(9, charging = true))
        assertEquals(10, alerts.update(9, charging = false))
    }

    @Test
    fun ukrainianPercentWord() {
        assertEquals("відсоток", BatteryAlerts.percentWord(1))
        assertEquals("відсотки", BatteryAlerts.percentWord(2))
        assertEquals("відсотків", BatteryAlerts.percentWord(5))
        assertEquals("відсотків", BatteryAlerts.percentWord(10))
        assertEquals("відсотків", BatteryAlerts.percentWord(11))
        assertEquals("відсоток", BatteryAlerts.percentWord(21))
    }
}
