package com.vyzorix.connector.deploy

import com.vyzorix.connector.config.AppConfig
import com.vyzorix.connector.config.ArtifactConfig
import com.vyzorix.connector.config.DeviceConfig
import com.vyzorix.connector.secret.SecretStore
import com.vyzorix.connector.secret.WrappedSecret
import java.io.File
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The apply path: verify, fetch, install atomically, write the registry. The
 * load-bearing cases are that a refused artifact changes nothing and that the
 * registry carries the supervisor's shape — not the config's.
 */
class DeployApplierTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var appsRoot: File
    private lateinit var staging: File

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        appsRoot = folder.newFolder("apps")
        staging = File(appsRoot, ".deploy-staging").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private class FakeSupervisor(
        private val reachable: Boolean = true,
    ) : SupervisorControl {
        val stopped = mutableListOf<String>()
        val exposed = mutableListOf<Pair<String, String>>()
        val unexposed = mutableListOf<String>()

        override fun ping(): Boolean = reachable

        override fun stop(name: String): Boolean {
            stopped += name

            return true
        }

        override fun expose(
            name: String,
            bind: String,
            token: String,
        ): Boolean {
            exposed += name to bind

            return true
        }

        override fun unexpose(name: String): Boolean {
            unexposed += name

            return true
        }
    }

    private fun applier(supervisor: SupervisorControl) =
        DeployApplier(
            fetcher = ArtifactFetcher(OkHttpClient()),
            registry = AppRegistryWriter(),
            supervisor = supervisor,
            stagingDir = staging,
        )

    private fun artifact(
        name: String,
        bytes: ByteArray,
        kind: String = "dir",
    ) = ArtifactConfig(
        name = name,
        arch = null,
        url = server.url("/$name").toString(),
        sha256 = TarFixtures.sha256(bytes),
        kind = kind,
    )

    @Test
    fun `a lan app is exposed on 0_0_0_0 and a loopback app is not`() {
        val archive = TarFixtures.tarGz(mapOf("run.sh" to "echo hi\n"))
        serve(archive)
        serve(archive)
        val supervisor = FakeSupervisor()
        val applier = applier(supervisor)

        applier.apply(
            config(
                app("vfd", listOf(artifact("engine", archive))),
                app("wiki", listOf(artifact("engine", archive))).copy(expose = "lan"),
            ),
        )

        assertEquals(listOf("wiki" to "0.0.0.0"), supervisor.exposed)
        // `loopback` is the default, so the loopback app is unexposed rather than
        // bound to 127.0.0.1 through the proxy.
        assertEquals(listOf("vfd"), supervisor.unexposed)
    }

    @Test
    fun `the lan token is stable across applies`() {
        val archive = TarFixtures.tarGz(mapOf("run.sh" to "echo hi\n"))
        serve(archive)
        serve(archive)
        val tokens = InMemoryExposeTokens()
        val supervisor = FakeSupervisor()
        val applier =
            DeployApplier(
                fetcher = ArtifactFetcher(OkHttpClient()),
                registry = AppRegistryWriter(),
                supervisor = supervisor,
                stagingDir = staging,
                exposeTokens = tokens,
            )
        val wiki = app("wiki", listOf(artifact("engine", archive))).copy(expose = "lan")

        applier.apply(config(wiki))
        val first = tokens.tokenFor("wiki")

        applier.apply(config(wiki))

        assertEquals(first, tokens.tokenFor("wiki"))
    }

    private fun app(
        name: String = "vfd",
        artifacts: List<ArtifactConfig>,
    ) = AppConfig(
        name = name,
        dir = "/home/apps/$name",
        cmd = "/home/apps/$name/engine/run.sh",
        port = 8080,
        autostart = true,
        expose = "loopback",
        purge = false,
        env = mapOf("MI_PORT" to "8080"),
        secretNames = emptyList(),
        artifacts = artifacts,
        pwa = false,
        pwaOverrides = emptyMap(),
    )

    private fun config(vararg apps: AppConfig) =
        DeviceConfig(
            deviceId = "d_1",
            organizationId = "o_1",
            name = "field-ops",
            tierCap = 2,
            streamPath = "/v1/device/d_1/stream",
            heartbeatSeconds = 60,
            terminalEnabled = true,
            usbEnabled = false,
            appsEnabled = true,
            version = 7,
            apps = apps.toList(),
            document = "v device d_1\n",
        )

    private fun serve(bytes: ByteArray) {
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
    }

    @Test
    fun `a successful apply installs the artifact and writes the registry`() {
        val archive = TarFixtures.tarGz(mapOf("run.sh" to "#!/bin/sh\necho vfd\n"))
        serve(archive)

        val result =
            applier(
                FakeSupervisor(),
            ).apply(config(app(artifacts = listOf(artifact("engine", archive)))))

        assertTrue(result.success)
        assertEquals(7, result.version)
        assertTrue(File(appsRoot, "vfd/engine/run.sh").isFile)

        val registry = AppRegistryWriter().read(File(appsRoot, "apps.json"))
        assertEquals(1, registry.size)
        val entry = registry.first()
        assertEquals("vfd", entry.name)
        assertEquals("/home/apps/vfd", entry.dir)
        assertEquals("/home/apps/vfd/engine/run.sh", entry.cmd)
        assertEquals(8080, entry.port)
        assertEquals(mapOf("MI_PORT" to "8080"), entry.env)
        assertTrue(entry.autostart)
        assertEquals(0, entry.pid)
        assertEquals(0L, entry.pidStartTicks)
    }

    @Test
    fun `a tampered digest is refused and nothing is installed`() {
        val archive = TarFixtures.tarGz(mapOf("run.sh" to "echo vfd\n"))
        serve(archive)
        val bad =
            artifact(
                "engine",
                archive,
            ).copy(sha256 = TarFixtures.sha256("wrong".toByteArray()))

        val result = applier(FakeSupervisor()).apply(config(app(artifacts = listOf(bad))))

        assertFalse(result.success)
        assertEquals("digest_mismatch", result.reason)
        assertTrue(result.detail.orEmpty().startsWith("engine"))
        assertFalse(File(appsRoot, "vfd").exists())
        assertFalse(File(appsRoot, "apps.json").exists())
    }

    @Test
    fun `a refused apply keeps the previous copy`() {
        val good = TarFixtures.tarGz(mapOf("run.sh" to "echo v1\n"))
        serve(good)
        val supervisor = FakeSupervisor()
        val applier = applier(supervisor)
        applier.apply(config(app(artifacts = listOf(artifact("engine", good)))))

        val bad = TarFixtures.tarGz(mapOf("run.sh" to "echo v2\n"))
        serve(bad)
        val tampered =
            artifact(
                "engine",
                bad,
            ).copy(sha256 = TarFixtures.sha256("wrong".toByteArray()))
        val result = applier.apply(config(app(artifacts = listOf(tampered))))

        assertFalse(result.success)
        // The previous artifact is still there, untouched.
        assertTrue(File(appsRoot, "vfd/engine/run.sh").isFile)
        assertEquals("echo v1\n", File(appsRoot, "vfd/engine/run.sh").readText())
    }

    @Test
    fun `an unreachable supervisor refuses before anything is fetched`() {
        val result =
            applier(
                FakeSupervisor(reachable = false),
            ).apply(config(app(artifacts = emptyList())))

        assertFalse(result.success)
        assertEquals("supervisor_unreachable", result.reason)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an app removed from the config is stopped and its data kept`() {
        val archive = TarFixtures.tarGz(mapOf("run.sh" to "echo hi\n"))
        serve(archive)
        serve(archive)
        serve(archive)
        val supervisor = FakeSupervisor()
        val applier = applier(supervisor)
        applier.apply(
            config(
                app("vfd", listOf(artifact("engine", archive))),
                app("wiki", listOf(artifact("engine", archive))),
            ),
        )

        val result = applier.apply(config(app("vfd", listOf(artifact("engine", archive)))))

        assertTrue(result.success)
        assertEquals(listOf("wiki"), supervisor.stopped)
        // The removed app's directory and artifact are retained; only purge deletes them.
        assertTrue(File(appsRoot, "wiki/engine/run.sh").isFile)
    }

    @Test
    fun `a secret that will not decrypt refuses the apply`() {
        val archive = TarFixtures.tarGz(mapOf("run.sh" to "echo hi\n"))
        serve(archive)
        val secrets =
            SecretStore(
                key = { null },
                transport = { _, _ -> WrappedSecret("c1", "RSA-OAEP-SHA1") },
            )
        // A secret the store cannot open must refuse the apply before any install.
        val applier =
            DeployApplier(
                fetcher = ArtifactFetcher(OkHttpClient()),
                registry = AppRegistryWriter(),
                supervisor = FakeSupervisor(),
                stagingDir = staging,
                secrets = secrets,
            )

        val result =
            applier.apply(
                config(
                    app(
                        "vfd",
                        listOf(artifact("engine", archive)),
                    ).copy(secretNames = listOf("DATABASE_URL")),
                ),
            )

        assertFalse(result.success)
        assertEquals("secret_undecryptable", result.reason)
    }

    /** An artifact for one ABI, served from its own path so the two can be told apart. */
    private fun artifactFor(
        name: String,
        arch: String,
        body: String,
    ): ArtifactConfig {
        val bytes = TarFixtures.tarGz(mapOf("run.sh" to body))
        serve(bytes)

        return ArtifactConfig(
            name = name,
            arch = arch,
            url = server.url("/$name-$arch").toString(),
            sha256 = TarFixtures.sha256(bytes),
            kind = "dir",
        )
    }

    @Test
    fun `only the artifact for the device's ABI is installed`() {
        // Two builds of one artifact name, as a manifest that targets several ABIs
        // would declare. They install to the same path, so taking both means the
        // last one wins — the defect this test pins.
        val arm = artifactFor("engine", "arm64-v8a", "#!/bin/sh\necho arm\n")
        val x86 = artifactFor("engine", "x86_64", "#!/bin/sh\necho x86\n")

        val applier =
            DeployApplier(
                fetcher = ArtifactFetcher(OkHttpClient()),
                registry = AppRegistryWriter(),
                supervisor = FakeSupervisor(),
                stagingDir = staging,
                deviceAbis = listOf("arm64-v8a", "armeabi-v7a"),
            )

        val result = applier.apply(config(app(artifacts = listOf(arm, x86))))

        assertTrue(result.success)
        assertEquals(
            "#!/bin/sh\necho arm\n",
            File(appsRoot, "vfd/engine/run.sh").readText(),
        )
    }

    @Test
    fun `an artifact with no arch applies to every device`() {
        val archive = TarFixtures.tarGz(mapOf("run.sh" to "echo any\n"))
        serve(archive)
        val any = artifact("engine", archive)

        val applier =
            DeployApplier(
                fetcher = ArtifactFetcher(OkHttpClient()),
                registry = AppRegistryWriter(),
                supervisor = FakeSupervisor(),
                stagingDir = staging,
                deviceAbis = listOf("x86_64"),
            )

        assertTrue(applier.apply(config(app(artifacts = listOf(any)))).success)
    }

    @Test
    fun `an app with no build for this device is refused, not guessed`() {
        val x86 = artifactFor("engine", "x86_64", "echo x86\n")

        val applier =
            DeployApplier(
                fetcher = ArtifactFetcher(OkHttpClient()),
                registry = AppRegistryWriter(),
                supervisor = FakeSupervisor(),
                stagingDir = staging,
                deviceAbis = listOf("arm64-v8a"),
            )

        val result = applier.apply(config(app(artifacts = listOf(x86))))

        assertFalse(result.success)
        assertEquals("no_artifact_for_arch", result.reason)
    }
}
