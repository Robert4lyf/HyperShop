package com.robert4lyf.hypershop.testutil

import java.io.File

/**
 * Where Roborazzi recordings go: `docs/screenshots/` at the repo root, so
 * tools/contact-sheet can put them on one page. Tests run with the module
 * directory (`android/app`) as the working directory.
 */
object Shots {
    private val dir: File = File(System.getProperty("user.dir"), "../../docs/screenshots").canonicalFile

    fun path(name: String): String {
        dir.mkdirs()
        return File(dir, name).path
    }
}
