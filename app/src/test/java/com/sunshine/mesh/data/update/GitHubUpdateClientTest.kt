package com.sunshine.mesh.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubUpdateClientTest {

    @Test
    fun parsesTagAndTopLevelUrl() {
        val json = """
            {
              "url": "https://api.github.com/repos/an9nim/Sunshine/releases/1",
              "html_url": "https://github.com/an9nim/Sunshine/releases/tag/v0.2.0",
              "tag_name": "v0.2.0",
              "body": "contains \"tag_name\": \"fake\" and \"html_url\": \"https://evil\""
            }
        """.trimIndent()

        val release = requireNotNull(parseLatestRelease(json))

        assertEquals("v0.2.0", release.tag)
        assertEquals(
            "https://github.com/an9nim/Sunshine/releases/tag/v0.2.0",
            release.downloadUrl,
        )
    }

    @Test
    fun missingHtmlUrlReturnsNull() {
        assertNull(parseLatestRelease("""{"tag_name":"v0.2.0"}"""))
    }

    @Test
    fun missingTagReturnsNull() {
        assertNull(
            parseLatestRelease(
                """{"html_url":"https://github.com/an9nim/Sunshine/releases/tag/v0.2.0"}""",
            ),
        )
    }

    @Test
    fun nonHttpsUrlReturnsNull() {
        assertNull(
            parseLatestRelease(
                """{"tag_name":"v0.2.0","html_url":"http://example.com/apk"}""",
            ),
        )
    }

    @Test
    fun escapedCharactersAreDecoded() {
        val release = requireNotNull(
            parseLatestRelease(
                """{"tag_name":"v0.2.0","html_url":"https://github.com/a\/b?x=1\u0026y=2"}""",
            ),
        )

        assertEquals("https://github.com/a/b?x=1&y=2", release.downloadUrl)
    }

    @Test
    fun malformedJsonReturnsNull() {
        assertNull(parseLatestRelease("not json"))
    }
}
