package com.robert4lyf.hypershop.testutil

import org.junit.rules.ExternalResource

/**
 * For a test that builds no [TestContainer]: winds the last test's containers down (their scopes stopped) before the test
 * starts. Order it before the compose rule (`@get:Rule(order = 0)`), so the last container's
 * leftover work (Room's refresh of a database Robolectric has reset: "Illegal connection pointer") can't land in this
 * test as an uncaught exception.
 */
fun quietStart(): ExternalResource =
    object : ExternalResource() {
        override fun before() = TestContainer.windDown()
    }
