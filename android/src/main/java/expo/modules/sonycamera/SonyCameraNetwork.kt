package expo.modules.sonycamera

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Opens HTTP connections on the Wi-Fi network the Sony camera is on.
 *
 * A camera's Wi-Fi Direct network has no internet access. Android keeps cellular as the
 * process default in that situation, so a plain `URL.openConnection()` is routed away
 * from the camera and either times out or reaches an unrelated host on the internet.
 *
 * Requesting a network with `TRANSPORT_WIFI` and `NET_CAPABILITY_INTERNET` explicitly
 * removed gives us a [Network] handle for the camera link. Opening connections through
 * that handle scopes the routing to this transport instead of calling
 * `bindProcessToNetwork`, which would redirect every socket in the host app.
 */
internal class SonyCameraNetwork(context: Context, private val trace: (String) -> Unit) {
  private val connectivityManager =
    context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
  private val network = AtomicReference<Network?>(null)
  @Volatile private var callback: ConnectivityManager.NetworkCallback? = null

  /**
   * Requests the camera's Wi-Fi network and waits briefly for it to become available.
   *
   * Returns true when a network handle was acquired. A false result is not fatal: the
   * phone may be on an infrastructure network that does have internet, where the default
   * route already reaches the camera.
   */
  fun acquire(timeoutMs: Long = 5_000): Boolean {
    if (network.get() != null) return true
    val latch = CountDownLatch(1)
    val request = NetworkRequest.Builder()
      .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
      .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
      .build()
    val next = object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(available: Network) {
        network.set(available)
        trace("Wi-Fi network acquired for Sony camera routing")
        latch.countDown()
      }

      override fun onLost(lost: Network) {
        if (network.compareAndSet(lost, null)) trace("Sony camera Wi-Fi network lost")
      }
    }
    return try {
      connectivityManager.requestNetwork(request, next)
      callback = next
      val acquired = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
      if (!acquired) trace("No camera-scoped Wi-Fi network within ${timeoutMs}ms; using the default route")
      acquired
    } catch (error: Throwable) {
      trace("Wi-Fi network request unavailable: ${error.message ?: error::class.java.simpleName}")
      release()
      false
    }
  }

  /** Opens [url] on the camera network when one was acquired, else on the default route. */
  fun open(url: String): HttpURLConnection {
    val target = URL(url)
    val active = network.get()
    val connection = active?.openConnection(target) ?: target.openConnection()
    return connection as HttpURLConnection
  }

  fun release() {
    callback?.let { current ->
      runCatching { connectivityManager.unregisterNetworkCallback(current) }
    }
    callback = null
    network.set(null)
  }
}
