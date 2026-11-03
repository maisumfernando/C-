package com.fernandogodinho.teclado

import com.fernandogodinho.teclado.ime.PrivacyApps
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyAppsTest {
    @Test
    fun detectsPrivacyFocusedApps() {
        assertTrue(PrivacyApps.matches("org.mozilla.focus"))
        assertTrue(PrivacyApps.matches("org.mozilla.klar"))
        assertTrue(PrivacyApps.matches("com.duckduckgo.mobile.android"))
        assertTrue(PrivacyApps.matches("org.torproject.torbrowser"))
        assertTrue(PrivacyApps.matches("org.thoughtcrime.securesms"))
        assertTrue(PrivacyApps.matches("com.x8bit.bitwarden"))
    }

    @Test
    fun leavesRegularAppsAlone() {
        assertFalse(PrivacyApps.matches("com.whatsapp"))
        assertFalse(PrivacyApps.matches("com.android.chrome"))
        assertFalse(PrivacyApps.matches(null))
    }
}
