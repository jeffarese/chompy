package com.jeffarese.rexmobile.model

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.security.MessageDigest

/** GitHub's releases, as the updater reads and downloads them, against a fake GitHub. */
class ReleaseFeedTest {
    @get:Rule val dir = TemporaryFolder()

    private val apkBytes = ByteArray(300_000) { (it % 251).toByte() }
    private val apkSha = MessageDigest.getInstance("SHA-256").digest(apkBytes).joinToString("") { "%02x".format(it) }

    private fun releaseJson(tag: String = "v0.3.2", digest: String? = "sha256:$apkSha", body: String = "- Faster\\r\\n- Smaller") = """
        {"tag_name":"$tag","draft":false,"body":"$body","assets":[
          {"name":"notes.txt","url":"https://api.github.com/repos/o/r/releases/assets/1","size":10},
          {"name":"rex-mobile-0.3.2.apk","url":"https://api.github.com/repos/o/r/releases/assets/2","size":${apkBytes.size}
           ${digest?.let { ",\"digest\":\"$it\"" } ?: ""}}
        ]}
    """.trimIndent()

    /** A fake GitHub that answers [answer] and keeps each request it was sent. */
    private class FakeGitHub(val answer: (Request) -> Pair<Int, ByteArray>) : Interceptor {
        val requests = mutableListOf<Request>()
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            requests += request
            val (code, body) = answer(request)
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("")
                .body(body.toResponseBody("application/octet-stream".toMediaType())).build()
        }
    }

    private fun client(fake: FakeGitHub) = OkHttpClient.Builder().addInterceptor(fake).build()

    @Test
    fun readsTheVersionNotesAndApkOfARelease() {
        val r = ReleaseFeed.parse(releaseJson())!!
        assertEquals("0.3.2", r.version)
        assertEquals("- Faster\n- Smaller", r.notes)
        assertEquals("rex-mobile-0.3.2.apk", r.apkName)
        assertEquals("https://api.github.com/repos/o/r/releases/assets/2", r.apkUrl)
        assertEquals(apkBytes.size.toLong(), r.size)
        assertEquals(apkSha, r.sha256)
        assertEquals(r, Release.fromJson(r.toJson()))
    }

    @Test
    fun skipsReleasesWithoutAnApkOrAVersion() {
        assertNull(ReleaseFeed.parse("""{"tag_name":"v1.0.0","assets":[{"name":"a.zip","url":"u","size":1}]}"""))
        assertNull(ReleaseFeed.parse("""{"tag_name":"nightly","assets":[{"name":"a.apk","url":"u","size":1}]}"""))
        assertNull(ReleaseFeed.parse("""{"tag_name":"v1.0.0","draft":true,"assets":[{"name":"a.apk","url":"u","size":1}]}"""))
        assertNull(ReleaseFeed.parse(releaseJson(digest = null))!!.sha256)
    }

    @Test
    fun comparesVersionsNumberByNumber() {
        assertTrue(compareVersions("0.10.0", "0.9.3") > 0)
        assertTrue(compareVersions("v0.3.2", "0.3.1") > 0)
        assertEquals(0, compareVersions("0.3", "0.3.0"))
        assertEquals(0, compareVersions("1.2.0-beta", "1.2.0"))
        assertTrue(compareVersions("0.3.1", "0.3.10") < 0)
    }

    @Test
    fun notesLoseTheirMarkdown() {
        val lines = releaseNoteLines("## What's new\n\n- **Faster** reconnects\n* `Smaller` APK\nThanks!")
        assertEquals(
            listOf(NoteLine("What's new", false), NoteLine("Faster reconnects", true), NoteLine("Smaller APK", true), NoteLine("Thanks!", false)),
            lines,
        )
    }

    @Test
    fun sendsTheTokenWhileItWorks() {
        val fake = FakeGitHub { 200 to releaseJson().toByteArray() }
        val feed = ReleaseFeed("o/r", "tok", client(fake))
        assertEquals("0.3.2", feed.latest()!!.version)
        assertEquals("https://api.github.com/repos/o/r/releases/latest", fake.requests.single().url.toString())
        assertEquals("Bearer tok", fake.requests.single().header("Authorization"))
    }

    @Test
    fun dropsATokenGitHubRefusesAndAsksWithout() {
        // Public by now, and the token in this build expired: GitHub refuses it, though it would answer anyone.
        val fake = FakeGitHub { r -> if (r.header("Authorization") != null) 401 to ByteArray(0) else 200 to releaseJson().toByteArray() }
        val feed = ReleaseFeed("o/r", "expired", client(fake))
        assertEquals("0.3.2", feed.latest()!!.version)
        assertEquals(listOf("Bearer expired", null), fake.requests.map { it.header("Authorization") })

        // And for good: the next request goes without it straight away.
        feed.latest()
        assertNull(fake.requests.last().header("Authorization"))
        assertEquals(3, fake.requests.size)
    }

    @Test
    fun aRefusedTokenFailsWhileTheRepositoryIsPrivate() {
        // Still private, and the token expired: without it the repository is a 404, not a lack of releases.
        val fake = FakeGitHub { r -> if (r.header("Authorization") != null) 401 to ByteArray(0) else 404 to ByteArray(0) }
        val feed = ReleaseFeed("o/r", "expired", client(fake))
        repeat(2) {
            try {
                feed.latest()
                fail("expected a failure")
            } catch (e: IOException) {
                assertEquals("GitHub refused the update token", e.message)
            }
        }
    }

    @Test
    fun noReleaseIsNoUpdateAndOtherAnswersFail() {
        assertNull(ReleaseFeed("o/r", "", client(FakeGitHub { 404 to ByteArray(0) })).latest())
        try {
            ReleaseFeed("o/r", "", client(FakeGitHub { 500 to ByteArray(0) })).latest()
            fail("expected a failure")
        } catch (e: IOException) {
            assertEquals("GitHub answered 500", e.message)
        }
    }

    @Test
    fun downloadsTheApkAndChecksItsDigest() {
        val fake = FakeGitHub { 200 to apkBytes }
        val feed = ReleaseFeed("o/r", "tok", client(fake))
        val release = ReleaseFeed.parse(releaseJson())!!
        val dest = dir.root.resolve("rex.apk")
        var last = 0L to 0L
        feed.download(release, dest) { read, total -> last = read to total }

        assertArrayEquals(apkBytes, dest.readBytes())
        assertEquals(apkBytes.size.toLong() to apkBytes.size.toLong(), last)
        assertEquals("application/octet-stream", fake.requests.single().header("Accept"))
        assertEquals(release.apkUrl, fake.requests.single().url.toString())
        assertFalse(dir.root.resolve("rex.apk.part").exists())
    }

    @Test
    fun aDamagedDownloadIsThrownAway() {
        val feed = ReleaseFeed("o/r", "", client(FakeGitHub { 200 to apkBytes.copyOf(apkBytes.size - 1) }))
        val dest = dir.root.resolve("rex.apk")
        try {
            feed.download(ReleaseFeed.parse(releaseJson())!!, dest) { _, _ -> }
            fail("expected a failure")
        } catch (e: IOException) {
            assertEquals("The download was damaged", e.message)
        }
        assertFalse(dest.exists())
        assertFalse(dir.root.resolve("rex.apk.part").exists())
    }
}
