package com.vyzorix.connector.deploy

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The installed-state record: what the device writes on a successful apply, and
 * what the telemetry heartbeat reports from it.
 *
 * The digest algorithm is a **wire contract** — the server computes the same value
 * in `deploystate.AppStateDigest` — so the literal below is asserted on both sides.
 * If either implementation changes, one of the two suites fails, which is the
 * point: a silent change here makes every device read as drifted.
 */
class InstalledStateTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun path(): File = File(folder.root, InstalledState.FILE_NAME)

    @Test
    fun `the digest matches the server's algorithm`() {
        // The literal is `hex(sha256("console=2222\nengine=1111"))[:16]`. The server's
        // test asserts the same value; both sides must agree or every device reads as
        // drifted.
        val got = InstalledStateStore.digest(mapOf("engine" to "1111", "console" to "2222"))

        assertEquals("47584980637a11e1", got)
    }

    @Test
    fun `the digest does not depend on map order`() {
        val a = InstalledStateStore.digest(mapOf("engine" to "11", "console" to "22", "extractors" to "33"))
        val b = InstalledStateStore.digest(mapOf("extractors" to "33", "engine" to "11", "console" to "22"))

        assertEquals(a, b)
    }

    @Test
    fun `a different artifact digest changes the app digest`() {
        val a = InstalledStateStore.digest(mapOf("engine" to "1111"))
        val b = InstalledStateStore.digest(mapOf("engine" to "2222"))

        assertTrue(a != b, "a changed sha256 must change the digest")
    }

    @Test
    fun `no artifacts has no digest`() {
        assertNull(InstalledStateStore.digest(emptyMap()))
    }

    @Test
    fun `the digest is sixteen hex characters`() {
        val got = InstalledStateStore.digest(mapOf("engine" to "1111"))

        assertNotNull(got)
        assertEquals(16, got.length)
        assertTrue(got.all { it in "0123456789abcdef" }, "not hex: $got")
    }

    @Test
    fun `a written state round-trips`() {
        val store = InstalledStateStore()
        val state =
            InstalledState(
                version = 7,
                apps = mapOf("vfd" to mapOf("engine" to "1111", "console" to "2222")),
            )

        store.write(path(), state)

        assertEquals(state, store.read(path()))
    }

    @Test
    fun `a missing file reads as no state`() {
        val got = InstalledStateStore().read(File(folder.root, "absent.json"))

        assertEquals(0, got.version)
        assertTrue(got.apps.isEmpty())
    }

    @Test
    fun `a corrupt file reads as no state rather than throwing`() {
        // The safe direction: a state that cannot be read must not crash the heartbeat,
        // and must not look healthy either.
        path().writeText("{ not json")

        val got = InstalledStateStore().read(path())

        assertEquals(0, got.version)
        assertTrue(got.apps.isEmpty())
    }

    @Test
    fun `a successful apply records what was installed`() {
        // The record is what the telemetry heartbeat reports, and what the server
        // compares to its expectation. A version alone would catch a failed apply; the
        // digests catch an app replaced in place.
        val archive = TarFixtures.tarGz(mapOf("run.sh" to "#!/bin/sh\necho vfd\n"))
        serve(archive)
        val (applier, target) = applierThatRecords(FakeSupervisor())

        val result = applier.apply(config(app(artifacts = listOf(artifact("engine", archive)))))

        assertTrue(result.success)
        val state = target.read()
        assertEquals(7, state.version)
        assertEquals(artifact("engine", archive).sha256, state.apps["vfd"]?.get("engine"))
    }

    @Test
    fun `a refused apply records nothing`() {
        // "A failed apply changes nothing" includes the record: a state claiming a
        // version the device is not running is exactly the lie this exists to remove.
        val archive = TarFixtures.tarGz(mapOf("run.sh" to "echo vfd\n"))
        serve(archive)
        val (applier, target) = applierThatRecords(FakeSupervisor(reachable = false))

        val result = applier.apply(config(app(artifacts = listOf(artifact("engine", archive)))))

        assertFalse(result.success)
        assertEquals(0, target.read().version)
    }

    @Test
    fun `stateFor records the version and every artifact digest`() {
        val app =
            com.vyzorix.connector.config
                .AppConfig(
                    name = "vfd",
                    dir = "/home/apps/vfd",
                    cmd = "/home/apps/vfd/engine/run.sh",
                    port = 8787,
                    autostart = true,
                    expose = "loopback",
                    purge = false,
                    env = emptyMap(),
                    secretNames = emptyList(),
                    artifacts =
                        listOf(
                            com.vyzorix.connector.config
                                .ArtifactConfig("engine", "arm64-v8a", "https://x/e", "1111", "dir"),
                            com.vyzorix.connector.config
                                .ArtifactConfig("console", null, "https://x/c", "2222", "webroot"),
                        ),
                    pwa = false,
                    pwaOverrides = emptyMap(),
                )

        val state = InstalledStateStore().stateFor(7, listOf(app))

        assertEquals(7, state.version)
        assertEquals(mapOf("engine" to "1111", "console" to "2222"), state.apps["vfd"])
    }
}
