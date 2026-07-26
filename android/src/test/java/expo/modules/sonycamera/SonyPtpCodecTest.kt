package expo.modules.sonycamera

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SonyPtpCodecTest {
  @Test
  fun buildsLittleEndianCommandContainer() {
    val bytes = SonyPtpCodec.buildContainer(1, 0x1009, 7, byteArrayOf(2, 1, 0, 0))
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    assertEquals(16, buffer.int)
    assertEquals(1, buffer.short.toInt())
    assertEquals(0x1009, buffer.short.toInt())
    assertEquals(7, buffer.int)
    assertArrayEquals(byteArrayOf(2, 1, 0, 0), bytes.copyOfRange(12, 16))
  }

  @Test
  fun extractsJpegFromSonyLiveViewDataset() {
    val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 0xFF.toByte(), 0xD9.toByte())
    val dataset = ByteBuffer.allocate(12 + jpeg.size).order(ByteOrder.LITTLE_ENDIAN)
      .putInt(12)
      .putInt(jpeg.size)
      .putInt(0)
      .put(jpeg)
      .array()
    assertArrayEquals(jpeg, SonyPtpCodec.parseSonyJpegData(dataset))
  }

  @Test
  fun rejectsOutOfBoundsLiveViewDataset() {
    val invalid = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(20).putInt(500).array()
    assertNull(SonyPtpCodec.parseSonyJpegData(invalid))
  }
}

class SonyPtpPropertiesTest {
  @Test
  fun parsesScalarPropertyRecord() {
    val payload = Dataset()
      .u64(1)
      .record(code = 0xD221, dataType = UINT16, getSet = 0, isEnabled = 1) {
        u16(0); u16(1); u8(FORM_NONE)
      }
      .bytes()

    val properties = SonyPtpProperties.parseAll(payload)

    assertEquals(1, properties.size)
    assertEquals(0xD221, properties[0].code)
    assertEquals(1L, properties[0].currentValue)
    assertEquals(0L, properties[0].defaultValue)
    assertFalse(properties[0].writable)
    assertEquals(SonyPropertyAvailability.ENABLED, properties[0].availability)
  }

  /**
   * The regression this parser exists for.
   *
   * The first record's enumeration list contains the bytes `15 D2 04 00` — the exact
   * two-byte code and datatype the previous linear scan searched for. That scan matched
   * inside the enumeration and returned bytes belonging to White Balance. Walking records
   * sequentially reads the real D215 that follows.
   */
  @Test
  fun doesNotMatchAPropertyCodeThatAppearsInsideAnEnumerationList() {
    val payload = Dataset()
      .u64(2)
      .record(code = 0x5005, dataType = UINT16, getSet = 1, isEnabled = 1) {
        u16(2); u16(2)
        u8(FORM_ENUMERATION)
        u16(2); u16(0xD215); u16(0x0004)
        u16(0)
      }
      .record(code = 0xD215, dataType = UINT16, getSet = 0, isEnabled = 1) {
        u16(0); u16(0x8001); u8(FORM_NONE)
      }
      .bytes()

    val properties = SonyPtpProperties.parseAll(payload)

    assertEquals(2, properties.size)
    assertEquals(0x8001L, SonyPtpProperties.scalar(properties, 0xD215))
    assertEquals(listOf(0xD215L, 0x0004L), properties[0].settableValues)
  }

  @Test
  fun parsesRangeFormAndSignedValues() {
    val payload = Dataset()
      .u64(1)
      .record(code = 0x5010, dataType = INT16, getSet = 1, isEnabled = 1) {
        u16(0); u16(0xFC18) // current = -1000
        u8(FORM_RANGE)
        u16(0xF448); u16(0x0BB8); u16(333) // -3000 .. 3000, step 333
      }
      .bytes()

    val property = SonyPtpProperties.parseAll(payload).single()

    assertEquals(-1000L, property.currentValue)
    assertEquals(-3000L, property.range?.minimum)
    assertEquals(3000L, property.range?.maximum)
    assertEquals(333L, property.range?.step)
  }

  @Test
  fun parsesBothEnumerationLists() {
    val payload = Dataset()
      .u64(1)
      .record(code = 0x500A, dataType = UINT16, getSet = 1, isEnabled = 1) {
        u16(1); u16(2)
        u8(FORM_ENUMERATION)
        u16(2); u16(1); u16(2)
        u16(3); u16(1); u16(2); u16(4)
      }
      .bytes()

    val property = SonyPtpProperties.parseAll(payload).single()

    assertEquals(listOf(1L, 2L), property.settableValues)
    assertEquals(listOf(1L, 2L, 4L), property.supportedValues)
  }

  @Test
  fun treatsAMissingSecondEnumerationListAsACompleteRecord() {
    val payload = Dataset()
      .u64(1)
      .record(code = 0x500A, dataType = UINT16, getSet = 1, isEnabled = 1) {
        u16(1); u16(2)
        u8(FORM_ENUMERATION)
        u16(1); u16(7)
      }
      .bytes()

    val property = SonyPtpProperties.parseAll(payload).single()

    assertEquals(listOf(7L), property.settableValues)
    assertTrue(property.supportedValues.isEmpty())
  }

  @Test
  fun mapsIsEnabledToAvailability() {
    val payload = Dataset()
      .u64(3)
      .record(code = 0x5007, dataType = UINT16, getSet = 1, isEnabled = 0) {
        u16(0); u16(0); u8(FORM_NONE)
      }
      .record(code = 0x5008, dataType = UINT16, getSet = 1, isEnabled = 1) {
        u16(0); u16(0); u8(FORM_NONE)
      }
      .record(code = 0x5009, dataType = UINT16, getSet = 0, isEnabled = 2) {
        u16(0); u16(0); u8(FORM_NONE)
      }
      .bytes()

    val properties = SonyPtpProperties.parseAll(payload)

    assertEquals(SonyPropertyAvailability.DISABLED, properties[0].availability)
    assertEquals(SonyPropertyAvailability.ENABLED, properties[1].availability)
    assertEquals(SonyPropertyAvailability.DISPLAY_ONLY, properties[2].availability)
    assertFalse(properties[0].enabled)
    assertTrue(properties[2].enabled)
  }

  @Test
  fun parsesStringAndArrayDatatypes() {
    val payload = Dataset()
      .u64(2)
      .record(code = 0xD415, dataType = STRING, getSet = 0, isEnabled = 1) {
        u8(1); u16(0) // default: null terminator only
        u8(3); u16('F'.code); u16('E'.code); u16(0)
        u8(FORM_NONE)
      }
      .record(code = 0xD416, dataType = ARRAY_UINT16, getSet = 0, isEnabled = 1) {
        u32(0)
        u32(2); u16(10); u16(20)
        u8(FORM_NONE)
      }
      .bytes()

    val properties = SonyPtpProperties.parseAll(payload)

    assertEquals("FE", properties[0].currentValue)
    assertEquals(listOf(10L, 20L), properties[1].currentValue)
  }

  @Test
  fun advancesPastAnUnknownButFixedWidthDatatype() {
    val payload = Dataset()
      .u64(2)
      .record(code = 0xD500, dataType = UINT64, getSet = 0, isEnabled = 1) {
        u64(0); u64(0x1122334455667788L); u8(FORM_NONE)
      }
      .record(code = 0xD215, dataType = UINT16, getSet = 0, isEnabled = 1) {
        u16(0); u16(0x8001); u8(FORM_NONE)
      }
      .bytes()

    val properties = SonyPtpProperties.parseAll(payload)

    assertEquals(2, properties.size)
    assertEquals(0x8001L, SonyPtpProperties.scalar(properties, 0xD215))
  }

  @Test
  fun returnsTheValidPrefixOfATruncatedPayload() {
    val complete = Dataset()
      .u64(4)
      .record(code = 0xD221, dataType = UINT16, getSet = 0, isEnabled = 1) {
        u16(0); u16(1); u8(FORM_NONE)
      }
      .record(code = 0xD215, dataType = UINT16, getSet = 0, isEnabled = 1) {
        u16(0); u16(0x8001); u8(FORM_NONE)
      }
      .bytes()

    val properties = SonyPtpProperties.parseAll(complete.copyOfRange(0, complete.size - 4))

    assertEquals(1, properties.size)
    assertEquals(0xD221, properties[0].code)
  }

  @Test
  fun toleratesAnImplausibleElementCount() {
    val payload = Dataset()
      .u64(Long.MAX_VALUE)
      .record(code = 0xD221, dataType = UINT16, getSet = 0, isEnabled = 1) {
        u16(0); u16(1); u8(FORM_NONE)
      }
      .bytes()

    val properties = SonyPtpProperties.parseAll(payload)

    assertEquals(1, properties.size)
    assertEquals(1L, SonyPtpProperties.scalar(properties, 0xD221))
  }

  @Test
  fun returnsNothingForAnEmptyOrHeaderOnlyPayload() {
    assertTrue(SonyPtpProperties.parseAll(byteArrayOf()).isEmpty())
    assertTrue(SonyPtpProperties.parseAll(Dataset().u64(0).bytes()).isEmpty())
  }

  @Test
  fun scalarReturnsNullForAnAbsentProperty() {
    val payload = Dataset()
      .u64(1)
      .record(code = 0xD221, dataType = UINT16, getSet = 0, isEnabled = 1) {
        u16(0); u16(1); u8(FORM_NONE)
      }
      .bytes()

    assertNull(SonyPtpProperties.scalar(SonyPtpProperties.parseAll(payload), 0xD215))
  }

  private companion object {
    const val UINT16 = 0x0004
    const val INT16 = 0x0003
    const val UINT64 = 0x0008
    const val STRING = 0xFFFF
    const val ARRAY_UINT16 = 0x4004
    const val FORM_NONE = 0x00
    const val FORM_RANGE = 0x01
    const val FORM_ENUMERATION = 0x02
  }
}

/**
 * Little-endian builder for `SDIDevicePropInfo Dataset Array` fixtures.
 *
 * [record] writes only the fixed-width header. The body writes the factory-default value,
 * the current value, the form flag, and any form data — the variable-width tail whose
 * width the parser has to derive from the datatype, which is the part under test.
 */
private class Dataset {
  private val out = java.io.ByteArrayOutputStream()

  fun u8(value: Int) = apply { out.write(value and 0xFF) }

  fun u16(value: Int) = apply {
    out.write(value and 0xFF)
    out.write((value shr 8) and 0xFF)
  }

  fun u32(value: Long) = apply { repeat(4) { out.write(((value shr (it * 8)) and 0xFF).toInt()) } }

  fun u64(value: Long) = apply { repeat(8) { out.write(((value ushr (it * 8)) and 0xFF).toInt()) } }

  fun record(code: Int, dataType: Int, getSet: Int, isEnabled: Int, body: Dataset.() -> Unit) = apply {
    u16(code)
    u16(dataType)
    u8(getSet)
    u8(isEnabled)
    body()
  }

  /** PTP string: UINT8 character count including the trailing null, then UTF-16LE units. */
  fun string(value: String) = apply {
    if (value.isEmpty()) {
      u8(0)
    } else {
      u8(value.length + 1)
      value.forEach { u16(it.code) }
      u16(0)
    }
  }

  /** PTP AUINT16: UINT32 element count, then that many UINT16 codes. */
  fun codes(vararg values: Int) = apply {
    u32(values.size.toLong())
    values.forEach { u16(it) }
  }

  fun bytes(): ByteArray = out.toByteArray()
}

class SonyPtpDeviceInfoTest {
  /** An A7 III-shaped DeviceInfo: PTP 2 codes, no GetPartialObject, no media enumeration. */
  private fun a7iiiDeviceInfo() = Dataset()
    .u16(100)
    .u32(0x00000006)
    .u16(100)
    .string("Sony PTP Extension")
    .u16(0)
    .codes(0x1001, 0x1002, 0x1003, 0x1008, 0x1009, 0x9201, 0x9202, 0x9205, 0x9207, 0x9209)
    .codes(0xC201, 0xC203)
    .codes(0x5005, 0xD213, 0xD215, 0xD221, 0xD22C)
    .codes(0x3801)
    .codes(0x3801, 0xB101)
    .string("Sony")
    .string("ILCE-7M3")
    .string("4.03")
    .string("SERIAL-REDACTED")
    .bytes()

  @Test
  fun parsesDeviceInfoDataset() {
    val info = requireNotNull(SonyPtpDeviceInfo.parse(a7iiiDeviceInfo()))

    assertEquals("ILCE-7M3", info.model)
    assertEquals("Sony", info.manufacturer)
    assertEquals("4.03", info.deviceVersion)
    assertEquals("Sony PTP Extension", info.vendorExtensionDescription)
    assertEquals(10, info.operationsSupported.size)
    assertTrue(info.supportsOperation(0x9209))
    assertFalse(info.supportsOperation(0x101B))
    assertTrue(info.supportsEvent(0xC201))
    assertTrue(info.imageFormats.contains(0xB101))
  }

  @Test
  fun derivesCapabilitiesFromDeviceEvidence() {
    val info = SonyPtpDeviceInfo.parse(a7iiiDeviceInfo())
    val capabilities = SonyCapabilityResolver.resolve(info, "sony_camera_control_ptp2", "usb")

    @Suppress("UNCHECKED_CAST")
    val features = capabilities["features"] as Map<String, Boolean>

    assertEquals(true, features["liveView"])
    assertEquals(true, features["stillCapture"])
    assertEquals(true, features["imageTransfer"])
    assertEquals(true, features["properties"])
    assertEquals(true, features["events"])
    assertEquals(true, features["touchFocus"])
    // D21D and 0x1007 are absent from this body, so these must not be advertised.
    assertEquals(false, features["movieRecording"])
    assertEquals(false, features["mediaBrowser"])
    assertEquals(
      listOf("connection", "live_view", "still_capture", "focus", "exposure", "health"),
      capabilities["categories"],
    )
    assertFalse(SonyCapabilityResolver.supportsPartialTransfer(info))
  }

  @Test
  fun collapsesCapabilitiesForAMinimalBody() {
    val payload = Dataset()
      .u16(100).u32(0).u16(100).string("").u16(0)
      .codes(0x1001, 0x1002, 0x1003)
      .codes()
      .codes()
      .codes(0x3801)
      .codes(0x3801)
      .string("Sony").string("DSC-UNKNOWN").string("1.00").string("X")
      .bytes()

    val capabilities = SonyCapabilityResolver.resolve(
      SonyPtpDeviceInfo.parse(payload), "sony_camera_control_ptp2", "usb",
    )

    @Suppress("UNCHECKED_CAST")
    val features = capabilities["features"] as Map<String, Boolean>
    assertEquals(false, features["liveView"])
    assertEquals(false, features["stillCapture"])
    assertEquals(listOf("connection", "health"), capabilities["categories"])
  }

  /**
   * Without DeviceInfo there is no evidence, so the honest report is an empty feature set
   * rather than the list of features this package happens to implement.
   */
  @Test
  fun reportsNoFeaturesWhenDeviceInfoIsUnavailable() {
    val capabilities = SonyCapabilityResolver.resolve(null, "sony_camera_control_ptp2", "usb")

    assertEquals(emptyMap<String, Boolean>(), capabilities["features"])
    assertEquals(listOf("connection"), capabilities["categories"])
  }

  @Test
  fun returnsNullForATruncatedDataset() {
    assertNull(SonyPtpDeviceInfo.parse(a7iiiDeviceInfo().copyOfRange(0, 20)))
  }
}

class SonySsdpDiscoveryTest {
  private val response = buildString {
    append("HTTP/1.1 200 OK\r\n")
    append("CACHE-CONTROL: max-age=1800\r\n")
    append("EXT:\r\n")
    append("LOCATION: http://192.168.1.42:64321/DmsRmtDesc.xml\r\n")
    append("ST: urn:schemas-sony-com:service:ScalarWebAPI:1\r\n")
    append("USN: uuid:00000000-0000-0000-0000-000000000000\r\n")
    append("\r\n")
  }

  @Test
  fun readsTheLocationHeader() {
    assertEquals(
      "http://192.168.1.42:64321/DmsRmtDesc.xml",
      SonySsdpDiscovery.headerValue(response, "LOCATION"),
    )
  }

  /** SSDP header casing is not guaranteed, so lookup must not depend on it. */
  @Test
  fun matchesHeaderNamesCaseInsensitively() {
    assertEquals(
      "http://192.168.1.42:64321/DmsRmtDesc.xml",
      SonySsdpDiscovery.headerValue(response.replace("LOCATION", "Location"), "LOCATION"),
    )
  }

  @Test
  fun returnsNullForAbsentOrEmptyHeaders() {
    assertNull(SonySsdpDiscovery.headerValue(response, "SERVER"))
    assertNull(SonySsdpDiscovery.headerValue(response, "EXT"))
  }

  @Test
  fun preservesUrlsThatContainColons() {
    val value = SonySsdpDiscovery.headerValue(response, "ST")
    assertEquals("urn", value?.substringBefore(':'))
    assertTrue(response.contains("ScalarWebAPI"))
  }
}
