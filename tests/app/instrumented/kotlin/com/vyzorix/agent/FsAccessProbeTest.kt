package com.vyzorix.agent

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A measurement harness, not a gate test: it reports what the app's **own process**
 * can reach on the filesystem and never asserts anything, so it cannot fail a run.
 *
 * ## Why it is an instrumented test and not a shell probe
 *
 * `adb shell run-as <pkg>` is **not** a faithful probe on Android 10+. It runs in the
 * `runas_app` SELinux domain — not the app's `untrusted_app` — and, more importantly,
 * in a different **mount namespace**: on API 33 the shared-storage mounts are absent
 * from that namespace, so `run-as` reports `/storage/emulated/0` as unreachable while
 * the app can read and list it perfectly well. Only the app's real process sees the
 * app's real mounts, so only an instrumented test measures the truth.
 *
 * The findings and how to read the output are recorded on the device.
 */
@RunWith(AndroidJUnit4::class)
class FsAccessProbeTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private data class Probe(
        val path: String,
        val exists: Boolean,
        val list: Boolean,
        val read: Boolean,
        val write: Boolean,
        val reason: String,
    )

    private fun probe(path: String): Probe {
        val file = File(path)
        val exists = file.exists()
        val list = exists && runCatching { file.list() != null }.getOrDefault(false)
        val read =
            exists &&
                runCatching {
                    file.canRead() &&
                        (file.isDirectory || file.length() == 0L || file.readBytes().isNotEmpty())
                }.getOrDefault(false)

        // A real write — create then delete — so a false `canWrite()` is caught. The
        // failure reason is kept, because "not writable" alone cannot tell a permission
        // denial from a read-only mount from a directory that does not exist.
        var reason = ""
        val write =
            exists &&
                runCatching {
                    val probe = File(file, ".vyz_probe_${System.nanoTime()}")
                    val created = probe.createNewFile()
                    if (created) probe.delete()

                    created
                }.getOrElse { failure ->
                    reason = failure.javaClass.simpleName + ":" + failure.message
                    false
                }

        return Probe(path, exists, list, read, write, reason)
    }

    /** Grants the storage permissions if the app declares them; a no-op otherwise. */
    private fun grantDeclaredStoragePermissions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (permission in STORAGE_PERMISSIONS) {
            runCatching {
                instrumentation.uiAutomation
                    .executeShellCommand("pm grant ${context.packageName} $permission")
                    .close()
            }
        }
    }

    @Test
    fun reportFilesystemAccess() {
        grantDeclaredStoragePermissions()

        val report = StringBuilder()
        report.appendLine(
            "PROBE_BEGIN sdk=${Build.VERSION.SDK_INT} release=${Build.VERSION.RELEASE}",
        )
        report.appendLine("PROBE uid=${Process.myUid()}")
        report.appendLine(
            "PROBE externalStorageState=${Environment.getExternalStorageState()} " +
                "dir=${Environment.getExternalStorageDirectory().absolutePath}",
        )
        report.appendLine(
            "PROBE legacy=${Environment.isExternalStorageLegacy()} " +
                "emulated=${Environment.isExternalStorageEmulated()}",
        )
        report.appendLine(
            "PROBE readExtStorage=${granted(Manifest.permission.READ_EXTERNAL_STORAGE)}",
        )
        report.appendLine(
            "PROBE writeExtStorage=${granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)}",
        )
        report.appendLine("PATH|EXISTS|LIST|READ|WRITE|REASON")

        for (path in PATHS) {
            val result = probe(path)
            report.appendLine(
                "${result.path}|${result.exists}|${result.list}|${result.read}|" +
                    "${result.write}|${result.reason}",
            )
        }

        report.appendLine("PROBE_END")
        println(report.toString())
    }

    private fun granted(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        val STORAGE_PERMISSIONS =
            listOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            )

        val PATHS =
            listOf(
                "/sdcard",
                "/storage/emulated/0",
                "/storage/self/primary",
                "/mnt/sdcard",
                "/storage/emulated/0/Download",
                "/storage/emulated/0/DCIM",
                "/storage/emulated/0/Pictures",
                "/storage/emulated/0/Music",
                "/storage/emulated/0/Movies",
                "/storage/emulated/0/Documents",
                "/storage/emulated/0/Android",
                "/storage/emulated/0/Android/data",
                "/storage/emulated/0/Android/media",
                "/storage/emulated/0/Android/obb",
                "/data/user/0/com.vyzorix.agent/files",
                "/data/user/0/com.vyzorix.agent/cache",
                "/data/user/0/com.vyzorix.agent",
                "/data/local/tmp",
                "/system",
                "/system/bin",
                "/system/lib64",
                "/vendor",
                "/product",
                "/etc",
                "/etc/hosts",
                "/proc/self/fd",
                "/proc/cpuinfo",
                "/dev/null",
                "/sys/class/net",
                "/dev/bus/usb",
                "/mnt/obb",
                "/mnt/asec",
                "/mnt/expand",
            )
    }
}
