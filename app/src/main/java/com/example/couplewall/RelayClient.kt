package com.example.couplewall

import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal client for an ntfy-style publish/subscribe relay (default https://ntfy.sh). The relay only ever sees
 * an unguessable topic name and AES-GCM ciphertext. Replace BASE_URL with your own server to self-host.
 */
class RelayClient(private val baseUrl: String) {
    @Volatile private var connection: HttpURLConnection? = null

    /** Publishes one message; false on any failure (network, rate limit). */
    fun publish(topic: String, text: String): Boolean {
        val c = URL("$baseUrl/$topic").openConnection() as HttpURLConnection
        return try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            val body = text.toByteArray(Charsets.UTF_8)
            c.setFixedLengthStreamingMode(body.size)
            c.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            c.outputStream.use { it.write(body) }
            val ok = c.responseCode in 200..299
            try { (if (ok) c.inputStream else c.errorStream)?.close() } catch (e: IOException) { }
            ok
        } catch (e: IOException) {
            false
        } finally {
            c.disconnect()
        }
    }

    /**
     * Streams messages (newline-delimited JSON) until the connection drops, [active] turns false or [abort] is called.
     * [since] is "all" or the id of the last message already processed.
     */
    fun subscribe(
        topic: String,
        since: String,
        active: () -> Boolean,
        onOpen: () -> Unit,
        onMessage: (id: String, text: String) -> Unit,
    ) {
        val c = URL("$baseUrl/$topic/json?since=$since").openConnection() as HttpURLConnection
        connection = c
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 120_000
            if (c.responseCode != 200) throw IOException("http ${c.responseCode}")
            BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8)).use { reader ->
                while (active()) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    val o = JSONObject(line)
                    when (o.optString("event")) {
                        "open" -> onOpen()
                        "message" -> onMessage(o.optString("id"), o.optString("message"))
                    }
                }
            }
        } finally {
            connection = null
            c.disconnect()
        }
    }

    fun abort() {
        try { connection?.disconnect() } catch (e: Exception) { }
    }
}
