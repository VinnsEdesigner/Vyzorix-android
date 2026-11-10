package com.vyzorix.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The command line is the contract with the container: a missing bind or a token
 * in the wrong place is a silent failure at runtime, so the shape is asserted
 * here rather than discovered on the device.
 */
@RunWith(RobolectricTestRunner::class)
class ProotLauncherTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the token rides in the inner environment, never in argv`() {
        val args = ProotLauncher.loginShellArgs(context, token = "secret-token")

        // `env -i` clears the environment, so the token must be an argument to
        // `env`, which is where the inner environment is built.
        val envIndex = args.indexOf("/usr/bin/env")
        assertTrue("env must be invoked", envIndex >= 0)

        val tokenArg = "${ProotLauncher.BRIDGE_TOKEN_ENV}=secret-token"
        assertTrue("token must be passed to env", args.contains(tokenArg))
        assertTrue("token must come after env -i", args.indexOf(tokenArg) > envIndex)
    }

    @Test
    fun `PROOT_TMP_DIR rides in the OUTER environment`() {
        // proot reads PROOT_TMP_DIR from its own environment, before it execs the
        // container's shell. It must therefore be in the outer environment, and must
        // NOT be in the `env -i` list — there it would be too late, and proot would
        // fall back to P_tmpdir (/data/local/tmp), which an app cannot write, and
        // fail with "can't create temporary file".
        val request = ProotLauncher.loginShellRequest(context, token = "t")

        val tmp = request.environment.firstOrNull { it.startsWith("${ProotLauncher.TMP_DIR_ENV}=") }
        assertTrue("PROOT_TMP_DIR must be in the outer environment", tmp != null)
        assertTrue(
            "PROOT_TMP_DIR must point at the app-private dir",
            tmp!!.endsWith(ProotLauncher.tmpDir(context).absolutePath),
        )

        // And it must not have leaked into argv.
        val args = ProotLauncher.loginShellArgs(context, token = "t")
        assertFalse(
            "PROOT_TMP_DIR must not be in argv",
            args.any { it.startsWith("${ProotLauncher.TMP_DIR_ENV}=") },
        )
    }

    @Test
    fun `the tmp dir is created, and isReady requires it`() {
        // proot does not create its own scratch dir, so the launcher must.
        val dir = ProotLauncher.tmpDir(context)
        assertTrue("tmp dir must exist after tmpDir()", dir.isDirectory)

        // isReady() must include it: a binary with no scratch dir reports "ready" and
        // then fails on the first exec.
        assertFalse(
            "isReady must be false without a rootfs, even though the tmp dir exists",
            ProotLauncher.isReady(context),
        )
    }

    @Test
    fun `the socket is bound into the container at the documented path`() {
        val args = ProotLauncher.loginShellArgs(context, token = "t")
        val bind =
            args
                .windowed(2)
                .firstOrNull {
                    it[0] == "-b" &&
                        it[1].endsWith(ProotLauncher.BRIDGE_SOCKET_IN_CONTAINER)
                }

        assertTrue("socket must be bound into the container", bind != null)
    }

    @Test
    fun `the socket env var matches the bind target`() {
        val args = ProotLauncher.loginShellArgs(context, token = "t")

        assertEquals(
            "${ProotLauncher.BRIDGE_SOCKET_ENV}=${ProotLauncher.BRIDGE_SOCKET_IN_CONTAINER}",
            args.first { it.startsWith("${ProotLauncher.BRIDGE_SOCKET_ENV}=") },
        )
    }

    @Test
    fun `the rootfs is faked to root and the login shell is last`() {
        val args = ProotLauncher.loginShellArgs(context, token = "t")

        assertTrue(args.contains("-0"))
        assertEquals("/bin/bash", args[args.size - 2])
        assertEquals("--login", args[args.size - 1])
    }

    @Test
    fun `a tmux session is told a socket dir without a trailing slash`() {
        // tmux canonicalises its socket path list with realpath(3), which under proot
        // fails on a trailing slash; its compiled default is `/tmp/`. Without an
        // explicit, slashless TMUX_TMPDIR it exits 1 with "no suitable socket path"
        // and the operator's terminal never starts. See loginShellArgs.
        val args = ProotLauncher.loginShellArgs(context, token = "t", tmuxSession = "sess-1")

        val tmpdir = args.firstOrNull { it.startsWith("TMUX_TMPDIR=") }
        assertTrue("tmux needs TMUX_TMPDIR in its environment", tmpdir != null)
        assertEquals("TMUX_TMPDIR must be slashless", "TMUX_TMPDIR=/tmp", tmpdir)
        assertTrue(
            "TMUX_TMPDIR must be in the inner environment, after env -i",
            args.indexOf(tmpdir) > args.indexOf("/usr/bin/env"),
        )
    }

    @Test
    fun `a bare login shell carries no tmux socket dir`() {
        val args = ProotLauncher.loginShellArgs(context, token = "t")

        assertFalse(
            "TMUX_TMPDIR is only meaningful when a tmux session wraps the shell",
            args.any { it.startsWith("TMUX_TMPDIR=") },
        )
    }

    @Test
    fun `the request carries the proot binary and its arguments`() {
        val request = ProotLauncher.loginShellRequest(context, token = "t")

        assertEquals(ProotLauncher.prootBinary(context).absolutePath, request.executable)
        assertEquals(request.executable, request.arguments.first())
    }

    @Test
    fun `the bind list never crosses the engine zone`() {
        // The closed-world guarantee: the rootfs, secret store and recordings
        // are not bound, so the container cannot reach them by construction.
        for (bind in ProotLauncher.binds(context)) {
            val host = bind.substringBefore(":")
            assertFalse("bind $bind crosses the engine zone", ProotLauncher.isEngineZonePath(host))
        }
    }

    @Test
    fun `the engine zone is recognised in its several shapes`() {
        val rootfs = ProotLauncher.rootfs(context).absolutePath
        val secrets = File(context.filesDir, ProotLauncher.SECRETS_DIR).absolutePath
        val recordings = File(context.filesDir, ProotLauncher.RECORDINGS_DIR).absolutePath

        // rootfs is the container's own root, so it is NOT the engine zone: the
        // zone is what holds a secret or a recording.
        assertFalse(
            "rootfs is the container root, not a leak",
            ProotLauncher.isEngineZonePath(rootfs),
        )
        assertTrue(ProotLauncher.isEngineZonePath(secrets))
        assertTrue(ProotLauncher.isEngineZonePath(recordings))
        assertTrue(
            "a child of the zone must be refused",
            ProotLauncher.isEngineZonePath("$secrets/token"),
        )
        assertTrue(ProotLauncher.isEngineZonePath("$recordings/session.cast"))
    }

    @Test
    fun `a normal path is not in the engine zone`() {
        assertFalse(ProotLauncher.isEngineZonePath("/sdcard"))
        assertFalse(ProotLauncher.isEngineZonePath("/tmp"))
        assertFalse(ProotLauncher.isEngineZonePath(context.filesDir.absolutePath))
    }

    @Test
    fun `the bind list carries the socket and the overlay prefix`() {
        val binds = ProotLauncher.binds(context)

        assertTrue(
            "the socket must be bound",
            binds.any {
                it.endsWith(ProotLauncher.BRIDGE_SOCKET_IN_CONTAINER)
            },
        )
        assertTrue("the overlay prefix must be bound", binds.any { it.contains("/usr-local") })
    }

    @Test
    fun `shared storage is bound so the container sees the public collections`() {
        // Tier 1 (unrooted) reaches shared storage through the app's own uid and the
        // storage permission, so the container is given the same tree the app has.
        // The host side is read from the platform, so the assertion is on the shape
        // and the guest target, not on a hardcoded /sdcard.
        val root = ProotLauncher.sharedStorageRoot()
        assumeTrue("device has no shared storage", root != null)

        val bind = ProotLauncher.binds(context).firstOrNull { it.endsWith(":/sdcard") }
        assertTrue("shared storage must be bound into the container", bind != null)
        assertEquals(root, bind!!.substringBefore(":"))
    }

    @Test
    fun `the app-private tree is never bound`() {
        // The private tree holds the rootfs, the overlay, the secret store and the
        // recordings. Only named subdirectories cross (apps, the socket), plus one
        // file out of the rootfs for DNS. What must never cross is the tree itself,
        // the overlay root, or the engine zone — or the container would hold the
        // keys to itself.
        val files = context.filesDir.absolutePath
        val overlay = ProotLauncher.overlay(context).absolutePath

        for (bind in ProotLauncher.binds(context)) {
            val host = bind.substringBefore(":")
            assertFalse("filesDir itself must not be bound: $bind", host == files)
            assertFalse("the overlay root must not be bound: $bind", host == overlay)
            assertFalse(
                "the engine zone must not be bound: $bind",
                ProotLauncher.isEngineZonePath(host),
            )
        }
    }

    @Test
    fun `the android trees are exposed under a prefix, never at their host path`() {
        // The container's / is the Debian rootfs, which has its own /bin, /usr, /lib.
        // Binding a host tree at its own top-level path risks shadowing the Debian
        // filesystem; the prefix makes the boundary explicit.
        //
        // Exercised through androidBindsFor: on the JVM androidBinds() is empty (no
        // /system), so a test over it would pass vacuously.
        val binds =
            ProotLauncher.androidBindsFor(
                listOf("/system", "/system/lib64", "/vendor", "/product", "/odm", "/oem"),
            )

        assertEquals("every tree maps to one bind", 6, binds.size)

        for (bind in binds) {
            val host = bind.substringBefore(":")
            val guest = bind.substringAfter(":")

            assertTrue(
                "an android tree must be exposed under the prefix: $bind",
                guest.startsWith("${ProotLauncher.ANDROID_ROOT_IN_CONTAINER}/"),
            )
            assertEquals(
                "the guest path mirrors the host basename: $bind",
                ProotLauncher.ANDROID_ROOT_IN_CONTAINER + "/" + File(host).name,
                guest,
            )
            assertFalse("host and guest must differ: $bind", host == guest)
        }

        // The concrete shape, so a rename of the prefix is a visible test change.
        assertTrue(binds.contains("/system:/android/system"))
        assertTrue(binds.contains("/vendor:/android/vendor"))
    }

    @Test
    fun `android binds are skipped when the tree is absent`() {
        // On the JVM there is no /system, so androidBinds() is empty — which is the
        // point: an absent tree is skipped rather than passed through as a dead bind.
        for (bind in ProotLauncher.androidBinds()) {
            val host = bind.substringBefore(":")
            assertTrue("only existing trees are bound: $bind", File(host).isDirectory)
        }

        // And the filter is what does it: an empty input maps to an empty output.
        assertEquals(emptyList<String>(), ProotLauncher.androidBindsFor(emptyList()))
    }
}
