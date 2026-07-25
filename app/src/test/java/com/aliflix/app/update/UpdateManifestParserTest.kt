package com.aliflix.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManifestParserTest {
    @Test
    fun parsesValidManifest() {
        val info = UpdateManifestParser.parse(
            """
            {
              "versionCode": 12,
              "versionName": "2.1-tv",
              "apkUrl": "https://github.com/example/aliflix/releases/latest/download/aliflix-tv-release.apk",
              "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
              "minimumSdk": 30,
              "notes": "TV remote improvements"
            }
            """.trimIndent(),
        )

        assertEquals(12, info.versionCode)
        assertEquals("2.1-tv", info.versionName)
        assertEquals(30, info.minimumSdk)
        assertEquals("TV remote improvements", info.notes)
    }

    @Test
    fun rejectsNonHttpsApk() {
        val result = runCatching {
            UpdateManifestParser.parse(
                """
                {
                  "versionCode": 12,
                  "versionName": "2.1-tv",
                  "apkUrl": "http://example.com/app.apk",
                  "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                }
                """.trimIndent(),
            )
        }

        assertTrue(result.isFailure)
    }

    @Test
    fun rejectsInvalidHash() {
        val result = runCatching {
            UpdateManifestParser.parse(
                """
                {
                  "versionCode": 12,
                  "versionName": "2.1-tv",
                  "apkUrl": "https://example.com/app.apk",
                  "sha256": "not-a-hash"
                }
                """.trimIndent(),
            )
        }

        assertTrue(result.isFailure)
    }
}
