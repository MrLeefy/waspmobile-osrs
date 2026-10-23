package com.example.waspmobile.vision

import java.io.File

/** Private user screenshots are external test inputs, never fabricated or silently skipped. */
internal object ScreenshotFixtures {
    private val names = listOf(
        "media_1792780906388_c77464df.jpg",
        "media_1792780906471_c6388f0e.jpg",
        "media_1792780906545_b4b11f4d.jpg"
    )
    fun file(number: Int): File {
        require(number in 1..3)
        val directory = System.getenv("WASP_SCREENSHOT_FIXTURES")?.takeIf { it.isNotBlank() }
            ?: "src/test/resources/osrs-s23"
        return File(directory, names[number - 1])
    }
}
