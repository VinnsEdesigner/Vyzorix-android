package com.vyzorix.bridge

/**
 * Declares an API's capability tags.
 *
 * The tag vocabulary is shared with the server's capability catalog
 * (`documents/ANDROID_SERVICE_BOUNDARY_CONTRACT.md`), so the device and the server
 * classify the same API with the same words. The annotation is **documentation**:
 * the server's catalog is the authority, and the device never evaluates tags. It
 * exists so a reader sees an API's capability next to its code, and so a test can
 * assert the device's declared tags match the server's catalog.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class VyzorixApi(
    val tags: Array<String>,
)

/**
 * The capability tags the device's APIs declare.
 *
 * Only the tags the device actually uses are defined. A tag no API declares is
 * inert — it reads as a capability nothing exercises — so the vocabulary grows
 * with the APIs that need it, the same rule the server catalog follows.
 * `ApiTagsTest` enforces this: a constant here that no handler names fails the
 * gate.
 */
object ApiTags {
    // Data sensitivity.
    const val DATA_PUBLIC = "data:public"
    const val DATA_APP = "data:app"
    const val DATA_PRIVATE = "data:private"

    // Operation. A read is repeatable; a write is not, and carries its own risk
    // tier. `op:write` is declared by the first write APIs — the USB attach/detach
    // pair — rather than added ahead of them.
    const val OP_READ = "op:read"

    // Android permission class, matching the boundary contract's `protection:*`
    // vocabulary. `protection:dangerous` is a runtime-granted permission and is the
    // tag a reviewer should stop on.
    const val PROTECTION_NORMAL = "protection:normal"
    const val PROTECTION_DANGEROUS = "protection:dangerous"

    // Signature-level trust: the capability is not grantable by a normal runtime
    // dialog. The server's USB capability uses this class, so a USB handler
    // declares it too — the two classifications must not drift.
    const val PROTECTION_SIGNATURE = "protection:signature"

    // Hardware touched. Only `usb` exists because only USB is in the catalogue;
    // camera/location/mic are added with their APIs, not before.
    const val HW_USB = "hw:usb"

    // Data sensitivity for hardware identity: the attached-device list, the SoC, a
    // device's serial. Distinct from `data:public` (battery level) because it names
    // physical hardware an operator may not want every role to see.
    const val DATA_HARDWARE = "data:hardware"

    // A write operation: it changes device or host state and is not repeatable in
    // the way a read is. First used by the USB attach/detach APIs, which claim a
    // hardware device.
    const val OP_WRITE = "op:write"

    // Who may see the result.
    const val VISIBILITY_PUBLIC = "visibility:public"
    const val VISIBILITY_PRIVATE = "visibility:private"
}
