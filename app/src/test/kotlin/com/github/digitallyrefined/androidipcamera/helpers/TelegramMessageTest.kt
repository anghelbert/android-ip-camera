package com.github.digitallyrefined.androidipcamera.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramMessageTest {

    @Test
    fun acceptsBotFatherTokens() {
        assertTrue(TelegramMessage.isValidToken("123456789:AAHdqTcvCH1vGWJxfSeofSAs0K5PALDsawQ"))
        assertTrue(TelegramMessage.isValidToken("7123456789:AAE-x_y1234567890abcdefghijklmnopqr"))
    }

    @Test
    fun rejectsMalformedTokens() {
        assertFalse(TelegramMessage.isValidToken(""))
        assertFalse(TelegramMessage.isValidToken("123456789"))
        assertFalse(TelegramMessage.isValidToken("abc:AAHdqTcvCH1vGWJxfSeofSAs0K5PALDsawQ"))
        assertFalse(TelegramMessage.isValidToken("123456789:short"))
        assertFalse(TelegramMessage.isValidToken("123456789:AAHdqTcvCH1vGWJxfSeofSAs0K5PALDsawQ/sendMessage"))
    }

    @Test
    fun chatIdIsNumberGroupOrChannel() {
        assertTrue(TelegramMessage.isValidChatId("123456789"))
        assertTrue(TelegramMessage.isValidChatId("-1001234567890"))
        assertTrue(TelegramMessage.isValidChatId("@mycamera_alerts"))
        assertFalse(TelegramMessage.isValidChatId(""))
        assertFalse(TelegramMessage.isValidChatId("12 34"))
        assertFalse(TelegramMessage.isValidChatId("@ab"))
        assertFalse(TelegramMessage.isValidChatId("mychat"))
    }

    @Test
    fun multipartBodyHasFieldsFileAndClosingBoundary() {
        val photo = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00, 0xFF.toByte(), 0xD9.toByte())
        val body = TelegramMessage.multipartBody(
            "B",
            linkedMapOf("chat_id" to "42", "caption" to "Movimento è qui"),
            TelegramMessage.FilePart("photo", "motion.jpg", "image/jpeg", photo)
        )
        val text = String(body, Charsets.ISO_8859_1)
        assertTrue(text.startsWith("--B\r\nContent-Disposition: form-data; name=\"chat_id\"\r\n"))
        assertTrue(String(body, Charsets.UTF_8).contains("\r\n\r\nMovimento è qui\r\n"))
        assertTrue(text.contains("name=\"photo\"; filename=\"motion.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n"))
        assertTrue(text.endsWith("\r\n--B--\r\n"))
        // The JPEG bytes are copied verbatim right before the closing boundary.
        val start = text.indexOf("image/jpeg\r\n\r\n") + "image/jpeg\r\n\r\n".length
        assertEquals(photo.toList(), body.copyOfRange(start, start + photo.size).toList())
    }

    @Test
    fun errorsExplainTheFix() {
        assertEquals("Telegram error 401: the bot token is wrong",
            TelegramMessage.explainError(401, """{"ok":false,"error_code":401,"description":"Unauthorized"}"""))
        assertTrue(TelegramMessage.explainError(400,
            """{"ok":false,"error_code":400,"description":"Bad Request: chat not found"}""").contains("/start"))
        assertEquals("Telegram error 500: Internal \"x\"",
            TelegramMessage.explainError(500, """{"ok":false,"description":"Internal \"x\""}"""))
        assertEquals("Telegram error 502", TelegramMessage.explainError(502, "<html>"))
    }

    @Test
    fun throttleSendsOnePerCooldown() {
        val t = MotionAlertThrottle()
        assertTrue(t.tryAcquire(1_000, 60_000))
        assertFalse(t.tryAcquire(30_000, 60_000))
        assertTrue(t.tryAcquire(61_000, 60_000))
        // Clock set back: do not stay silent until the old time comes around again.
        assertTrue(t.tryAcquire(5_000, 60_000))
        // Cooldown 0 lets every alert through.
        assertTrue(t.tryAcquire(5_001, 0))
    }
}
