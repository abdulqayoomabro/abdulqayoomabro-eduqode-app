package com.eduqode.app.offline

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.util.Collections
import kotlin.random.Random

/** Tests pause/resume, retry and integrity checks against a real local HTTP server. */
class FileDownloaderTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: HttpServer
    private val data = Random(42).nextBytes(700_000)
    private val rangesSeen = Collections.synchronizedList(ArrayList<String?>())
    @Volatile private var honourRange = true
    @Volatile private var truncateOnce = false

    private val base get() = "http://127.0.0.1:${server.address.port}"
    private val downloader = FileDownloader("EduQodeApp/test")

    @Before fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/video.mp4") { ex ->
            val range = ex.requestHeaders.getFirst("Range")
            rangesSeen += range
            val from = if (honourRange && range != null) range.removePrefix("bytes=").substringBefore('-').toInt() else 0
            if (from >= data.size) {
                ex.sendResponseHeaders(416, -1); ex.close(); return@createContext
            }
            val body = data.copyOfRange(from, data.size)
            if (from > 0) ex.responseHeaders.add("Content-Range", "bytes $from-${data.size - 1}/${data.size}")
            ex.sendResponseHeaders(if (from > 0) 206 else 200, body.size.toLong())
            ex.responseBody.use { out ->
                if (truncateOnce) {
                    truncateOnce = false
                    out.write(body, 0, body.size / 3) // connection drops part-way
                    out.flush()
                    throw IOException("simulated drop")
                }
                out.write(body)
            }
        }
        server.createContext("/missing.pdf") { ex -> ex.sendResponseHeaders(404, -1); ex.close() }
        server.createContext("/busy.mp4") { ex -> ex.sendResponseHeaders(503, -1); ex.close() }
        server.start()
    }

    @After fun stop() = server.stop(0)

    private fun target() = File(tmp.root, "media/video.mp4")

    @Test fun downloadsWholeFile() {
        val out = downloader.download("$base/video.mp4", target(), 0, { false }) { _, _ -> }
        assertEquals(FileDownloader.Outcome.Done(data.size.toLong()), out)
        assertArrayEquals(data, target().readBytes())
        assertFalse(File(target().path + ".part").exists())
    }

    @Test fun pauseThenResumeContinuesFromWhereItStopped() {
        var stop = true
        var seen = 0L
        val first = downloader.download("$base/video.mp4", target(), data.size.toLong(), { stop && seen > 100_000 }) { d, _ -> seen = d }
        assertTrue(first is FileDownloader.Outcome.Stopped)
        val part = File(target().path + ".part")
        val partial = part.length()
        assertTrue("partial file kept", partial in 100_000 until data.size)

        stop = false
        val second = downloader.download("$base/video.mp4", target(), data.size.toLong(), { false }) { _, _ -> }
        assertEquals(FileDownloader.Outcome.Done(data.size.toLong()), second)
        assertEquals("bytes=$partial-", rangesSeen.last())
        assertArrayEquals(data, target().readBytes())
    }

    @Test fun serverThatIgnoresRangeStillGivesCorrectFile() {
        var seen = 0L
        downloader.download("$base/video.mp4", target(), 0, { seen > 200_000 }) { d, _ -> seen = d }
        honourRange = false
        val out = downloader.download("$base/video.mp4", target(), 0, { false }) { _, _ -> }
        assertEquals(FileDownloader.Outcome.Done(data.size.toLong()), out)
        assertArrayEquals(data, target().readBytes())
    }

    @Test fun droppedConnectionIsRetriedAndResumed() {
        truncateOnce = true
        try {
            downloader.download("$base/video.mp4", target(), 0, { false }) { _, _ -> }
            fail("expected an IOException so the job is retried")
        } catch (e: IOException) {
            // expected
        }
        assertFalse(target().exists())
        val out = downloader.download("$base/video.mp4", target(), 0, { false }) { _, _ -> }
        assertEquals(FileDownloader.Outcome.Done(data.size.toLong()), out)
        assertArrayEquals(data, target().readBytes())
    }

    @Test fun missingFileFailsWithoutRetry() {
        val out = downloader.download("$base/missing.pdf", File(tmp.root, "m.pdf"), 0, { false }) { _, _ -> }
        assertEquals(FileDownloader.Outcome.Failed("Server answered 404"), out)
    }

    @Test fun busyServerIsRetriedLater() {
        try {
            downloader.download("$base/busy.mp4", File(tmp.root, "b.mp4"), 0, { false }) { _, _ -> }
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("503"))
        }
    }

    @Test fun alreadyDownloadedFileIsNotFetchedAgain() {
        target().parentFile!!.mkdirs()
        target().writeBytes(data)
        rangesSeen.clear()
        val out = downloader.download("$base/video.mp4", target(), data.size.toLong(), { false }) { _, _ -> }
        assertEquals(FileDownloader.Outcome.Done(data.size.toLong()), out)
        assertTrue("no request made", rangesSeen.isEmpty())
    }

    @Test fun completePartFileIsSavedWhenServerSays416() {
        val part = File(target().path + ".part")
        part.parentFile!!.mkdirs()
        part.writeBytes(data)
        val out = downloader.download("$base/video.mp4", target(), data.size.toLong(), { false }) { _, _ -> }
        assertEquals(FileDownloader.Outcome.Done(data.size.toLong()), out)
        assertArrayEquals(data, target().readBytes())
    }
}
