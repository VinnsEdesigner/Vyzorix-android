package com.vyzorix.connector.identity

import android.content.Context
import android.os.Build
import android.os.StatFs
import java.io.File
import java.util.UUID

/**
 * The facts a device reports at enroll. Everything here is either a platform
 * constant or a device-generated value — nothing is a secret, and nothing needs a
 * privileged permission.
 *
 * **IMEI is deliberately absent.** On Android 10+ `getImei()` needs
 * `READ_PRIVILEGED_PHONE_STATE`, which is system-only; a normal app gets null or an
 * exception, on the C22 as anywhere. The stable identity is the device-generated
 * UUID, and the IMEI — where it exists at all — is a best-effort attribute the
 * server does not require. See doc/REGISTRATION_CONTRACT.md §9.
 */
data class DeviceFacts(
    val deviceUuid: String,
    val model: String,
    val manufacturer: String,
    val osVersion: String,
    val apiLevel: Int,
    val supportedAbis: List<String>,
    val storageBytes: Long,
    val ramBytes: Long,
    val tierHint: Int,
    val appVersion: String,
    val deviceClass: String,
)

/**
 * Collects [DeviceFacts] from the platform.
 *
 * The tier hint is the device's self-report (unrooted / rooted / advanced). The
 * server uses it only to *cap* what it grants, never to grant more than policy
 * allows, because it cannot be verified — the honest framing from
 * REGISTRATION_CONTRACT.md §9.
 */
object DeviceFactsCollector {
    /** Tier hint values, matching the server's `TierFromInt`. */
    const val TIER_UNROOTED = 0
    const val TIER_ROOTED = 1
    const val TIER_ADVANCED = 2

    fun collect(
        context: Context,
        deviceUuid: String,
        appVersion: String,
        tierHint: Int,
    ): DeviceFacts =
        DeviceFacts(
            deviceUuid = deviceUuid,
            model = Build.MODEL,
            manufacturer = Build.MANUFACTURER,
            osVersion = Build.VERSION.RELEASE,
            apiLevel = Build.VERSION.SDK_INT,
            supportedAbis = Build.SUPPORTED_ABIS.toList(),
            storageBytes = availableStorageBytes(context),
            ramBytes = totalRamBytes(),
            tierHint = tierHint,
            appVersion = appVersion,
            deviceClass = deviceClass(),
        )

    /**
     * Free space on the filesystem the app's private storage lives on. The app
     * writes the rootfs and the overlay there, so this is the number that matters.
     */
    @Suppress("SwallowedException")
    private fun availableStorageBytes(context: Context): Long =
        try {
            val stat = StatFs(context.filesDir.absolutePath)

            stat.availableBytes
        } catch (t: Throwable) {
            // Storage is a reported fact, not a requirement; a device that cannot
            // read it reports 0 and still enrolls.
            0
        }

    /**
     * Total RAM, from `/proc/meminfo`. Read rather than `ActivityManager`: the
     * `MemoryInfo.totalMem` field is API 16+, but reading the proc file needs no
     * permission and works identically on the target devices.
     */
    @Suppress("SwallowedException")
    private fun totalRamBytes(): Long =
        try {
            val line = File("/proc/meminfo").readLines().firstOrNull { it.startsWith("MemTotal:") }
            val kb = line?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull() ?: 0

            kb * 1024
        } catch (t: Throwable) {
            // Same: RAM is a reported fact with a safe fallback.
            0
        }

    /** A coarse class, so the server can group devices without a per-model table. */
    private fun deviceClass(): String =
        when {
            Build.MODEL.contains("Tablet", ignoreCase = true) -> "tablet"
            Build.MODEL.contains("TV", ignoreCase = true) -> "tv"
            else -> "phone"
        }
}

/**
 * Persists the device-generated UUID, which is the device's stable identity across
 * runs. It is not a secret and not a credential — the keypair is the credential —
 * so plain storage is correct; it only has to survive a restart so the server sees
 * the same device rather than a new one.
 *
 * Storage is a small interface so the Android implementation (DataStore or
 * SharedPreferences) and the test implementation (a map) behave identically.
 */
interface UuidStorage {
    fun read(): String?

    fun write(uuid: String)
}

class DeviceUuidStore(
    private val storage: UuidStorage,
    private val generate: () -> String = { UUID.randomUUID().toString() },
) {
    /** The stored uuid, generating and persisting one on first call. */
    fun uuid(): String {
        storage.read()?.let { if (it.isNotEmpty()) return it }

        val generated = generate()
        storage.write(generated)

        return generated
    }
}
