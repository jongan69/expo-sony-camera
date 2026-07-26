import Foundation

/// Availability reported by the `IsEnabled` field of an `SDIExtDevicePropInfo` record.
///
/// A property can be supported by the body yet invalid in the camera's current mode.
/// Callers need to tell that apart from "this body does not have this property" because
/// the two conditions need different UI and different error copy.
enum SonyPropertyAvailability {
  /// `IsEnabled = 0x00`. Supported but invalid in the current mode.
  case disabled
  /// `IsEnabled = 0x01`. Readable, and writable when `writable` is also true.
  case enabled
  /// `IsEnabled = 0x02`. Indication only; the value must not be written.
  case displayOnly
}

/// One value decoded from a PTP payload.
///
/// The associated types mirror the PTP datatype space: every integer datatype decodes to
/// `.integer`, `STR` to `.text`, array datatypes to `.array`, and the 128-bit datatypes
/// to `.opaque` (correctly sized, coarsely represented).
enum SonyPropertyValue: Equatable {
  case integer(Int64)
  case text(String)
  case array([SonyPropertyValue])
  case opaque(String)

  var integerValue: Int64? {
    if case let .integer(value) = self { return value }
    return nil
  }
}

struct SonyPropertyRange: Equatable {
  let minimum: SonyPropertyValue
  let maximum: SonyPropertyValue
  let step: SonyPropertyValue
}

/// One decoded device property from `SDIO_GetAllExtDevicePropInfo` (0x9209).
struct SonyDeviceProperty: Equatable {
  let code: UInt16
  let dataType: UInt16
  let writable: Bool
  let availability: SonyPropertyAvailability
  let defaultValue: SonyPropertyValue
  let currentValue: SonyPropertyValue
  let range: SonyPropertyRange?
  let settableValues: [SonyPropertyValue]
  let supportedValues: [SonyPropertyValue]

  var enabled: Bool { availability != .disabled }

  static func == (lhs: SonyDeviceProperty, rhs: SonyDeviceProperty) -> Bool {
    lhs.code == rhs.code && lhs.dataType == rhs.dataType && lhs.writable == rhs.writable
      && lhs.availability == rhs.availability && lhs.currentValue == rhs.currentValue
  }
}

struct SonyPtpParseFailure: LocalizedError {
  let message: String
  var errorDescription: String? { message }
}

/// Parser for Sony's `SDIDevicePropInfo Dataset Array`.
///
/// Array layout:
/// ```
/// Num of Elements            UINT64
/// SDIExtDevicePropInfo[0 .. Num of Elements - 1]
/// ```
///
/// Each `SDIExtDevicePropInfo` record:
/// ```
/// Device Property Code       UINT16
/// DataType                   UINT16
/// GetSet                     UINT8    0 = read only, 1 = the initiator can set the value
/// IsEnabled                  UINT8    0 = false, 1 = true, 2 = display only
/// Factory Default Value      variable, sized by DataType
/// Current Value              variable, sized by DataType
/// Form Flag                  UINT8    0 = none, 1 = range, 2 = enumeration
///   range form:              minimum, maximum, step   each sized by DataType
///   enumeration form:        UINT16 count + values    (settable list)
///                            UINT16 count + values    (supported list)
/// ```
///
/// The previous implementation searched the payload for a two-byte property code and
/// assumed a fixed offset to the current value. That could match bytes inside an earlier
/// record's enumeration list and return an unrelated value, and it could not enumerate.
/// This parser walks records sequentially so every offset is derived from the record that
/// precedes it.
enum SonyPtpProperties {
  private static let maxElements: Int64 = 4_096
  private static let formRange: UInt8 = 0x01
  private static let formEnumeration: UInt8 = 0x02

  static func parseAll(_ data: Data) -> [SonyDeviceProperty] {
    let reader = SonyPtpReader(data)
    var properties: [SonyDeviceProperty] = []
    do {
      let declared = try reader.u64()
      let count = (declared < 0 || declared > maxElements) ? maxElements : declared
      var index: Int64 = 0
      while index < count, reader.remaining > 0 {
        properties.append(try parseRecord(reader))
        index += 1
      }
    } catch {
      // A truncated payload still carries every record that preceded the truncation.
      // Returning them is strictly better than discarding a valid prefix.
    }
    return properties
  }

  static func find(_ properties: [SonyDeviceProperty], code: UInt16) -> SonyDeviceProperty? {
    properties.first { $0.code == code }
  }

  /// Convenience for the readiness properties the transport polls (D213, D215, D221).
  static func scalar(_ properties: [SonyDeviceProperty], code: UInt16) -> Int64? {
    find(properties, code: code)?.currentValue.integerValue
  }

  private static func parseRecord(_ reader: SonyPtpReader) throws -> SonyDeviceProperty {
    let code = try reader.u16()
    let dataType = try reader.u16()
    let getSet = try reader.u8()
    let isEnabled = try reader.u8()
    let defaultValue = try reader.value(dataType)
    let currentValue = try reader.value(dataType)
    let formFlag = try reader.u8()

    var range: SonyPropertyRange?
    var settableValues: [SonyPropertyValue] = []
    var supportedValues: [SonyPropertyValue] = []

    switch formFlag {
    case formRange:
      range = SonyPropertyRange(
        minimum: try reader.value(dataType),
        maximum: try reader.value(dataType),
        step: try reader.value(dataType)
      )
    case formEnumeration:
      settableValues = try reader.valueList(dataType)
      // Sony emits a second list. It is absent on some firmware, so a clean end of
      // payload here is a complete record rather than a parse failure.
      supportedValues = reader.remaining >= 2 ? try reader.valueList(dataType) : []
    default:
      break
    }

    let availability: SonyPropertyAvailability
    switch isEnabled {
    case 0: availability = .disabled
    case 2: availability = .displayOnly
    default: availability = .enabled
    }

    return SonyDeviceProperty(
      code: code,
      dataType: dataType,
      writable: getSet == 1,
      availability: availability,
      defaultValue: defaultValue,
      currentValue: currentValue,
      range: range,
      settableValues: settableValues,
      supportedValues: supportedValues
    )
  }
}

/// Little-endian cursor over a PTP payload.
///
/// Every read is bounds-checked. Advancing the cursor by the true size of each field is
/// what makes sequential record parsing possible, so an unreadable field must stop the
/// walk rather than silently return a zero and desynchronise every following record.
final class SonyPtpReader {
  private let data: Data
  private var offset: Int

  private static let maxElements: Int64 = 65_536
  private static let arrayFlag: UInt16 = 0x4000

  private enum DataType {
    static let int8: UInt16 = 0x0001
    static let uint8: UInt16 = 0x0002
    static let int16: UInt16 = 0x0003
    static let uint16: UInt16 = 0x0004
    static let int32: UInt16 = 0x0005
    static let uint32: UInt16 = 0x0006
    static let int64: UInt16 = 0x0007
    static let uint64: UInt16 = 0x0008
    static let int128: UInt16 = 0x0009
    static let uint128: UInt16 = 0x000A
    static let string: UInt16 = 0xFFFF
  }

  init(_ data: Data, offset: Int = 0) {
    self.data = data
    self.offset = offset
  }

  var remaining: Int { data.count - offset }

  func u8() throws -> UInt8 {
    let bytes = try take(1)
    return bytes[0]
  }

  func u16() throws -> UInt16 {
    let bytes = try take(2)
    return UInt16(bytes[0]) | (UInt16(bytes[1]) << 8)
  }

  func u32() throws -> Int64 {
    let bytes = try take(4)
    var result: Int64 = 0
    for index in stride(from: 3, through: 0, by: -1) {
      result = (result << 8) | Int64(bytes[index])
    }
    return result
  }

  func u64() throws -> Int64 {
    let bytes = try take(8)
    var result: UInt64 = 0
    for index in stride(from: 7, through: 0, by: -1) {
      result = (result << 8) | UInt64(bytes[index])
    }
    return Int64(bitPattern: result)
  }

  /// Reads one value of `dataType` and advances past it.
  ///
  /// An unknown datatype of known width is recoverable because the cursor still advances
  /// correctly; an unknown width is not, and throws.
  func value(_ dataType: UInt16) throws -> SonyPropertyValue {
    switch dataType {
    case DataType.int8: return .integer(signed(Int64(try u8()), bits: 8))
    case DataType.uint8: return .integer(Int64(try u8()))
    case DataType.int16: return .integer(signed(Int64(try u16()), bits: 16))
    case DataType.uint16: return .integer(Int64(try u16()))
    case DataType.int32: return .integer(signed(try u32(), bits: 32))
    case DataType.uint32: return .integer(try u32())
    // A UINT64 above Int64.max is returned as its two's-complement value. No Sony device
    // property uses that range; the cursor position is what matters here.
    case DataType.int64, DataType.uint64: return .integer(try u64())
    case DataType.int128, DataType.uint128: return .opaque(try hex(16))
    case DataType.string: return .text(try string())
    default:
      guard dataType & Self.arrayFlag != 0 else {
        throw SonyPtpParseFailure(message: String(format: "Unknown PTP datatype 0x%04X.", dataType))
      }
      return .array(try array(elementType: dataType & ~Self.arrayFlag))
    }
  }

  /// Reads a `UINT16` count followed by that many `dataType`-sized values.
  func valueList(_ dataType: UInt16) throws -> [SonyPropertyValue] {
    let count = try u16()
    guard Int64(count) <= Self.maxElements else {
      throw SonyPtpParseFailure(message: "Enumeration count \(count) is implausible.")
    }
    return try (0..<count).map { _ in try value(dataType) }
  }

  /// PTP string: `UINT8` character count, then that many UTF-16LE units including the null.
  private func string() throws -> String {
    let characters = Int(try u8())
    if characters == 0 { return "" }
    let bytes = try take(characters * 2)
    let decoded = String(data: Data(bytes), encoding: .utf16LittleEndian) ?? ""
    return decoded.trimmingCharacters(in: CharacterSet(charactersIn: "\0 "))
  }

  /// PTP array: `UINT32` element count, then that many elements of the element datatype.
  private func array(elementType: UInt16) throws -> [SonyPropertyValue] {
    let count = try u32()
    guard count >= 0, count <= Self.maxElements else {
      throw SonyPtpParseFailure(message: "Array length \(count) is implausible.")
    }
    return try (0..<count).map { _ in try value(elementType) }
  }

  private func hex(_ count: Int) throws -> String {
    let bytes = try take(count)
    return bytes.reversed().map { String(format: "%02X", $0) }.joined()
  }

  private func signed(_ raw: Int64, bits: Int) -> Int64 {
    let signBit: Int64 = 1 << (bits - 1)
    return (raw & signBit) != 0 ? raw - (1 << bits) : raw
  }

  private func take(_ size: Int) throws -> [UInt8] {
    guard size >= 0, offset + size <= data.count else {
      throw SonyPtpParseFailure(message: "PTP payload ended after \(offset) of \(data.count) bytes.")
    }
    let start = data.index(data.startIndex, offsetBy: offset)
    let end = data.index(start, offsetBy: size)
    offset += size
    return [UInt8](data[start..<end])
  }
}
