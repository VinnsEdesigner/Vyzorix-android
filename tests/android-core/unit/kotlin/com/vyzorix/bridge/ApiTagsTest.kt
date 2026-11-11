package com.vyzorix.bridge

import com.vyzorix.bridge.apis.AppOpsApi
import com.vyzorix.bridge.apis.BatteryApi
import com.vyzorix.bridge.apis.DeviceInfoApi
import com.vyzorix.bridge.apis.EscalationReconApi
import com.vyzorix.bridge.apis.LogcatApi
import com.vyzorix.bridge.apis.NetworkApi
import com.vyzorix.bridge.apis.SmsReadApi
import com.vyzorix.bridge.apis.StorageApi
import com.vyzorix.bridge.apis.SystemUiStateApi
import com.vyzorix.bridge.apis.UsbAttachApi
import com.vyzorix.bridge.apis.UsbDetachApi
import com.vyzorix.bridge.apis.UsbStateApi
import com.vyzorix.bridge.apis.UserRestrictionsApi
import com.vyzorix.bridge.apis.WirelessDebugApi
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

    // Class references rather than instances: the annotation is on the class, and
    // the USB handlers are classes that need a broker to construct. The tag contract
    // is about the declaration, so a class reference is the honest subject.
    private val handlers =
        listOf(
            BatteryApi::class.java,
            DeviceInfoApi::class.java,
            StorageApi::class.java,
            NetworkApi::class.java,
            LogcatApi::class.java,
            SmsReadApi::class.java,
            UsbStateApi::class.java,
            UsbAttachApi::class.java,
            UsbDetachApi::class.java,
            UserRestrictionsApi::class.java,
            WirelessDebugApi::class.java,
            SystemUiStateApi::class.java,
            AppOpsApi::class.java,
            EscalationReconApi::class.java,
        )

    private fun tagsOf(handler: Class<*>): List<String> {
        val annotation =
            handler.getAnnotation(VyzorixApi::class.java)
                ?: error("${handler.simpleName} is missing @VyzorixApi")

        return annotation.tags.toList()
    }

    @Test
    fun `every api declares tags`() {
        for (handler in handlers) {
            val tags = tagsOf(handler)
            assertFalse("${handler.simpleName} declares no tags", tags.isEmpty())
        }
    }

    @Test
    fun `every declared tag is in the shared vocabulary`() {
        val vocabulary =
            setOf(
                ApiTags.DATA_PUBLIC,
                ApiTags.DATA_APP,
                ApiTags.DATA_HARDWARE,
                ApiTags.DATA_PRIVATE,
                ApiTags.OP_READ,
                ApiTags.OP_WRITE,
                ApiTags.PROTECTION_NORMAL,
                ApiTags.PROTECTION_DANGEROUS,
                ApiTags.PROTECTION_SIGNATURE,
                ApiTags.VISIBILITY_PUBLIC,
                ApiTags.VISIBILITY_PRIVATE,
                ApiTags.HW_USB,
            )

        for (handler in handlers) {
            for (tag in tagsOf(handler)) {
                assertTrue(
                    "${handler.simpleName} declares tag $tag, which is not in the vocabulary",
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
                ApiTags.DATA_HARDWARE,
                ApiTags.DATA_PRIVATE,
                ApiTags.OP_READ,
                ApiTags.OP_WRITE,
                ApiTags.PROTECTION_NORMAL,
                ApiTags.PROTECTION_DANGEROUS,
                ApiTags.PROTECTION_SIGNATURE,
                ApiTags.VISIBILITY_PUBLIC,
                ApiTags.VISIBILITY_PRIVATE,
                ApiTags.HW_USB,
            )

        for (tag in vocabulary) {
            assertTrue("tag $tag is in the vocabulary but no API declares it", tag in declared)
        }
    }

    @Test
    fun `the permission-free five declare protection normal`() {
        // These five were chosen so the bridge needs no runtime permission; if one
        // ever declares protection:dangerous it has stopped being headless-testable.
        val five =
            listOf(
                BatteryApi::class.java,
                DeviceInfoApi::class.java,
                StorageApi::class.java,
                NetworkApi::class.java,
                LogcatApi::class.java,
            )

        for (handler in five) {
            assertTrue(
                "${handler.simpleName} must declare protection:normal",
                ApiTags.PROTECTION_NORMAL in tagsOf(handler),
            )
        }
    }

    @Test
    fun `the usb handlers declare the hardware capability tags`() {
        // USB is the first hardware capability: hw:usb and data:hardware, and
        // protection:signature to match the server catalog's USB class. The write
        // half (attach/detach) additionally declares op:write.
        val usb =
            listOf(
                UsbStateApi::class.java,
                UsbAttachApi::class.java,
                UsbDetachApi::class.java,
            )

        for (handler in usb) {
            val tags = tagsOf(handler)
            assertTrue("${handler.simpleName} must declare hw:usb", ApiTags.HW_USB in tags)
            assertTrue(
                "${handler.simpleName} must declare data:hardware",
                ApiTags.DATA_HARDWARE in tags,
            )
            assertTrue(
                "${handler.simpleName} must declare protection:signature",
                ApiTags.PROTECTION_SIGNATURE in tags,
            )
        }

        for (handler in listOf(UsbAttachApi::class.java, UsbDetachApi::class.java)) {
            assertTrue(
                "${handler.simpleName} is a write API and must declare op:write",
                ApiTags.OP_WRITE in tagsOf(handler),
            )
        }

        assertTrue(
            "usb_state is a read and must not declare op:write",
            ApiTags.OP_WRITE !in tagsOf(UsbStateApi::class.java),
        )
    }

    @Test
    fun `logcat is private and the rest are not`() {
        assertTrue(ApiTags.VISIBILITY_PRIVATE in tagsOf(LogcatApi::class.java))
        assertTrue(ApiTags.VISIBILITY_PUBLIC in tagsOf(BatteryApi::class.java))
    }
}
