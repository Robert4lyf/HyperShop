package com.robert4lyf.hypershop.testutil

import android.app.Application
import org.robolectric.TestLifecycleApplication
import java.lang.reflect.Method

/**
 * The Application for tests (robolectric.properties). The real one opens the
 * on-disk database and starts background work, which outlived tests and broke
 * the next one; tests build their own container with [TestContainer].
 *
 * After every test it winds those containers down ([TestContainer.windDown]): Robolectric calls [afterTest] once the
 * test and its rules have finished and before it resets SQLite, which is the one moment that is early enough for the
 * leftover work to finish and late enough that the test no longer needs it.
 */
class TestApplication : Application(), TestLifecycleApplication {
    override fun beforeTest(method: Method?) = Unit

    override fun prepareTest(test: Any?) = Unit

    override fun afterTest(method: Method?) = TestContainer.windDown()
}
