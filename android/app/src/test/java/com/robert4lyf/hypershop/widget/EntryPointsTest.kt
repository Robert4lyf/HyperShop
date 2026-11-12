package com.robert4lyf.hypershop.widget

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.EntryRequest
import com.robert4lyf.hypershop.HyperShopApplication
import com.robert4lyf.hypershop.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/**
 * The widget's two taps and the launcher shortcut, up to the request the app shell takes: the item preview asks
 * for the list, Add item for the Add field, each with its own PendingIntent, from a cold start or an open app.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = HyperShopApplication::class)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class EntryPointsTest {
    private val app get() = ApplicationProvider.getApplicationContext<HyperShopApplication>()

    @Test
    fun thePreviewAndAddItemHaveTheirOwnIntents() {
        val taps = ShoppingWidget.intents(app)
        val list = shadowOf(taps.openList)
        val add = shadowOf(taps.add)
        assertEquals(EntryRequest.ACTION_OPEN_LIST, list.savedIntent.action)
        assertEquals(EntryRequest.ACTION_ADD, add.savedIntent.action)
        assertEquals(MainActivity::class.java.name, list.savedIntent.component?.className)
        assertEquals(MainActivity::class.java.name, add.savedIntent.component?.className)
        assertNotEquals(list.requestCode, add.requestCode)
        assertNotEquals(taps.openList, taps.add)
        for (s in listOf(list, add)) {
            assertEquals(Intent.FLAG_ACTIVITY_SINGLE_TOP, s.savedIntent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP)
            assertEquals(true, s.isImmutable)
        }
        // Built again (each refresh), the same two intents: neither replaces the other.
        val again = ShoppingWidget.intents(app)
        assertEquals(taps.openList, again.openList)
        assertEquals(taps.add, again.add)
    }

    @Test
    fun intentsReadAsTheirRequestAndNothingFromRecents() {
        assertEquals(EntryRequest.LIST, EntryRequest.from(Intent(EntryRequest.ACTION_OPEN_LIST)))
        assertEquals(EntryRequest.ADD, EntryRequest.from(Intent(EntryRequest.ACTION_ADD)))
        assertEquals(EntryRequest.ADD, EntryRequest.from(Intent(MainActivity.ACTION_ADD)))
        assertNull(EntryRequest.from(Intent(Intent.ACTION_MAIN)))
        assertNull(EntryRequest.from(null))
        val recents = Intent(EntryRequest.ACTION_OPEN_LIST).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        assertNull(EntryRequest.from(recents))
    }

    @Test
    fun theActivityPassesAColdStartAndALaterTapOnAsTheirOwnRequests() {
        val c = app.container
        val cold = ShoppingWidget.entryIntent(app, EntryRequest.ACTION_OPEN_LIST)
        val activity = Robolectric.buildActivity(MainActivity::class.java, cold).create()
        // Waiting for the app shell, which is composed only once start-up is ready.
        assertEquals(listOf(EntryRequest.LIST), c.entryRequests.replayCache)
        // Already open: Add item arrives as a new intent (single top) and replaces the waiting list request.
        activity.newIntent(ShoppingWidget.entryIntent(app, EntryRequest.ACTION_ADD))
        assertEquals(listOf(EntryRequest.ADD), c.entryRequests.replayCache)
        c.entryRequestHandled()
        // The task brought back from Recents carries the old intent: nothing is asked again.
        activity.newIntent(ShoppingWidget.entryIntent(app, EntryRequest.ACTION_ADD).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY))
        assertEquals(emptyList<EntryRequest>(), c.entryRequests.replayCache)
        activity.destroy()
    }
}
