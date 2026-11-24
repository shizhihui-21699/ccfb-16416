package com.privateime.cloud

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * An HTTPS transport for [CloudLexiconClient].
 *
 * ## The contract this class must not break
 *
 * The privacy policy is enforced one layer up, in [CloudLexiconClient], which
 * refuses to call a transport at all when the field is guarded or locked, when the
 * prefix looks like a credential, or when the user disabled cloud suggestions.
 * This class therefore never receives field content — only a bounded composing
 * prefix or a list of *ordinary* words. It still takes no shortcuts:
 *
 *  * it never logs a request or response body (a prefix is user content),
 *  * it never sends the package name, device id, or any identifier beyond a
 *    rotated anonymous install id,
 *  * failures return empty, never a cached response (a stale suggestion is worse
 *    than none, and caching would persist user content on disk).
 *
 * ## Why not a network library
 *
 * The dependency surface of a keyboard should be near zero; this is a few dozen
 * lines of `HttpURLConnection` and no third-party code sees the data.
 */
class HttpCloudTransport(
    private val endpointBase: String,
    private val installIdProvider: () -> String,
) : CloudLexiconClient.Transport {

    override fun fetchCompletions(prefix: String, locale: String): List<String> {
        val payload = JSONObject().apply {
            put("p", prefix)
            put("l", locale)
        }
        val response = post("/suggest", payload.toString()) ?: return emptyList()
        return try {
            val array = JSONObject(response).optJSONArray("candidates") ?: JSONArray()
            val out = ArrayList<String>(array.length())
            for (i in 0 until array.length()) {
                val candidate = array.optString(i, "")
                if (candidate.isNotBlank() && candidate.length <= 48) out.add(candidate)
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun uploadVocabulary(words: List<String>, locale: String): Boolean {
        if (words.isEmpty()) return false
        val payload = JSONObject().apply {
            put("words", JSONArray(words))
            put("l", locale)
        }
        return post("/vocabulary", payload.toString()) != null
    }

    private fun post(path: String, body: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(endpointBase.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                // An anonymous, rotatable id. No package name, no device id.
                setRequestProperty("X-Install-Id", installIdProvider())
            }
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body) }
            if (connection.responseCode !in 200..299) {
                // Status only: the response body may echo the request.
                Log.w(TAG, "cloud request failed with HTTP " + connection.responseCode)
                return null
            }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "cloud request failed: " + e.javaClass.simpleName)
            null
        } finally {
            connection?.disconnect()
        }
    }

    private companion object {
        const val TAG = "HttpCloudTransport"
        const val CONNECT_TIMEOUT_MS = 2_000
        const val READ_TIMEOUT_MS = 2_500
    }
}
