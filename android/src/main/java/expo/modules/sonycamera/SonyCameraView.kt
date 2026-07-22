package expo.modules.sonycamera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.views.ExpoView

class SonyCameraView(context: Context, appContext: AppContext) : ExpoView(context, appContext), SonyCameraController.Listener {
  private val mainHandler = Handler(Looper.getMainLooper())
  private val imageView = ImageView(context).apply {
    layoutParams = FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    scaleType = ImageView.ScaleType.CENTER_CROP
    setBackgroundColor(Color.BLACK)
  }
  private val statusView = TextView(context).apply {
    layoutParams = FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER)
    setPadding(24, 14, 24, 14)
    setTextColor(Color.WHITE)
    setBackgroundColor(Color.argb(165, 5, 10, 14))
    text = "Connect Sony camera"
  }
  private var active = false
  private var bitmap: Bitmap? = null
  private var controller: SonyCameraController? = null

  init {
    addView(imageView)
    addView(statusView)
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    attachController()
  }

  override fun onDetachedFromWindow() {
    detachController()
    super.onDetachedFromWindow()
  }

  fun setActive(next: Boolean) {
    if (active == next) return
    active = next
    if (next) {
      attachController()
      controller?.startLiveView()
    } else {
      controller?.stopLiveView()
    }
  }

  fun cleanup() {
    active = false
    detachController()
    imageView.setImageDrawable(null)
    bitmap?.recycle()
    bitmap = null
  }

  private fun attachController() {
    val next = SonyCameraRegistry.controller ?: return
    if (controller === next) return
    detachController()
    controller = next
    next.addListener(this)
    if (active) next.startLiveView()
  }

  private fun detachController() {
    val current = controller ?: return
    if (active) current.stopLiveView()
    current.removeListener(this)
    controller = null
  }

  override fun onFrame(jpeg: ByteArray) {
    val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return
    mainHandler.post {
      val previous = bitmap
      bitmap = decoded
      imageView.setImageBitmap(decoded)
      statusView.visibility = GONE
      if (previous != null && previous !== decoded && !previous.isRecycled) previous.recycle()
    }
  }

  override fun onStateChanged(payload: Map<String, Any?>) {
    val state = payload["state"] as? String ?: return
    val message = payload["message"] as? String
    mainHandler.post {
      statusView.text = message ?: state.replace('_', ' ')
      statusView.visibility = if (state == "streaming" && bitmap != null) GONE else VISIBLE
    }
  }

  override fun onDeviceAttached(payload: Map<String, Any?>) = onStateChanged(payload)
  override fun onPhotoCaptured(payload: Map<String, Any?>) = Unit
}
