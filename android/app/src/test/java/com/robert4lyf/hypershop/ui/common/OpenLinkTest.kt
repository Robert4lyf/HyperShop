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

    @Test
    fun siteNamesAreShortLowerCaseHosts() {
        assertEquals("bbcgoodfood.com", siteName("https://WWW.BBCGoodFood.com/recipes/x"))
        assertEquals("cooking.nytimes.com", siteName("https://cooking.nytimes.com/recipes/1"))
        assertEquals("example.com", siteName("http://example.com:8080/r"))
        assertNull(siteName("not a url"))
        assertNull(siteName("https:///nohost"))
    }
}
