package com.vyzorix.bridge

import com.vyzorix.bridge.apis.BatteryApi
import com.vyzorix.bridge.apis.DeviceInfoApi
import com.vyzorix.bridge.apis.LogcatApi
import com.vyzorix.bridge.apis.NetworkApi
import com.vyzorix.bridge.apis.SessionApi
import com.vyzorix.bridge.apis.StorageApi
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `apis.txt` is the container's view of the bridge, and it must match what the
 * router serves.
 *
 * `debian-engine/android-bin/apis.txt` decides which commands exist inside the
 * container — one symlink per name. The authority is the set of handlers Layer 1
 * registers. The two are separate files in separate languages, so nothing makes
 * them agree except this test.
 *
 * The failure it prevents is quiet: a name in `apis.txt` that the router does not
 * serve is a command that exists and fails at call time with `unknown_api`; a
 * handler that is missing from `apis.txt` is one an operator simply cannot reach.
 * Both are invisible until someone uses the feature.
 *
 * The router's own list is built here from the same registrations the service
 * makes. It is duplicated deliberately: importing the service would drag the whole
 * Android service into a JVM test, and the point is to catch a change to the
 * registrations, which a shared helper would hide. `SessionApi` is the one
 * exception — it needs the service's session table, so only its `NAME` is used.
 */
class ApiCatalogTest {

    /**
     * The API names the router serves, mirroring
     * `VyzorixForegroundService.buildRouter()`.
     */
    private val registeredApis: Set<String> =
        setOf(
            BatteryApi.NAME,
            DeviceInfoApi.NAME,
            StorageApi.NAME,
            NetworkApi.NAME,
            LogcatApi.NAME,
            SessionApi.NAME,
        )

    private fun apisTxt(): List<String> {
        val file = File(repoRoot(), "debian-engine/android-bin/apis.txt")
        assertTrue("apis.txt must exist at ${file.absolutePath}", file.isFile)

        return file
            .readLines()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * The repository root, found by walking up from the working directory.
     *
     * Gradle runs JVM tests with the module directory as the working directory
     * (`android-core/`), so the root is two levels up — but the test should not
     * hard-code a depth that a Gradle change would break, so it walks until it
     * finds the marker file.
     */
    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }

        throw AssertionError("could not find the repository root from ${File("").absolutePath}")
    }

    @Test
    fun `every command in apis txt is served by the router`() {
        val served = registeredApis

        val missing = apisTxt().filterNot { it in served }

        assertTrue(
            "apis.txt lists commands the router does not serve: $missing. " +
                "Either register the handler or remove the name.",
            missing.isEmpty(),
        )
    }

    @Test
    fun `every api the router serves is reachable as a command`() {
        val listed = apisTxt().toSet()

        val unreachable = registeredApis.filterNot { it in listed }

        assertTrue(
            "these APIs are registered but not in apis.txt, so no command reaches " +
                "them: $unreachable",
            unreachable.isEmpty(),
        )
    }

    @Test
    fun `apis txt has no duplicates`() {
        val names = apisTxt()

        assertEquals(
            "apis.txt must not repeat a name",
            names.size,
            names.toSet().size,
        )
    }

    @Test
    fun `api names are portable file and argv tokens`() {
        // A name becomes a symlink and an argv[0], so anything outside this set is a
        // portability bug the generator would reject anyway — failing here says why.
        val portable = Regex("^[A-Za-z0-9_]+$")

        val bad = apisTxt().filterNot { portable.matches(it) }

        assertTrue("apis.txt has names that are not portable tokens: $bad", bad.isEmpty())
    }
}
