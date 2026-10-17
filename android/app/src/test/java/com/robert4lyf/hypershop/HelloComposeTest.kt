package com.robert4lyf.hypershop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.robert4lyf.hypershop.testutil.Shots
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Step 0.4: Robolectric Compose with a semantics assertion, plus a Roborazzi recording. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class HelloComposeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun semanticsAssertion() {
        compose.setContent { MaterialTheme { Text("Hello HyperShop") } }
        compose.onNodeWithText("Hello HyperShop").assertIsDisplayed()
    }

    @Test
    fun roborazziRecording() {
        compose.setContent { MaterialTheme { Text("Hello HyperShop") } }
        compose.onRoot().captureRoboImage(Shots.path("hello.png"))
    }
}
