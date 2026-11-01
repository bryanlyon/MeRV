// Ported from ha-onecontrol by Pete Hurth (https://github.com/phurth)
// https://github.com/phurth/ha-onecontrol
// MIT License, Copyright (c) 2026 Pete Hurth. Full text in LICENSE.
// Source:
//   https://github.com/phurth/ha-onecontrol/blob/0235555/custom_components/ha_onecontrol/protocol/events.py
package net.rvremote.protocol

/** Decoded gateway events, reduced to what this app shows. */
sealed class Event {
    data class GatewayInfo(val table: Int, val deviceCount: Int) : Event()
    data class RvStatus(val volts: Double?, val tempF: Double?) : Event()
    data class Relay(val table: Int, val device: Int, val on: Boolean, val faultCode: Int) : Event()
    data class Dimmer(val table: Int, val device: Int, val on: Boolean, val brightness: Int) : Event()
    data class Tank(val table: Int, val device: Int, val percent: Int) : Event()
    data class Motor(val table: Int, val device: Int, val status: Int) : Event()
    data class Online(val table: Int, val device: Int, val online: Boolean) : Event()
    data class Metadata(val table: Int, val entries: List<Entry>) : Event() {
        data class Entry(val device: Int, val function: Int, val instance: Int)
    }
    data class CommandDone(val cmdId: Int) : Event()
    class CommandPart(val cmdId: Int, val frame: ByteArray) : Event()
    data class CommandFailed(val cmdId: Int, val code: Int) : Event()

    companion object {
        private fun u(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

        fun parse(f: ByteArray): List<Event> {
            if (f.isEmpty()) return emptyList()
            return when (u(f, 0)) {
                0x01 -> if (f.size >= 13) listOf(GatewayInfo(u(f, 4), u(f, 3))) else emptyList()
                0x02 -> parseCommandResponse(f)
                0x03 -> if (f.size >= 4) listOf(Online(u(f, 1), u(f, 2), u(f, 3) != 0xFF)) else emptyList()
                0x05, 0x06 -> if (f.size >= 4) {
                    val fault = if (f.size >= 9) (u(f, 5) shl 8) or u(f, 6) else 0
                    listOf(Relay(u(f, 1), u(f, 2), (u(f, 3) and 0x0F) == 0x01, fault))
                } else emptyList()
                0x07 -> if (f.size >= 6) {
                    val v = (u(f, 1) shl 8) or u(f, 2)
                    val t = (u(f, 3) shl 8) or u(f, 4)
                    listOf(RvStatus(
                        if (v == 0xFFFF) null else v / 256.0,
                        if (t == 0xFFFF || t == 0x7FFF) null else t / 256.0
                    ))
                } else emptyList()
                0x08 -> if (f.size >= 5) {
                    val bright = if (f.size >= 7) u(f, 6) else u(f, 4)
                    listOf(Dimmer(u(f, 1), u(f, 2), u(f, 3) != 0, bright))
                } else emptyList()
                0x0C -> {
                    val out = ArrayList<Event>()
                    if (f.size >= 4) {
                        var i = 2
                        while (i + 1 < f.size) {
                            out.add(Tank(u(f, 1), u(f, i), minOf(100, u(f, i + 1)))); i += 2
                        }
                    }
                    out
                }
                0x1B -> if (f.size >= 4) listOf(Tank(u(f, 1), u(f, 2), minOf(100, u(f, 3) and 0x7F))) else emptyList()
                0x0D, 0x0E -> if (f.size >= 4) listOf(Motor(u(f, 1), u(f, 2), u(f, 3))) else emptyList()
                else -> emptyList()
            }
        }

        /** 0x02: [0x02][cmdId LSB][cmdId MSB][respType][...] */
        private fun parseCommandResponse(f: ByteArray): List<Event> {
            if (f.size < 4) return emptyList()
            val cmdId = u(f, 1) or (u(f, 2) shl 8)
            return when (u(f, 3)) {
                0x81 -> listOf(CommandDone(cmdId))
                0x01 -> listOf(CommandPart(cmdId, f))
                0x02, 0x82 -> listOf(CommandFailed(cmdId, if (f.size >= 5) u(f, 4) else -1))
                else -> emptyList()
            }
        }

        /**
         * Only valid for a frame answering a GetDevicesMetadata request; the
         * response type byte is shared with other commands, so the caller must
         * match the command ID first.
         */
        fun parseMetadata(f: ByteArray): Metadata? {
            if (f.size < 7) return null
            // Metadata entries: [table][start][count] then [protocol][size][payload]...
            val table = u(f, 4); val start = u(f, 5); val count = u(f, 6)
            val entries = ArrayList<Metadata.Entry>()
            var off = 7; var idx = 0
            while (idx < count && off + 2 < f.size) {
                val proto = u(f, off); val size = u(f, off + 1)
                val dev = (start + idx) and 0xFF
                if ((proto == 1 || proto == 2) && size == 17 && off + 2 + size <= f.size) {
                    entries.add(Metadata.Entry(dev, (u(f, off + 2) shl 8) or u(f, off + 3), u(f, off + 4)))
                } else if (proto == 1 && size == 0) {
                    entries.add(Metadata.Entry(dev, 323, 15)) // the gateway itself
                }
                off += 2 + size; idx++
            }
            return if (entries.isEmpty()) null else Metadata(table, entries)
        }
    }
}
