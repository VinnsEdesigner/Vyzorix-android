package com.vyzorix.connector.deploy

import java.io.File
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The fetcher is where a bad response would become installed code, so the digest is
 * the point: a mismatch must refuse the file, and a resume must verify the whole
 * file, not just the tail it downloaded.
 */
class ArtifactFetcherTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun fetcher(): ArtifactFetcher = ArtifactFetcher(OkHttpClient())

    private val payload = "vyzorix artifact payload".toByteArray()

    @Test
    fun `a matching digest is accepted`() {
        server.enqueue(MockResponse().setBody(Buffer().write(payload)))
        val dest = folder.newFile("engine.tar.gz")

        fetcher().fetch(server.url("/engine").toString(), TarFixtures.sha256(payload), dest)

        assertEquals(payload.toList(), dest.readBytes().toList())
    }

    @Test
    fun `a mismatched digest is refused and the file is removed`() {
        server.enqueue(MockResponse().setBody(Buffer().write(payload)))
        val dest = File(folder.root, "engine.tar.gz")

        assertThrows(DigestMismatch::class.java) {
            fetcher().fetch(
                server.url("/engine").toString(),
                TarFixtures.sha256("other".toByteArray()),
                dest,
            )
        }
        assertFalse(dest.exists())
    }

    @Test
    fun `a partial download resumes with a Range request`() {
        val prefix = payload.copyOfRange(0, 8)
        val dest = folder.newFile("engine.tar.gz").apply { writeBytes(prefix) }

        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setBody(Buffer().write(payload.copyOfRange(8, payload.size))),
        )

        fetcher().fetch(server.url("/engine").toString(), TarFixtures.sha256(payload), dest)

        val recorded = server.takeRequest()
        assertEquals("bytes=8-", recorded.getHeader("Range"))
        assertEquals(payload.toList(), dest.readBytes().toList())
    }

    @Test
    fun `a tampered prefix fails even though the tail is correct`() {
        // The whole-file check: a resume must not trust a prefix it did not fetch.
        val tampered = payload.copyOf().apply { this[0] = (this[0] + 1).toByte() }
        val dest = folder.newFile("engine.tar.gz").apply { writeBytes(tampered.copyOfRange(0, 8)) }

        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setBody(Buffer().write(payload.copyOfRange(8, payload.size))),
        )

        assertThrows(DigestMismatch::class.java) {
            fetcher().fetch(server.url("/engine").toString(), TarFixtures.sha256(payload), dest)
        }
        assertTrue(!dest.exists())
    }
}
