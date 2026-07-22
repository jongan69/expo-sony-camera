import ExpoModulesCore
import UIKit

final class SonyCameraView: ExpoView, SonyCameraControllerListener {
  private let imageView = UIImageView()
  private let statusLabel = UILabel()
  private weak var controller: SonyCameraController?
  private var active = false

  required init(appContext: AppContext? = nil) {
    super.init(appContext: appContext)
    clipsToBounds = true
    backgroundColor = .black

    imageView.backgroundColor = .black
    imageView.contentMode = .scaleAspectFill
    addSubview(imageView)

    statusLabel.backgroundColor = UIColor(red: 0.02, green: 0.04, blue: 0.06, alpha: 0.72)
    statusLabel.textColor = .white
    statusLabel.textAlignment = .center
    statusLabel.numberOfLines = 0
    statusLabel.text = "Connect Sony camera"
    addSubview(statusLabel)
  }

  override func layoutSubviews() {
    super.layoutSubviews()
    imageView.frame = bounds
    let horizontalInset: CGFloat = 24
    let labelSize = statusLabel.sizeThatFits(CGSize(width: max(0, bounds.width - horizontalInset * 2), height: bounds.height))
    statusLabel.frame = CGRect(
      x: horizontalInset,
      y: (bounds.height - labelSize.height - 28) / 2,
      width: max(0, bounds.width - horizontalInset * 2),
      height: labelSize.height + 28
    )
    statusLabel.layer.cornerRadius = 10
    statusLabel.clipsToBounds = true
  }

  override func didMoveToWindow() {
    super.didMoveToWindow()
    if window == nil {
      detachController()
    } else {
      attachController()
    }
  }

  func setActive(_ next: Bool) {
    active = next
    if next {
      attachController()
      try? controller?.startLiveView()
    } else {
      controller?.stopLiveView()
    }
  }

  private func attachController() {
    guard let next = SonyCameraRegistry.controller, controller !== next else { return }
    detachController()
    controller = next
    next.addListener(self)
    if active { try? next.startLiveView() }
  }

  private func detachController() {
    guard let current = controller else { return }
    if active { current.stopLiveView() }
    current.removeListener(self)
    controller = nil
  }

  func sonyCameraController(_ controller: SonyCameraController, didReceiveFrame jpeg: Data) {
    guard let image = UIImage(data: jpeg) else { return }
    DispatchQueue.main.async { [weak self] in
      self?.imageView.image = image
      self?.statusLabel.isHidden = true
    }
  }

  func sonyCameraController(_ controller: SonyCameraController, didChangeState payload: [String: Any]) {
    let state = payload["state"] as? String ?? "disconnected"
    let message = payload["message"] as? String ?? state.replacingOccurrences(of: "_", with: " ")
    DispatchQueue.main.async { [weak self] in
      guard let self else { return }
      statusLabel.text = message
      statusLabel.isHidden = state == "streaming" && imageView.image != nil
      setNeedsLayout()
    }
  }

  func sonyCameraController(_ controller: SonyCameraController, didAttach payload: [String: Any]) {
    sonyCameraController(controller, didChangeState: payload)
  }

  func sonyCameraController(_ controller: SonyCameraController, didCapture payload: [String: Any]) {}

  deinit {
    detachController()
  }
}
