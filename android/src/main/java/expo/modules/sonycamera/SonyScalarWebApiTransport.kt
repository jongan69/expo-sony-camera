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
  private val network: SonyCameraNetwork,
  private val trace: (String) -> Unit,
) {
  data class Descriptor(
    val host: String,
    val descriptorUrl: String,
    val apiBaseUrl: String,
    val liveViewUrl: String,
    val modelName: String,
    /**
     * Service name to endpoint URL.
     *
     * Sony advertises `camera`, `system`, `avContent`, and `guide` separately. Assuming
     * every method lives under `camera` makes media browsing unreachable.
     */
    val serviceUrls: Map<String, String> = emptyMap(),
  )

  companion object {
    private val DIRECT_IP_CANDIDATES = listOf("192.168.122.1", "192.168.0.1")

    fun discover(network: SonyCameraNetwork, trace: (String) -> Unit): SonyScalarWebApiTransport {
      var lastError: Throwable? = null

      // SSDP first: it finds the camera wherever the network put it. The fixed Wi-Fi
      // Direct addresses stay as a bounded fallback because multicast is unreliable on
      // some Android builds and blocked on some networks.
      val candidates = SonySsdpDiscovery.search(trace = trace) +
        DIRECT_IP_CANDIDATES.map { "http://$it:64321/DmsRmtDesc.xml" }

      candidates.distinct().forEach { descriptorUrl ->
        try {
          val host = URL(descriptorUrl).host
          trace("Scalar probe descriptor=$descriptorUrl")
          val xml = readText(network, descriptorUrl, 1_500, 2_500)
          if (!xml.contains("ScalarWebAPI", ignoreCase = true)) {
            throw SonyPtpException("Sony endpoint did not advertise ScalarWebAPI.")
          }
          val apiBaseUrl = extract(xml, "X_ScalarWebAPI_ActionList_URL")
            ?: throw SonyPtpException("Sony ScalarWebAPI control URL is missing.")
          val liveViewUrl = extract(xml, "X_ScalarWebAPI_LiveView_URL")
            ?: throw SonyPtpException("Sony ScalarWebAPI live-view URL is missing.")
          val modelName = extract(xml, "modelName") ?: "Sony Scalar Camera"
          val serviceUrls = extractServiceUrls(xml, apiBaseUrl)
          trace(
            "Scalar discovered host=$host api=${URL(apiBaseUrl).port} " +
              "live=${URL(liveViewUrl).port} services=${serviceUrls.keys.sorted()}",
          )
          return SonyScalarWebApiTransport(
            Descriptor(host, descriptorUrl, apiBaseUrl, liveViewUrl, modelName, serviceUrls),
            network,
            trace,
          )
        } catch (error: Throwable) {
          lastError = error
          trace(
            "Scalar probe unavailable descriptor=$descriptorUrl " +
              "reason=${error.message ?: error::class.java.simpleName}",
          )
        }
      }
      val detail = lastError?.message?.takeIf(String::isNotBlank)
      throw SonyPtpException(
        if (detail == null) "No Sony ScalarWebAPI camera was found on the connected Wi-Fi network."
        else "No Sony ScalarWebAPI camera was found on the connected Wi-Fi network: $detail",
      )
    }

    /**
     * Reads the `X_ScalarWebAPI_ServiceList` entries from the device descriptor.
     *
     * Each entry pairs a service type (`camera`, `system`, `avContent`, `guide`) with its
     * action-list URL. Falls back to the advertised action-list base for any service the
     * descriptor names without a URL.
     */
    private fun extractServiceUrls(xml: String, apiBaseUrl: String): Map<String, String> {
      val entry = Regex(
        "<(?:[^:>]+:)?X_ScalarWebAPI_Service>(.*?)</(?:[^:>]+:)?X_ScalarWebAPI_Service>",
        RegexOption.DOT_MATCHES_ALL,
      )
      val services = linkedMapOf<String, String>()
      entry.findAll(xml).forEach { match ->
        val block = match.groupValues[1]
        val type = extract(block, "X_ScalarWebAPI_ServiceType") ?: return@forEach
        val url = extract(block, "X_ScalarWebAPI_ActionList_URL") ?: apiBaseUrl
        services[type] = url.trimEnd('/') + "/" + type
      }
      if (services.isEmpty()) services["camera"] = apiBaseUrl.trimEnd('/') + "/camera"
      return services
    }

    private fun extract(xml: String, tag: String): String? {
      val pattern = Regex("<(?:[^:>]+:)?${Regex.escape(tag)}[^>]*>(.*?)</(?:[^:>]+:)?${Regex.escape(tag)}>", RegexOption.DOT_MATCHES_ALL)
      return pattern.find(xml)?.groupValues?.getOrNull(1)?.trim()?.takeIf(String::isNotEmpty)
    }

    private fun readText(
      network: SonyCameraNetwork,
      url: String,
      connectTimeoutMs: Int,
      readTimeoutMs: Int,
    ): String {
      val connection = network.open(url).apply {
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
    refreshAvailableApis()

    // Several Sony bodies expose almost no shooting APIs until the camera has been put
    // into remote shooting mode. Without this the adapter can conclude "unsupported"
    // against a camera that would have worked, so the API list is re-read afterwards.
    if (availableApis.contains("startRecMode")) {
      trace("Scalar entering remote shooting mode")
      runCatching { call("startRecMode", timeoutMs = 10_000) }
        .onFailure { trace("Scalar startRecMode failed: ${it.message ?: "unknown"}") }
      Thread.sleep(500)
      refreshAvailableApis()
    }

    if (availableApis.isEmpty()) throw SonyPtpException("Sony camera returned no available remote APIs.")
    trace("Scalar ready APIs=${availableApis.size}")
  }

  private fun refreshAvailableApis() {
    val response = call("getAvailableApiList")
    availableApis = response.optJSONArray("result")
      ?.optJSONArray(0)
      ?.let { values -> (0 until values.length()).mapNotNull(values::optString).toSet() }
      .orEmpty()
  }

  fun supports(method: String): Boolean = availableApis.contains(method)

  fun descriptor(): Descriptor = descriptor

  fun supportsTouchFocus(): Boolean = availableApis.contains("setTouchAFPosition")

  fun focusAt(x: Double, y: Double): String {
    if (!supportsTouchFocus()) return "unsupported"
    call("setTouchAFPosition", JSONArray().put(x * 100.0).put(y * 100.0))
    trace("Scalar touch focus requested x=${String.format(java.util.Locale.US, "%.1f", x * 100.0)} y=${String.format(java.util.Locale.US, "%.1f", y * 100.0)}")
    if (!availableApis.contains("getEvent")) return "started"
    // "started" is a truthful outcome: the request was accepted but the camera did not
    // report a result within the budget. It must not be reported as "focused".
    return awaitFocusOutcome(2_000) ?: "started"
  }

  fun startLiveView() {
    closeLiveStream()
    val activeLiveViewUrl = if (availableApis.contains("startLiveview")) {
      call("startLiveview").optJSONArray("result")?.optString(0)?.takeIf(String::isNotBlank)
        ?: throw SonyPtpException("Sony did not return an active live-view URL.")
    } else {
      descriptor.liveViewUrl
    }
    val connection = network.open(activeLiveViewUrl).apply {
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

  /**
   * Waits for the camera to report a focus outcome, bounded by [timeoutMs].
   *
   * The first read is immediate so an already-focused camera is not made to wait for a
   * change notification that will never arrive. Later reads use Sony's long-polling form,
   * which returns as soon as the camera's state changes instead of the client sleeping
   * blindly between snapshots. The read timeout is clamped to the remaining budget so the
   * long poll can never outlive the deadline.
   *
   * Returns `"focused"`, `"failed"`, or `null` when the deadline passed without an outcome.
   */
  private fun awaitFocusOutcome(timeoutMs: Long): String? {
    if (!availableApis.contains("getEvent")) {
      Thread.sleep(minOf(timeoutMs, 900))
      return null
    }
    val deadline = System.currentTimeMillis() + timeoutMs
    var longPolling = false
    while (true) {
      val remaining = deadline - System.currentTimeMillis()
      if (remaining <= 0) return null
      val event = runCatching {
        call(
          "getEvent",
          JSONArray().put(longPolling),
          timeoutMs = remaining.coerceIn(500, timeoutMs).toInt(),
        ).toString()
      }.getOrNull() ?: return null
      when {
        event.contains("Focused", ignoreCase = true) -> return "focused"
        event.contains("Failed", ignoreCase = true) -> return "failed"
      }
      longPolling = true
    }
  }

  private fun waitForFocus() {
    when (awaitFocusOutcome(3_000)) {
      "focused" -> trace("Scalar autofocus ready")
      "failed" -> trace("Scalar autofocus reported failure; shutter remains user-requested")
      else -> trace("Scalar autofocus status timed out; attempting requested shutter")
    }
  }

  fun stopLiveView() {
    closeLiveStream()
  }

  fun close() {
    closeLiveStream()
  }

  private fun call(
    method: String,
    params: JSONArray = JSONArray(),
    timeoutMs: Int = 8_000,
    service: String = "camera",
  ): JSONObject {
    val endpoint = descriptor.serviceUrls[service] ?: if (service == "camera") {
      // Older descriptors advertise only the action-list base; camera is the default.
      descriptor.apiBaseUrl.trimEnd('/') + "/camera"
    } else {
      throw SonyPtpException("This Sony camera does not advertise the $service service.")
    }
    val body = JSONObject()
      .put("method", method)
      .put("params", params)
      .put("id", requestId.getAndIncrement())
      .put("version", "1.0")
      .toString()
    val connection = network.open(endpoint).apply {
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
    val connection = network.open(url).apply {
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
