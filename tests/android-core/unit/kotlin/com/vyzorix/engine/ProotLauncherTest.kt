package com.vyzorix.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun `the request carries the proot binary and its arguments`() {
        val request = ProotLauncher.loginShellRequest(context, token = "t")

        assertEquals(ProotLauncher.prootBinary(context).absolutePath, request.executable)
        assertEquals(request.executable, request.arguments.first())
    }
}
