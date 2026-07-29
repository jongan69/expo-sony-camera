package expo.modules.sonycamera

import android.content.Context
import android.graphics.SurfaceTexture
import android.graphics.Color
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import android.widget.TextView
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.views.ExpoView
import java.util.Locale

class SonyUsbStreamingView(context: Context, appContext: AppContext) :
  ExpoView(context, appContext), TextureView.SurfaceTextureListener, SonyUsbStreamingController.Listener {
  private val textureView = TextureView(context).apply {
    isOpaque = true
    surfaceTextureListener = this@SonyUsbStreamingView
  }
  private var previewSurface: Surface? = null
  private val statusView = TextView(context).apply {
    setTextColor(Color.WHITE); setBackgroundColor(Color.argb(175, 5, 10, 14)); setPadding(24, 14, 24, 14)
    gravity = Gravity.CENTER; text = "Connect a6700 in USB Streaming mode"
  }
  private var active = false
  private var controller: SonyUsbStreamingController? = null

  init {
    setBackgroundColor(Color.BLACK)
    addView(textureView, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    addView(statusView, FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
  }

  override fun onAttachedToWindow() { super.onAttachedToWindow(); attachController() }
  override fun onDetachedFromWindow() { detachController(); super.onDetachedFromWindow() }

  fun setActive(next: Boolean) {
    val shouldStart = next && !active
    active = next
    attachController()
    if (shouldStart) controller?.start(appContext.currentActivity)
  }

  fun cleanup() { active = false; detachController() }

  override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
    previewSurface?.release()
    previewSurface = Surface(texture)
    controller?.setPreviewSurface(previewSurface)
    if (active) controller?.start(appContext.currentActivity)
  }
  override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
  override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
  override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
    controller?.setPreviewSurface(null)
    previewSurface?.release()
    previewSurface = null
    return true
  }

  override fun onStateChanged(payload: Map<String, Any?>) {
    post {
      val state = payload["state"] as? String ?: "disconnected"
      val fps = payload["fps"] as? Double ?: 0.0
      val width = payload["width"] as? Int ?: 0
      val height = payload["height"] as? Int ?: 0
      statusView.text = if (state == "streaming") String.format(Locale.US, "%dx%d  %.1f fps  YUV", width, height, fps)
        else payload["message"] as? String ?: state
      statusView.visibility = if (state == "streaming" && fps > 0) GONE else VISIBLE
    }
  }

  private fun attachController() {
    val next = SonyUsbStreamingRegistry.controller ?: return
    if (controller === next) return
    detachController(); controller = next; next.addListener(this)
    previewSurface?.takeIf { it.isValid }?.let(next::setPreviewSurface)
  }

  private fun detachController() {
    val current = controller ?: return
    current.setPreviewSurface(null); current.removeListener(this); controller = null
  }
}
