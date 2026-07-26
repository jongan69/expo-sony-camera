package expo.modules.sonycamera

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * SSDP discovery for Sony ScalarWebAPI cameras.
 *
 * The previous adapter probed two hardcoded addresses, `192.168.122.1` and `192.168.0.1`.
 * That works for Wi-Fi Direct, where the camera is the DHCP server and its address is
 * predictable, and fails completely on an infrastructure network where the router assigns
 * the camera an arbitrary address.
 *
 * `M-SEARCH` for `urn:schemas-sony-com:service:ScalarWebAPI:1` asks every camera on the
 * link to report its own descriptor URL, which works in both topologies. The fixed
 * addresses remain as a bounded fallback because SSDP multicast is unreliable on some
 * Android builds and blocked on some networks.
 */
internal object SonySsdpDiscovery {
  private const val MULTICAST_HOST = "239.255.255.250"
  private const val MULTICAST_PORT = 1900
  private const val SERVICE_TYPE = "urn:schemas-sony-com:service:ScalarWebAPI:1"

  /** `MX` tells devices the maximum seconds to stagger their replies over. */
  private const val MX_SECONDS = 2

  private val REQUEST = buildString {
    append("M-SEARCH * HTTP/1.1\r\n")
    append("HOST: $MULTICAST_HOST:$MULTICAST_PORT\r\n")
    append("MAN: \"ssdp:discover\"\r\n")
    append("MX: $MX_SECONDS\r\n")
    append("ST: $SERVICE_TYPE\r\n")
    append("\r\n")
  }

  /**
   * Returns descriptor URLs advertised by cameras on the active network, most responsive
   * first, de-duplicated. Never throws: discovery failure is a normal condition that must
   * fall back to the fixed-address probes rather than fail the connection attempt.
   */
  fun search(timeoutMs: Int = 3_000, trace: (String) -> Unit): List<String> {
    val found = LinkedHashSet<String>()
    var socket: DatagramSocket? = null
    try {
      socket = DatagramSocket().apply {
        broadcast = true
        soTimeout = 500
      }
      val payload = REQUEST.toByteArray(Charsets.US_ASCII)
      val target = InetSocketAddress(InetAddress.getByName(MULTICAST_HOST), MULTICAST_PORT)
      // Datagrams are lossy and unacknowledged, so the search is sent more than once.
      repeat(2) { socket.send(DatagramPacket(payload, payload.size, target)) }

      val deadline = System.currentTimeMillis() + timeoutMs
      val buffer = ByteArray(2_048)
      while (System.currentTimeMillis() < deadline) {
        val packet = DatagramPacket(buffer, buffer.size)
        try {
          socket.receive(packet)
        } catch (timeout: Throwable) {
          continue // Per-receive timeout; the outer deadline governs when to stop.
        }
        val response = String(packet.data, 0, packet.length, Charsets.US_ASCII)
        val location = headerValue(response, "LOCATION") ?: continue
        if (!response.contains("ScalarWebAPI", ignoreCase = true)) continue
        if (found.add(location)) trace("SSDP camera advertised location=$location")
      }
    } catch (error: Throwable) {
      trace("SSDP unavailable: ${error.message ?: error::class.java.simpleName}")
    } finally {
      runCatching { socket?.close() }
    }
    trace("SSDP search complete results=${found.size}")
    return found.toList()
  }

  /** Case-insensitive HTTP-style header lookup over an SSDP response. */
  internal fun headerValue(response: String, name: String): String? = response
    .lineSequence()
    .map(String::trim)
    .firstOrNull { it.startsWith("$name:", ignoreCase = true) }
    ?.substringAfter(':')
    ?.trim()
    ?.takeIf(String::isNotEmpty)
}
