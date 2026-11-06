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

    // Operation. A read is repeatable; a write is not, and will need its own risk
    // tier when the first one lands. There is deliberately no `op:write` constant
    // yet: the dead-code gate fails on a constant no API uses, and that is the right
    // behaviour — an unused tag is a claim nothing makes. Add it with the API that
    // needs it, not before.
    const val OP_READ = "op:read"

    // Android permission class, matching the boundary contract's `protection:*`
    // vocabulary. `protection:dangerous` is a runtime-granted permission and is the
    // tag a reviewer should stop on.
    const val PROTECTION_NORMAL = "protection:normal"
    const val PROTECTION_DANGEROUS = "protection:dangerous"

    // Who may see the result.
    const val VISIBILITY_PUBLIC = "visibility:public"
    const val VISIBILITY_PRIVATE = "visibility:private"
}
