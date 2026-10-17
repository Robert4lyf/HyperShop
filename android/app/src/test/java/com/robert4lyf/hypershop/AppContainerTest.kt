package com.robert4lyf.hypershop

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Step 0.4: the Application builds its AppContainer under Robolectric. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34, 36], application = HyperShopApplication::class)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class AppContainerTest {
    @Test
    fun containerIsBuilt() {
        val app = ApplicationProvider.getApplicationContext<HyperShopApplication>()
        assertNotNull(app.container)
        assertNotNull(app.container.applicationScope)
    }
}
