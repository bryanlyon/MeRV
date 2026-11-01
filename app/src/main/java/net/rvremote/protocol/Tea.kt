// Ported from ha-onecontrol (MIT License, (c) 2026 Pete Hurth). See LICENSE.
package net.rvremote.protocol

import java.util.Base64

/**
 * Gateway unlock math (32-round TEA variant).
 *
 * The round constants are kept masked and are recovered at runtime, matching
 * how the upstream project chose to ship them.
 */
object Tea {
    private const val MASK = 0xFFFFFFFFL
    private const val DELTA = 0x9E3779B9L
    private const val ROUNDS = 32

    private val consts: LongArray = run {
        val blob = Base64.getDecoder().decode("hL2RibW7hpiz8qi0lKGPk+NW0CVG0un9drnhRQ==")
        val mask = 0xC7D2E1F0L
        LongArray(7) { i ->
            val o = i * 4
            val word = ((blob[o].toLong() and 0xFF) shl 24) or
                ((blob[o + 1].toLong() and 0xFF) shl 16) or
                ((blob[o + 2].toLong() and 0xFF) shl 8) or
                (blob[o + 3].toLong() and 0xFF)
            word xor mask
        }
    }

    private val c1 get() = consts[0]
    private val c2 get() = consts[1]
    private val c3 get() = consts[2]
    private val c4 get() = consts[3]
    private val step1Cipher get() = consts[4]

    fun encrypt(cipher: Long, seed: Long): Long {
        var c = cipher and MASK
        var s = seed and MASK
        var delta = DELTA
        repeat(ROUNDS) {
            s = (s + ((((c shl 4) + c1) and MASK) xor ((c + delta) and MASK) xor (((c ushr 5) + c2) and MASK))) and MASK
            c = (c + ((((s shl 4) + c3) and MASK) xor ((s + delta) and MASK) xor (((s ushr 5) + c4) and MASK))) and MASK
            delta = (delta + DELTA) and MASK
        }
        return s
    }

    /** 4-byte challenge read from UNLOCK_STATUS -> 4-byte key, both big-endian. */
    fun step1Key(challenge: ByteArray): ByteArray {
        require(challenge.size == 4) { "challenge must be 4 bytes" }
        val seed = ((challenge[0].toLong() and 0xFF) shl 24) or
            ((challenge[1].toLong() and 0xFF) shl 16) or
            ((challenge[2].toLong() and 0xFF) shl 8) or
            (challenge[3].toLong() and 0xFF)
        val k = encrypt(step1Cipher, seed)
        return byteArrayOf(
            (k ushr 24).toByte(), (k ushr 16).toByte(), (k ushr 8).toByte(), k.toByte()
        )
    }
}
