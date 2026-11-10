package com.vyzorix.bridge

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `apis.txt` is the container's view of the bridge, and it must match what the
 * router serves.
 *
 * `debian-container/android-bin/apis.txt` decides which commands exist inside the
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
     * The API names the router serves.
     *
     * Read from [ApiCatalogue] rather than listed here. The earlier version mirrored
     * `buildRouter()` by hand, which with six APIs was a tolerable duplication and
     * with fifty is a list that would be wrong within a week — and wrong silently,
     * which is the failure this test exists to prevent. The catalogue is now the one
     * source the service registers from and `apis.txt` is written from, so this test
     * checks the two consumers against each other rather than against a third copy.
     */
    private val registeredApis: Set<String> = ApiCatalogue.names.toSet()

    private fun apisTxt(): List<String> {
        val file = File(repoRoot(), "debian-container/android-bin/apis.txt")
        assertTrue("apis.txt must exist at ${file.absolutePath}", file.isFile)

        return file
            .readLines()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
    }

    private companion object {
        /**
         * The floor the catalogue must stay above.
         *
         * Not the exact count — that would fail on every addition and be relaxed
         * reflexively — but a number low enough to be honest and high enough that an
         * emptied or truncated catalogue is caught rather than written to `apis.txt`.
         */
        const val MINIMUM_API_COUNT = 50
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
    fun `every catalogue handler declares the name it is registered under`() {
        // `sync-apis-txt.py` reads each API's `const val NAME` from its own file and
        // the catalogue keys on `SomeApi.NAME`, so a mismatch between the two would
        // produce an `apis.txt` entry that the router does not serve. This asserts the
        // link the generator relies on.
        for ((registeredAs, handler) in ApiCatalogue.handlers) {
            val declared =
                handler.javaClass.getField("NAME").get(null) as? String
                    ?: throw AssertionError(
                        "${handler.javaClass.simpleName} has no static NAME field",
                    )

            assertEquals(
                "${handler.javaClass.simpleName} is registered as '$registeredAs' but " +
                    "declares NAME='$declared'",
                registeredAs,
                declared,
            )
        }
    }

    @Test
    fun `every registered api is listed in apis txt`() {
        // The same check as below, stated for the whole catalogue: a name the router
        // serves but apis.txt omits is a command an operator cannot reach.
        val listed = apisTxt().toSet()

        val unreachable = registeredApis.filterNot { it in listed }

        assertTrue(
            "these APIs are registered but not in apis.txt, so no command reaches " +
                "them: $unreachable. Run: python3 scripts/sync-apis-txt.py",
            unreachable.isEmpty(),
        )
    }

    @Test
    fun `the catalogue is substantial and every entry is unique`() {
        // A guard on the generator: if a refactor emptied the catalogue, apis.txt
        // would be rewritten to nothing and every container command would vanish.
        assertTrue(
            "the catalogue should serve the full API surface, not ${registeredApis.size}",
            registeredApis.size >= MINIMUM_API_COUNT,
        )
        // The service registers the `serviceApiNames` itself (session, the USB trio),
        // because they need state only the service holds. The catalogue's `handlers`
        // map holds the rest, so the two together are the full set.
        assertEquals(
            "the catalogue must not register two handlers under one name",
            registeredApis.size,
            ApiCatalogue.handlers.size + ApiCatalogue.serviceApiNames.size,
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
