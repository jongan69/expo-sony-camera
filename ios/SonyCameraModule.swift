import ExpoModulesCore

public final class SonyCameraModule: Module, SonyCameraControllerListener {
  private var controller: SonyCameraController!

  public func definition() -> ModuleDefinition {
    Name("SonyCamera")

    Events("onStateChanged", "onDeviceAttached", "onPhotoCaptured")

    OnCreate {
      controller = SonyCameraController()
      controller.addListener(self)
      SonyCameraRegistry.controller = controller
      controller.start()
    }

    OnAppEntersForeground {
      controller.refresh()
    }

    OnDestroy {
      controller.removeListener(self)
      controller.close()
      if SonyCameraRegistry.controller === controller {
        SonyCameraRegistry.controller = nil
      }
    }

    Function("getState") {
      controller.statePayload()
    }

    // The options argument is declared in TypeScript and must be accepted here so the
    // documented signature matches runtime. Android supports transport/protocol preference
    // hints and iOS enforces supported wired behavior.
    AsyncFunction("connect") { (options: [String: Any]?) in
      try controller.connectBlocking(options: options ?? [:])
    }

    AsyncFunction("disconnect") {
      controller.disconnectBlocking()
    }

    Function("getDiagnostics") {
      controller.diagnosticsSnapshot()
    }

    Function("clearDiagnostics") {
      controller.clearDiagnostics()
    }

    AsyncFunction("startLiveView") {
      try controller.startLiveView()
      return controller.statePayload()
    }

    AsyncFunction("stopLiveView") {
      controller.stopLiveView()
      return controller.statePayload()
    }

    AsyncFunction("capturePhoto") {
      try controller.capturePhotoBlocking()
    }

    AsyncFunction("capturePreviewFrame") {
      try controller.capturePreviewFrameBlocking()
    }

    View(SonyCameraView.self) {
      Prop("active") { (view: SonyCameraView, active: Bool) in
        view.setActive(active)
      }
    }
  }

  func sonyCameraController(_ controller: SonyCameraController, didChangeState payload: [String: Any]) {
    let state = payload["state"] as? String ?? "unknown"
    let message = payload["message"] as? String ?? ""
    NSLog("[SonyCamera][state] %@: %@", state, message)
    sendEvent("onStateChanged", payload)
  }

  func sonyCameraController(_ controller: SonyCameraController, didAttach payload: [String: Any]) {
    let state = payload["state"] as? String ?? "unknown"
    let message = payload["message"] as? String ?? ""
    NSLog("[SonyCamera][attach] %@: %@", state, message)
    sendEvent("onDeviceAttached", payload)
  }

  func sonyCameraController(_ controller: SonyCameraController, didCapture payload: [String: Any]) {
    NSLog("[SonyCamera][capture] completed")
    sendEvent("onPhotoCaptured", payload)
  }

  func sonyCameraController(_ controller: SonyCameraController, didReceiveFrame jpeg: Data) {}
}

enum SonyCameraRegistry {
  static weak var controller: SonyCameraController?
}
