package com.sunshine.mesh.data.update

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Minimal GitHub Releases client. HttpURLConnection and two JSON string fields
 * are enough, so the app does not pull in an HTTP or JSON dependency.
 */
class GitHubUpdateClient(
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 5_000,
) : UpdateClient {

    override suspend fun fetchLatestRelease(): LatestReleaseResult =
        withContext(ioDispatcher) {
            val url = try {
                URL(endpoint)
            } catch (error: Exception) {
                return@withContext LatestReleaseResult.Failed
            }
            if (url.protocol != "https") return@withContext LatestReleaseResult.Failed

            var connection: HttpURLConnection? = null
            try {
                connection = (url.openConnection() as? HttpURLConnection)
                    ?: return@withContext LatestReleaseResult.Failed
                connection.requestMethod = "GET"
                connection.connectTimeout = connectTimeoutMs
                connection.readTimeout = readTimeoutMs
                connection.useCaches = false
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", "Sunshine-Android")
                connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")

                when (val code = connection.responseCode) {
                    HttpURLConnection.HTTP_NOT_FOUND -> LatestReleaseResult.NotFound
                    in 200..299 -> {
                        val body = connection.inputStream
                            .bufferedReader(StandardCharsets.UTF_8)
                            .use { it.readText() }
                        parseLatestRelease(body)
                            ?.let(LatestReleaseResult::Found)
                            ?: LatestReleaseResult.Failed
                    }
                    else -> LatestReleaseResult.HttpError(code)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                LatestReleaseResult.NoConnection
            } catch (error: Exception) {
                LatestReleaseResult.Failed
            } finally {
                connection?.disconnect()
            }
        }

    private companion object {
        const val DEFAULT_ENDPOINT =
            "https://api.github.com/repos/an9nim/Sunshine/releases/latest"
    }
}

/**
 * GitHub returns a large release object; only `tag_name` and the top-level
 * `html_url` are needed. The regex allows escaped JSON string characters.
 */
internal fun parseLatestRelease(json: String): LatestRelease? {
    val tag = extractJsonString(json, "tag_name") ?: return null
    val url = extractJsonString(json, "html_url") ?: return null
    if (tag.isBlank() || !url.startsWith("https://")) return null
    return LatestRelease(tag = tag, downloadUrl = url)
}

private fun extractJsonString(json: String, field: String): String? {
    val pattern = Regex(
        "\"${Regex.escape(field)}\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"",
    )
    val value = pattern.find(json)?.groupValues?.getOrNull(1) ?: return null
    return unescapeJson(value)
}

private fun unescapeJson(value: String): String {
    if ('\\' !in value) return value

    val result = StringBuilder(value.length)
    var index = 0
    while (index < value.length) {
        val current = value[index++]
        if (current != '\\' || index >= value.length) {
            result.append(current)
            continue
        }
        when (val escaped = value[index++]) {
            '"' -> result.append('"')
            '\\' -> result.append('\\')
            '/' -> result.append('/')
            'b' -> result.append('\b')
            'f' -> result.append('\u000C')
            'n' -> result.append('\n')
            'r' -> result.append('\r')
            't' -> result.append('\t')
            'u' -> {
                if (index + 4 <= value.length) {
                    val code = value.substring(index, index + 4).toIntOrNull(16)
                    if (code != null) {
                        result.append(code.toChar())
                        index += 4
                    } else {
                        result.append(escaped)
                    }
                } else {
                    result.append(escaped)
                }
            }
            else -> result.append(escaped)
        }
    }
    return result.toString()
}
