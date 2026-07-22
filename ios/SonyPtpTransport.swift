import Foundation
import ImageCaptureCore

struct SonyPtpFailure: LocalizedError {
  let message: String
  let retryable: Bool

  init(_ message: String, retryable: Bool = false) {
    self.message = message
    self.retryable = retryable
  }

  var errorDescription: String? { message }
}

private struct SonyPtpResult {
  let data: Data?
  let responseCode: UInt16
}

final class SonyPtpTransport {
  private static let responseOK: UInt16 = 0x2001
  private static let responseInvalidHandle: UInt16 = 0x2009
  private static let responseAccessDenied: UInt16 = 0x200F
  private static let responseDeviceBusy: UInt16 = 0x2019
  private static let capturedImageHandle: UInt32 = 0xFFFFC001
  private static let liveViewHandle: UInt32 = 0xFFFFC002
  private static let focusFoundProperty: UInt16 = 0xD213
  private static let objectInMemoryProperty: UInt16 = 0xD215
  private static let liveViewStatusProperty: UInt32 = 0xD221

  private let camera: ICCameraDevice
  private var transactionID: UInt32 = 0
  private var capturedObjectConsumed = false

  init(camera: ICCameraDevice) {
    self.camera = camera
  }

  func authenticate() throws {
    _ = try execute(operation: 0x9201, params: [1, 0, 0], expectsData: true)
    _ = try execute(operation: 0x9201, params: [2, 0, 0], expectsData: true)
    var protocolData: Data?
    for _ in 0..<6 {
      let info = try execute(operation: 0x9202, params: [0x00C8], expectsData: true, allowBusy: true)
      if let data = info.data, !data.isEmpty {
        protocolData = data
        break
      }
      Thread.sleep(forTimeInterval: 0.15)
    }
    guard let protocolData, protocolData.count >= 2, protocolData.uint16LE(at: 0) >= 0x00C8 else {
      throw SonyPtpFailure("The Sony camera reported an unsupported PTP protocol version.")
    }
    _ = try execute(operation: 0x9201, params: [3, 0, 0], expectsData: true)
  }

  func prepareStillCapture() {
    _ = try? execute(operation: 0x9205, params: [0xD25A], outgoingData: Data([1]))
    _ = try? execute(operation: 0x9205, params: [0x5013], outgoingData: Data.littleEndian32(1))
  }

  func prepareLiveView() throws {
    var lastStatus: Data?
    for _ in 0..<50 {
      let statusResult = try execute(
        operation: 0x9209,
        expectsData: true,
        allowBusy: true
      )
      lastStatus = statusResult.data.flatMap {
        Self.sonyScalarPropertyValue(in: $0, propertyCode: UInt16(Self.liveViewStatusProperty))
      }
      if let status = lastStatus?.first, status != 0 {
        do {
          _ = try execute(operation: 0x1008, params: [Self.liveViewHandle], expectsData: true)
          return
        } catch let error as SonyPtpFailure where error.retryable {
          // D221 can become ready immediately before the live-view object is exposed.
        }
      }
      Thread.sleep(forTimeInterval: 0.1)
    }
    let status = lastStatus?.map { String(format: "%02X", $0) }.joined(separator: " ") ?? "missing"
    throw SonyPtpFailure("Sony live view did not become ready (D221=\(status)). Set USB Connection to PC Remote.")
  }

  func getLiveViewJpeg() throws -> Data? {
    let info = try execute(
      operation: 0x1008,
      params: [Self.liveViewHandle],
      expectsData: true,
      acceptedResponses: [Self.responseOK, Self.responseInvalidHandle, Self.responseDeviceBusy]
    )
    if info.responseCode != Self.responseOK { return nil }
    let result = try execute(
      operation: 0x1009,
      params: [Self.liveViewHandle],
      expectsData: true,
      acceptedResponses: [Self.responseOK, Self.responseAccessDenied]
    )
    if result.responseCode == Self.responseAccessDenied { return nil }
    guard let data = result.data else { return nil }
    return data.sonyJPEGData
  }

  func captureStill() throws {
    capturedObjectConsumed = false
    try setButton(0xD2C1, down: true)
    Thread.sleep(forTimeInterval: 0.09)
    try setButton(0xD2C2, down: true)
    defer {
      _ = try? setButton(0xD2C2, down: false)
      _ = try? setButton(0xD2C1, down: false)
    }
    let focusDeadline = Date().addingTimeInterval(1)
    while Date() < focusDeadline {
      let properties = try execute(operation: 0x9209, expectsData: true, allowBusy: true)
      let focus = properties.data
        .flatMap { Self.sonyScalarPropertyValue(in: $0, propertyCode: Self.focusFoundProperty) }
        .flatMap(\.unsignedScalarValue)
      if focus == 2 || focus == 3 { break }
      Thread.sleep(forTimeInterval: 0.05)
    }
  }

  func awaitCapturedJpeg(timeout: TimeInterval) throws -> Data {
    let deadline = Date().addingTimeInterval(timeout)
    while Date() < deadline {
      do {
        if let jpeg = try pollCapturedJpeg() { return jpeg }
      } catch let error as SonyPtpFailure where error.retryable {
        // The object handle becomes readable shortly after the exposure completes.
      }
      Thread.sleep(forTimeInterval: 0.1)
    }
      throw SonyPtpFailure("The camera fired, but no JPEG reached the app. Check PC Remote save settings and JPEG output.")
  }

  func pollCapturedJpeg() throws -> Data? {
    let properties = try execute(operation: 0x9209, expectsData: true, allowBusy: true)
    let objectInMemory = properties.data
      .flatMap { Self.sonyScalarPropertyValue(in: $0, propertyCode: Self.objectInMemoryProperty) }
      .flatMap(\.unsignedScalarValue)
    guard let objectInMemory, objectInMemory >= 0x8000 else {
      capturedObjectConsumed = false
      return nil
    }
    if capturedObjectConsumed { return nil }

    let info = try execute(
      operation: 0x1008,
      params: [Self.capturedImageHandle],
      expectsData: true,
      acceptedResponses: [Self.responseOK, Self.responseInvalidHandle, Self.responseDeviceBusy]
    )
    if info.responseCode != Self.responseOK { return nil }
    let result = try execute(
      operation: 0x1009,
      params: [Self.capturedImageHandle],
      expectsData: true,
      acceptedResponses: [Self.responseOK, Self.responseInvalidHandle, Self.responseDeviceBusy]
    )
    if result.responseCode != Self.responseOK { return nil }
    guard let jpeg = result.data?.sonyJPEGData else {
      throw SonyPtpFailure(
        "Sony transferred the captured object, but it did not contain a readable JPEG (bytes=\(result.data?.count ?? 0))."
      )
    }
    capturedObjectConsumed = true
    return jpeg
  }

  private func setButton(_ code: UInt32, down: Bool) throws {
    _ = try execute(operation: 0x9207, params: [code], outgoingData: Data.littleEndian16(down ? 2 : 1))
  }

  private func execute(
    operation: UInt16,
    params: [UInt32] = [],
    outgoingData: Data? = nil,
    expectsData: Bool = false,
    allowBusy: Bool = false,
    acceptedResponses: Set<UInt16> = [SonyPtpTransport.responseOK]
  ) throws -> SonyPtpResult {
    transactionID &+= 1
    let command = Self.container(type: 1, code: operation, transactionID: transactionID, payload: Data.parameters(params))
    let semaphore = DispatchSemaphore(value: 0)
    var callbackData: Data?
    var callbackResponse: Data?
    var callbackError: Error?

    camera.requestSendPTPCommand(command, outData: outgoingData) { responseData, ptpResponseData, error in
      callbackData = responseData
      callbackResponse = ptpResponseData
      callbackError = error
      semaphore.signal()
    }
    guard semaphore.wait(timeout: .now() + 5) == .success else {
      throw SonyPtpFailure("Sony PTP command timed out.", retryable: true)
    }
    if let callbackError { throw SonyPtpFailure(callbackError.localizedDescription, retryable: true) }

    let responseCode = Self.responseCode(from: callbackResponse)
      ?? Self.responseCode(from: callbackData)
      ?? Self.responseOK
    let allowed = acceptedResponses.contains(responseCode) || (allowBusy && responseCode == Self.responseDeviceBusy)
    guard allowed else {
      let retryable = [Self.responseDeviceBusy, Self.responseAccessDenied, Self.responseInvalidHandle].contains(responseCode)
      throw SonyPtpFailure(String(format: "Sony PTP 0x%04X failed with 0x%04X.", operation, responseCode), retryable: retryable)
    }

    let incoming = Self.dataPayload(from: callbackData) ?? Self.dataPayload(from: callbackResponse)
    return SonyPtpResult(data: expectsData ? incoming : nil, responseCode: responseCode)
  }

  private static func container(type: UInt16, code: UInt16, transactionID: UInt32, payload: Data) -> Data {
    var result = Data()
    result.appendLittleEndian(UInt32(12 + payload.count))
    result.appendLittleEndian(type)
    result.appendLittleEndian(code)
    result.appendLittleEndian(transactionID)
    result.append(payload)
    return result
  }

  private static func responseCode(from data: Data?) -> UInt16? {
    guard let data, data.count >= 8, data.uint16LE(at: 4) == 3 else { return nil }
    return data.uint16LE(at: 6)
  }

  private static func dataPayload(from data: Data?) -> Data? {
    guard let data, !data.isEmpty else { return nil }
    if data.count >= 12 {
      let declaredLength = Int(data.uint32LE(at: 0))
      let containerType = data.uint16LE(at: 4)
      if containerType == 2, declaredLength >= 12, declaredLength <= data.count {
        return data.subdata(in: 12..<declaredLength)
      }
      if containerType == 3 { return nil }
    }
    return data
  }

  private static func sonyScalarPropertyValue(in data: Data, propertyCode: UInt16) -> Data? {
    guard data.count >= 16 else { return nil }
    for offset in 8...(data.count - 8) where data.uint16LE(at: offset) == propertyCode {
      let valueSize: Int
      switch data.uint16LE(at: offset + 2) {
      case 0x0001, 0x0002: valueSize = 1
      case 0x0003, 0x0004: valueSize = 2
      case 0x0005, 0x0006: valueSize = 4
      case 0x0007, 0x0008: valueSize = 8
      default: continue
      }
      let currentValueOffset = offset + 6 + valueSize
      guard currentValueOffset + valueSize <= data.count else { return nil }
      return data.subdata(in: currentValueOffset..<(currentValueOffset + valueSize))
    }
    return nil
  }
}

extension Data {
  fileprivate static func parameters(_ values: [UInt32]) -> Data {
    var result = Data()
    values.forEach { result.appendLittleEndian($0) }
    return result
  }

  fileprivate static func littleEndian16(_ value: UInt16) -> Data {
    var result = Data()
    result.appendLittleEndian(value)
    return result
  }

  fileprivate static func littleEndian32(_ value: UInt32) -> Data {
    var result = Data()
    result.appendLittleEndian(value)
    return result
  }

  fileprivate mutating func appendLittleEndian<T: FixedWidthInteger>(_ value: T) {
    var littleEndian = value.littleEndian
    Swift.withUnsafeBytes(of: &littleEndian) { append(contentsOf: $0) }
  }

  fileprivate func uint16LE(at offset: Int) -> UInt16 {
    guard offset >= 0, offset + 2 <= count else { return 0 }
    return withUnsafeBytes { (bytes: UnsafeRawBufferPointer) in
      UInt16(bytes[offset]) | (UInt16(bytes[offset + 1]) << 8)
    }
  }

  fileprivate func uint32LE(at offset: Int) -> UInt32 {
    guard offset >= 0, offset + 4 <= count else { return 0 }
    return withUnsafeBytes { (bytes: UnsafeRawBufferPointer) in
      let byte0 = UInt32(bytes[offset])
      let byte1 = UInt32(bytes[offset + 1]) << 8
      let byte2 = UInt32(bytes[offset + 2]) << 16
      let byte3 = UInt32(bytes[offset + 3]) << 24
      return byte0 | byte1 | byte2 | byte3
    }
  }

  fileprivate var sonyJPEGData: Data? {
    guard count >= 4 else { return nil }
    let suggestedOffset = Int(uint32LE(at: 0))
    let searchStart = suggestedOffset >= 0 && suggestedOffset < count - 1 ? suggestedOffset : 0
    var jpegStart = jpegStartIndex(from: searchStart)
    if jpegStart == nil, searchStart != 0 { jpegStart = jpegStartIndex(from: 0) }
    guard let jpegStart else { return nil }
    var jpegEnd = count
    if count >= jpegStart + 4 {
      for index in stride(from: count - 2, through: jpegStart + 2, by: -1) {
        if self[index] == 0xFF && self[index + 1] == 0xD9 {
          jpegEnd = index + 2
          break
        }
      }
    }
    return subdata(in: jpegStart..<jpegEnd)
  }

  fileprivate var unsignedScalarValue: UInt64? {
    switch count {
    case 1: return UInt64(self[0])
    case 2: return UInt64(uint16LE(at: 0))
    case 4: return UInt64(uint32LE(at: 0))
    default: return nil
    }
  }

  private func jpegStartIndex(from searchStart: Int) -> Int? {
    guard searchStart >= 0, searchStart < count - 1 else { return nil }
    for index in searchStart..<(count - 1) where self[index] == 0xFF && self[index + 1] == 0xD8 {
      return index
    }
    return nil
  }
}
