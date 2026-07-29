import AVFoundation
import ExpoModulesCore
import UIKit

final class SonyUsbStreamingView: ExpoView, SonyUsbStreamingControllerListener {
  private let previewLayer = AVCaptureVideoPreviewLayer()
  private let statusLabel = UILabel()
  private weak var controller: SonyUsbStreamingController?
  private var active = false

  required init(appContext: AppContext? = nil) {
    super.init(appContext: appContext)
    clipsToBounds = true
    backgroundColor = .black

    previewLayer.backgroundColor = UIColor.black.cgColor
    previewLayer.videoGravity = .resizeAspect
    previewLayer.isHidden = true
    layer.addSublayer(previewLayer)

    statusLabel.backgroundColor = UIColor(red: 0.02, green: 0.04, blue: 0.06, alpha: 0.78)
    statusLabel.textColor = .white
    statusLabel.textAlignment = .center
    statusLabel.numberOfLines = 0
    statusLabel.text = "Connect the Sony camera in USB Streaming mode"
    addSubview(statusLabel)
  }

  override func layoutSubviews() {
    super.layoutSubviews()
    previewLayer.frame = bounds
    let inset: CGFloat = 24
    let size = statusLabel.sizeThatFits(
      CGSize(width: max(0, bounds.width - inset * 2), height: bounds.height)
    )
    statusLabel.frame = CGRect(
      x: inset,
      y: (bounds.height - size.height - 28) / 2,
      width: max(0, bounds.width - inset * 2),
      height: size.height + 28
    )
    statusLabel.layer.cornerRadius = 10
    statusLabel.clipsToBounds = true
  }

  override func didMoveToWindow() {
    super.didMoveToWindow()
    if window == nil { detachController() } else { attachController() }
  }

  func setActive(_ next: Bool) {
    active = next
    attachController()
    guard let controller else { return }
    if next {
      Task {
        do {
          _ = try await controller.startStreaming()
        } catch {
          NSLog("[SonyUsbStreaming][view] start failed: %@", error.localizedDescription)
        }
      }
    } else {
      Task { _ = await controller.stopStreaming() }
    }
  }

  private func attachController() {
    guard let next = SonyUsbStreamingRegistry.controller, controller !== next else { return }
    detachController()
    controller = next
    previewLayer.session = next.captureSession
    next.addListener(self)
  }

  private func detachController() {
    guard let current = controller else { return }
    current.removeListener(self)
    previewLayer.session = nil
    controller = nil
  }

  func sonyUsbStreamingController(
    _ controller: SonyUsbStreamingController,
    didChangeState payload: [String: Any]
  ) {
    let state = payload["state"] as? String ?? "disconnected"
    let message = payload["message"] as? String ?? state.replacingOccurrences(of: "_", with: " ")
    DispatchQueue.main.async { [weak self] in
      guard let self else { return }
      previewLayer.isHidden = state != "streaming"
      statusLabel.text = message
      statusLabel.isHidden = state == "streaming"
      setNeedsLayout()
    }
  }

  deinit {
    detachController()
  }
}
