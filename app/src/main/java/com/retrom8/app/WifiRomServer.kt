package com.retrom8.app

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

class WifiRomServer(
    private val rootDir: File,
    private val port: Int = 8080,
    private val onUploaded: (system: String, fileName: String) -> Unit
) {
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    @Volatile
    var isRunning = false
        private set

    fun start(): String? {
        if (isRunning) return getLocalIpAddress()
        val ip = getLocalIpAddress() ?: return null
        return runCatching {
            val ss = ServerSocket(port)
            serverSocket = ss
            isRunning = true
            executor.execute {
                while (isRunning) {
                    val client = runCatching { ss.accept() }.getOrNull() ?: break
                    executor.execute { handleClient(client) }
                }
            }
            ip
        }.getOrNull()
    }

    fun stop() {
        isRunning = false
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 60_000
            val input = s.getInputStream()
            val output = s.getOutputStream()

            val headerText = readHeaders(input)
            if (headerText.isEmpty()) return

            val lines = headerText.split("\r\n")
            val reqParts = lines.firstOrNull()?.split(" ") ?: return
            if (reqParts.size < 2) return

            val method = reqParts[0].uppercase()
            val fullPath = reqParts[1]
            val pathOnly = fullPath.substringBefore("?")
            val queryStr = fullPath.substringAfter("?", "")

            val headers = mutableMapOf<String, String>()
            for (i in 1 until lines.size) {
                val idx = lines[i].indexOf(":")
                if (idx > 0) {
                    headers[lines[i].substring(0, idx).trim().lowercase()] =
                        lines[i].substring(idx + 1).trim()
                }
            }

            if (headers["expect"]?.contains("100-continue", ignoreCase = true) == true) {
                output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray())
                output.flush()
            }

            when {
                method == "GET" && (pathOnly == "/" || pathOnly == "/index.html") -> {
                    val body = htmlPage().toByteArray(Charsets.UTF_8)
                    val resp = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/html; charset=utf-8\r\n" +
                        "Content-Length: ${body.size}\r\n" +
                        "Connection: close\r\n\r\n"
                    output.write(resp.toByteArray())
                    output.write(body)
                    output.flush()
                }

                method == "POST" && pathOnly == "/upload" -> {
                    val params = parseQuery(queryStr)
                    val sys = params["sys"]?.uppercase() ?: ""
                    val rawName = params["name"] ?: ""
                    val contentLength = headers["content-length"]?.toLongOrNull() ?: -1L
                    val allowed = setOf("NES", "SNES", "GBA", "PS1", "BIOS")

                    if (sys !in allowed || rawName.isBlank() || contentLength < 0) {
                        sendText(output, 400, "Thong so khong hop le")
                        return
                    }

                    val safeName = File(rawName).name.replace("/", "_").replace("\\", "_")
                    val targetDir = File(rootDir, sys).apply { mkdirs() }
                    val outFile = File(targetDir, safeName)

                    val ok = runCatching {
                        val buf = ByteArray(16384)
                        var remaining = contentLength
                        FileOutputStream(outFile).use { fos ->
                            while (remaining > 0) {
                                val toRead = minOf(buf.size.toLong(), remaining).toInt()
                                val read = input.read(buf, 0, toRead)
                                if (read == -1) break
                                fos.write(buf, 0, read)
                                remaining -= read
                            }
                        }
                        remaining == 0L
                    }.getOrDefault(false)

                    if (ok) {
                        sendText(output, 200, "OK")
                        onUploaded(sys, safeName)
                    } else {
                        outFile.delete()
                        sendText(output, 500, "Loi ghi file")
                    }
                }

                else -> sendText(output, 404, "Not Found")
            }
        }
    }

    private fun readHeaders(input: InputStream): String {
        val baos = ByteArrayOutputStream()
        var state = 0
        while (true) {
            val b = input.read()
            if (b == -1) break
            baos.write(b)
            state = when {
                state == 0 && b == 13 -> 1
                state == 1 && b == 10 -> 2
                state == 2 && b == 13 -> 3
                state == 3 && b == 10 -> 4
                b == 13 -> 1
                else -> 0
            }
            if (state == 4 || baos.size() > 65536) break
        }
        return baos.toString("UTF-8")
    }

    private fun parseQuery(query: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        if (query.isBlank()) return map
        query.split("&").forEach { pair ->
            val parts = pair.split("=", limit = 2)
            if (parts.size == 2) {
                val k = runCatching { URLDecoder.decode(parts[0], "UTF-8") }.getOrDefault(parts[0])
                val v = runCatching { URLDecoder.decode(parts[1], "UTF-8") }.getOrDefault(parts[1])
                map[k] = v
            }
        }
        return map
    }

    private fun sendText(output: java.io.OutputStream, code: Int, msg: String) {
        val body = msg.toByteArray(Charsets.UTF_8)
        val status = if (code == 200) "OK" else if (code == 400) "Bad Request" else if (code == 404) "Not Found" else "Error"
        val resp = "HTTP/1.1 $code$status\r\n" +
            "Content-Type: text/plain; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        output.write(resp.toByteArray())
        output.write(body)
        output.flush()
    }

    private fun getLocalIpAddress(): String? {
        return runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            var fallback: String? = null
            while (interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (ip.startsWith("192.168.") || ip.startsWith("10.")) return ip
                        fallback = ip
                    }
                }
            }
            fallback
        }.getOrNull()
    }

    private fun htmlPage(): String = """
<!DOCTYPE html>
<html lang="vi">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>RetroM8 - Nạp ROM qua Wi-Fi</title>
<style>
  body { background:#111114; color:#f2f2f7; font-family:system-ui,sans-serif; max-width:540px; margin:32px auto; padding:0 16px; }
  h1 { color:#e53935; margin:0 0 6px; font-size:24px; }
  p { color:#9e9ea8; margin:0 0 20px; font-size:14px; }
  .card { background:#1b1b22; border:1px solid #2e2e3a; border-radius:14px; padding:20px; }
  label { display:block; font-size:13px; color:#b8b8c4; margin-bottom:6px; }
  select, input[type=file] { width:100%; padding:10px; border-radius:10px; border:1px solid #383846; background:#121217; color:#fff; margin-bottom:16px; box-sizing:border-box; }
  button { width:100%; padding:12px; border:none; border-radius:10px; background:#e53935; color:#fff; font-weight:700; font-size:15px; cursor:pointer; }
  button:disabled { opacity:0.5; cursor:not-allowed; }
  #barWrap { margin-top:16px; background:#121217; border-radius:8px; height:12px; overflow:hidden; display:none; }
  #bar { height:100%; width:0%; background:#43a047; transition:width 0.1s; }
  #log { margin-top:14px; font-size:13px; color:#d0d0dc; white-space:pre-line; line-height:1.5; }
</style>
</head>
<body>
  <h1>RETRO M8 • NẠP ROM WI-FI</h1>
  <p>Chọn hệ máy và thả file ROM để bắn thẳng vào HTC M8 (hỗ trợ chọn nhiều file cùng lúc).</p>
  <div class="card">
    <label for="sys">Thư mục đích</label>
    <select id="sys">
      <option value="NES">NES (.nes, .zip)</option>
      <option value="SNES">SNES (.sfc, .smc, .zip)</option>
      <option value="GBA">GBA (.gba, .zip)</option>
      <option value="PS1">PS1 (.chd, .pbp, .iso, .cue, .bin)</option>
      <option value="BIOS">BIOS (scph1001.bin...)</option>
    </select>
    <label for="files">Chọn file game</label>
    <input id="files" type="file" multiple>
    <button id="btn" onclick="startUpload()">Gửi sang HTC M8</button>
    <div id="barWrap"><div id="bar"></div></div>
    <div id="log"></div>
  </div>
<script>
async function startUpload() {
  var input = document.getElementById('files');
  var sys = document.getElementById('sys').value;
  var btn = document.getElementById('btn');
  var barWrap = document.getElementById('barWrap');
  var bar = document.getElementById('bar');
  var log = document.getElementById('log');
  if (!input.files.length) { log.textContent = 'Chưa chọn file nào.'; return; }

  btn.disabled = true;
  barWrap.style.display = 'block';
  log.textContent = '';

  for (var i = 0; i < input.files.length; i++) {
    var file = input.files[i];
    log.textContent += 'Đang gửi (' + (i + 1) + '/' + input.files.length + '): ' + file.name + '...\n';
    var ok = await uploadOne(sys, file, function(pct) { bar.style.width = pct + '%'; });
    log.textContent += ok ? '✓ Xong: ' + file.name + '\n' : '✗ Lỗi: ' + file.name + '\n';
  }
  btn.disabled = false;
  input.value = '';
}

function uploadOne(sys, file, onProgress) {
  return new Promise(function(resolve) {
    var xhr = new XMLHttpRequest();
    xhr.open('POST', '/upload?sys=' + encodeURIComponent(sys) + '&name=' + encodeURIComponent(file.name), true);
    xhr.upload.onprogress = function(e) {
      if (e.lengthComputable) onProgress(Math.round((e.loaded / e.total) * 100));
    };
    xhr.onload = function() { resolve(xhr.status === 200); };
    xhr.onerror = function() { resolve(false); };
    xhr.send(file);
  });
}
</script>
</body>
</html>
    """.trimIndent()
}
