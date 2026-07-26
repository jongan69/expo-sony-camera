package expo.modules.sonycamera

/**
 * Parser for the PTP `DeviceInfo` dataset returned by `GetDeviceInfo` (0x1001), and the
 * capability resolution built on top of it.
 *
 * Layout (PIMA 15740):
 * ```
 * StandardVersion             UINT16
 * VendorExtensionID           UINT32
 * VendorExtensionVersion      UINT16
 * VendorExtensionDesc         STR
 * FunctionalMode              UINT16
 * OperationsSupported         AUINT16
 * EventsSupported             AUINT16
 * DevicePropertiesSupported   AUINT16
 * CaptureFormats              AUINT16
 * ImageFormats                AUINT16
 * Manufacturer                STR
 * Model                       STR
 * DeviceVersion               STR
 * SerialNumber                STR
 * ```
 *
 * The serial number is deliberately parsed and discarded. It uniquely identifies the
 * user's camera body and has no role in capability decisions, so retaining it would put
 * an identifier into state payloads and diagnostics for no benefit.
 */
internal object SonyPtpDeviceInfo {
  fun parse(data: ByteArray): SonyDeviceInfo? {
    val reader = SonyPtpReader(data)
    return try {
      val standardVersion = reader.u16()
      val vendorExtensionId = reader.u32()
      val vendorExtensionVersion = reader.u16()
      val vendorExtensionDescription = reader.value(DATATYPE_STRING) as? String ?: ""
      val functionalMode = reader.u16()
      val operations = reader.codeSet()
      val events = reader.codeSet()
      val properties = reader.codeSet()
      val captureFormats = reader.codeSet()
      val imageFormats = reader.codeSet()
      val manufacturer = reader.value(DATATYPE_STRING) as? String ?: ""
      val model = reader.value(DATATYPE_STRING) as? String ?: ""
      val deviceVersion = reader.value(DATATYPE_STRING) as? String ?: ""
      SonyDeviceInfo(
        standardVersion = standardVersion,
        vendorExtensionId = vendorExtensionId,
        vendorExtensionVersion = vendorExtensionVersion,
        vendorExtensionDescription = vendorExtensionDescription,
        functionalMode = functionalMode,
        operationsSupported = operations,
        eventsSupported = events,
        devicePropertiesSupported = properties,
        captureFormats = captureFormats,
        imageFormats = imageFormats,
        manufacturer = manufacturer,
        model = model,
        deviceVersion = deviceVersion,
      )
    } catch (error: SonyPtpParseException) {
      null
    }
  }

  private const val DATATYPE_STRING = 0xFFFF
  private const val DATATYPE_ARRAY_UINT16 = 0x4004

  @Suppress("UNCHECKED_CAST")
  private fun SonyPtpReader.codeSet(): Set<Int> =
    (value(DATATYPE_ARRAY_UINT16) as? List<Any?>)
      ?.mapNotNull { (it as? Long)?.toInt() }
      ?.toSet()
      .orEmpty()
}

/**
 * What the attached camera reports it supports.
 *
 * This is evidence from the device, not an assumption from a model name. A body that does
 * not list an operation code here will reject that operation regardless of what Sony's
 * reference says the model can do in another mode or firmware.
 */
internal data class SonyDeviceInfo(
  val standardVersion: Int,
  val vendorExtensionId: Long,
  val vendorExtensionVersion: Int,
  val vendorExtensionDescription: String,
  val functionalMode: Int,
  val operationsSupported: Set<Int>,
  val eventsSupported: Set<Int>,
  val devicePropertiesSupported: Set<Int>,
  val captureFormats: Set<Int>,
  val imageFormats: Set<Int>,
  val manufacturer: String,
  val model: String,
  val deviceVersion: String,
) {
  fun supportsOperation(code: Int): Boolean = operationsSupported.contains(code)

  fun supportsProperty(code: Int): Boolean = devicePropertiesSupported.contains(code)

  fun supportsEvent(code: Int): Boolean = eventsSupported.contains(code)
}

/**
 * Derives the public capability payload from device evidence.
 *
 * Every flag traces to an operation, property, or event code the camera itself listed.
 * Nothing here is a literal chosen because a feature happens to be implemented in this
 * package: a feature this package implements against a camera that does not advertise the
 * underlying codes is still unsupported.
 */
internal object SonyCapabilityResolver {
  private const val OPERATION_GET_OBJECT = 0x1009
  private const val OPERATION_GET_OBJECT_HANDLES = 0x1007
  private const val OPERATION_GET_PARTIAL_OBJECT = 0x101B
  private const val OPERATION_SET_DEVICE_PROP = 0x9205
  private const val OPERATION_CONTROL_DEVICE = 0x9207
  private const val OPERATION_GET_ALL_PROPS = 0x9209

  private const val PROPERTY_FOCUS_INDICATION = 0xD213
  private const val PROPERTY_OBJECT_IN_MEMORY = 0xD215
  private const val PROPERTY_MOVIE_RECORDING_STATE = 0xD21D
  private const val PROPERTY_LIVE_VIEW_STATUS = 0xD221
  private const val PROPERTY_FOCUS_AREA = 0xD22C

  private const val EVENT_OBJECT_ADDED = 0xC201
  private const val EVENT_DEVICE_PROP_CHANGED = 0xC203

  /** PTP 3 `AF Area Position (x, y)`; absent on PTP 2 bodies. */
  private const val CONTROL_AF_AREA_POSITION = 0xD2DC

  fun resolve(info: SonyDeviceInfo?, protocol: String, transport: String): Map<String, Any?> {
    if (info == null) {
      // No DeviceInfo means no evidence. Reporting an empty capability set is honest;
      // reporting the package's implemented features would be a guess.
      return mapOf(
        "protocols" to listOf(protocol),
        "transports" to listOf(transport),
        "connectionModes" to listOf(if (transport == "usb") "usb" else "wifi_direct"),
        "categories" to listOf("connection"),
        "features" to emptyMap<String, Boolean>(),
      )
    }

    val liveView = info.supportsProperty(PROPERTY_LIVE_VIEW_STATUS) &&
      info.supportsOperation(OPERATION_GET_OBJECT)
    val stillCapture = info.supportsOperation(OPERATION_CONTROL_DEVICE)
    val imageTransfer = info.supportsOperation(OPERATION_GET_OBJECT) &&
      info.supportsProperty(PROPERTY_OBJECT_IN_MEMORY)
    val properties = info.supportsOperation(OPERATION_GET_ALL_PROPS)
    val writableProperties = properties && info.supportsOperation(OPERATION_SET_DEVICE_PROP)
    val movie = info.supportsProperty(PROPERTY_MOVIE_RECORDING_STATE)
    val mediaBrowser = info.supportsOperation(OPERATION_GET_OBJECT_HANDLES)
    val events = info.supportsEvent(EVENT_OBJECT_ADDED) || info.supportsEvent(EVENT_DEVICE_PROP_CHANGED)
    val halfPress = info.supportsProperty(PROPERTY_FOCUS_INDICATION)
    val touchFocus = info.supportsProperty(PROPERTY_FOCUS_AREA) ||
      info.supportsProperty(CONTROL_AF_AREA_POSITION)

    val categories = buildList {
      add("connection")
      if (liveView) add("live_view")
      if (stillCapture) add("still_capture")
      if (halfPress || touchFocus) add("focus")
      if (writableProperties) add("exposure")
      if (movie) add("movie")
      if (mediaBrowser) add("media")
      add("health")
    }

    return mapOf(
      "protocols" to listOf(protocol),
      "transports" to listOf(transport),
      "connectionModes" to listOf(if (transport == "usb") "usb" else "wifi_direct"),
      "categories" to categories,
      "features" to mapOf(
        "liveView" to liveView,
        "stillCapture" to stillCapture,
        "imageTransfer" to imageTransfer,
        "properties" to properties,
        "movieRecording" to movie,
        "mediaBrowser" to mediaBrowser,
        "events" to events,
        "halfPress" to halfPress,
        "touchFocus" to touchFocus,
      ),
    )
  }

  /** True when the camera can stream large objects without a single full-size allocation. */
  fun supportsPartialTransfer(info: SonyDeviceInfo?): Boolean =
    info?.supportsOperation(OPERATION_GET_PARTIAL_OBJECT) == true
}
