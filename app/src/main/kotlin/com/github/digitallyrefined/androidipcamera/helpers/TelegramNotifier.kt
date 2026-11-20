package com.github.digitallyrefined.androidipcamera.helpers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import androidx.preference.PreferenceManager
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Sends motion alerts, with a photo when one is available, to a Telegram chat through a bot.
 *
 * The bot token is a secret (anyone holding it controls the bot), so it is kept in [SecureStorage]
 * and never logged: every request URL contains it, so only the outcome is logged, never the URL.
 * The pure-JVM parts live in [TelegramMessage] so they can be unit tested.
 */
object TelegramNotifier {
    private const val TAG = "TelegramNotifier"

    const val PREF_ENABLED = "telegram_enabled"
    const val PREF_CHAT_ID = "telegram_chat_id"
    const val PREF_COOLDOWN = "telegram_cooldown_seconds"
    const val DEFAULT_COOLDOWN_SECONDS = 60

    private const val API_BASE = "https://api.telegram.org/bot"
    private const val TIMEOUT_MS = 20_000
    /** Longest side of the photo sent: plenty on a phone screen and quick to upload on 4G. */
    private const val PHOTO_MAX_SIDE = 1280
    private const val PHOTO_QUALITY = 80

    private val throttle = MotionAlertThrottle()

    /** Outcome of one request: [ok], or the reason it failed in words a user can act on. */
    data class Result(val ok: Boolean, val message: String)

    fun token(context: Context): String =
        (SecureStorage(context).getSecureString(SecureStorage.KEY_TELEGRAM_TOKEN, "") ?: "").trim()

    fun chatId(context: Context): String =
        (PreferenceManager.getDefaultSharedPreferences(context).getString(PREF_CHAT_ID, "") ?: "").trim()

    fun isEnabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_ENABLED, false)

    /** True when alerts are switched on and both the token and the chat id look valid. */
    fun isConfigured(context: Context): Boolean =
        isEnabled(context) &&
            TelegramMessage.isValidToken(token(context)) &&
            TelegramMessage.isValidChatId(chatId(context))

    private fun cooldownMs(context: Context): Long {
        val raw = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(PREF_COOLDOWN, DEFAULT_COOLDOWN_SECONDS.toString())
        val seconds = raw?.trim()?.toIntOrNull()?.coerceIn(0, 86_400) ?: DEFAULT_COOLDOWN_SECONDS
        return seconds * 1000L
    }

    /**
     * Sends a motion alert unless one went out less than the configured cooldown ago. [photo]
     * captures a JPEG (it may be slow or return null); it is only called when an alert will be sent.
     * Blocking: call it off the main thread.
     */
    fun sendMotionAlert(context: Context, cameraName: String, photo: () -> ByteArray?) {
        if (!isConfigured(context)) return
        if (!throttle.tryAcquire(System.currentTimeMillis(), cooldownMs(context))) {
            Log.i(TAG, "Motion alert skipped: cooldown")
            return
        }
        val time = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())
        val caption = TelegramMessage.motionCaption(cameraName, time)
        val jpeg = try {
            photo()?.let { shrinkJpeg(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Motion photo failed: ${e.message}")
            null
        }
        val result = if (jpeg != null) {
            sendPhoto(token(context), chatId(context), caption, jpeg)
        } else {
            sendMessage(token(context), chatId(context), "$caption\n(photo not available)")
        }
        Log.i(TAG, "Motion alert ${if (result.ok) "sent" else "failed: ${result.message}"}")
    }

    /** Sends a plain text message; used by the "Send test message" setting. Blocking. */
    fun sendTest(context: Context): Result {
        val token = token(context)
        val chatId = chatId(context)
        if (!TelegramMessage.isValidToken(token)) return Result(false, "The bot token is missing or not valid")
        if (!TelegramMessage.isValidChatId(chatId)) return Result(false, "The chat ID is missing or not valid")
        return sendMessage(token, chatId, "✅ Android IP Camera (${deviceName()}) is connected to this chat.")
    }

    fun deviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    private fun sendMessage(token: String, chatId: String, text: String): Result {
        val boundary = "----AndroidIpCamera" + UUID.randomUUID().toString().replace("-", "")
        val body = TelegramMessage.multipartBody(boundary, mapOf("chat_id" to chatId, "text" to text), null)
        return post(token, "sendMessage", boundary, body)
    }

    private fun sendPhoto(token: String, chatId: String, caption: String, jpeg: ByteArray): Result {
        val boundary = "----AndroidIpCamera" + UUID.randomUUID().toString().replace("-", "")
        val body = TelegramMessage.multipartBody(
            boundary,
            mapOf("chat_id" to chatId, "caption" to caption),
            TelegramMessage.FilePart("photo", "motion.jpg", "image/jpeg", jpeg)
        )
        return post(token, "sendPhoto", boundary, body)
    }

    private fun post(token: String, method: String, boundary: String, body: ByteArray): Result {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("$API_BASE$token/$method").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                setFixedLengthStreamingMode(body.size)
            }
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val response = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code in 200..299) Result(true, "Sent")
            else Result(false, TelegramMessage.explainError(code, response))
        } catch (e: java.net.UnknownHostException) {
            Result(false, "No internet connection")
        } catch (e: java.net.SocketTimeoutException) {
            Result(false, "Telegram did not answer in time")
        } catch (e: Exception) {
            // The exception text can contain the request URL, and with it the token: keep it out.
            Result(false, "Network error (${e.javaClass.simpleName})")
        } finally {
            conn?.disconnect()
        }
    }

    /** Scales a full-resolution still down to [PHOTO_MAX_SIDE] so it uploads in a second or two. */
    private fun shrinkJpeg(jpeg: ByteArray): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= PHOTO_MAX_SIDE) return jpeg
        var sample = 1
        while (longest / (sample * 2) >= PHOTO_MAX_SIDE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size,
            BitmapFactory.Options().apply { inSampleSize = sample }) ?: return jpeg
        val scale = PHOTO_MAX_SIDE.toFloat() / maxOf(decoded.width, decoded.height)
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
        } else decoded
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, PHOTO_QUALITY, out)
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()
        return out.toByteArray()
    }
}

/** Lets one alert through per cooldown window, so a person walking around sends one message. */
class MotionAlertThrottle {
    private var lastSentMs = Long.MIN_VALUE

    @Synchronized
    fun tryAcquire(nowMs: Long, cooldownMs: Long): Boolean {
        // A clock set back must not block alerts for the size of the jump.
        if (lastSentMs != Long.MIN_VALUE && nowMs >= lastSentMs && nowMs - lastSentMs < cooldownMs) return false
        lastSentMs = nowMs
        return true
    }
}

/** Pure-JVM helpers for [TelegramNotifier]. */
object TelegramMessage {
    private val TOKEN = Regex("^[0-9]{5,16}:[A-Za-z0-9_-]{30,64}$")
    private val NUMERIC_CHAT = Regex("^-?[0-9]{1,20}$")
    private val CHANNEL_CHAT = Regex("^@[A-Za-z][A-Za-z0-9_]{4,31}$")
    private val DESCRIPTION = Regex("\"description\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

    class FilePart(val field: String, val fileName: String, val contentType: String, val bytes: ByteArray)

    fun isValidToken(token: String): Boolean = TOKEN.matches(token)

    fun isValidChatId(chatId: String): Boolean = NUMERIC_CHAT.matches(chatId) || CHANNEL_CHAT.matches(chatId)

    fun motionCaption(cameraName: String, time: String): String =
        "🚨 Motion detected\n📷 $cameraName\n🕒 $time"

    fun multipartBody(boundary: String, fields: Map<String, String>, file: FilePart?): ByteArray {
        val out = ByteArrayOutputStream()
        fun line(s: String) = out.write("$s\r\n".toByteArray(Charsets.UTF_8))
        fields.forEach { (name, value) ->
            line("--$boundary")
            line("Content-Disposition: form-data; name=\"$name\"")
            line("Content-Type: text/plain; charset=UTF-8")
            line("")
            line(value)
        }
        if (file != null) {
            line("--$boundary")
            line("Content-Disposition: form-data; name=\"${file.field}\"; filename=\"${file.fileName}\"")
            line("Content-Type: ${file.contentType}")
            line("")
            out.write(file.bytes)
            line("")
        }
        line("--$boundary--")
        return out.toByteArray()
    }

    /** Turns a failed Bot API reply into a hint the user can act on. */
    fun explainError(code: Int, response: String): String {
        val description = DESCRIPTION.find(response)?.groupValues?.get(1)?.replace("\\\"", "\"")
        val hint = when (code) {
            401, 404 -> "the bot token is wrong"
            400 -> if (description?.contains("chat not found", ignoreCase = true) == true)
                "chat not found: check the chat ID and send /start to the bot first" else null
            403 -> "the bot cannot write to this chat: send /start to the bot (or add it to the group)"
            429 -> "too many messages, Telegram asked to slow down"
            else -> null
        }
        return listOfNotNull("Telegram error $code", hint, description?.takeIf { hint == null }).joinToString(": ")
    }
}
