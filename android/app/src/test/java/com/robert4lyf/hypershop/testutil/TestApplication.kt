package com.robert4lyf.hypershop.testutil

import android.app.Application

/**
 * The Application for tests (robolectric.properties). The real one opens the
 * on-disk database and starts background work, which outlived tests and broke
 * the next one; tests build their own container with [TestContainer].
 */
class TestApplication : Application()
