package com.vyzorix.bridge.apis

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.util.Log
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.VyzorixApi
import org.json.JSONObject

/**
 * Whether this app holds all-files access, and what that grants.
 *
 * `MANAGE_EXTERNAL_STORAGE` is not a runtime permission: there is no dialog, and the
 * only way to grant it is a Settings screen the user opens. So this API reports the
 * state rather than requesting it, and the app raises the screen — the request path
 * lives in `PermissionRegistry` as a `settingsAction`, not here. A handler that
 * could trigger a Settings screen from a socket call would be a handler that moves
 * the UI on a command, which the bridge deliberately does not do.
 *
 * The read is `Environment.isExternalStorageManager()`, which is available from API
 * 30. Below that the permission does not exist (scoped storage arrived with it), so
 * the answer is "not applicable" rather than "denied" — a distinction an operator
 * needs, because a device on API 29 does not need this permission and one on API 30
 * might.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_APP,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_DANGEROUS,
        ApiTags.VISIBILITY_PUBLIC,
    ],
)
object StorageAllFilesApi : ApiHandler {

    const val NAME = "storage_all_files"

    override fun handle(call: ApiCall): ApiOutcome {
        // Below API 30 the permission does not exist, so the question is not "is it
        // granted" but "does it apply". Saying so is more useful than a false.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return ApiOutcome.ok(
                JSONObject()
                    .put("applicable", false)
                    .put("granted", false)
                    .put("reason", "all-files access does not exist before API 30"),
            )
        }

        // `isExternalStorageManager` walks the storage volumes, so it can throw on a
        // device or runtime with none (Robolectric, an emulator with no volume). A
        // state query must answer, not fail: an exception here would surface as an
        // `internal` error, which reads as "the API is broken" rather than "the
        // grant is absent". The camera handler catches its platform call for the
        // same reason.
        val granted =
            try {
                Environment.isExternalStorageManager()
            } catch (e: Exception) {
                return ApiOutcome.ok(
                    JSONObject()
                        .put("applicable", true)
                        .put("granted", false)
                        .put("reason", "storage manager unavailable: ${e.message}")
                        .put("settingsAction", SETTINGS_ACTION),
                )
            }
        return ApiOutcome.ok(
            JSONObject()
                .put("applicable", true)
                .put("granted", granted)
                // The screen that grants it, so the app does not have to know the
                // action string separately from the API that reports the state.
                .put("settingsAction", SETTINGS_ACTION)
                .put("sharedRoot", Environment.getExternalStorageDirectory().absolutePath)
                .put("volumes", volumeCount(call.context)),
        )
    }

    /**
     * How many storage volumes the device exposes.
     *
     * The count, not the list: the volumes themselves are `storage_volumes`' job,
     * and duplicating them here would be two APIs answering one question. The count
     * is what makes "all files" meaningful — access to one volume is a narrower
     * thing than access to all of them.
     */
    private fun volumeCount(context: Context): Int =
        try {
            val manager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            manager?.storageVolumes?.size ?: 0
        } catch (e: Exception) {
            // A device with no storage service reports no volumes. The count is
            // advisory, so zero is the honest answer rather than a failed call.
            Log.d(TAG, "storage volumes unavailable: ${e.message}")
            0
        }

    /** The Settings screen that grants all-files access. */
    const val SETTINGS_ACTION = "android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION"

    private const val TAG = "StorageAllFilesApi"
}
