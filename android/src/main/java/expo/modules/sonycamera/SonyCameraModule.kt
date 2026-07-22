package expo.modules.sonycamera

import android.content.Intent
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

class SonyCameraModule : Module(), SonyCameraController.Listener {
  private lateinit var controller: SonyCameraController

  override fun definition() = ModuleDefinition {
    Name("SonyCamera")

    Events("onStateChanged", "onDeviceAttached", "onPhotoCaptured")

    OnCreate {
      val context = requireNotNull(appContext.reactContext)
      controller = SonyCameraController(context.applicationContext)
      controller.addListener(this@SonyCameraModule)
      SonyCameraRegistry.controller = controller
      appContext.currentActivity?.intent?.let(controller::handleIntent)
      controller.refreshAttachedDevice(autoConnect = true)
    }

    OnNewIntent { intent -> controller.handleIntent(intent) }
    OnActivityEntersForeground { controller.refreshAttachedDevice(autoConnect = true) }

    OnDestroy {
      controller.removeListener(this@SonyCameraModule)
      controller.close()
      if (SonyCameraRegistry.controller === controller) SonyCameraRegistry.controller = null
    }

    Function("getState") { controller.statePayload() }
    AsyncFunction("connect") { controller.connectBlocking() }
    AsyncFunction("disconnect") { controller.disconnectBlocking() }
    AsyncFunction("startLiveView") { controller.startLiveView(); controller.statePayload() }
    AsyncFunction("stopLiveView") { controller.stopLiveView(); controller.statePayload() }
    AsyncFunction("capturePhoto") { controller.capturePhotoBlocking() }
    AsyncFunction("focusAt") { x: Double, y: Double, viewWidth: Double, viewHeight: Double ->
      controller.focusAtBlocking(x, y, viewWidth, viewHeight)
    }

    View(SonyCameraView::class) {
      Prop("active") { view: SonyCameraView, active: Boolean -> view.setActive(active) }
      OnViewDestroys { view: SonyCameraView -> view.cleanup() }
    }
  }

  override fun onStateChanged(payload: Map<String, Any?>) {
    sendEvent("onStateChanged", payload)
  }

  override fun onDeviceAttached(payload: Map<String, Any?>) {
    sendEvent("onDeviceAttached", payload)
  }

  override fun onPhotoCaptured(payload: Map<String, Any?>) {
    sendEvent("onPhotoCaptured", payload)
  }

  override fun onFrame(jpeg: ByteArray) = Unit
}

internal object SonyCameraRegistry {
  @Volatile var controller: SonyCameraController? = null
}
