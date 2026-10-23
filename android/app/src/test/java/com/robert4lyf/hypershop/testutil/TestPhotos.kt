package com.robert4lyf.hypershop.testutil

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import java.io.File

/** A stand-in recipe photo for screenshots: a bowl of something red on a wooden table, drawn. */
object TestPhotos {
    val dish: String by lazy {
        val b = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(b)
        canvas.drawColor(android.graphics.Color.rgb(150, 98, 58))
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = android.graphics.Color.rgb(120, 76, 44)
        for (y in 0 until 600 step 60) canvas.drawRect(0f, y.toFloat(), 800f, y + 6f, p)
        p.color = android.graphics.Color.rgb(242, 238, 230)
        canvas.drawCircle(400f, 300f, 230f, p)
        p.color = android.graphics.Color.rgb(176, 42, 28)
        canvas.drawCircle(400f, 300f, 180f, p)
        p.color = android.graphics.Color.rgb(250, 246, 236)
        canvas.drawCircle(450f, 260f, 40f, p)
        p.color = android.graphics.Color.rgb(70, 140, 60)
        for ((x, y) in listOf(340f to 250f, 380f to 360f, 470f to 340f, 320f to 320f)) canvas.drawCircle(x, y, 14f, p)
        File.createTempFile("dish", ".jpg").apply {
            deleteOnExit()
            outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        }.path
    }

    /** Photos are decoded off the main thread: give them time to arrive before a picture is taken. */
    fun settle(compose: androidx.compose.ui.test.junit4.ComposeContentTestRule) {
        Thread.sleep(500)
        compose.waitForIdle()
    }
}
