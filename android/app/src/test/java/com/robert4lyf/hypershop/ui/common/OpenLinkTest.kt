package com.robert4lyf.hypershop.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenLinkTest {
    @Test
    fun onlyWebAddressesAreLinks() {
        assertEquals("https://www.recipetineats.com/x/", webLink(" https://www.recipetineats.com/x/ "))
        assertEquals("http://example.com/r", webLink("http://example.com/r"))
        assertNull(webLink(null))
        assertNull(webLink(""))
        assertNull(webLink("javascript:alert(1)"))
        assertNull(webLink("file:///data/data/x"))
        assertNull(webLink("intent://x#Intent;end"))
    }
}
