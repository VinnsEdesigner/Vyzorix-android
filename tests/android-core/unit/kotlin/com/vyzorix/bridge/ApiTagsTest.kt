package com.vyzorix.bridge

import com.vyzorix.bridge.apis.BatteryApi
import com.vyzorix.bridge.apis.DeviceInfoApi
import com.vyzorix.bridge.apis.LogcatApi
import com.vyzorix.bridge.apis.NetworkApi
import com.vyzorix.bridge.apis.StorageApi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The declared capability tags must match the vocabulary the server's catalog
 * uses, and no tag may be inert. The annotation is documentation, but documentation
 * that disagrees with the server is worse than none — it is a second, wrong
 * classification of the same API.
 */
class ApiTagsTest {

    private val handlers =
        listOf(BatteryApi, DeviceInfoApi, StorageApi, NetworkApi, LogcatApi)

    private fun tagsOf(handler: Any): List<String> {
        val annotation =
            handler.javaClass.getAnnotation(VyzorixApi::class.java)
                ?: error("${handler.javaClass.simpleName} is missing @VyzorixApi")

        return annotation.tags.toList()
    }

    @Test
    fun `every api declares tags`() {
        for (handler in handlers) {
            val tags = tagsOf(handler)
            assertFalse("${handler.javaClass.simpleName} declares no tags", tags.isEmpty())
        }
    }

    @Test
    fun `every declared tag is in the shared vocabulary`() {
        val vocabulary =
            setOf(
                ApiTags.DATA_PUBLIC,
                ApiTags.DATA_APP,
                ApiTags.DATA_PRIVATE,
                ApiTags.OP_READ,
                ApiTags.PROTECTION_NORMAL,
                ApiTags.VISIBILITY_PUBLIC,
                ApiTags.VISIBILITY_PRIVATE,
            )

        for (handler in handlers) {
            for (tag in tagsOf(handler)) {
                assertTrue(
                    "${handler.javaClass.simpleName} declares tag $tag, which is not in the vocabulary",
                    tag in vocabulary,
                )
            }
        }
    }

    @Test
    fun `no tag is inert`() {
        // Every tag in the vocabulary must be declared by at least one API. A tag
        // nothing declares reads as a capability nothing exercises — the same rule
        // the server catalog enforces with TestNoInertTags.
        val declared = handlers.flatMap { tagsOf(it) }.toSet()

        val vocabulary =
            listOf(
                ApiTags.DATA_PUBLIC,
                ApiTags.DATA_APP,
                ApiTags.DATA_PRIVATE,
                ApiTags.OP_READ,
                ApiTags.PROTECTION_NORMAL,
                ApiTags.VISIBILITY_PUBLIC,
                ApiTags.VISIBILITY_PRIVATE,
            )

        for (tag in vocabulary) {
            assertTrue("tag $tag is in the vocabulary but no API declares it", tag in declared)
        }
    }

    @Test
    fun `the permission-free five declare protection normal`() {
        // These five were chosen so the bridge needs no runtime permission; if one
        // ever declares protection:dangerous it has stopped being headless-testable.
        for (handler in handlers) {
            assertTrue(
                "${handler.javaClass.simpleName} must declare protection:normal",
                ApiTags.PROTECTION_NORMAL in tagsOf(handler),
            )
        }
    }

    @Test
    fun `logcat is private and the rest are not`() {
        assertTrue(ApiTags.VISIBILITY_PRIVATE in tagsOf(LogcatApi))
        assertTrue(ApiTags.VISIBILITY_PUBLIC in tagsOf(BatteryApi))
    }
}
