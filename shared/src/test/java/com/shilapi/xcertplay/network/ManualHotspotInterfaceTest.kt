package com.shilapi.xcertplay.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class ManualHotspotInterfaceTest {
    private fun ip(text: String) = InetAddress.getByName(text)

    @Test fun phoneHotspotInterfacesAreAccepted() {
        assertTrue(ManualHotspotManager.isLikelyHotspot("swlan0", ip("192.168.43.1")))
        assertTrue(ManualHotspotManager.isLikelyHotspot("ap0", ip("fe80::1")))
        assertTrue(ManualHotspotManager.isLikelyHotspot("wlan1", ip("10.0.0.1")))
    }

    @Test fun cellularLinksAreNeverTakenForTheHotspot() {
        // Seen on a Samsung phone with its hotspot off: Wi-Fi calling's IMS tunnel.
        assertFalse(ManualHotspotManager.isLikelyHotspot("epdg2", ip("2001:b400:e000::1")))
        assertFalse(ManualHotspotManager.isLikelyHotspot("ccmni0", ip("2001:db8::1")))
        assertTrue(ManualHotspotManager.EXCLUDED_INTERFACE_PREFIXES.any { "epdg2".startsWith(it) })
    }
}
