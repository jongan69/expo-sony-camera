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

    AsyncFunction("connect") {
      try controller.connectBlocking()
    }

    AsyncFunction("disconnect") {
      controller.disconnectBlocking()
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

    View(SonyCameraView.self) {
      Prop("active") { (view: SonyCameraView, active: Bool) in
        view.setActive(active)
      }
    }
  }

  func sonyCameraController(_ controller: SonyCameraController, didChangeState payload: [String: Any]) {
    sendEvent("onStateChanged", payload)
  }

  func sonyCameraController(_ controller: SonyCameraController, didAttach payload: [String: Any]) {
    sendEvent("onDeviceAttached", payload)
  }

  func sonyCameraController(_ controller: SonyCameraController, didCapture payload: [String: Any]) {
    sendEvent("onPhotoCaptured", payload)
  }

  func sonyCameraController(_ controller: SonyCameraController, didReceiveFrame jpeg: Data) {}
}

enum SonyCameraRegistry {
  static weak var controller: SonyCameraController?
}
