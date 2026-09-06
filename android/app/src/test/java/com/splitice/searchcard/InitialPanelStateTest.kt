package com.splitice.searchcard

import kotlin.test.*

class InitialPanelStateTest {
    @Test fun `a saved session starts connecting without a sign in prompt`() {
        val initial = initialPanelState(hasDashboard = true, hasSession = true)
        assertEquals("Connecting…", initial.status)
        assertFalse(initial.needsLogin)
        assertFalse(initial.needsDashboard)
        assertFalse(initial.connected, "Cached data must not enable controls before fresh state arrives")
    }

    @Test fun `an account without a session requests sign in`() {
        val initial = initialPanelState(hasDashboard = true, hasSession = false)
        assertEquals("Sign in to Home Assistant", initial.status)
        assertTrue(initial.needsLogin)
    }

    @Test fun `missing dashboard always starts setup`() {
        val initial = initialPanelState(hasDashboard = false, hasSession = true)
        assertTrue(initial.needsDashboard)
        assertFalse(initial.needsLogin)
    }
}
