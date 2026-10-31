package com.v2ray.ang.util

import com.v2ray.ang.AppConfig
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class TvHttpServer(
    private val preferredPort: Int = 10828,
    private val onSubscriptionReceived: (remarks: String, url: String) -> Unit
) {
    private var serverSocket: ServerSocket? = null
    private var executor: ExecutorService? = null
    @Volatile
    private var isRunning = false
    var localIp: String? = null
        private set
    var port: Int = preferredPort
        private set

    fun start(): String? {
        if (isRunning) return getUrl()

        localIp = getDeviceLocalIp()
        if (localIp == null) {
            LogUtil.e(AppConfig.TAG, "TvHttpServer: No local IP address found")
            return null
        }

        try {
            serverSocket = try {
                ServerSocket(preferredPort).also { port = preferredPort }
            } catch (e: Exception) {
                ServerSocket(0).also { port = it.localPort }
            }
            isRunning = true
            executor = Executors.newCachedThreadPool()

            executor?.execute {
                while (isRunning) {
                    try {
                        val client = serverSocket?.accept() ?: break
                        executor?.execute { handleClient(client) }
                    } catch (e: SocketException) {
                        break
                    } catch (e: Exception) {
                        LogUtil.e(AppConfig.TAG, "TvHttpServer accept error", e)
                    }
                }
            }
            return getUrl()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "TvHttpServer start failed", e)
            stop()
            return null
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (ignored: Exception) {}
        serverSocket = null

        executor?.shutdownNow()
        executor = null
    }

    fun getUrl(): String? {
        val ip = localIp ?: return null
        return "http://$ip:$port/"
    }

    private fun handleClient(client: Socket) {
        try {
            client.use { socket ->
                socket.soTimeout = 10000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                val out = socket.getOutputStream()

                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val path = parts[1]

                var contentLength = 0
                var line: String? = reader.readLine()
                while (!line.isNullOrEmpty()) {
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line.substring(15).trim().toIntOrNull() ?: 0
                    }
                    line = reader.readLine()
                }

                if (method.equals("GET", ignoreCase = true) && (path == "/" || path.startsWith("/?"))) {
                    sendHtmlResponse(out, getHtmlContent())
                } else if (method.equals("POST", ignoreCase = true) && path.startsWith("/import")) {
                    val body = CharArray(contentLength)
                    var readTotal = 0
                    while (readTotal < contentLength) {
                        val read = reader.read(body, readTotal, contentLength - readTotal)
                        if (read == -1) break
                        readTotal += read
                    }
                    val bodyStr = String(body, 0, readTotal)
                    val params = parseQueryParams(bodyStr)
                    val remarks = params["remarks"].orEmpty()
                    val url = params["url"].orEmpty()

                    if (url.isNotBlank()) {
                        onSubscriptionReceived(remarks, url)
                        val json = "{\"success\":true,\"message\":\"\\u8ba2\\u9605\\u5df2\\u6210\\u529f\\u53d1\\u9001\\u81f3\\u7535\\u89c6\\uff01\"}"
                        sendJsonResponse(out, 200, json)
                    } else {
                        val json = "{\"success\":false,\"message\":\"\\u8bf7\\u8f93\\u5165\\u6709\\u6548\\u7684\\u8ba2\\u9605\\u5730\\u5740\"}"
                        sendJsonResponse(out, 400, json)
                    }
                } else {
                    sendNotFound(out)
                }
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "TvHttpServer client handling error", e)
        }
    }

    private fun parseQueryParams(body: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val pairs = body.split("&")
        for (pair in pairs) {
            val idx = pair.indexOf("=")
            if (idx > 0) {
                val key = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                val value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                map[key] = value
            }
        }
        return map
    }

    private fun sendHtmlResponse(out: OutputStream, html: String) {
        val bytes = html.toByteArray(StandardCharsets.UTF_8)
        val header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
        out.write(header.toByteArray(StandardCharsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun sendJsonResponse(out: OutputStream, code: Int, json: String) {
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        val statusText = if (code == 200) "OK" else "Bad Request"
        val header = "HTTP/1.1 $code $statusText\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n"
        out.write(header.toByteArray(StandardCharsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun sendNotFound(out: OutputStream) {
        val bytes = "404 Not Found".toByteArray(StandardCharsets.UTF_8)
        val header = "HTTP/1.1 404 Not Found\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
        out.write(header.toByteArray(StandardCharsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun getDeviceLocalIp(): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            var candidate: String? = null
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress ?: continue
                        if (host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172.")) {
                            return host
                        }
                        if (candidate == null && !host.startsWith("127.")) {
                            candidate = host
                        }
                    }
                }
            }
            return candidate
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "TvHttpServer: Failed to get local IP", e)
            return null
        }
    }

    private fun getHtmlContent(): String {
        return """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<title>v2rayNG TV 订阅导入助手</title>
<style>
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body {
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
    background: #f4f6f9;
    color: #333;
    display: flex;
    justify-content: center;
    padding: 24px 16px;
  }
  .card {
    background: #ffffff;
    border-radius: 16px;
    box-shadow: 0 4px 20px rgba(0,0,0,0.08);
    max-width: 480px;
    width: 100%;
    padding: 28px 24px;
  }
  .header {
    text-align: center;
    margin-bottom: 24px;
  }
  .icon {
    font-size: 40px;
    margin-bottom: 8px;
  }
  h1 {
    font-size: 20px;
    font-weight: 700;
    color: #1a1a1a;
  }
  .sub {
    font-size: 13px;
    color: #888;
    margin-top: 4px;
  }
  .form-group {
    margin-bottom: 18px;
  }
  label {
    display: block;
    font-size: 14px;
    font-weight: 600;
    margin-bottom: 8px;
    color: #444;
  }
  input[type="text"], textarea {
    width: 100%;
    border: 1.5px solid #e2e8f0;
    border-radius: 10px;
    padding: 12px 14px;
    font-size: 14px;
    outline: none;
    transition: border-color 0.2s;
    background: #fafafa;
  }
  input[type="text"]:focus, textarea:focus {
    border-color: #3b82f6;
    background: #fff;
  }
  textarea {
    min-height: 120px;
    resize: vertical;
    font-family: monospace;
    font-size: 13px;
    line-height: 1.4;
  }
  .btn {
    width: 100%;
    background: #2563eb;
    color: #fff;
    border: none;
    border-radius: 10px;
    padding: 14px;
    font-size: 16px;
    font-weight: 600;
    cursor: pointer;
    box-shadow: 0 4px 12px rgba(37,99,235,0.25);
    transition: background 0.2s, transform 0.1s;
  }
  .btn:active {
    transform: scale(0.98);
    background: #1d4ed8;
  }
  .tip {
    margin-top: 16px;
    font-size: 12px;
    color: #94a3b8;
    line-height: 1.5;
    text-align: center;
  }
  #result {
    display: none;
    margin-top: 20px;
    padding: 14px;
    border-radius: 10px;
    font-size: 14px;
    text-align: center;
    font-weight: 600;
  }
  .success {
    background: #ecfdf5;
    color: #059669;
    border: 1px solid #a7f3d0;
  }
  .error {
    background: #fef2f2;
    color: #dc2626;
    border: 1px solid #fecaca;
  }
</style>
</head>
<body>
<div class="card">
  <div class="header">
    <div class="icon">📺 ➔ 📱</div>
    <h1>v2rayNG TV 订阅导入助手</h1>
    <p class="sub">已连接到电视端，请在下方粘贴订阅地址</p>
  </div>
  <form id="importForm" onsubmit="submitForm(event)">
    <div class="form-group">
      <label for="remarks">订阅名称 / 备注（可选）</label>
      <input type="text" id="remarks" name="remarks" placeholder="例如：我的订阅">
    </div>
    <div class="form-group">
      <label for="url">订阅地址 / 节点链接（必填）</label>
      <textarea id="url" name="url" placeholder="请在此粘贴 http/https 订阅链接，或 vmess/vless/ss 节点链接" required></textarea>
    </div>
    <button type="submit" id="submitBtn" class="btn">🚀 立即导入到电视</button>
  </form>
  <div id="result"></div>
  <p class="tip">提示：手机与电视须在同一局域网下。点击导入后，电视将自动添加该订阅并更新节点。</p>
</div>
<script>
function submitForm(e) {
  e.preventDefault();
  var btn = document.getElementById('submitBtn');
  var res = document.getElementById('result');
  var remarks = document.getElementById('remarks').value.trim();
  var url = document.getElementById('url').value.trim();
  if (!url) {
    alert('请输入订阅地址');
    return;
  }
  btn.disabled = true;
  btn.innerText = '正在发送至电视...';
  res.style.display = 'none';

  var xhr = new XMLHttpRequest();
  xhr.open('POST', '/import', true);
  xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded; charset=UTF-8');
  xhr.onload = function() {
    btn.disabled = false;
    btn.innerText = '🚀 立即导入到电视';
    if (xhr.status === 200) {
      try {
        var data = JSON.parse(xhr.responseText);
        res.className = 'success';
        res.innerHTML = '✅ ' + (data.message || '导入成功！电视正在更新节点。');
        res.style.display = 'block';
        document.getElementById('url').value = '';
      } catch(ex) {
        res.className = 'success';
        res.innerHTML = '✅ 导入成功！已发送至电视。';
        res.style.display = 'block';
      }
    } else {
      res.className = 'error';
      res.innerHTML = '❌ 导入失败，请检查电视连接。';
      res.style.display = 'block';
    }
  };
  xhr.onerror = function() {
    btn.disabled = false;
    btn.innerText = '🚀 立即导入到电视';
    res.className = 'error';
    res.innerHTML = '❌ 网络请求失败，请确保手机与电视在同一Wi-Fi下。';
    res.style.display = 'block';
  };
  var payload = 'remarks=' + encodeURIComponent(remarks) + '&url=' + encodeURIComponent(url);
  xhr.send(payload);
}
</script>
</body>
</html>"""
    }
}
