package com.jeffarese.rexmobile.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** A published version of the app: its APK and what changed. */
data class Release(
    /** Such as "0.3.2", from a tag such as "v0.3.2". */
    val version: String,
    /** The release's notes, as written on GitHub. */
    val notes: String,
    /** The APK through GitHub's API, which serves private and public repositories alike. */
    val apkUrl: String,
    val apkName: String,
    val size: Long,
    /** The APK's SHA-256 in hex, when GitHub has it. */
    val sha256: String?,
) {
    fun toJson(): String = buildJsonObject {
        put("version", version)
        put("notes", notes)
        put("apkUrl", apkUrl)
        put("apkName", apkName)
        put("size", size)
        sha256?.let { put("sha256", it) }
    }.toString()

    companion object {
        fun fromJson(text: String): Release? = runCatching {
            val o = Json.parseToJsonElement(text).jsonObject
            Release(
                o.str("version")!!, o.str("notes").orEmpty(), o.str("apkUrl")!!, o.str("apkName")!!,
                o["size"]?.jsonPrimitive?.longOrNull ?: 0, o.str("sha256"),
            )
        }.getOrNull()
    }
}

private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull

/**
 * [a] against [b] number by number, so 0.10.0 is after 0.9.3; a leading "v" and anything after a
 * "-" or "+" are ignored.
 */
fun compareVersions(a: String, b: String): Int {
    fun parts(v: String) = v.trim().removePrefix("v").substringBefore('-').substringBefore('+')
        .split('.').map { it.toIntOrNull() ?: 0 }
    val x = parts(a)
    val y = parts(b)
    for (i in 0 until maxOf(x.size, y.size)) {
        val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
        if (c != 0) return c
    }
    return 0
}

/**
 * The app's releases on GitHub, in [repo] ("owner/name"). [token], a fine-grained token that can only
 * read that repository, reaches it while it is private. Once the repository is public, a token that
 * has expired or been revoked is dropped and the request made again without it, so builds that carry
 * one keep finding updates.
 */
class ReleaseFeed(private val repo: String, private val token: String, private val http: OkHttpClient) {
    @Volatile private var useToken = token.isNotBlank()
    /** GitHub refused the token. A private repository is then a 404, which mustn't read as no release. */
    @Volatile private var tokenRefused = false

    /** The newest release with an APK, or null if there is none. Blocking. */
    fun latest(): Release? {
        val request = Request.Builder().url("$API/repos/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        return call(request).use { r ->
            when {
                r.code == 404 && tokenRefused -> throw IOException("GitHub refused the update token")
                r.code == 404 -> null
                !r.isSuccessful -> throw IOException(describe(r))
                else -> parse(r.body!!.string())
            }
        }
    }

    /**
     * Download [release]'s APK to [dest], reporting the bytes so far and the total, and check it
     * against GitHub's SHA-256 when there is one. Blocking.
     */
    fun download(release: Release, dest: File, onProgress: (Long, Long) -> Unit) {
        val request = Request.Builder().url(release.apkUrl).header("Accept", "application/octet-stream").build()
        val part = File(dest.path + ".part")
        val digest = MessageDigest.getInstance("SHA-256")
        call(request).use { r ->
            if (!r.isSuccessful) throw IOException(describe(r))
            val body = r.body!!
            val total = body.contentLength().takeIf { it > 0 } ?: release.size
            body.byteStream().use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        read += n
                        if (read - lastReport >= 128 * 1024) {
                            lastReport = read
                            onProgress(read, total)
                        }
                    }
                    onProgress(read, total)
                }
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        if (release.sha256 != null && !hex.equals(release.sha256, ignoreCase = true)) {
            part.delete()
            throw IOException("The download was damaged")
        }
        if (!part.renameTo(dest)) throw IOException("Couldn't save the download")
    }

    /**
     * [request] with the token while it works. GitHub answers 401 to a bad token even for a public
     * repository, so after one the token is left off for good, and a private one is out of reach. OkHttp drops the header itself when
     * a download is redirected to GitHub's file storage, which refuses requests that carry it.
     */
    private fun call(request: Request): Response {
        if (useToken) {
            val r = http.newCall(request.newBuilder().header("Authorization", "Bearer $token").build()).execute()
            if (r.code != 401) return r
            r.close()
            useToken = false
            tokenRefused = true
        }
        return http.newCall(request).execute()
    }

    private fun describe(r: Response) = when (r.code) {
        401, 403 -> if (r.header("x-ratelimit-remaining") == "0") "GitHub's limit is reached; try again later" else "GitHub refused (${r.code})"
        else -> "GitHub answered ${r.code}"
    }

    companion object {
        private const val API = "https://api.github.com"

        /** A release from GitHub's JSON for one, or null if it has no APK or no version in its tag. */
        fun parse(json: String): Release? {
            val o = Json.parseToJsonElement(json).jsonObject
            if (o["draft"]?.jsonPrimitive?.contentOrNull == "true") return null
            val version = o.str("tag_name")?.removePrefix("v")?.takeIf { it.firstOrNull()?.isDigit() == true } ?: return null
            val apk = o["assets"]?.jsonArray?.map { it.jsonObject }?.firstOrNull { it.str("name")?.endsWith(".apk") == true } ?: return null
            return Release(
                version = version,
                notes = o.str("body").orEmpty().replace("\r\n", "\n").trim(),
                apkUrl = apk.str("url") ?: return null,
                apkName = apk.str("name")!!,
                size = apk["size"]?.jsonPrimitive?.longOrNull ?: 0,
                sha256 = apk.str("digest")?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:"),
            )
        }
    }
}

/** One line of a release's notes, and whether it was a list item. */
data class NoteLine(val text: String, val item: Boolean)

/** A release's notes as lines to show: headings, list markers and emphasis without their marks, blank lines gone. */
fun releaseNoteLines(notes: String): List<NoteLine> = notes.lines().mapNotNull { raw ->
    val line = raw.trim()
    val item = LIST_ITEM.containsMatchIn(line)
    val text = line.replace(HEADING, "").replace(LIST_ITEM, "").replace("**", "").replace("`", "").trim()
    text.takeIf { it.isNotEmpty() }?.let { NoteLine(it, item) }
}

private val HEADING = Regex("^#+\\s*")
private val LIST_ITEM = Regex("^[-*•]\\s+")
