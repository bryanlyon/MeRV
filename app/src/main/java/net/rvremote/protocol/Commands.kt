// Ported from ha-onecontrol (MIT License, (c) 2026 Pete Hurth). See LICENSE.
package net.rvremote.protocol

/**
 * MyRvLink command builder. Wire format: [cmdId LSB][cmdId MSB][type][payload...]
 * The result is raw; COBS-encode it before writing.
 */
class Commands {
    private var nextId = 0

    @Synchronized
    private fun id(): ByteArray {
        val cid = nextId
        nextId = (nextId + 1) and 0xFFFF
        return byteArrayOf(cid.toByte(), (cid ushr 8).toByte())
    }

    private fun build(type: Int, vararg payload: Int): ByteArray =
        id() + byteArrayOf(type.toByte()) + ByteArray(payload.size) { payload[it].toByte() }

    fun getDevices(table: Int) = build(0x01, table, 0x00, 0xFF)

    fun getDevicesMetadata(table: Int) = build(0x02, table, 0x00, 0xFF)

    fun switch(table: Int, device: Int, on: Boolean) =
        build(0x40, table, if (on) 1 else 0, device)

    fun dimmable(table: Int, device: Int, brightness: Int): ByteArray {
        val b = brightness.coerceIn(0, 255)
        return build(0x43, table, device, if (b == 0) 0 else 1, b, 0)
    }

    enum class Motion(val wire: Int) {
        STOP(0x80), EXTEND(0x82), RETRACT(0x83)
    }

    /** Momentary: must be repeated while held, exactly like the panel button. */
    fun hbridge(table: Int, device: Int, motion: Motion) =
        build(0x41, table, device, motion.wire)
}
