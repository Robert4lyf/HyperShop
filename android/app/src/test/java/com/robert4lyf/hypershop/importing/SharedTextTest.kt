package com.robert4lyf.hypershop.importing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Share payloads in the shapes each app sends (EXTRA_TEXT, EXTRA_SUBJECT).
 * Reconstructed from the apps' documented behaviour; Hand-off A checks real ones.
 */
class SharedTextTest {
    private fun url(
        text: String?,
        subject: String? = null,
    ) = SharedText.parse(text, subject)?.url

    @Test
    fun chromeSendsTheBareUrlAndTheTitleAsSubject() {
        val i = SharedText.parse("https://www.bbcgoodfood.com/recipes/chilli-con-carne-recipe", "Chilli con carne recipe | BBC Good Food")
        assertEquals("https://www.bbcgoodfood.com/recipes/chilli-con-carne-recipe", i!!.url)
        assertEquals("Chilli con carne recipe | BBC Good Food", i.subject)
    }

    @Test
    fun firefoxAndSamsungPutTheTitleBeforeTheUrl() {
        assertEquals(
            "https://www.recipetineats.com/chicken-chow-mein/",
            url("Chicken Chow Mein | RecipeTin Eats\nhttps://www.recipetineats.com/chicken-chow-mein/"),
        )
        assertEquals(
            "https://www.allrecipes.com/recipe/24074/alysias-basic-meat-lasagna/",
            url("Alysia's Basic Meat Lasagna https://www.allrecipes.com/recipe/24074/alysias-basic-meat-lasagna/?utm_source=share"),
        )
    }

    @Test
    fun instagramAndYouTubeTrackingIsStrippedButOtherParamsKept() {
        assertEquals("https://www.instagram.com/reel/C9xYz/", url("https://www.instagram.com/reel/C9xYz/?igsh=MWQ1ZGUxMzBkMA=="))
        assertEquals("https://www.instagram.com/p/AbC/", url("https://www.instagram.com/p/AbC/?igshid=abc&utm_medium=copy_link"))
        assertEquals("https://youtu.be/dQw4w9WgXcQ?si=Xy12", url("https://youtu.be/dQw4w9WgXcQ?si=Xy12"))
        assertEquals(
            "https://www.youtube.com/watch?v=abc&t=30#comments",
            url("Watch this https://www.youtube.com/watch?utm_campaign=x&v=abc&t=30#comments"),
        )
    }

    @Test
    fun prosePunctuationAndBracketsAroundTheUrl() {
        assertEquals("https://example.com/cake", url("Try this (https://example.com/cake)."))
        assertEquals("https://en.wikipedia.org/wiki/Bread_(food)", url("See https://en.wikipedia.org/wiki/Bread_(food), it's good"))
        assertEquals("https://a.example/x", url("first https://a.example/x then https://b.example/y"))
    }

    @Test
    fun onlyHttpsLinksAndASubjectThatIsJustTheUrlIsNoTitle() {
        assertNull(url("http://example.com/recipe"))
        assertNull(url("content://media/1 or file:///sdcard/x.html"))
        assertNull(url("no link here"))
        assertNull(url(null))
        assertNull(SharedText.parse("https://example.com/r", "https://example.com/r")!!.subject)
    }
}
