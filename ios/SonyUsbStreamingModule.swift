import ExpoModulesCore

public final class SonyUsbStreamingModule: Module, SonyUsbStreamingControllerListener {
  private var controller: SonyUsbStreamingController!

  public func definition() -> ModuleDefinition {
    Name("SonyUsbStreaming")

    Events("onStateChanged")

    OnCreate {
      controller = SonyUsbStreamingController()
      controller.addListener(self)
      SonyUsbStreamingRegistry.controller = controller
      controller.startDiscovery()
    }

    OnAppEntersForeground {
      controller.refresh()
    }

    OnDestroy {
      controller.removeListener(self)
      controller.close()
      if SonyUsbStreamingRegistry.controller === controller {
        SonyUsbStreamingRegistry.controller = nil
      }
    }

    Function("getState") {
      controller.statePayload()
    }

    Function("getDiagnostics") {
      controller.diagnosticsSnapshot()
    }

    Function("clearDiagnostics") {
      controller.clearDiagnostics()
    }

    AsyncFunction("startStreaming") { () async throws -> [String: Any] in
      try await controller.startStreaming()
    }

    AsyncFunction("stopStreaming") { () async -> [String: Any] in
      await controller.stopStreaming()
    }

    AsyncFunction("capturePreviewFrame") {
      try controller.capturePreviewFrame()
    }

    View(SonyUsbStreamingView.self) {
      Prop("active") { (view: SonyUsbStreamingView, active: Bool) in
        view.setActive(active)
      }
    }
  }

  func sonyUsbStreamingController(
    _ controller: SonyUsbStreamingController,
    didChangeState payload: [String: Any]
  ) {
    let state = payload["state"] as? String ?? "unknown"
    let message = payload["message"] as? String ?? ""
    NSLog("[SonyUsbStreaming][state] %@: %@", state, message)
    sendEvent("onStateChanged", payload)
  }
}

enum SonyUsbStreamingRegistry {
  static weak var controller: SonyUsbStreamingController?
}
