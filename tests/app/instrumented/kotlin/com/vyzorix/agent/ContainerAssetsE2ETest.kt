package com.vyzorix.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vyzorix.container.ProotLauncher
import com.vyzorix.service.RootfsBootstrap
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The container's userspace and the Vyzorix CLI are **fetched**, not bundled. This drives the
 * real installers end to end against a served release: the checksum-compiled-in fetch,
 * the extraction, and the "is it ready" check — the path a device takes on first boot.
 *
 * It needs the assets reachable at the build's `ROOTFS_BASE_URL` (the emulator's
 * `10.0.2.2` alias for the host), with the exact bytes the checksums describe. A build
 * with no checksum or no URL is the fail-closed state and this test would fail there by
 * design — it is only meaningful against a build wired to a release.
 */
@RunWith(AndroidJUnit4::class)
class ContainerAssetsE2ETest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theCoreRootfsIsFetchedAndBecomesReady() {
        assertTrue(
            "the core rootfs must install from the release",
            RootfsBootstrap.ensureInstalled(context),
        )
        assertTrue("the container must be ready after install", ProotLauncher.isReady(context))
    }

    @Test
    fun theExtendedTierIsFetched() {
        // Optional: a failure leaves the device bridge-only. Asserted here because the
        // point of the test is that the fetch path works, and the extended tier is the
        // second asset on the same release.
        RootfsBootstrap.ensureInstalled(context)
        assertTrue(
            "the extended tier must install from the release",
            RootfsBootstrap.ensureExtended(context),
        )
    }

    @Test
    fun theVyzorixCliIsFetched() {
        RootfsBootstrap.ensureInstalled(context)
        assertTrue(
            "the Vyzorix CLI must install from the release",
            RootfsBootstrap.ensureVyzorixCli(context),
        )
        assertTrue(
            "the supervisor must be present after install",
            ProotLauncher.hasSupervisor(context),
        )
    }
}
