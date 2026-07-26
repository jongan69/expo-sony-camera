package expo.modules.sonycamera

/**
 * Parser for Sony's `SDIDevicePropInfo Dataset Array`, the payload returned by
 * `SDIO_GetAllExtDevicePropInfo` (0x9209).
 *
 * Array layout:
 * ```
 * Num of Elements            UINT64
 * SDIExtDevicePropInfo[0 .. Num of Elements - 1]
 * ```
 *
 * Each `SDIExtDevicePropInfo` record:
 * ```
 * Device Property Code       UINT16
 * DataType                   UINT16
 * GetSet                     UINT8    0 = read only, 1 = the initiator can set the value
 * IsEnabled                  UINT8    0 = false, 1 = true, 2 = display only
 * Factory Default Value      variable, sized by DataType
 * Current Value              variable, sized by DataType
 * Form Flag                  UINT8    0 = none, 1 = range, 2 = enumeration
 *   range form:              minimum, maximum, step   each sized by DataType
 *   enumeration form:        UINT16 count + values    (settable list)
 *                            UINT16 count + values    (supported list)
 * ```
 *
 * The previous implementation searched the payload for a two-byte property code and
 * assumed a fixed offset to the current value. That could match bytes inside an earlier
 * record's enumeration list and return an unrelated value, and it could not enumerate.
 * This parser walks records sequentially so every offset is derived from the record that
 * precedes it.
 */
internal object SonyPtpProperties {
  /** Sony reports the element count as a UINT64; no camera returns anything near this. */
  private const val MAX_ELEMENTS = 4_096

  /** Guards against a corrupt count field turning into a multi-gigabyte allocation. */
  private const val MAX_ARRAY_ELEMENTS = 65_536

  fun parseAll(data: ByteArray): List<SonyDeviceProperty> {
    val reader = SonyPtpReader(data)
    val properties = ArrayList<SonyDeviceProperty>()
    return try {
      val declared = reader.u64()
      val count = if (declared < 0 || declared > MAX_ELEMENTS) MAX_ELEMENTS.toLong() else declared
      var index = 0L
      while (index < count && reader.remaining() > 0) {
        properties.add(parseRecord(reader))
        index += 1
      }
      properties
    } catch (error: SonyPtpParseException) {
      // A truncated payload still carries every record that preceded the truncation.
      // Returning them is strictly better than discarding a valid prefix.
      properties
    }
  }

  fun find(properties: List<SonyDeviceProperty>, code: Int): SonyDeviceProperty? =
    properties.firstOrNull { it.code == code }

  /** Convenience for the readiness properties the transport polls (D213, D215, D221). */
  fun scalar(properties: List<SonyDeviceProperty>, code: Int): Long? =
    (find(properties, code)?.currentValue as? Long)

  private fun parseRecord(reader: SonyPtpReader): SonyDeviceProperty {
    val code = reader.u16()
    val dataType = reader.u16()
    val getSet = reader.u8()
    val isEnabled = reader.u8()
    val defaultValue = reader.value(dataType)
    val currentValue = reader.value(dataType)
    val formFlag = reader.u8()

    var range: SonyPropertyRange? = null
    var settableValues = emptyList<Any?>()
    var supportedValues = emptyList<Any?>()

    when (formFlag) {
      FORM_RANGE -> {
        range = SonyPropertyRange(
          minimum = reader.value(dataType),
          maximum = reader.value(dataType),
          step = reader.value(dataType),
        )
      }
      FORM_ENUMERATION -> {
        settableValues = reader.valueList(dataType)
        // Sony emits a second list. It is absent on some firmware, so a clean end of
        // payload here is a complete record rather than a parse failure.
        supportedValues = if (reader.remaining() >= 2) reader.valueList(dataType) else emptyList()
      }
    }

    return SonyDeviceProperty(
      code = code,
      dataType = dataType,
      writable = getSet == 1,
      availability = when (isEnabled) {
        0 -> SonyPropertyAvailability.DISABLED
        2 -> SonyPropertyAvailability.DISPLAY_ONLY
        else -> SonyPropertyAvailability.ENABLED
      },
      defaultValue = defaultValue,
      currentValue = currentValue,
      range = range,
      settableValues = settableValues,
      supportedValues = supportedValues,
    )
  }

  private const val FORM_RANGE = 0x01
  private const val FORM_ENUMERATION = 0x02

  /** Reads a `UINT16` count followed by that many DataType-sized values. */
  private fun SonyPtpReader.valueList(dataType: Int): List<Any?> {
    val count = u16()
    if (count > MAX_ARRAY_ELEMENTS) throw SonyPtpParseException("Enumeration count $count is implausible.")
    return (0 until count).map { value(dataType) }
  }
}

internal enum class SonyPropertyAvailability {
  /** `IsEnabled = 0x00`. Supported by the body but invalid in the current mode. */
  DISABLED,

  /** `IsEnabled = 0x01`. Readable, and writable when `writable` is also true. */
  ENABLED,

  /** `IsEnabled = 0x02`. Indication only; the value must not be written. */
  DISPLAY_ONLY,
}

internal data class SonyPropertyRange(val minimum: Any?, val maximum: Any?, val step: Any?)

/**
 * One decoded device property.
 *
 * [currentValue] is a [Long] for every integer datatype, a [String] for `STR`, a
 * `List<Any?>` for array datatypes, and a hex [String] for the 128-bit datatypes.
 */
internal data class SonyDeviceProperty(
  val code: Int,
  val dataType: Int,
  val writable: Boolean,
  val availability: SonyPropertyAvailability,
  val defaultValue: Any?,
  val currentValue: Any?,
  val range: SonyPropertyRange?,
  val settableValues: List<Any?>,
  val supportedValues: List<Any?>,
) {
  val enabled: Boolean get() = availability != SonyPropertyAvailability.DISABLED
}

internal class SonyPtpParseException(message: String) : Exception(message)

/**
 * Little-endian cursor over a PTP payload.
 *
 * Every read is bounds-checked. Advancing the cursor by the true size of each field is
 * what makes sequential record parsing possible, so an unreadable field must stop the
 * walk rather than silently return a zero and desynchronise every following record.
 */
internal class SonyPtpReader(private val data: ByteArray, private var offset: Int = 0) {
  fun remaining(): Int = data.size - offset

  fun u8(): Int = read(1) { data[it].toInt() and 0xFF }

  fun u16(): Int = read(2) {
    (data[it].toInt() and 0xFF) or ((data[it + 1].toInt() and 0xFF) shl 8)
  }

  fun u32(): Long = read(4) {
    var result = 0L
    for (index in 3 downTo 0) result = (result shl 8) or (data[it + index].toLong() and 0xFF)
    result
  }

  fun u64(): Long = read(8) {
    var result = 0L
    for (index in 7 downTo 0) result = (result shl 8) or (data[it + index].toLong() and 0xFF)
    result
  }

  /**
   * Reads one value of [dataType] and advances past it.
   *
   * Returns `null` for datatypes this parser does not decode, but only after advancing
   * the cursor by the correct number of bytes. An unknown datatype of known width is
   * recoverable; an unknown width is not.
   */
  fun value(dataType: Int): Any? = when (dataType) {
    DATATYPE_INT8 -> signed(u8().toLong(), 8)
    DATATYPE_UINT8 -> u8().toLong()
    DATATYPE_INT16 -> signed(u16().toLong(), 16)
    DATATYPE_UINT16 -> u16().toLong()
    DATATYPE_INT32 -> signed(u32(), 32)
    DATATYPE_UINT32 -> u32()
    // A UINT64 above Long.MAX_VALUE is returned as its two's-complement Long. No Sony
    // device property uses that range; the cursor position is what matters here.
    DATATYPE_INT64, DATATYPE_UINT64 -> u64()
    DATATYPE_INT128, DATATYPE_UINT128 -> hex(16)
    DATATYPE_STRING -> string()
    else ->
      if (dataType and ARRAY_FLAG != 0) array(dataType and ARRAY_FLAG.inv())
      else throw SonyPtpParseException("Unknown PTP datatype 0x%04X.".format(dataType))
  }

  /** PTP string: `UINT8` character count, then that many UTF-16LE units including the null. */
  private fun string(): String {
    val characters = u8()
    if (characters == 0) return ""
    val bytes = read(characters * 2) { start -> data.copyOfRange(start, start + characters * 2) }
    return String(bytes, Charsets.UTF_16LE).trimEnd('\u0000', ' ')
  }

  /** PTP array: `UINT32` element count, then that many elements of the element datatype. */
  private fun array(elementType: Int): List<Any?> {
    val count = u32()
    if (count < 0 || count > MAX_ELEMENTS) throw SonyPtpParseException("Array length $count is implausible.")
    return (0 until count).map { value(elementType) }
  }

  private fun hex(bytes: Int): String = read(bytes) { start ->
    (0 until bytes).joinToString("") { "%02X".format(data[start + bytes - 1 - it]) }
  }

  private fun signed(raw: Long, bits: Int): Long {
    val signBit = 1L shl (bits - 1)
    return if (raw and signBit != 0L) raw - (1L shl bits) else raw
  }

  private fun <T> read(size: Int, decode: (Int) -> T): T {
    if (size < 0 || offset + size > data.size) {
      throw SonyPtpParseException("PTP payload ended after $offset of ${data.size} bytes.")
    }
    val start = offset
    offset += size
    return decode(start)
  }

  private companion object {
    const val MAX_ELEMENTS = 65_536L
    const val ARRAY_FLAG = 0x4000

    const val DATATYPE_INT8 = 0x0001
    const val DATATYPE_UINT8 = 0x0002
    const val DATATYPE_INT16 = 0x0003
    const val DATATYPE_UINT16 = 0x0004
    const val DATATYPE_INT32 = 0x0005
    const val DATATYPE_UINT32 = 0x0006
    const val DATATYPE_INT64 = 0x0007
    const val DATATYPE_UINT64 = 0x0008
    const val DATATYPE_INT128 = 0x0009
    const val DATATYPE_UINT128 = 0x000A
    const val DATATYPE_STRING = 0xFFFF
  }
}
