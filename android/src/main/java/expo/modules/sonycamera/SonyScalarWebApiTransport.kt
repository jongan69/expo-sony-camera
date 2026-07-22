package expo.modules.sonycamera

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

internal class SonyScalarWebApiTransport private constructor(
  private val descriptor: Descriptor,
  private val trace: (String) -> Unit,
) {
  data class Descriptor(
    val host: String,
    val descriptorUrl: String,
    val apiBaseUrl: String,
    val liveViewUrl: String,
    val modelName: String,
  )

  companion object {
    private val DIRECT_IP_CANDIDATES = listOf("192.168.122.1", "192.168.0.1")

    fun discover(trace: (String) -> Unit): SonyScalarWebApiTransport {
      var lastError: Throwable? = null
      DIRECT_IP_CANDIDATES.forEach { host ->
        try {
          val descriptorUrl = "http://$host:64321/DmsRmtDesc.xml"
          trace("Scalar probe host=$host")
          val xml = readText(descriptorUrl, 1_500, 2_500)
          if (!xml.contains("ScalarWebAPI", ignoreCase = true)) {
            throw SonyPtpException("Sony endpoint did not advertise ScalarWebAPI.")
          }
          val apiBaseUrl = extract(xml, "X_ScalarWebAPI_ActionList_URL")
            ?: throw SonyPtpException("Sony ScalarWebAPI control URL is missing.")
          val liveViewUrl = extract(xml, "X_ScalarWebAPI_LiveView_URL")
            ?: throw SonyPtpException("Sony ScalarWebAPI live-view URL is missing.")
          val modelName = extract(xml, "modelName") ?: "Sony Scalar Camera"
          trace("Scalar discovered host=$host api=${URL(apiBaseUrl).port} live=${URL(liveViewUrl).port}")
          return SonyScalarWebApiTransport(
            Descriptor(host, descriptorUrl, apiBaseUrl, liveViewUrl, modelName),
            trace,
          )
        } catch (error: Throwable) {
          lastError = error
          trace("Scalar probe unavailable host=$host reason=${error.message ?: error::class.java.simpleName}")
        }
      }
      val detail = lastError?.message?.takeIf(String::isNotBlank)
      throw SonyPtpException(
        if (detail == null) "No Sony ScalarWebAPI camera was found on the connected Wi-Fi network."
        else "No Sony ScalarWebAPI camera was found on the connected Wi-Fi network: $detail",
      )
    }

    private fun extract(xml: String, tag: String): String? {
      val pattern = Regex("<(?:[^:>]+:)?${Regex.escape(tag)}[^>]*>(.*?)</(?:[^:>]+:)?${Regex.escape(tag)}>", RegexOption.DOT_MATCHES_ALL)
      return pattern.find(xml)?.groupValues?.getOrNull(1)?.trim()?.takeIf(String::isNotEmpty)
    }

    private fun readText(url: String, connectTimeoutMs: Int, readTimeoutMs: Int): String {
      val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = connectTimeoutMs
        readTimeout = readTimeoutMs
        useCaches = false
      }
      return try {
        val code = connection.responseCode
        if (code !in 200..299) throw SonyPtpException("Sony discovery returned HTTP $code.")
        connection.inputStream.bufferedReader().use { it.readText() }
      } finally {
        connection.disconnect()
      }
    }
  }

  private val requestId = AtomicInteger(1)
  private var availableApis = emptySet<String>()
  private var liveConnection: HttpURLConnection? = null
  private var liveInput: BufferedInputStream? = null

  fun connect() {
    val response = call("getAvailableApiList")
    availableApis = response.optJSONArray("result")
      ?.optJSONArray(0)
      ?.let { values -> (0 until values.length()).mapNotNull(values::optString).toSet() }
      .orEmpty()
    if (availableApis.isEmpty()) throw SonyPtpException("Sony camera returned no available remote APIs.")
    trace("Scalar ready APIs=${availableApis.size}")
  }

  fun descriptor(): Descriptor = descriptor

  fun supportsTouchFocus(): Boolean = availableApis.contains("setTouchAFPosition")

  fun focusAt(x: Double, y: Double): String {
    if (!supportsTouchFocus()) return "unsupported"
    call("setTouchAFPosition", JSONArray().put(x * 100.0).put(y * 100.0))
    trace("Scalar touch focus requested x=${String.format(java.util.Locale.US, "%.1f", x * 100.0)} y=${String.format(java.util.Locale.US, "%.1f", y * 100.0)}")
    if (!availableApis.contains("getEvent")) return "started"
    repeat(6) {
      val event = call("getEvent", JSONArray().put(false), timeoutMs = 4_000).toString()
      if (event.contains("Focused", ignoreCase = true)) return "focused"
      if (event.contains("Failed", ignoreCase = true)) return "failed"
      Thread.sleep(180)
    }
    return "started"
  }

  fun startLiveView() {
    closeLiveStream()
    val activeLiveViewUrl = if (availableApis.contains("startLiveview")) {
      call("startLiveview").optJSONArray("result")?.optString(0)?.takeIf(String::isNotBlank)
        ?: throw SonyPtpException("Sony did not return an active live-view URL.")
    } else {
      descriptor.liveViewUrl
    }
    val connection = (URL(activeLiveViewUrl).openConnection() as HttpURLConnection).apply {
      requestMethod = "GET"
      connectTimeout = 3_000
      readTimeout = 8_000
      useCaches = false
      setRequestProperty("Accept", "image/jpeg, */*")
    }
    val code = connection.responseCode
    if (code !in 200..299) {
      connection.disconnect()
      throw SonyPtpException("Sony live view returned HTTP $code.")
    }
    liveConnection = connection
    liveInput = BufferedInputStream(connection.inputStream, 128 * 1024)
    trace("Scalar live view opened")
  }

  fun getLiveViewJpeg(): ByteArray {
    val input = liveInput ?: throw SonyPtpException("Sony live view is not open.")
    val output = ByteArrayOutputStream(128 * 1024)
    var previous = -1
    var started = false
    while (output.size() <= 8 * 1024 * 1024) {
      val current = input.read()
      if (current < 0) throw SonyPtpException("Sony live-view stream ended.")
      if (!started) {
        if (previous == 0xFF && current == 0xD8) {
          output.write(0xFF)
          output.write(0xD8)
          started = true
        }
      } else {
        output.write(current)
        if (previous == 0xFF && current == 0xD9) return output.toByteArray()
      }
      previous = current
    }
    throw SonyPtpException("Sony live-view JPEG exceeded the safe frame limit.")
  }

  fun capturePhoto(): ByteArray {
    if (!availableApis.contains("actTakePicture")) {
      throw SonyPtpException("This Sony mode does not currently expose remote still capture.")
    }
    repeat(2) { attempt ->
      try {
        return capturePhotoAttempt()
      } catch (error: SonyPtpException) {
        if (!error.retryable || attempt == 1) throw error
        trace("Scalar capture rejected; resetting shutter state before retry")
        if (availableApis.contains("cancelHalfPressShutter")) {
          runCatching { call("cancelHalfPressShutter") }
        }
        Thread.sleep(800)
      }
    }
    throw SonyPtpException("Sony capture retry ended unexpectedly.")
  }

  private fun capturePhotoAttempt(): ByteArray {
    val canHalfPress = availableApis.contains("actHalfPressShutter")
    if (canHalfPress) {
      call("actHalfPressShutter")
      waitForFocus()
    }
    return try {
      val response = call("actTakePicture", timeoutMs = 35_000)
      val result = response.optJSONArray("result")
      val urls = result?.optJSONArray(0)
      val photoUrl = urls?.optString(0)?.takeIf(String::isNotBlank)
        ?: throw SonyPtpException("Sony capture completed without a transferable JPEG URL.")
      downloadJpeg(photoUrl)
    } finally {
      if (canHalfPress && availableApis.contains("cancelHalfPressShutter")) {
        runCatching { call("cancelHalfPressShutter") }
      }
    }
  }

  private fun waitForFocus() {
    if (!availableApis.contains("getEvent")) {
      Thread.sleep(900)
      return
    }
    repeat(8) { attempt ->
      val event = call("getEvent", JSONArray().put(false), timeoutMs = 4_000).toString()
      when {
        event.contains("Focused", ignoreCase = true) -> {
          trace("Scalar autofocus ready attempt=${attempt + 1}")
          return
        }
        event.contains("Failed", ignoreCase = true) -> {
          trace("Scalar autofocus reported failure; shutter remains user-requested")
          return
        }
      }
      Thread.sleep(250)
    }
    trace("Scalar autofocus status timed out; attempting requested shutter")
  }

  fun stopLiveView() {
    closeLiveStream()
  }

  fun close() {
    closeLiveStream()
  }

  private fun call(method: String, params: JSONArray = JSONArray(), timeoutMs: Int = 8_000): JSONObject {
    val endpoint = descriptor.apiBaseUrl.trimEnd('/') + "/camera"
    val body = JSONObject()
      .put("method", method)
      .put("params", params)
      .put("id", requestId.getAndIncrement())
      .put("version", "1.0")
      .toString()
    val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
      requestMethod = "POST"
      connectTimeout = 3_000
      readTimeout = timeoutMs
      doOutput = true
      useCaches = false
      setRequestProperty("Content-Type", "application/json")
      setRequestProperty("Accept", "application/json")
    }
    return try {
      connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
      val code = connection.responseCode
      val stream = if (code in 200..299) connection.inputStream else connection.errorStream
      val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
      if (code !in 200..299) throw SonyPtpException("Sony $method returned HTTP $code.")
      val response = JSONObject(text)
      if (response.has("error")) {
        val apiError = response.getJSONArray("error")
        val code = apiError.optInt(0)
        throw SonyPtpException("Sony $method failed: $apiError.", retryable = code == 40400)
      }
      response
    } finally {
      connection.disconnect()
    }
  }

  private fun downloadJpeg(url: String): ByteArray {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
      requestMethod = "GET"
      connectTimeout = 3_000
      readTimeout = 20_000
      useCaches = false
    }
    return try {
      val code = connection.responseCode
      if (code !in 200..299) throw SonyPtpException("Sony photo download returned HTTP $code.")
      val bytes = connection.inputStream.use { it.readBytes() }
      if (bytes.size < 4 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) {
        throw SonyPtpException("Sony returned an invalid JPEG after capture.")
      }
      bytes
    } finally {
      connection.disconnect()
    }
  }

  private fun closeLiveStream() {
    runCatching { liveInput?.close() }
    liveInput = null
    liveConnection?.disconnect()
    liveConnection = null
  }
}
