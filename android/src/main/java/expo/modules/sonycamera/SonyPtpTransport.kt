package expo.modules.sonycamera

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

private const val USB_RECIPIENT_ENDPOINT = 0x02

internal class SonyPtpException(message: String, val retryable: Boolean = false) : Exception(message)

internal class SonyPtpTransport private constructor(
  private val connection: UsbDeviceConnection,
  private val usbInterface: UsbInterface,
  private val bulkIn: UsbEndpoint,
  private val bulkOut: UsbEndpoint,
  private val trace: (String) -> Unit,
) {
  companion object {
    private const val CONTAINER_COMMAND = 1
    private const val CONTAINER_DATA = 2
    private const val CONTAINER_RESPONSE = 3
    private const val RESPONSE_OK = 0x2001
    private const val RESPONSE_DEVICE_BUSY = 0x2019
    private const val RESPONSE_INVALID_HANDLE = 0x2009
    private const val RESPONSE_ACCESS_DENIED = 0x200F
    private const val HANDLE_CAPTURED_IMAGE = -0x3FFF // 0xFFFFC001
    private const val HANDLE_LIVE_VIEW = -0x3FFE // 0xFFFFC002
    private const val PROPERTY_FOCUS_FOUND = 0xD213
    private const val PROPERTY_OBJECT_IN_MEMORY = 0xD215
    private const val PROPERTY_LIVE_VIEW_STATUS = 0xD221
    private const val MAX_CONTAINER_BYTES = 100 * 1024 * 1024
    private const val MAX_USB_READ_BYTES = 64 * 1024
    private const val USB_TIMEOUT_MS = 5_000

    /** Above this, prefer GetPartialObject over a single full-size GetObject allocation. */
    private const val PARTIAL_TRANSFER_THRESHOLD_BYTES = 8L * 1024 * 1024
    private const val PARTIAL_TRANSFER_CHUNK_BYTES = 4 * 1024 * 1024

    fun open(manager: UsbManager, device: UsbDevice, trace: (String) -> Unit): SonyPtpTransport {
      val target = (0 until device.interfaceCount)
        .map(device::getInterface)
        .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE }
        ?: throw SonyPtpException("The attached Sony device does not expose a PTP interface.")
      var input: UsbEndpoint? = null
      var output: UsbEndpoint? = null
      for (index in 0 until target.endpointCount) {
        val endpoint = target.getEndpoint(index)
        if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
        if (endpoint.direction == UsbConstants.USB_DIR_IN) input = endpoint
        if (endpoint.direction == UsbConstants.USB_DIR_OUT) output = endpoint
      }
      val connection = manager.openDevice(device)
        ?: throw SonyPtpException("Android could not open the Sony USB device.")
      if (!connection.claimInterface(target, true)) {
        connection.close()
        throw SonyPtpException("The Sony PTP interface is already in use.")
      }
      trace(
        "USB interface=${target.id} alt=${target.alternateSetting} " +
          "bulkIn=${input?.address?.hex()} max=${input?.maxPacketSize} " +
          "bulkOut=${output?.address?.hex()} max=${output?.maxPacketSize}",
      )
      input?.let { clearEndpointHalt(connection, it, trace) }
      output?.let { clearEndpointHalt(connection, it, trace) }
      return SonyPtpTransport(
        connection,
        target,
        input ?: throw SonyPtpException("Sony PTP bulk input endpoint is missing."),
        output ?: throw SonyPtpException("Sony PTP bulk output endpoint is missing."),
        trace,
      )
    }
  }

  private val transaction = AtomicInteger(0)
  private var sessionOpen = false
  private var liveViewTransactions = 0
  private var capturedObjectConsumed = false
  private var bufferedInput = byteArrayOf()
  private var bufferedInputOffset = 0

  /** Populated during [authenticate]; null when the camera refused `GetDeviceInfo`. */
  var deviceInfo: SonyDeviceInfo? = null
    private set

  fun authenticate() {
    execute(0x1002, intArrayOf(1))
    sessionOpen = true
    execute(0x9201, intArrayOf(1, 0, 0), expectsData = true)
    execute(0x9201, intArrayOf(2, 0, 0), expectsData = true)
    var versionData: ByteArray? = null
    for (attempt in 0 until 6) {
      val info = execute(0x9202, intArrayOf(0x00C8), expectsData = true, allowBusy = true)
      if (info.data?.isNotEmpty() == true) {
        versionData = info.data
        break
      }
      SystemClock.sleep(150)
    }
    val protocolData = versionData ?: throw SonyPtpException("Sony PTP authentication did not return protocol information.")
    if (protocolData.size < 2 || protocolData.u16(0) < 0x00C8) {
      throw SonyPtpException("The Sony camera reported an unsupported PTP protocol version.")
    }
    execute(0x9201, intArrayOf(3, 0, 0), expectsData = true)

    // Read the camera's own capability lists. This must not be fatal: a body that
    // refuses GetDeviceInfo can still stream and capture, it just cannot be described
    // accurately, and an empty capability set is the honest result in that case.
    deviceInfo = runCatching {
      execute(0x1001, expectsData = true, allowBusy = true).data?.let(SonyPtpDeviceInfo::parse)
    }.getOrNull()
    deviceInfo?.let {
      trace(
        "Sony DeviceInfo model=${it.model} version=${it.deviceVersion} " +
          "operations=${it.operationsSupported.size} properties=${it.devicePropertiesSupported.size} " +
          "events=${it.eventsSupported.size}",
      )
    } ?: trace("Sony camera did not return DeviceInfo; capabilities will be reported as unknown")
  }

  fun prepareStillCapture() {
    // These documented properties select host dial control and still capture.
    runCatching { execute(0x9205, intArrayOf(0xD25A), outgoingData = byteArrayOf(1)) }
    runCatching { execute(0x9205, intArrayOf(0x5013), outgoingData = le32(1)) }
  }

  /** Reads and parses every device property the camera currently reports. */
  private fun readDeviceProperties(): List<SonyDeviceProperty> {
    val result = execute(0x9209, expectsData = true, allowBusy = true)
    return result.data?.let(SonyPtpProperties::parseAll).orEmpty()
  }

  fun prepareLiveView() {
    var lastStatus: Long? = null
    repeat(50) { attempt ->
      val status = SonyPtpProperties.scalar(readDeviceProperties(), PROPERTY_LIVE_VIEW_STATUS)
      if (attempt == 0 || attempt % 10 == 0 || status != lastStatus) {
        trace("Sony live-view status D221=${status?.let { "0x%04X".format(it) } ?: "missing"}")
      }
      lastStatus = status
      if ((status ?: 0L) != 0L) {
        try {
          execute(0x1008, intArrayOf(HANDLE_LIVE_VIEW), expectsData = true)
          return
        } catch (error: SonyPtpException) {
          if (!error.retryable) throw error
        }
      }
      SystemClock.sleep(100)
    }
    throw SonyPtpException(
      "Sony live view did not become ready (D221=${lastStatus?.let { "0x%04X".format(it) } ?: "missing"}). " +
        "Set USB Connection to PC Remote.",
    )
  }

  fun getLiveViewJpeg(): ByteArray? {
    // Sony requires ObjectInfo to be refreshed before each read of its virtual
    // live-view handle. Repeating GetObject alone after AccessDenied can stall
    // the A7 III USB transport.
    val info = execute(
      0x1008,
      intArrayOf(HANDLE_LIVE_VIEW),
      expectsData = true,
      acceptedResponses = setOf(RESPONSE_OK, RESPONSE_INVALID_HANDLE, RESPONSE_DEVICE_BUSY),
    )
    if (info.responseCode != RESPONSE_OK) return null
    val attempt = liveViewTransactions + 1
    if (attempt <= 10) {
      trace("live-view cycle=$attempt objectBytes=${info.data?.getOrNull(8)?.let { info.data.u32(8) } ?: "unknown"}")
    }
    val result = execute(
      0x1009,
      intArrayOf(HANDLE_LIVE_VIEW),
      expectsData = true,
      acceptedResponses = setOf(RESPONSE_OK, RESPONSE_ACCESS_DENIED),
    )
    if (result.responseCode == RESPONSE_ACCESS_DENIED) return null
    val jpeg = result.data?.let(SonyPtpCodec::parseSonyJpegData)
    if (liveViewTransactions <= 10) {
      trace("live-view cycle=$liveViewTransactions payloadBytes=${result.data?.size ?: 0} jpegBytes=${jpeg?.size ?: 0}")
    }
    return jpeg
  }

  fun captureStill() {
    capturedObjectConsumed = false
    setButton(0xD2C1, true)
    SystemClock.sleep(90)
    setButton(0xD2C2, true)
    try {
      // Sony's reference implementation waits briefly for the focus result
      // before releasing the shutter buttons. Do not make focus a hard gate:
      // manual-focus lenses and back-button-focus setups may never report it.
      val focusDeadline = SystemClock.elapsedRealtime() + 1_000
      while (SystemClock.elapsedRealtime() < focusDeadline) {
        val focus = SonyPtpProperties.scalar(readDeviceProperties(), PROPERTY_FOCUS_FOUND)
        if (focus == 2L || focus == 3L) break
        SystemClock.sleep(50)
      }
    } finally {
      runCatching { setButton(0xD2C2, false) }
      runCatching { setButton(0xD2C1, false) }
    }
  }

  fun awaitCapturedJpeg(timeoutMs: Long): ByteArray {
    val deadline = SystemClock.elapsedRealtime() + timeoutMs
    while (SystemClock.elapsedRealtime() < deadline) {
      try {
        pollCapturedJpeg()?.let { return it }
      } catch (error: SonyPtpException) {
        if (!error.retryable) throw error
      }
      SystemClock.sleep(100)
    }
    throw SonyPtpException("The camera fired, but no JPEG reached the app. Check PC Remote save settings and JPEG output.")
  }

  fun pollCapturedJpeg(): ByteArray? {
    val objectInMemory = SonyPtpProperties.scalar(readDeviceProperties(), PROPERTY_OBJECT_IN_MEMORY)
    if (objectInMemory == null || objectInMemory < 0x8000) {
      capturedObjectConsumed = false
      return null
    }
    if (capturedObjectConsumed) return null
    trace("Sony captured-object ready D215=${"0x%04X".format(objectInMemory)}")

    // Sony warns that reading the virtual captured-image handle before D215
    // reaches 0x8000 can crash camera firmware. ObjectInfo must also be read
    // before GetObject for Sony's virtual handle.
    val info = execute(
      0x1008,
      intArrayOf(HANDLE_CAPTURED_IMAGE),
      expectsData = true,
      acceptedResponses = setOf(RESPONSE_OK, RESPONSE_INVALID_HANDLE, RESPONSE_DEVICE_BUSY),
    )
    if (info.responseCode != RESPONSE_OK) return null
    // ObjectInfo carries the compressed size at offset 8. Anything large is streamed with
    // GetPartialObject rather than pulled into one allocation the size of the whole file.
    val declaredSize = info.data?.takeIf { it.size >= 12 }?.u32(8) ?: 0L
    val result = if (declaredSize > PARTIAL_TRANSFER_THRESHOLD_BYTES &&
      deviceInfo?.supportsOperation(0x101B) == true
    ) {
      trace("captured object size=$declaredSize using GetPartialObject")
      PtpResult(readObjectInChunks(HANDLE_CAPTURED_IMAGE, declaredSize), RESPONSE_OK)
    } else {
      execute(
        0x1009,
        intArrayOf(HANDLE_CAPTURED_IMAGE),
        expectsData = true,
        acceptedResponses = setOf(RESPONSE_OK, RESPONSE_INVALID_HANDLE, RESPONSE_DEVICE_BUSY),
      )
    }
    if (result.responseCode != RESPONSE_OK) return null
    val jpeg = result.data?.let(SonyPtpCodec::parseSonyJpegData)
      ?: throw SonyPtpException(
        "Sony transferred the captured object, but it did not contain a readable JPEG (bytes=${result.data?.size ?: 0}).",
      )
    capturedObjectConsumed = true
    return jpeg
  }

  /**
   * Streams an object with `GetPartialObject` (0x101B) in bounded windows.
   *
   * `GetObject` requires a single allocation the size of the whole file. A 60 MB RAW is a
   * 60 MB contiguous byte array, which fails on mid-range Android devices long before the
   * 100 MB container ceiling is reached. Reading in windows keeps peak transient memory at
   * one chunk; the assembled result still has to fit in memory, but the allocation pattern
   * no longer needs a single contiguous block of the full size up front.
   */
  private fun readObjectInChunks(handle: Int, declaredSize: Long): ByteArray {
    val output = java.io.ByteArrayOutputStream(minOf(declaredSize, 8L * 1024 * 1024).toInt())
    var offset = 0L
    while (offset < declaredSize) {
      val window = minOf(PARTIAL_TRANSFER_CHUNK_BYTES.toLong(), declaredSize - offset).toInt()
      val chunk = execute(
        0x101B,
        intArrayOf(handle, offset.toInt(), window),
        expectsData = true,
        allowBusy = true,
      )
      val bytes = chunk.data
      if (bytes == null || bytes.isEmpty()) {
        // A short or empty window means the camera stopped supplying data. Returning what
        // arrived lets the JPEG validator reject it, rather than looping forever.
        trace("partial transfer ended early at offset=$offset of $declaredSize")
        break
      }
      output.write(bytes)
      offset += bytes.size
      if (bytes.size < window) {
        trace("partial transfer short window at offset=$offset expected=$window got=${bytes.size}")
      }
    }
    trace("partial transfer complete bytes=${output.size()} declared=$declaredSize")
    return output.toByteArray()
  }

  fun close() {
    if (sessionOpen) runCatching { execute(0x1003) }
    sessionOpen = false
    runCatching { connection.releaseInterface(usbInterface) }
    connection.close()
  }

  private fun setButton(code: Int, down: Boolean) {
    execute(0x9207, intArrayOf(code), outgoingData = le16(if (down) 2 else 1))
  }

  private fun execute(
    operation: Int,
    params: IntArray = intArrayOf(),
    outgoingData: ByteArray? = null,
    expectsData: Boolean = false,
    allowBusy: Boolean = false,
    acceptedResponses: Set<Int> = setOf(RESPONSE_OK),
  ): PtpResult {
    // PIMA 15740 and Sony's reference require OpenSession to use transaction 0.
    // The first transaction inside the open session then starts at 1.
    val id = if (operation == 0x1002) 0 else transaction.incrementAndGet()
    val isLiveView = operation == 0x1009 && params.firstOrNull() == HANDLE_LIVE_VIEW
    if (isLiveView) liveViewTransactions += 1
    val shouldTrace = !isLiveView || liveViewTransactions <= 10 || liveViewTransactions % 30 == 0
    if (shouldTrace) trace("PTP send op=${operation.hex()} tx=$id params=${params.joinToString { it.hex32() }}")
    writeContainer(CONTAINER_COMMAND, operation, id, params.toPayload())
    if (outgoingData != null) writeContainer(CONTAINER_DATA, operation, id, outgoingData)
    var data: ByteArray? = null
    while (true) {
      val container = try {
        readContainer()
      } catch (error: SonyPtpException) {
        throw SonyPtpException(
          "PTP op ${operation.hex()} tx=$id failed while reading: ${error.message}",
          retryable = error.retryable,
        )
      }
      if (container.transactionId != id) continue
      when (container.type) {
        CONTAINER_DATA -> data = container.payload
        CONTAINER_RESPONSE -> {
          val allowed = container.code in acceptedResponses || (allowBusy && container.code == RESPONSE_DEVICE_BUSY)
          if (!allowed) {
            val retryable = container.code in setOf(RESPONSE_DEVICE_BUSY, RESPONSE_ACCESS_DENIED, RESPONSE_INVALID_HANDLE)
            trace("PTP rejected op=${operation.hex()} tx=$id code=${container.code.hex()} bytes=${data?.size ?: 0}")
            throw SonyPtpException("Sony PTP ${operation.hex()} failed with ${container.code.hex()}.", retryable)
          }
          if (shouldTrace || container.code != RESPONSE_OK) {
            trace("PTP response op=${operation.hex()} tx=$id code=${container.code.hex()} bytes=${data?.size ?: 0}")
          }
          return PtpResult(if (expectsData) data else null, container.code)
        }
      }
    }
  }

  private fun writeContainer(type: Int, code: Int, transactionId: Int, payload: ByteArray) {
    val output = SonyPtpCodec.buildContainer(type, code, transactionId, payload)
    var offset = 0
    while (offset < output.size) {
      val written = connection.bulkTransfer(bulkOut, output, offset, output.size - offset, USB_TIMEOUT_MS)
      if (written <= 0) throw SonyPtpException("USB write to Sony camera failed.")
      offset += written
    }
  }

  private fun readContainer(): PtpContainer {
    val header = readExact(12)
    val length = header.u32(0).toInt()
    if (length < 12 || length > MAX_CONTAINER_BYTES) throw SonyPtpException("Sony returned an invalid PTP container size.")
    val type = header.u16(4)
    val code = header.u16(6)
    val id = header.u32(8).toInt()
    val payload = if (length == 12) byteArrayOf() else readExact(length - 12)
    return PtpContainer(type, code, id, payload)
  }

  private fun readExact(size: Int): ByteArray {
    val output = ByteArray(size)
    var offset = 0
    var consecutiveReadFailures = 0
    while (offset < size) {
      val bufferedBytes = bufferedInput.size - bufferedInputOffset
      if (bufferedBytes > 0) {
        val count = minOf(size - offset, bufferedBytes)
        bufferedInput.copyInto(output, offset, bufferedInputOffset, bufferedInputOffset + count)
        bufferedInputOffset += count
        offset += count
        if (bufferedInputOffset == bufferedInput.size) {
          bufferedInput = byteArrayOf()
          bufferedInputOffset = 0
        }
        continue
      }

      // A USB bulk read shorter than the device's packet discards the unread
      // remainder. Always read at least one complete packet and retain bytes
      // beyond the current PTP header/payload request for the next read.
      val requestSize = maxOf(
        bulkIn.maxPacketSize,
        minOf(MAX_USB_READ_BYTES, size - offset),
      )
      val chunk = ByteArray(requestSize)
      val read = connection.bulkTransfer(bulkIn, chunk, 0, chunk.size, USB_TIMEOUT_MS)
      if (read == 0) {
        // A PTP data container whose USB transfer is an exact multiple of the
        // 512-byte max packet is terminated by a valid zero-length packet.
        // Consume it and continue reading the response container; clearing the
        // endpoint here discards Sony's following response and freezes live view.
        trace("USB bulk-IN zero-length packet requested=${chunk.size} remaining=${size - offset}")
        continue
      }
      if (read < 0) {
        consecutiveReadFailures += 1
        trace(
          "USB bulk-IN retry=$consecutiveReadFailures result=$read " +
            "requested=${chunk.size} remaining=${size - offset}",
        )
        clearEndpointHalt(connection, bulkIn, trace)
        if (consecutiveReadFailures >= 5) {
          // Once a PTP transaction has been sent, advancing to a new command
          // after an incomplete read desynchronizes Sony's USB state machine.
          throw SonyPtpException("USB read from Sony camera could not be recovered.")
        }
        SystemClock.sleep(20)
        continue
      }
      consecutiveReadFailures = 0
      bufferedInput = if (read == chunk.size) chunk else chunk.copyOf(read)
      bufferedInputOffset = 0
    }
    return output
  }
}

private fun clearEndpointHalt(
  connection: UsbDeviceConnection,
  endpoint: UsbEndpoint,
  trace: (String) -> Unit,
) {
  val result = connection.controlTransfer(
    UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_STANDARD or USB_RECIPIENT_ENDPOINT,
    1, // CLEAR_FEATURE
    0, // ENDPOINT_HALT
    endpoint.address,
    null,
    0,
    1_000,
  )
  trace("USB clear-halt endpoint=${endpoint.address.hex()} result=$result")
}

private data class PtpContainer(val type: Int, val code: Int, val transactionId: Int, val payload: ByteArray)
private data class PtpResult(val data: ByteArray?, val responseCode: Int)

internal object SonyPtpCodec {
  fun buildContainer(type: Int, code: Int, transactionId: Int, payload: ByteArray): ByteArray =
    ByteBuffer.allocate(12 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
      .putInt(12 + payload.size)
      .putShort(type.toShort())
      .putShort(code.toShort())
      .putInt(transactionId)
      .put(payload)
      .array()

  fun parseSonyJpegData(data: ByteArray): ByteArray? {
    if (data.size < 4) return null
    val suggestedOffset = data.u32(0).toInt()
    val searchStart = if (suggestedOffset in 0 until data.size - 1) suggestedOffset else 0
    val start = (
      findJpegStart(data, searchStart) ?: if (searchStart == 0) null else findJpegStart(data, 0)
    ) ?: return null
    var end = data.size
    for (index in data.size - 2 downTo start + 2) {
      if (data[index] == 0xFF.toByte() && data[index + 1] == 0xD9.toByte()) {
        end = index + 2
        break
      }
    }
    return data.copyOfRange(start, end)
  }

  private fun findJpegStart(data: ByteArray, searchStart: Int): Int? =
    (searchStart until data.size - 1).firstOrNull {
      data[it] == 0xFF.toByte() && data[it + 1] == 0xD8.toByte()
    }
}

private fun IntArray.toPayload(): ByteArray {
  val buffer = ByteBuffer.allocate(size * 4).order(ByteOrder.LITTLE_ENDIAN)
  forEach(buffer::putInt)
  return buffer.array()
}

private fun le16(value: Int): ByteArray = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()
private fun le32(value: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
private fun ByteArray.u16(offset: Int): Int = ByteBuffer.wrap(this, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
private fun ByteArray.u32(offset: Int): Long = ByteBuffer.wrap(this, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
private fun Int.hex(): String = "0x%04X".format(this)
private fun Int.hex32(): String = "0x%08X".format(this)
