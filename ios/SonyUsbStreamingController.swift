import AVFoundation
import CoreImage
import Foundation
import UIKit

protocol SonyUsbStreamingControllerListener: AnyObject {
  func sonyUsbStreamingController(
    _ controller: SonyUsbStreamingController,
    didChangeState payload: [String: Any]
  )
}

struct SonyUsbStreamingFailure: LocalizedError {
  let message: String

  init(_ message: String) {
    self.message = message
  }

  var errorDescription: String? { message }
}

final class SonyUsbStreamingController: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate {
  let captureSession = AVCaptureSession()

  private let sessionQueue = DispatchQueue(label: "expo.sony-camera.uvc.session")
  private let frameQueue = DispatchQueue(label: "expo.sony-camera.uvc.frames")
  private let stateLock = NSLock()
  private let listeners = NSHashTable<AnyObject>.weakObjects()
  private let imageContext = CIContext(options: [.cacheIntermediates: false])
  private let videoOutput = AVCaptureVideoDataOutput()

  private var observers: [NSObjectProtocol] = []
  private var externalDevice: AVCaptureDevice?
  private var state = "disconnected"
  private var message: String?
  private var deviceName: String?
  private var activeFormat: [String: Any]?
  private var availableFormats: [[String: Any]] = []
  private var diagnostics: [String] = []
  private var lastFrameJpeg: Data?
  private var lastFrameSize: CGSize = .zero
  private var frameCount = 0

  private static let maxDiagnostics = 2_000
  private static let timestampFormatter: DateFormatter = {
    let formatter = DateFormatter()
    formatter.dateFormat = "HH:mm:ss.SSS"
    formatter.locale = Locale(identifier: "en_US_POSIX")
    return formatter
  }()

  func startDiscovery() {
    let center = NotificationCenter.default
    observers.append(
      center.addObserver(
        forName: AVCaptureDevice.wasConnectedNotification,
        object: nil,
        queue: nil
      ) { [weak self] notification in
        guard let device = notification.object as? AVCaptureDevice,
              device.hasMediaType(.video) else { return }
        self?.trace("external video device connected name=\(device.localizedName)")
        self?.refresh()
      }
    )
    observers.append(
      center.addObserver(
        forName: AVCaptureDevice.wasDisconnectedNotification,
        object: nil,
        queue: nil
      ) { [weak self] notification in
        guard let device = notification.object as? AVCaptureDevice,
              device.hasMediaType(.video) else { return }
        self?.trace("external video device disconnected name=\(device.localizedName)")
        self?.refresh()
      }
    )
    trace("USB streaming discovery started")
    refresh()
  }

  func refresh() {
    sessionQueue.async { [weak self] in
      self?.refreshOnSessionQueue()
    }
  }

  func addListener(_ listener: SonyUsbStreamingControllerListener) {
    stateLock.lock()
    listeners.add(listener)
    let payload = statePayloadLocked()
    stateLock.unlock()
    listener.sonyUsbStreamingController(self, didChangeState: payload)
  }

  func removeListener(_ listener: SonyUsbStreamingControllerListener) {
    stateLock.lock()
    listeners.remove(listener)
    stateLock.unlock()
  }

  func statePayload() -> [String: Any] {
    stateLock.lock()
    defer { stateLock.unlock() }
    return statePayloadLocked()
  }

  func diagnosticsSnapshot() -> [String: Any] {
    stateLock.lock()
    let entries = diagnostics
    stateLock.unlock()
    return ["entries": entries, "protocol": "uvc", "transport": "usb_uvc"]
  }

  func clearDiagnostics() {
    stateLock.lock()
    diagnostics.removeAll()
    stateLock.unlock()
  }

  func startStreaming() async throws -> [String: Any] {
    let authorized: Bool
    switch AVCaptureDevice.authorizationStatus(for: .video) {
    case .authorized:
      authorized = true
    case .notDetermined:
      authorized = await AVCaptureDevice.requestAccess(for: .video)
    default:
      authorized = false
    }

    guard authorized else {
      return updateState(
        "permission_required",
        "Camera permission is required to receive Sony USB Streaming video."
      )
    }

    return try await withCheckedThrowingContinuation { continuation in
      sessionQueue.async { [weak self] in
        guard let self else {
          continuation.resume(throwing: SonyUsbStreamingFailure("USB streaming controller closed."))
          return
        }
        do {
          continuation.resume(returning: try configureAndStartOnSessionQueue())
        } catch {
          let message = error.localizedDescription
          _ = updateState("error", message)
          trace("USB streaming start failed error=\(message)")
          continuation.resume(throwing: error)
        }
      }
    }
  }

  func stopStreaming() async -> [String: Any] {
    await withCheckedContinuation { continuation in
      sessionQueue.async { [weak self] in
        guard let self else {
          continuation.resume(returning: [
            "state": "disconnected",
            "message": "USB streaming controller closed.",
          ])
          return
        }
        if captureSession.isRunning {
          captureSession.stopRunning()
          trace("AVCaptureSession stopped")
        }
        let nextState = externalDevice == nil ? "disconnected" : "ready"
        let nextMessage = externalDevice == nil
          ? "No external USB/UVC camera is attached."
          : "Sony USB/UVC camera is ready."
        continuation.resume(returning: updateState(nextState, nextMessage))
      }
    }
  }

  func capturePreviewFrame() throws -> [String: Any] {
    stateLock.lock()
    let jpeg = lastFrameJpeg
    let size = lastFrameSize
    stateLock.unlock()

    guard let jpeg else {
      throw SonyUsbStreamingFailure(
        "No USB streaming frame is available yet. Start streaming and wait for video."
      )
    }

    let fileName = "sony-usb-stream-\(UUID().uuidString).jpg"
    let directory = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
      .appendingPathComponent("expo-sony-camera", isDirectory: true)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    let url = directory.appendingPathComponent(fileName)
    try jpeg.write(to: url, options: .atomic)
    trace("USB streaming preview frame persisted bytes=\(jpeg.count)")
    return [
      "uri": url.absoluteString,
      "width": Int(size.width),
      "height": Int(size.height),
      "fileName": fileName,
      "mimeType": "image/jpeg",
    ]
  }

  func close() {
    observers.forEach(NotificationCenter.default.removeObserver)
    observers.removeAll()
    sessionQueue.async { [weak self] in
      guard let self else { return }
      if captureSession.isRunning { captureSession.stopRunning() }
      videoOutput.setSampleBufferDelegate(nil, queue: nil)
    }
  }

  func captureOutput(
    _ output: AVCaptureOutput,
    didOutput sampleBuffer: CMSampleBuffer,
    from connection: AVCaptureConnection
  ) {
    frameCount += 1
    guard frameCount == 1 || frameCount % 6 == 0,
          let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }

    let image = CIImage(cvPixelBuffer: pixelBuffer)
    guard let cgImage = imageContext.createCGImage(image, from: image.extent),
          let jpeg = UIImage(cgImage: cgImage).jpegData(compressionQuality: 0.86) else { return }

    stateLock.lock()
    lastFrameJpeg = jpeg
    lastFrameSize = CGSize(width: image.extent.width, height: image.extent.height)
    stateLock.unlock()

    if frameCount == 1 {
      trace(
        "first USB streaming frame received width=\(Int(image.extent.width))"
          + " height=\(Int(image.extent.height)) bytes=\(jpeg.count)"
      )
    }
  }

  private func refreshOnSessionQueue() {
    guard #available(iOS 17.0, *) else {
      externalDevice = nil
      _ = updateState("unsupported", "External USB cameras require iOS 17 or newer.")
      return
    }

    let nextDevice = discoverExternalCamera()
    if nextDevice?.uniqueID == externalDevice?.uniqueID { return }

    if captureSession.isRunning {
      captureSession.stopRunning()
      trace("AVCaptureSession stopped after external device change")
    }
    externalDevice = nextDevice
    updateDeviceMetadata(nextDevice)

    guard let nextDevice else {
      _ = updateState(
        "disconnected",
        "No external USB/UVC camera detected. Set the a6700 USB mode to USB Streaming."
      )
      return
    }
    trace("external USB/UVC camera discovered name=\(nextDevice.localizedName)")
    _ = updateState("ready", "External camera detected: \(nextDevice.localizedName)")
  }

  @available(iOS 17.0, *)
  private func discoverExternalCamera() -> AVCaptureDevice? {
    let devices = AVCaptureDevice.DiscoverySession(
      deviceTypes: [
        .external,
        .builtInWideAngleCamera,
        .builtInUltraWideCamera,
        .builtInTelephotoCamera,
        .builtInDualCamera,
        .builtInDualWideCamera,
        .builtInTripleCamera,
        .builtInTrueDepthCamera,
      ],
      mediaType: .video,
      position: .unspecified
    ).devices

    let summary = devices.map {
      "\($0.localizedName){type=\($0.deviceType.rawValue),position=\($0.position.rawValue)}"
    }.joined(separator: ", ")
    trace("AVFoundation video devices count=\(devices.count) devices=[\(summary)]")

    return devices.first(where: {
      $0.localizedName.localizedCaseInsensitiveContains("Sony")
        || $0.localizedName.localizedCaseInsensitiveContains("ILCE")
    }) ?? devices.first(where: { $0.deviceType == .external })
  }

  private func configureAndStartOnSessionQueue() throws -> [String: Any] {
    if captureSession.isRunning {
      return updateState("streaming", "Sony USB/UVC video is streaming.")
    }

    if externalDevice == nil {
      if #available(iOS 17.0, *) { externalDevice = discoverExternalCamera() }
      updateDeviceMetadata(externalDevice)
    }
    guard let device = externalDevice else {
      throw SonyUsbStreamingFailure(
        "No external USB/UVC camera detected. Set the a6700 USB mode to USB Streaming, reconnect the cable, and try again."
      )
    }

    let input = try AVCaptureDeviceInput(device: device)
    captureSession.beginConfiguration()
    captureSession.inputs.forEach(captureSession.removeInput)
    captureSession.outputs.forEach(captureSession.removeOutput)
    if captureSession.canSetSessionPreset(.high) {
      captureSession.sessionPreset = .high
    } else {
      captureSession.sessionPreset = .inputPriority
    }

    guard captureSession.canAddInput(input) else {
      captureSession.commitConfiguration()
      throw SonyUsbStreamingFailure("iOS could not add the external Sony camera as a video input.")
    }
    captureSession.addInput(input)

    videoOutput.alwaysDiscardsLateVideoFrames = true
    videoOutput.videoSettings = [
      kCVPixelBufferPixelFormatTypeKey as String: Int(kCVPixelFormatType_32BGRA),
    ]
    videoOutput.setSampleBufferDelegate(self, queue: frameQueue)
    guard captureSession.canAddOutput(videoOutput) else {
      captureSession.removeInput(input)
      captureSession.commitConfiguration()
      throw SonyUsbStreamingFailure("iOS could not create a video output for the Sony USB stream.")
    }
    captureSession.addOutput(videoOutput)
    if let connection = videoOutput.connection(with: .video), connection.isVideoMirroringSupported {
      connection.isVideoMirrored = false
    }
    captureSession.commitConfiguration()

    frameCount = 0
    captureSession.startRunning()
    updateDeviceMetadata(device)
    trace("AVCaptureSession started name=\(device.localizedName)")
    return updateState("streaming", "Sony USB/UVC video is streaming.")
  }

  private func updateDeviceMetadata(_ device: AVCaptureDevice?) {
    let name = device?.localizedName
    let formats = device.map(Self.describeFormats) ?? []
    var selected: [String: Any]?
    if let device {
      let dimensions = CMVideoFormatDescriptionGetDimensions(device.activeFormat.formatDescription)
      let maxFrameRate = device.activeFormat.videoSupportedFrameRateRanges
        .map(\.maxFrameRate)
        .max() ?? 0
      selected = [
        "width": Int(dimensions.width),
        "height": Int(dimensions.height),
        "maxFrameRate": maxFrameRate,
      ]
    }
    stateLock.lock()
    deviceName = name
    availableFormats = formats
    activeFormat = selected
    stateLock.unlock()
  }

  private static func describeFormats(_ device: AVCaptureDevice) -> [[String: Any]] {
    var seen = Set<String>()
    var result: [[String: Any]] = []
    for format in device.formats {
      let dimensions = CMVideoFormatDescriptionGetDimensions(format.formatDescription)
      let maxFrameRate = format.videoSupportedFrameRateRanges.map(\.maxFrameRate).max() ?? 0
      let key = "\(dimensions.width)x\(dimensions.height)@\(Int(maxFrameRate))"
      guard seen.insert(key).inserted else { continue }
      result.append([
        "width": Int(dimensions.width),
        "height": Int(dimensions.height),
        "maxFrameRate": maxFrameRate,
      ])
    }
    return result.sorted {
      (($0["width"] as? Int ?? 0) * ($0["height"] as? Int ?? 0))
        > (($1["width"] as? Int ?? 0) * ($1["height"] as? Int ?? 0))
    }
  }

  @discardableResult
  private func updateState(_ nextState: String, _ nextMessage: String) -> [String: Any] {
    stateLock.lock()
    state = nextState
    message = nextMessage
    let payload = statePayloadLocked()
    let currentListeners = listeners.allObjects.compactMap { $0 as? SonyUsbStreamingControllerListener }
    stateLock.unlock()
    trace("state=\(nextState) message=\(nextMessage)")
    currentListeners.forEach {
      $0.sonyUsbStreamingController(self, didChangeState: payload)
    }
    return payload
  }

  private func statePayloadLocked() -> [String: Any] {
    var payload: [String: Any] = [
      "state": state,
      "capabilities": [
        "liveView": true,
        "previewFrameCapture": true,
        "audio": true,
        "remoteShutter": false,
        "cameraProperties": false,
      ],
      "diagnostics": Array(diagnostics.suffix(200)),
    ]
    if let message { payload["message"] = message }
    if let deviceName {
      var device: [String: Any] = [
        "name": deviceName,
        "protocol": "uvc",
        "transport": "usb_uvc",
        "formats": availableFormats,
      ]
      if let activeFormat { device["activeFormat"] = activeFormat }
      payload["device"] = device
    }
    return payload
  }

  private func trace(_ event: String) {
    let timestamp = Self.timestampFormatter.string(from: Date())
    stateLock.lock()
    diagnostics.append("\(timestamp) \(event)")
    if diagnostics.count > Self.maxDiagnostics {
      diagnostics.removeFirst(diagnostics.count - Self.maxDiagnostics)
    }
    stateLock.unlock()
    NSLog("[SonyUsbStreaming] %@", event)
  }
}
