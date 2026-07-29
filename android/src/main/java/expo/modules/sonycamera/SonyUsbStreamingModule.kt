package expo.modules.sonycamera

import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

class SonyUsbStreamingModule : Module(), SonyUsbStreamingController.Listener {
  private lateinit var controller: SonyUsbStreamingController

  override fun definition() = ModuleDefinition {
    Name("SonyUsbStreaming")
    Events("onStateChanged")
    OnCreate {
      controller = SonyUsbStreamingController(requireNotNull(appContext.reactContext).applicationContext)
      controller.addListener(this@SonyUsbStreamingModule)
      SonyUsbStreamingRegistry.controller = controller
    }
    OnActivityEntersForeground { controller.refresh() }
    OnDestroy {
      controller.removeListener(this@SonyUsbStreamingModule); controller.close()
      if (SonyUsbStreamingRegistry.controller === controller) SonyUsbStreamingRegistry.controller = null
    }
    Function("getState") { controller.payload() }
    Function("getDiagnostics") { controller.diagnosticsSnapshot() }
    AsyncFunction("startStreaming") {
      controller.start(appContext.currentActivity)
      controller.payload()
    }
    AsyncFunction("stopStreaming") {
      controller.stop()
      controller.payload()
    }
    View(SonyUsbStreamingView::class) {
      Prop("active") { view: SonyUsbStreamingView, active: Boolean -> view.setActive(active) }
      OnViewDestroys { view: SonyUsbStreamingView -> view.cleanup() }
    }
  }

  override fun onStateChanged(payload: Map<String, Any?>) { sendEvent("onStateChanged", payload) }
}

internal object SonyUsbStreamingRegistry {
  @Volatile var controller: SonyUsbStreamingController? = null
}
