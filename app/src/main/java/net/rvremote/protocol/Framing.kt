// Ported from ha-onecontrol by Pete Hurth (https://github.com/phurth)
// https://github.com/phurth/ha-onecontrol
// MIT License, Copyright (c) 2026 Pete Hurth. Full text in LICENSE.
// Source:
//   https://github.com/phurth/ha-onecontrol/blob/0235555/custom_components/ha_onecontrol/protocol/crc8.py
//   https://github.com/phurth/ha-onecontrol/blob/0235555/custom_components/ha_onecontrol/protocol/cobs.py
package net.rvremote.protocol

/** CRC8, polynomial 0x07 reflected table, init 0x55. */
object Crc8 {
    private val table = IntArray(256).also { t ->
        // Table matches upstream: reflected CRC8 with polynomial 0x8C (0x31 reflected).
        for (i in 0 until 256) {
            var crc = i
            repeat(8) {
                crc = if (crc and 1 != 0) (crc ushr 1) xor 0x8C else crc ushr 1
            }
            t[i] = crc and 0xFF
        }
    }

    fun update(crc: Int, b: Int): Int = table[(crc xor b) and 0xFF]

    fun compute(data: ByteArray, len: Int = data.size, init: Int = 0x55): Int {
        var crc = init and 0xFF
        for (i in 0 until len) crc = update(crc, data[i].toInt())
        return crc
    }
}

/**
 * The gateway's COBS variant: frames delimited by 0x00, CRC8 appended before
 * stuffing, and runs of zeros compressed into the upper bits of the code byte.
 */
object Cobs {
    private const val FRAME = 0
    private const val MAX_DATA = 63
    private const val ZERO_STEP = 64
    private const val MAX_COMPRESSED = 192

    fun encode(data: ByteArray): ByteArray {
        val out = ArrayList<Byte>(data.size * 2 + 4)
        out.add(0)
        if (data.isEmpty()) {
            out.add(0); return out.toByteArray()
        }
        val srcLen = data.size
        val total = srcLen + 1 // + CRC byte
        var crc = 0x55
        var src = 0
        while (src < total) {
            val codeIdx = out.size
            var code = 0
            out.add(0xFF.toByte())
            while (src < total) {
                val b: Int
                if (src < srcLen) {
                    b = data[src].toInt() and 0xFF
                    if (b == FRAME) break
                    crc = Crc8.update(crc, b)
                } else {
                    b = crc and 0xFF
                    if (b == FRAME) break
                }
                src++
                out.add(b.toByte())
                code++
                if (code >= MAX_DATA) break
            }
            while (src < total) {
                val b = if (src < srcLen) data[src].toInt() and 0xFF else crc and 0xFF
                if (b != FRAME) break
                if (src < srcLen) crc = Crc8.update(crc, FRAME)
                src++
                code += ZERO_STEP
                if (code >= MAX_COMPRESSED) break
            }
            out[codeIdx] = code.toByte()
        }
        out.add(0)
        return out.toByteArray()
    }

    /** Stateful decoder fed one notification at a time. */
    class Decoder {
        private val buf = ByteArray(382)
        private var dst = 0
        private var code = 0

        private fun reset() { dst = 0; code = 0 }

        /** Returns every complete, CRC-valid frame contained in [chunk]. */
        fun feed(chunk: ByteArray): List<ByteArray> {
            val frames = ArrayList<ByteArray>()
            for (raw in chunk) {
                val b = raw.toInt() and 0xFF
                if (b == FRAME) {
                    if (code == 0 && dst > 1) {
                        val received = buf[dst - 1].toInt() and 0xFF
                        val n = dst - 1
                        if (Crc8.compute(buf, n) == received) frames.add(buf.copyOf(n))
                    }
                    reset()
                    continue
                }
                if (code <= 0) {
                    code = b
                } else {
                    code--
                    if (dst < buf.size) buf[dst++] = b.toByte()
                }
                if (code and MAX_DATA == 0) {
                    while (code > 0) {
                        if (dst < buf.size) buf[dst++] = 0
                        code -= ZERO_STEP
                    }
                }
            }
            return frames
        }
    }
}
