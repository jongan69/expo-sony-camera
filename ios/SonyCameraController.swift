import Foundation
import ImageCaptureCore
import UIKit

protocol SonyCameraControllerListener: AnyObject {
  func sonyCameraController(_ controller: SonyCameraController, didChangeState payload: [String: Any])
  func sonyCameraController(_ controller: SonyCameraController, didAttach payload: [String: Any])
  func sonyCameraController(_ controller: SonyCameraController, didCapture payload: [String: Any])
  func sonyCameraController(_ controller: SonyCameraController, didReceiveFrame jpeg: Data)
}

final class SonyCameraController: NSObject, ICDeviceBrowserDelegate, ICCameraDeviceDelegate {
  private static let sonyVendorID = 0x054C
  private let browser = ICDeviceBrowser()
  private let workQueue = DispatchQueue(label: "expo.sony-camera")
  private let stateLock = NSLock()
  private let listeners = NSHashTable<AnyObject>.weakObjects()
  private var camera: ICCameraDevice?
  private var transport: SonyPtpTransport?
  private var state = "disconnected"
  private var message: String?
  private var liveViewRequested = false
  private var streaming = false
  private var connecting = false

  func start() {
    DispatchQueue.main.async { [weak self] in
      guard let self else { return }
      browser.delegate = self
      let rawMask = ICDeviceTypeMask.camera.rawValue | ICDeviceLocationTypeMask.local.rawValue
      if let mask = ICDeviceTypeMask(rawValue: rawMask) {
        browser.browsedDeviceTypeMask = mask
      }
      requestControlAccessAndBrowse()
    }
  }

  func refresh() {
    DispatchQueue.main.async { [weak self] in
      guard let self else { return }
      if !browser.isBrowsing { requestControlAccessAndBrowse() }
      if let camera, transport == nil { autoConnect(camera) }
    }
  }

  func addListener(_ listener: SonyCameraControllerListener) {
    stateLock.lock()
    listeners.add(listener)
    let payload = statePayloadLocked()
    stateLock.unlock()
    listener.sonyCameraController(self, didChangeState: payload)
  }

  func removeListener(_ listener: SonyCameraControllerListener) {
    stateLock.lock()
    listeners.remove(listener)
    stateLock.unlock()
  }

  func statePayload() -> [String: Any] {
    stateLock.lock()
    defer { stateLock.unlock() }
    return statePayloadLocked()
  }

  func connectBlocking() throws -> [String: Any] {
    guard let camera else {
      return updateState("disconnected", "No compatible Sony PTP camera is attached.")
    }
    return try workQueue.sync { try connectInternal(camera) }
  }

  func startLiveView() throws {
    stateLock.lock()
    liveViewRequested = true
    stateLock.unlock()
    if transport == nil { _ = try connectBlocking() }
    beginLiveView()
  }

  private func beginLiveView() {
    stateLock.lock()
    if streaming {
      stateLock.unlock()
      return
    }
    streaming = true
    stateLock.unlock()
    _ = updateState("streaming", "Sony live view")
    workQueue.async { [weak self] in self?.streamLoop() }
  }

  func stopLiveView() {
    stopLiveView(preserveRequest: false)
  }

  private func stopLiveView(preserveRequest: Bool) {
    stateLock.lock()
    if !preserveRequest { liveViewRequested = false }
    streaming = false
    let shouldBecomeReady = state == "streaming"
    stateLock.unlock()
    if shouldBecomeReady { _ = updateState("ready", "Sony camera is ready.") }
  }

  func capturePhotoBlocking() throws -> [String: Any] {
    stateLock.lock()
    let resumeStream = liveViewRequested || streaming
    streaming = false
    stateLock.unlock()

    do {
      let result = try workQueue.sync { () throws -> [String: Any] in
        guard let transport else { throw SonyPtpFailure("Sony camera is not connected.") }
        _ = updateState("capturing", "Capturing on Sony camera…")
        try transport.captureStill()
        let jpeg = try transport.awaitCapturedJpeg(timeout: 35)
        let payload = try persistCapturedJpeg(jpeg)
        _ = updateState("ready", "Photo transferred from Sony camera.")
        return payload
      }
      if resumeStream { try startLiveView() }
      return result
    } catch {
      fail(error)
      throw error
    }
  }

  func disconnectBlocking(preserveLiveViewRequest: Bool = false) -> [String: Any] {
    stopLiveView(preserveRequest: preserveLiveViewRequest)
    let activeCamera = camera
    transport = nil
    if let activeCamera, activeCamera.hasOpenSession {
      let semaphore = DispatchSemaphore(value: 0)
      activeCamera.requestCloseSession(options: nil) { _ in semaphore.signal() }
      _ = semaphore.wait(timeout: .now() + 3)
    }
    return updateState("disconnected", "Sony camera disconnected.")
  }

  func close() {
    _ = disconnectBlocking()
    DispatchQueue.main.async { [weak self] in
      self?.browser.stop()
      self?.browser.delegate = nil
    }
  }

  private func requestControlAccessAndBrowse() {
    browser.requestControlAuthorization { [weak self] status in
      DispatchQueue.main.async {
        guard let self else { return }
        if status == .authorized {
          if !self.browser.isBrowsing { self.browser.start() }
        } else {
          _ = self.updateState("permission_required", "Allow this app to control the attached camera in iOS Settings.")
        }
      }
    }
  }

  private func autoConnect(_ camera: ICCameraDevice) {
    workQueue.async { [weak self, weak camera] in
      guard let self, let camera else { return }
      do {
        _ = try connectInternal(camera)
        stateLock.lock()
        let shouldResume = liveViewRequested
        stateLock.unlock()
        if shouldResume { beginLiveView() }
      } catch { fail(error) }
    }
  }

  private func connectInternal(_ camera: ICCameraDevice) throws -> [String: Any] {
    stateLock.lock()
    if transport != nil && ["ready", "streaming", "capturing"].contains(state) {
      let payload = statePayloadLocked()
      stateLock.unlock()
      return payload
    }
    if connecting {
      let payload = statePayloadLocked()
      stateLock.unlock()
      return payload
    }
    connecting = true
    stateLock.unlock()
    defer {
      stateLock.lock()
      connecting = false
      stateLock.unlock()
    }

    _ = updateState("connecting", "Negotiating Sony Camera Control PTP 2…")
    if !camera.hasOpenSession {
      let semaphore = DispatchSemaphore(value: 0)
      var openError: Error?
      camera.requestOpenSession(options: nil) { error in
        openError = error
        semaphore.signal()
      }
      guard semaphore.wait(timeout: .now() + 8) == .success else {
        throw SonyPtpFailure("iOS timed out while opening the Sony camera.")
      }
      if let openError { throw openError }
    }
    guard camera.capabilities.contains(ICDeviceCapability.cameraDeviceCanAcceptPTPCommands.rawValue) else {
      throw SonyPtpFailure("The attached Sony camera does not allow PTP commands on iOS.")
    }
    let next = SonyPtpTransport(camera: camera)
    try next.authenticate()
    next.prepareStillCapture()
    transport = next
    return updateState("ready", "Sony camera is ready.")
  }

  private func streamLoop() {
    do {
      guard let transport else { throw SonyPtpFailure("Sony camera is not connected.") }
      try transport.prepareLiveView()
      var frameCount = 0
      while isStreaming() {
        let started = Date()
        if frameCount > 0, frameCount % 10 == 0, let captured = try transport.pollCapturedJpeg() {
          _ = try persistCapturedJpeg(captured)
        }
        if let jpeg = try transport.getLiveViewJpeg(), !jpeg.isEmpty {
          frameCount += 1
          notify { $0.sonyCameraController(self, didReceiveFrame: jpeg) }
        }
        let remainder = 0.1 - Date().timeIntervalSince(started)
        if remainder > 0 { Thread.sleep(forTimeInterval: remainder) }
      }
      if currentState() == "streaming" { _ = updateState("ready", "Sony camera is ready.") }
    } catch let error as SonyPtpFailure where error.retryable && !isStreaming() {
      // Stopping the view can interrupt an in-flight PTP request.
    } catch {
      if isStreaming() { fail(error) }
    }
  }

  private func isStreaming() -> Bool {
    stateLock.lock()
    defer { stateLock.unlock() }
    return streaming
  }

  private func currentState() -> String {
    stateLock.lock()
    defer { stateLock.unlock() }
    return state
  }

  private func fail(_ error: Error) {
    stateLock.lock()
    streaming = false
    stateLock.unlock()
    transport = nil
    _ = updateState("error", error.localizedDescription)
  }

  private func persistCapturedJpeg(_ jpeg: Data) throws -> [String: Any] {
    guard let image = UIImage(data: jpeg) else { throw SonyPtpFailure("Sony returned an unreadable image.") }
    let fileName = "sony-camera-\(Int(Date().timeIntervalSince1970 * 1000)).jpg"
    let url = FileManager.default.temporaryDirectory.appendingPathComponent(fileName)
    try jpeg.write(to: url, options: .atomic)
    let payload: [String: Any] = [
      "uri": url.absoluteString,
      "width": Int(image.size.width * image.scale),
      "height": Int(image.size.height * image.scale),
      "fileName": fileName,
      "mimeType": "image/jpeg",
    ]
    notify { $0.sonyCameraController(self, didCapture: payload) }
    return payload
  }

  @discardableResult
  private func updateState(_ next: String, _ nextMessage: String?) -> [String: Any] {
    stateLock.lock()
    state = next
    message = nextMessage
    let payload = statePayloadLocked()
    stateLock.unlock()
    notify { $0.sonyCameraController(self, didChangeState: payload) }
    return payload
  }

  private func statePayloadLocked() -> [String: Any] {
    var payload: [String: Any] = ["state": state]
    if let message { payload["message"] = message }
    if let camera { payload["device"] = devicePayload(camera) }
    return payload
  }

  private func devicePayload(_ camera: ICCameraDevice) -> [String: Any] {
    [
      "vendorId": Int(camera.usbVendorID),
      "productId": Int(camera.usbProductID),
      "deviceName": camera.uuidString ?? camera.name ?? "Sony USB Camera",
      "manufacturerName": "Sony",
      "productName": camera.name ?? "Sony PTP Camera",
      "model": camera.name ?? "Sony PTP Camera",
      "protocol": "sony_camera_control_ptp2",
      "transport": "usb",
      "connectionMode": "usb",
      "certification": "capability_detected",
      "capabilities": [
        "protocols": ["sony_camera_control_ptp2"], "transports": ["usb"], "connectionModes": ["usb"],
        "categories": ["connection", "live_view", "still_capture", "health"],
        "features": ["liveView": true, "stillCapture": true, "imageTransfer": true, "halfPress": true, "properties": false, "movieRecording": false, "mediaBrowser": false, "events": false],
      ],
    ]
  }

  private func notify(_ body: @escaping (SonyCameraControllerListener) -> Void) {
    stateLock.lock()
    let current = listeners.allObjects.compactMap { $0 as? SonyCameraControllerListener }
    stateLock.unlock()
    DispatchQueue.main.async { current.forEach(body) }
  }

  func deviceBrowser(_ browser: ICDeviceBrowser, didAdd device: ICDevice, moreComing: Bool) {
    guard let camera = device as? ICCameraDevice, Int(camera.usbVendorID) == Self.sonyVendorID else { return }
    camera.delegate = self
    self.camera = camera
    let payload = payloadForAttachment(camera)
    notify { $0.sonyCameraController(self, didAttach: payload) }
    autoConnect(camera)
  }

  func deviceBrowser(_ browser: ICDeviceBrowser, didRemove device: ICDevice, moreGoing: Bool) {
    guard device === camera else { return }
    camera = nil
    transport = nil
    stateLock.lock()
    streaming = false
    stateLock.unlock()
    _ = updateState("disconnected", "Sony camera disconnected.")
  }

  private func payloadForAttachment(_ camera: ICCameraDevice) -> [String: Any] {
    [
      "state": "disconnected",
      "message": "Sony camera attached. Preparing USB connection.",
      "device": devicePayload(camera),
    ]
  }

  func device(_ device: ICDevice, didCloseSessionWithError error: Error?) {}
  func didRemove(_ device: ICDevice) {}
  func device(_ device: ICDevice, didOpenSessionWithError error: Error?) {}
  func cameraDevice(_ camera: ICCameraDevice, didAdd items: [ICCameraItem]) {}
  func cameraDevice(_ camera: ICCameraDevice, didRemove items: [ICCameraItem]) {}
  func cameraDevice(_ camera: ICCameraDevice, didReceiveThumbnail thumbnail: CGImage?, for item: ICCameraItem, error: Error?) {}
  func cameraDevice(_ camera: ICCameraDevice, didReceiveMetadata metadata: [AnyHashable: Any]?, for item: ICCameraItem, error: Error?) {}
  func cameraDevice(_ camera: ICCameraDevice, didRenameItems items: [ICCameraItem]) {}
  func cameraDeviceDidChangeCapability(_ camera: ICCameraDevice) {}
  func cameraDevice(_ camera: ICCameraDevice, didReceivePTPEvent eventData: Data) {}
  func deviceDidBecomeReady(withCompleteContentCatalog device: ICCameraDevice) {}
  func cameraDeviceDidRemoveAccessRestriction(_ device: ICDevice) {}
  func cameraDeviceDidEnableAccessRestriction(_ device: ICDevice) {}
}
