package expo.modules.sonycamera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.core.app.ActivityCompat
import com.herohan.uvcapp.CameraException
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.serenegiant.usb.Size as UvcSize
import com.serenegiant.usb.UVCCamera
import com.serenegiant.utils.UVCUtils
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/** Receives real YUV buffers from Sony USB Streaming through Android's UVC provider. */
internal class SonyUsbStreamingController(private val context: Context) {
  interface Listener { fun onStateChanged(payload: Map<String, Any?>) }

  companion object {
    private const val TAG = "SonyUsbStreaming"
    private const val CAMERA_PERMISSION_REQUEST = 6700
    private const val METRIC_INTERVAL_MS = 2_000L
  }

  private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
  private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
  private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
  private val listeners = CopyOnWriteArraySet<Listener>()
  private val diagnostics = java.util.ArrayDeque<String>()
  private val thread = HandlerThread("SonyUsbStreaming").apply { start() }
  private val handler = Handler(thread.looper)
  private val requested = AtomicBoolean(false)
  private var previewSurface: Surface? = null
  private var camera: CameraDevice? = null
  private var session: CameraCaptureSession? = null
  private var imageReader: ImageReader? = null
  private var cameraId: String? = null
  private var directDevice: UsbDevice? = null
  private var directHelper: CameraHelper? = null
  private var directSurfaceAdded = false
  private var directOpening = false
  private var state = "disconnected"
  private var message = "Set the Sony camera to USB Streaming and connect it by USB-C."
  private var width = 0
  private var height = 0
  private var frameCount = 0L
  private var measuredFps = 0.0
  private var metricStartedAt = 0L
  private var metricFrameCount = 0L
  private var lastFrameAt = 0L
  private var maximumFrameGapMs = 0L
  private var sourceFpsRange: Range<Int>? = null

  private val availabilityCallback = object : CameraManager.AvailabilityCallback() {
    override fun onCameraAvailable(id: String) {
      if (!isExternal(id)) return
      trace("external camera available id=$id")
      cameraId = id
      updateState("ready", "Sony USB video device is available.")
      if (requested.get()) openIfPossible()
    }

    override fun onCameraUnavailable(id: String) {
      if (id == cameraId) trace("external camera unavailable id=$id opened=${camera != null}")
    }
  }

  private val directStateCallback = object : ICameraHelper.StateCallback {
    override fun onAttach(device: UsbDevice) {
      if (!isSonyUvc(device)) return
      trace("direct UVC attached ${describeUsb(device)}")
      directDevice = device
      updateState("ready", "Sony UVC device detected through direct USB access.")
      if (requested.get()) directHelper?.selectDevice(device)
    }

    override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) {
      if (!isSonyUvc(device)) return
      trace("direct USB device opened first=$isFirstOpen ${describeUsb(device)}")
      directOpening = true
      directHelper?.openCamera()
    }

    override fun onCameraOpen(device: UsbDevice) {
      if (!isSonyUvc(device)) return
      directOpening = false
      val sizes = directHelper?.supportedSizeList.orEmpty()
      val selected = sizes.firstOrNull { it.width == 1920 && it.height == 1080 && it.fps >= 30 }
        ?: sizes.filter { it.width <= 1920 && it.height <= 1080 && it.fps >= 30 }
          .maxByOrNull { it.width.toLong() * it.height }
        ?: sizes.maxByOrNull { it.width.toLong() * it.height }
      trace("direct UVC camera opened formats=${sizes.joinToString()} selected=$selected")
      selected?.let {
        width = it.width
        height = it.height
        sourceFpsRange = Range(it.fps, it.fps)
        directHelper?.setPreviewSize(it)
      }
      attachDirectSurface()
      directHelper?.setFrameCallback({ buffer: ByteBuffer -> recordDirectFrame(buffer) }, UVCCamera.PIXEL_FORMAT_RAW)
      directHelper?.startPreview()
      updateState("opening", "Sony UVC stream negotiated. Waiting for the first frame...")
    }

    override fun onCameraClose(device: UsbDevice) {
      directOpening = false
      directSurfaceAdded = false
      trace("direct UVC camera closed")
    }

    override fun onDeviceClose(device: UsbDevice) {
      trace("direct USB device closed")
    }

    override fun onDetach(device: UsbDevice) {
      if (device.deviceId != directDevice?.deviceId) return
      trace("direct UVC detached ${describeUsb(device)}")
      directDevice = null
      directOpening = false
      directSurfaceAdded = false
      updateState("disconnected", "Sony USB camera disconnected.")
    }

    override fun onCancel(device: UsbDevice) {
      directOpening = false
      updateState("permission_required", "USB permission was denied for the Sony camera.")
    }

    override fun onError(device: UsbDevice, error: CameraException) {
      directOpening = false
      trace("direct UVC error code=${error.code} message=${error.message}")
      updateState("error", "Sony UVC error ${error.code}: ${error.message ?: "unknown"}")
    }
  }

  init {
    UVCUtils.init(context)
    directHelper = CameraHelper().also { it.setStateCallback(directStateCallback) }
    cameraManager.registerAvailabilityCallback(availabilityCallback, handler)
    handler.post(::refresh)
  }

  fun addListener(listener: Listener) { listeners.add(listener); listener.onStateChanged(payload()) }
  fun removeListener(listener: Listener) { listeners.remove(listener) }

  fun setPreviewSurface(surface: Surface?) {
    val previous = previewSurface
    previewSurface = surface
    trace("preview surface ${if (surface == null) "removed" else "available"}")
    if (surface == null) {
      if (directSurfaceAdded && previous != null) runCatching { directHelper?.removeSurface(previous) }
      directSurfaceAdded = false
      closeSession()
    } else {
      if (directHelper?.isCameraOpened == true) attachDirectSurface()
      if (requested.get()) openIfPossible()
    }
  }

  fun start(activity: android.app.Activity?): Map<String, Any?> {
    requested.set(true)
    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
      activity?.let { ActivityCompat.requestPermissions(it, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST) }
      updateState("permission_required", "Allow camera access, then tap Start USB Stream again.")
      return payload()
    }
    handler.post { refresh(); openIfPossible() }
    return payload()
  }

  fun stop(): Map<String, Any?> {
    requested.set(false)
    handler.post {
      closeCamera()
      updateState(if (cameraId == null) "disconnected" else "ready", "Sony USB stream stopped.")
    }
    return payload()
  }

  fun refresh() {
    val external = runCatching { cameraManager.cameraIdList.firstOrNull(::isExternal) }
      .onFailure { trace("camera discovery failed: ${it.message}") }.getOrNull()
    cameraId = external
    directDevice = usbManager.deviceList.values.firstOrNull(::isSonyUvc)
    if (external == null) {
      if (directDevice != null) {
        if (state !in setOf("opening", "streaming")) {
          updateState("ready", "Sony UVC device detected. Direct USB backend is available.")
        }
      } else if (camera == null) {
        updateState("disconnected", "No Sony USB Streaming device is attached.")
      }
    } else if (camera == null && state !in setOf("opening", "streaming")) {
      updateState("ready", "External USB camera detected.")
    }
  }

  fun payload(): Map<String, Any?> = linkedMapOf(
    "state" to state,
    "message" to message,
    "deviceName" to (directDevice?.productName ?: cameraId?.let { "External Camera $it" }),
    "width" to width,
    "height" to height,
    "fps" to measuredFps,
    "frameCount" to frameCount,
    "maximumFrameGapMs" to maximumFrameGapMs,
    "pixelFormat" to if (frameCount > 0) (if (directDevice != null) "MJPEG" else "YUV_420_888") else null,
    "sourceFpsMin" to sourceFpsRange?.lower,
    "sourceFpsMax" to sourceFpsRange?.upper,
    "audioAvailable" to usbAudioInputs().isNotEmpty(),
    "audioDevices" to usbAudioInputs(),
    "streamReady" to (frameCount > 0 && measuredFps >= 24.0),
    "transport" to if (directDevice != null) "android_libuvc_direct" else "android_camera2_uvc",
  )

  fun diagnosticsSnapshot(): Map<String, Any?> = synchronized(diagnostics) {
    mapOf("entries" to diagnostics.toList())
  }

  private fun openIfPossible() {
    if (!requested.get() || camera != null) return
    directDevice?.let { device ->
      if (!directOpening && directHelper?.isCameraOpened != true) {
        directOpening = true
        updateState("opening", "Claiming Sony UVC interfaces directly...")
        directHelper?.selectDevice(device)
      } else if (directHelper?.isCameraOpened == true) {
        attachDirectSurface()
      }
      return
    }
    val id = cameraId ?: run {
      updateState("waiting", "Waiting for Android to publish the a6700 UVC camera.")
      return
    }
    if (previewSurface == null) {
      updateState("waiting", "USB camera detected. Waiting for the native preview surface.")
      return
    }
    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
      updateState("permission_required", "Camera permission is required for USB video.")
      return
    }
    updateState("opening", "Opening Sony USB video stream...")
    try {
      cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
        override fun onOpened(device: CameraDevice) {
          camera = device
          trace("external camera opened id=${device.id}")
          try { configureSession(device) } catch (error: Throwable) {
            trace("configure failed: ${error.message}")
            updateState("error", "Could not configure Sony USB video: ${error.message ?: error::class.java.simpleName}")
          }
        }

        override fun onDisconnected(device: CameraDevice) {
          device.close(); camera = null; closeSession()
          updateState("disconnected", "Sony USB camera disconnected.")
        }

        override fun onError(device: CameraDevice, error: Int) {
          trace("external camera error id=${device.id} code=$error")
          device.close(); camera = null; closeSession()
          updateState("error", "Android external-camera error $error.")
        }
      }, handler)
    } catch (error: Throwable) {
      trace("open failed: ${error.message}")
      updateState("error", "Could not open Sony USB video: ${error.message ?: error::class.java.simpleName}")
    }
  }

  private fun configureSession(device: CameraDevice) {
    val characteristics = cameraManager.getCameraCharacteristics(device.id)
    val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
      ?: throw IllegalStateException("External camera has no stream configuration map")
    val sizes = map.getOutputSizes(ImageFormat.YUV_420_888).orEmpty()
    val selected = selectSize(sizes)
    width = selected.width; height = selected.height
    sourceFpsRange = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
      ?.filter { it.upper >= 30 }?.maxByOrNull { it.lower }
    trace("config formats=${sizes.joinToString()} selected=${width}x$height fpsRange=$sourceFpsRange")

    imageReader?.close()
    imageReader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 4).also { reader ->
      reader.setOnImageAvailableListener({ source ->
        val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
        try {
          val now = SystemClock.elapsedRealtime()
          frameCount += 1
          if (metricStartedAt == 0L) {
            metricStartedAt = now; metricFrameCount = frameCount
            trace("first stream-ready YUV frame timestamp=${image.timestamp} planes=${image.planes.size}")
            updateState("streaming", "Receiving stream-ready Sony YUV frames.")
          }
          if (lastFrameAt > 0L) maximumFrameGapMs = maxOf(maximumFrameGapMs, now - lastFrameAt)
          lastFrameAt = now
          if (now - metricStartedAt >= METRIC_INTERVAL_MS) {
            measuredFps = (frameCount - metricFrameCount) * 1_000.0 / (now - metricStartedAt)
            trace("metrics frames=$frameCount fps=${String.format(Locale.US, "%.2f", measuredFps)} size=${width}x$height maxGapMs=$maximumFrameGapMs audio=${usbAudioInputs().isNotEmpty()}")
            metricStartedAt = now; metricFrameCount = frameCount
            listeners.forEach { it.onStateChanged(payload()) }
          }
        } finally { image.close() }
      }, handler)
    }

    val outputs = listOfNotNull(previewSurface, imageReader?.surface).distinct()
    device.createCaptureSession(outputs, object : CameraCaptureSession.StateCallback() {
      override fun onConfigured(next: CameraCaptureSession) {
        if (camera !== device) { next.close(); return }
        session = next
        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
          outputs.forEach(::addTarget)
          sourceFpsRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
          set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        }.build()
        next.setRepeatingRequest(request, null, handler)
        trace("capture session running outputs=${outputs.size}")
      }

      override fun onConfigureFailed(failed: CameraCaptureSession) {
        updateState("error", "Android could not configure the Sony USB video outputs.")
      }
    }, handler)
  }

  private fun selectSize(sizes: Array<out Size>): Size {
    if (sizes.isEmpty()) throw IllegalStateException("External camera exposes no YUV output sizes")
    return sizes.firstOrNull { it.width == 1920 && it.height == 1080 }
      ?: sizes.filter { it.width <= 1920 && it.height <= 1080 }.maxByOrNull { it.width.toLong() * it.height }
      ?: sizes.minBy { it.width.toLong() * it.height }
  }

  private fun isExternal(id: String): Boolean = runCatching {
    cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_EXTERNAL
  }.getOrDefault(false)

  private fun isSonyUvc(device: UsbDevice): Boolean = device.vendorId == 0x054C &&
    (0 until device.interfaceCount).any { device.getInterface(it).interfaceClass == 14 }

  private fun describeUsb(device: UsbDevice): String = buildString {
    append(String.format(Locale.US, "%04x:%04x", device.vendorId, device.productId))
    append(" interfaces=")
    append((0 until device.interfaceCount).joinToString { index ->
      val intf = device.getInterface(index)
      "${intf.interfaceClass}/${intf.interfaceSubclass}/${intf.interfaceProtocol}"
    })
  }

  private fun attachDirectSurface() {
    val surface = previewSurface ?: return
    if (directSurfaceAdded || directHelper?.isCameraOpened != true) return
    directHelper?.addSurface(surface, false)
    directSurfaceAdded = true
    trace("direct UVC preview surface attached")
  }

  @Synchronized
  private fun recordDirectFrame(buffer: ByteBuffer) {
    val now = SystemClock.elapsedRealtime()
    frameCount += 1
    if (metricStartedAt == 0L) {
      metricStartedAt = now
      metricFrameCount = frameCount
      val first = buffer.get(0).toInt() and 0xff
      val second = buffer.get(1).toInt() and 0xff
      trace("first stream-ready MJPEG frame received bytes=${buffer.capacity()} signature=${String.format(Locale.US, "%02x%02x", first, second)}")
      updateState("streaming", "Receiving stream-ready Sony MJPEG frames.")
    }
    if (lastFrameAt > 0L) maximumFrameGapMs = maxOf(maximumFrameGapMs, now - lastFrameAt)
    lastFrameAt = now
    if (now - metricStartedAt >= METRIC_INTERVAL_MS) {
      measuredFps = (frameCount - metricFrameCount) * 1_000.0 / (now - metricStartedAt)
      trace("direct metrics frames=$frameCount fps=${String.format(Locale.US, "%.2f", measuredFps)} size=${width}x$height maxGapMs=$maximumFrameGapMs audio=${usbAudioInputs().isNotEmpty()}")
      metricStartedAt = now
      metricFrameCount = frameCount
      listeners.forEach { it.onStateChanged(payload()) }
    }
  }

  private fun usbAudioInputs(): List<String> = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
    .filter { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
    .map { it.productName.toString() }

  private fun closeSession() {
    runCatching { session?.stopRepeating() }; session?.close(); session = null
    imageReader?.close(); imageReader = null
  }

  private fun closeCamera() {
    runCatching { directHelper?.setFrameCallback(null, 0) }
    runCatching { directHelper?.stopPreview() }
    runCatching { directHelper?.closeCamera() }
    directSurfaceAdded = false
    directOpening = false
    closeSession(); camera?.close(); camera = null
    frameCount = 0; measuredFps = 0.0; metricStartedAt = 0; lastFrameAt = 0; maximumFrameGapMs = 0
  }

  fun close() {
    requested.set(false)
    cameraManager.unregisterAvailabilityCallback(availabilityCallback)
    directHelper?.setStateCallback(null)
    directHelper?.release()
    directHelper = null
    handler.post { closeCamera(); thread.quitSafely() }
  }

  private fun updateState(next: String, nextMessage: String) {
    state = next; message = nextMessage
    trace("state=$next message=$nextMessage")
    val current = payload(); listeners.forEach { it.onStateChanged(current) }
  }

  private fun trace(value: String) {
    Log.i(TAG, value)
    synchronized(diagnostics) {
      diagnostics.addLast("${SystemClock.elapsedRealtime()} $value")
      while (diagnostics.size > 500) diagnostics.removeFirst()
    }
  }
}
