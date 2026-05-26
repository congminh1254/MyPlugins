package com.example

import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

object LocalHttpServer {
    private val contentMap = ConcurrentHashMap<String, ByteArray>()
    private val contentTypeMap = ConcurrentHashMap<String, String>()
    @Volatile
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    @Volatile
    private var port: Int = 0

    fun start() {
        if (serverSocket != null) return
        try {
            // Bind to loopback only
            serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            port = serverSocket!!.localPort
            executor.execute {
                try {
                    while (!serverSocket!!.isClosed) {
                        val socket = serverSocket!!.accept()
                        executor.execute { handleClient(socket) }
                    }
                } catch (_: Exception) {
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            serverSocket = null
            port = 0
        }
    }

    fun stop() {
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        port = 0
        contentMap.clear()
        contentTypeMap.clear()
        executor.shutdownNow()
    }

    fun register(path: String, content: ByteArray, contentType: String = "application/octet-stream") {
        val cleanPath = if (path.startsWith("/")) path else "/$path"
        contentMap[cleanPath] = content
        contentTypeMap[cleanPath] = contentType
        start()
    }

    fun url(path: String): String {
        val cleanPath = if (path.startsWith("/")) path else "/$path"
        start()
        // Wait briefly for server to bind and port to be assigned
        var attempts = 0
        while (port == 0 && attempts < 20) {
            try { Thread.sleep(10) } catch (_: Exception) {}
            attempts++
        }
        return "http://127.0.0.1:$port$cleanPath"
    }

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            try {
                val input = s.getInputStream()
                val reader = input.bufferedReader()
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 2) return
                val rawPath = parts[1]
                val pathOnly = rawPath.substringBefore('?')
                val content = contentMap[pathOnly]
                val contentType = contentTypeMap[pathOnly] ?: "application/octet-stream"

                val out = BufferedOutputStream(s.getOutputStream())
                if (content == null) {
                    // Support on-demand proxying at /proxy?url=<encoded>
                    if (pathOnly == "/proxy") {
                        val query = rawPath.substringAfter('?', "")
                        val params = query.split('&').mapNotNull {
                            val kv = it.split('=', limit = 2)
                            if (kv.size == 2) kv[0] to java.net.URLDecoder.decode(kv[1], "UTF-8") else null
                        }.toMap()
                        val targetUrl = params["url"]
                        val refererParam = params["referer"]
                        if (targetUrl != null) {
                            try {
                                val url = java.net.URL(targetUrl)
                                val host = url.host.lowercase()
                                val fallbackReferer = if (host.contains("kingcdn") || host.contains("katcdn")) {
                                    "https://phimfit.com/"
                                } else null

                                // Retry strategy for CDN anti-hotlink behavior that may intermittently return 404.
                                val refererCandidates = linkedSetOf<String?>()
                                refererCandidates.add(refererParam)
                                refererCandidates.add(fallbackReferer)
                                refererCandidates.add(null)

                                var lastStatus = 502
                                var successBytes: ByteArray? = null
                                var successType = "application/octet-stream"

                                for ((attemptIndex, referer) in refererCandidates.withIndex()) {
                                    val conn = url.openConnection() as java.net.HttpURLConnection
                                    conn.connectTimeout = 15000
                                    conn.readTimeout = 15000
                                    conn.instanceFollowRedirects = true
                                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                                    conn.setRequestProperty("Accept", "*/*")

                                    if (!referer.isNullOrBlank()) {
                                        conn.setRequestProperty("Referer", referer)
                                        val origin = try {
                                            val ru = java.net.URL(referer)
                                            "${ru.protocol}://${ru.host}" + if (ru.port != -1) ":${ru.port}" else ""
                                        } catch (_: Exception) {
                                            null
                                        }
                                        if (!origin.isNullOrBlank()) {
                                            conn.setRequestProperty("Origin", origin)
                                        }
                                    }

                                    val status = conn.responseCode
                                    lastStatus = status
                                    System.err.println("LocalHttpServer proxy fetching: $targetUrl attempt=${attemptIndex + 1} referer=${referer ?: ""} -> status=$status")

                                    if (status in 200..299) {
                                        successBytes = conn.inputStream.use { it.readBytes() }
                                        successType = conn.contentType ?: "application/octet-stream"
                                        break
                                    }

                                    // Retry only on 404 with another referer profile.
                                    if (status != 404) {
                                        break
                                    }
                                }

                                if (successBytes != null) {
                                    val b = successBytes
                                    val header = StringBuilder()
                                    header.append("HTTP/1.1 200 OK\r\n")
                                    header.append("Content-Length: ${b.size}\r\n")
                                    header.append("Content-Type: $successType\r\n")
                                    header.append("Connection: close\r\n")
                                    header.append("\r\n")
                                    val out = BufferedOutputStream(s.getOutputStream())
                                    out.write(header.toString().toByteArray())
                                    out.write(b)
                                    out.flush()
                                    return
                                }

                                val body = "Bad Gateway".toByteArray()
                                val header = "HTTP/1.1 502 Bad Gateway\r\nContent-Length: ${body.size}\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\n"
                                val out = BufferedOutputStream(s.getOutputStream())
                                out.write(header.toByteArray())
                                out.write(body)
                                out.flush()
                                if (lastStatus == 404) {
                                    System.err.println("LocalHttpServer proxy final result: upstream 404 after retries for $targetUrl")
                                }
                                return
                            } catch (e: Exception) {
                                val body = "Bad Gateway".toByteArray()
                                val header = "HTTP/1.1 502 Bad Gateway\r\nContent-Length: ${body.size}\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\n"
                                val out = BufferedOutputStream(s.getOutputStream())
                                out.write(header.toByteArray())
                                out.write(body)
                                out.flush()
                                return
                            }
                        }
                    }
                    val body = "Not Found".toByteArray()
                    val header = "HTTP/1.1 404 Not Found\r\nContent-Length: ${body.size}\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\n"
                    out.write(header.toByteArray())
                    out.write(body)
                    out.flush()
                    return
                }

                val header = StringBuilder()
                header.append("HTTP/1.1 200 OK\r\n")
                header.append("Content-Length: ${content.size}\r\n")
                header.append("Content-Type: $contentType\r\n")
                header.append("Connection: close\r\n")
                header.append("\r\n")

                out.write(header.toString().toByteArray())
                out.write(content)
                out.flush()
            } catch (e: Exception) {
                // ignore
            }
        }
    }
}
