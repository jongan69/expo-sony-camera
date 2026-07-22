package expo.modules.sonycamera

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SonyPtpCodecTest {
  @Test
  fun buildsLittleEndianCommandContainer() {
    val bytes = SonyPtpCodec.buildContainer(1, 0x1009, 7, byteArrayOf(2, 1, 0, 0))
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    assertEquals(16, buffer.int)
    assertEquals(1, buffer.short.toInt())
    assertEquals(0x1009, buffer.short.toInt())
    assertEquals(7, buffer.int)
    assertArrayEquals(byteArrayOf(2, 1, 0, 0), bytes.copyOfRange(12, 16))
  }

  @Test
  fun extractsJpegFromSonyLiveViewDataset() {
    val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 0xFF.toByte(), 0xD9.toByte())
    val dataset = ByteBuffer.allocate(12 + jpeg.size).order(ByteOrder.LITTLE_ENDIAN)
      .putInt(12)
      .putInt(jpeg.size)
      .putInt(0)
      .put(jpeg)
      .array()
    assertArrayEquals(jpeg, SonyPtpCodec.parseLiveViewJpeg(dataset))
  }

  @Test
  fun rejectsOutOfBoundsLiveViewDataset() {
    val invalid = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(20).putInt(500).array()
    assertNull(SonyPtpCodec.parseLiveViewJpeg(invalid))
  }
}
