package com.vyzorix.connector.deploy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The report frame: the server routes on `type` and records the version, so the
 * discriminator and the field names are the contract.
 */
class DeployReporterTest {
    @Test
    fun `a success frame carries device_ready and the version`() {
        val json = DeployReporter.frame(ApplyResult.ok(7)).toJson()

        assertTrue(json.contains("\"type\":\"device_ready\""))
        assertTrue(json.contains("\"version\":7"))
        assertTrue(json.contains("\"success\":true"))
    }

    @Test
    fun `a refusal frame carries the reason and detail`() {
        val frame = DeployReporter.frame(ApplyResult.refused(7, "digest_mismatch", "engine"))

        assertEquals("device_ready", frame.type)
        assertEquals("digest_mismatch", frame.reason)
        assertEquals("engine", frame.detail)
    }
}
