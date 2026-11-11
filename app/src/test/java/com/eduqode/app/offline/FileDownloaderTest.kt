package com.eduqode.app.offline

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
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import kotlin.random.Random

/** Tests pause/resume, retry and integrity checks against a real local HTTP server. */
class FileDownloaderTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: ServerSocket
    private val data = Random(42).nextBytes(700_000)
    private val rangesSeen = Collections.synchronizedList(ArrayList<String?>())
    @Volatile private var honourRange = true
    @Volatile private var truncateOnce = false

    private val base get() = "http://127.0.0.1:${server.localPort}"
    private val downloader = FileDownloader("EduQodeApp/test")

    /** A very small HTTP/1.1 server (android.jar has no com.sun.net.httpserver), one thread per connection. */
    @Before fun start() {
        server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        Thread {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (e: IOException) { break }
                Thread { runCatching { handle(socket) }; runCatching { socket.close() } }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun handle(socket: Socket) {
        val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val requestLine = input.readLine() ?: return
        val path = requestLine.split(" ").getOrElse(1) { "/" }
        val headers = HashMap<String, String>()
        while (true) {
            val line = input.readLine() ?: break
            if (line.isEmpty()) break
            headers[line.substringBefore(':').trim().lowercase()] = line.substringAfter(':').trim()
        }
        val out = socket.getOutputStream()
        fun head(code: Int, reason: String, extra: List<String>, length: Long) {
            val h = StringBuilder("HTTP/1.1 $code $reason\r\n")
            extra.forEach { h.append(it).append("\r\n") }
            h.append("Content-Length: $length\r\nConnection: close\r\n\r\n")
            out.write(h.toString().toByteArray(Charsets.ISO_8859_1))
        }
        when (path) {
            "/missing.pdf" -> { head(404, "Not Found", emptyList(), 0); out.flush(); return }
            "/busy.mp4" -> { head(503, "Service Unavailable", emptyList(), 0); out.flush(); return }
            "/page.mp4" -> {
                val html = "<html>quota</html>".toByteArray()
                head(200, "OK", listOf("Content-Type: text/html; charset=utf-8"), html.size.toLong()); out.write(html); out.flush(); return
            }
        }
        val range = headers["range"]
        rangesSeen += range
        val from = if (honourRange && range != null) range.removePrefix("bytes=").substringBefore('-').toInt() else 0
        if (from >= data.size) { head(416, "Range Not Satisfiable", emptyList(), 0); out.flush(); return }
        val body = data.copyOfRange(from, data.size)
        if (from > 0) head(206, "Partial Content", listOf("Content-Range: bytes $from-${data.size - 1}/${data.size}"), body.size.toLong())
        else head(200, "OK", emptyList(), body.size.toLong())
        if (truncateOnce) {
            truncateOnce = false
            out.write(body, 0, body.size / 3) // connection drops part-way
            out.flush()
            return
        }
        out.write(body)
        out.flush()
    }

    @After fun stop() = server.close()

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

    @Test fun webPageInsteadOfFileFails() {
        val out = downloader.download("$base/page.mp4", File(tmp.root, "p.mp4"), 0, { false }) { _, _ -> }
        assertEquals(FileDownloader.Outcome.Failed("The link opens a web page, not a file."), out)
        assertFalse(File(tmp.root, "p.mp4").exists())
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
