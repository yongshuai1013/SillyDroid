package com.jm.sillydroid.feature.main.ui.home.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.gecko.util.GeckoBundle

class GeckoNativeMessageCodecTest {

    @Test
    fun `success response uses gecko bundle instead of json object`() {
        val response = GeckoNativeMessageCodec.successResponse("host-version-json")

        assertTrue(response.getBoolean("ok"))
        assertEquals("host-version-json", response.getString("result"))
    }

    @Test
    fun `error response is directly serializable by gecko native messaging`() {
        val response = GeckoNativeMessageCodec.errorResponse("untrusted_sender")

        assertFalse(response.getBoolean("ok"))
        assertEquals("untrusted_sender", response.getString("error"))
    }

    @Test
    fun `gecko bundle payload can be parsed without json conversion`() {
        val payload = GeckoBundle(2).apply {
            putString("action", "getHostVersionInfo")
            putString("payload", "")
        }

        val message = GeckoNativeMessageCodec.toPayloadOrNull(payload)

        assertEquals("getHostVersionInfo", message?.action)
        assertEquals("", message?.payload)
    }

    @Test
    fun `gecko bundle object payload exposes string fields`() {
        val objectPayload = GeckoBundle(2).apply {
            putString("statusBarColor", "#112233")
            putString("navigationBarColor", "#445566")
        }

        assertEquals("#112233", GeckoNativeMessageCodec.objectString(objectPayload, "statusBarColor"))
        assertEquals("#445566", GeckoNativeMessageCodec.objectString(objectPayload, "navigationBarColor"))
    }
}
