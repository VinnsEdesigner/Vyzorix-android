package com.vyzorix.bridge.apis

import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.VyzorixApi
import com.vyzorix.container.ContainerConfig
import com.vyzorix.container.ProotLauncher
import com.vyzorix.container.VyzorixCliInstaller
import java.io.File
import org.json.JSONObject

/**
 * Why the container's shell is (or is not) working — the state that otherwise shows up
 * only as an empty terminal.
 *
 * ## The failure this makes visible
 *
 * The container is three parts installed at different times: the **rootfs** (the Debian
 * tree), the **overlay's the Vyzorix CLI** (the supervisor + `android-bin`, fetched separately),
 * and **proot**. A shell opens only when all three are present — `ProotLauncher.isReady`
 * gates the terminal. When the Vyzorix CLI is missing (its fetch 404'd, or a build that does not
 * bundle it has no checksum), the terminal opens and then produces **nothing**, which
 * reads as a hang rather than as a missing piece. This API reports each part and the
 * exact fetch URLs the device would use, so "the shell is empty" has an answer.
 *
 * ## Read-only
 *
 * It inspects files and reports. It does not install, fetch, or restart anything — a
 * status read must not have side effects. The parts it names are the ones the service's
 * start path installs ([com.vyzorix.service.RootfsBootstrap]).
 *
 * No runtime permission.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_PRIVATE,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_NORMAL,
        ApiTags.VISIBILITY_PRIVATE,
    ],
)
object ContainerStatusApi : ApiHandler {

    const val NAME = "container_status"

    override fun handle(call: ApiCall): ApiOutcome {
        val context = call.context
        val config = ContainerConfig.get()
        val abi = config?.bundledAbi.orEmpty()
        val baseUrl = config?.baseUrl.orEmpty()

        val rootfsDir = ProotLauncher.rootfs(context)
        val rootfsReady = ProotLauncher.isReady(context)
        val supervisorPresent = ProotLauncher.hasSupervisor(context)
        val prootExecutable = ProotLauncher.prootBinary(context).canExecute()
        val vyzorixCliSha = config?.vyzorixCliSha256.orEmpty()

        // The layer-3 marker: what is written vs what this build expects. A mismatch is a
        // stale install, which the next start reinstalls.
        val cli =
            VyzorixCliInstaller(
                context = context,
                abi = abi,
                baseUrl = baseUrl,
                expectedSha256 = config?.vyzorixCliSha256.orEmpty(),
            )
        val vyzorixCliInstalled = cli.isInstalled()

        // The one-line verdict a user acts on, named rather than inferred.
        val verdict =
            when {
                !rootfsReady -> "rootfs missing: the Debian tree is not installed"
                !supervisorPresent ->
                    "the Vyzorix CLI is missing: no supervisor, so the shell is empty"
                !vyzorixCliInstalled ->
                    "the Vyzorix CLI is stale: its marker does not match this build"
                else -> "ready"
            }

        return ApiOutcome.ok(
            JSONObject()
                // The three parts, each as its own fact.
                .put("rootfsReady", rootfsReady)
                .put("rootfsDir", rootfsDir.absolutePath)
                .put("rootfsBashPresent", File(rootfsDir, "bin/bash").exists())
                .put("prootExecutable", prootExecutable)
                .put("supervisorPresent", supervisorPresent)
                .put("vyzorixCliInstalled", vyzorixCliInstalled)
                .put("vyzorixCliMarker", cli.installedMarker())
                .put("vyzorixCliMarkerExpected", cli.expectedMarker())
                // A shell opens only when all three hold; this is the terminal's own gate.
                .put("shellUsable", rootfsReady && supervisorPresent)
                .put("verdict", verdict)
                // What the device would fetch, so a missing asset is attributable. The
                // layer-3 URL is the one that 404'd when the release lacked the asset.
                .put("abi", abi)
                .put("baseUrl", baseUrl)
                .put("vyzorixCliUrl", vyzorixCliUrl(baseUrl, abi))
                .put("rootfsUrl", rootfsUrl(baseUrl, abi))
                .put("vyzorixCliSha256", config?.vyzorixCliSha256.orEmpty())
                .put("rootfsSha256", config?.sha256.orEmpty())
                // Empty checksum or URL disables the fetch (fail-closed), which is itself a
                // reason the shell can be empty — named, not left to inference.
                .put("fetchConfigured", baseUrl.isNotEmpty() && vyzorixCliSha.isNotEmpty()),
        )
    }

    private fun vyzorixCliUrl(
        baseUrl: String,
        abi: String,
    ): String = if (baseUrl.isEmpty() || abi.isEmpty()) "" else "$baseUrl/vyzorix-cli-$abi.tar.gz"

    private fun rootfsUrl(
        baseUrl: String,
        abi: String,
    ): String = if (baseUrl.isEmpty() || abi.isEmpty()) "" else "$baseUrl/debian-core-$abi.tar.gz"
}
