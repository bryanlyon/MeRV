package net.rvremote

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import net.rvremote.protocol.*
import java.util.ArrayDeque
import java.util.UUID

/** One thing on the RV the gateway reports on. */
data class Device(
    val table: Int,
    val id: Int,
    var name: String = "Device $id",
    var function: Int = -1,
    var kind: Kind = Kind.UNKNOWN,
    var on: Boolean = false,
    var brightness: Int = 0,
    var percent: Int = -1,
    var motorStatus: Int = 0,
    var online: Boolean = true,
    var fault: Int = 0,
    var pendingOn: Boolean? = null,
    var pendingUntil: Long = 0,
) {
    /** What the UI should show: the user's latest choice until the panel confirms it. */
    val shownOn: Boolean get() = pendingOn ?: on

    /** Accept a reported state, unless it contradicts a fresh user command. */
    fun report(reported: Boolean) {
        val p = pendingOn
        if (p != null) {
            if (reported == p || System.currentTimeMillis() > pendingUntil) pendingOn = null
            else return
        }
        on = reported
    }
    enum class Kind { UNKNOWN, RELAY, DIMMER, TANK, MOTOR }
    val key get() = (table shl 8) or id
}

/**
 * Talks to a Lippert OneControl / WeRV push-button gateway over BLE.
 * All callbacks to [listener] arrive on the main thread.
 */
@SuppressLint("MissingPermission") // MainActivity requests permissions before start()
class Gateway(private val ctx: Context, private val listener: Listener) {

    interface Listener {
        fun onStatus(text: String)
        fun onDevicesChanged()
        fun onBattery(volts: Double?)
        fun onLog(line: String)
    }

    companion object {
        private fun uuid(n: String) = UUID.fromString("0000$n-0200-a58e-e411-afe28044e62c")
        val AUTH_SERVICE = uuid("0010")
        val SEED = uuid("0011")
        val UNLOCK_STATUS = uuid("0012")
        val KEY = uuid("0013")
        val AUTH_STATUS = uuid("0014")
        val DATA_SERVICE = uuid("0030")
        val DATA_WRITE = uuid("0033")
        val DATA_READ = uuid("0034")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val LIPPERT_ID = 0x0499
        const val LIPPERT_ID_ALT = 0x05C7
        const val NAME_PREFIX = "LCIRemote"
        const val HEARTBEAT_MS = 5000L
        const val MOTOR_REPEAT_MS = 200L
        const val MOTOR_MAX_MS = 60_000L   // hard cap on one press-and-hold
        const val STALE_MS = 60_000L
        private const val PREFS = "gateway"
        private const val PREF_ADDR = "address"
        private const val PREF_TABLE = "table"
    }

    private val main = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val devices = LinkedHashMap<Int, Device>()
    private val cmd = Commands()
    private val decoder = Cobs.Decoder()

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var unlockChar: BluetoothGattCharacteristic? = null
    private var keyChar: BluetoothGattCharacteristic? = null
    private var table = 0 // unknown until the gateway announces it (or saved from last time)
    private val refusalsLogged = HashSet<Int>()
    private var ready = false
    private var wanted = false
    private var scanning = false
    private var unlockAttempts = 0
    private var reconnectDelay = 5000L
    private var lastEventAt = 0L
    private val pendingMetadata = HashSet<Int>()
    private val pendingGetDevices = HashMap<Int, Int>() // cmdId -> table
    private val seenTypes = HashSet<Int>()
    private var metadataLoaded = false

    // ---- logging / status --------------------------------------------------

    private fun log(s: String) { main.post { listener.onLog(s) } }
    private fun status(s: String) { log("» $s"); main.post { listener.onStatus(s) } }
    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it) }

    // ---- lifecycle ---------------------------------------------------------

    fun start() {
        if (wanted) return
        wanted = true
        ctx.registerReceiver(bondReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
        connectOrScan()
    }

    fun stop() {
        if (!wanted) return
        wanted = false
        stopMotor()
        main.removeCallbacksAndMessages(null)
        stopScan()
        try { ctx.unregisterReceiver(bondReceiver) } catch (_: IllegalArgumentException) {}
        gatt?.let { it.disconnect(); it.close() }
        gatt = null
        ready = false
        ops.clear(); opBusy = false
        status("Disconnected")
    }

    /** Forget the saved gateway (e.g. after a panel reset). */
    fun forget() {
        prefs.edit().remove(PREF_ADDR).remove(PREF_TABLE).apply()
        stop()
        devices.clear(); metadataLoaded = false
        main.post { listener.onDevicesChanged() }
    }

    private fun connectOrScan() {
        if (!wanted) return
        val a = adapter
        if (a == null || !a.isEnabled) { status("Bluetooth is off"); scheduleReconnect(); return }
        val saved = prefs.getString(PREF_ADDR, null)
        if (saved != null) {
            val dev = a.getRemoteDevice(saved)
            if (dev.bondState == BluetoothDevice.BOND_BONDED) { connect(dev); return }
        }
        startScan()
    }

    private fun scheduleReconnect() {
        if (!wanted) return
        val d = reconnectDelay
        reconnectDelay = (reconnectDelay * 2).coerceAtMost(60_000L)
        log("reconnect in ${d / 1000}s")
        main.postDelayed({ connectOrScan() }, d)
    }

    // ---- scanning and push-button pairing ----------------------------------

    private fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: run { scheduleReconnect(); return }
        if (scanning) return
        scanning = true
        status("Looking for the RV…")
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, scanCb)
        main.postDelayed({ if (scanning) { stopScan(); scheduleReconnect() } }, 30_000L)
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try { adapter?.bluetoothLeScanner?.stopScan(scanCb) } catch (_: Exception) {}
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(type: Int, r: ScanResult) {
            val rec = r.scanRecord
            val mfr = rec?.getManufacturerSpecificData(LIPPERT_ID)
                ?: rec?.getManufacturerSpecificData(LIPPERT_ID_ALT)
            val name = rec?.deviceName ?: r.device.name ?: ""
            if (mfr == null && !name.startsWith(NAME_PREFIX)) return

            val dev = r.device
            if (dev.bondState == BluetoothDevice.BOND_BONDED) {
                stopScan(); remember(dev); connect(dev); return
            }
            val info = mfr?.firstOrNull()?.toInt()?.and(0xFF) ?: 0
            val pushButton = info and 0x01 != 0
            val pairingOpen = info and 0x02 != 0
            if (pushButton && !pairingOpen) {
                status("Found ${name.ifEmpty { "gateway" }}. Press CONNECT on the panel.")
                return
            }
            stopScan()
            status("Pairing…")
            log("bonding with ${dev.address} (pairing info 0x%02X)".format(info))
            remember(dev)
            if (!dev.createBond()) { status("Pairing failed to start"); scheduleReconnect() }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            status("Scan failed ($errorCode)")
            scheduleReconnect()
        }
    }

    private fun remember(dev: BluetoothDevice) {
        prefs.edit().putString(PREF_ADDR, dev.address).apply()
    }

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            @Suppress("DEPRECATION")
            val dev = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            if (dev.address != prefs.getString(PREF_ADDR, null)) return
            when (i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)) {
                BluetoothDevice.BOND_BONDED -> { log("bonded"); connect(dev) }
                BluetoothDevice.BOND_NONE -> { status("Pairing was refused. Press CONNECT and try again."); scheduleReconnect() }
            }
        }
    }

    // ---- GATT --------------------------------------------------------------

    private fun connect(dev: BluetoothDevice) {
        if (!wanted || gatt != null) return
        status("Connecting…")
        gatt = dev.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE)
    }

    private fun dropLink(reason: String) {
        log("link down: $reason")
        stopMotor()
        gatt?.close()
        gatt = null
        ready = false
        ops.clear(); opBusy = false
        main.removeCallbacks(heartbeat)
        status("Connection lost")
        scheduleReconnect()
    }

    // Android allows one outstanding GATT operation at a time; queue them.
    private val ops = ArrayDeque<() -> Boolean>()
    private var opBusy = false
    private val opTimeout = Runnable { log("gatt op timed out"); opDone() }

    private fun enqueue(op: () -> Boolean) { main.post { ops.add(op); pump() } }

    private fun pump() {
        if (opBusy) return
        while (ops.isNotEmpty()) {
            val op = ops.poll() ?: return
            if (op()) { opBusy = true; main.postDelayed(opTimeout, 3000); return }
        }
    }

    private fun opDone() {
        main.post {
            main.removeCallbacks(opTimeout)
            opBusy = false
            pump()
        }
    }

    @Suppress("DEPRECATION")
    private fun writeChar(c: BluetoothGattCharacteristic, value: ByteArray, type: Int): Boolean {
        val g = gatt ?: return false
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, value, type) == BluetoothStatusCodes.SUCCESS
        } else {
            c.writeType = type; c.value = value; g.writeCharacteristic(c)
        }
    }

    @Suppress("DEPRECATION")
    private fun enableNotify(c: BluetoothGattCharacteristic): Boolean {
        val g = gatt ?: return false
        g.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(CCCD) ?: return false
        val v = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, v) == BluetoothStatusCodes.SUCCESS
        } else {
            d.value = v; g.writeDescriptor(d)
        }
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            if (g != gatt) return
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("connected (status $st)")
                status("Unlocking…")
                main.postDelayed({ gatt?.discoverServices() }, 400)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                main.post { if (gatt == g) dropLink("status $st") }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            val auth = g.getService(AUTH_SERVICE)
            val data = g.getService(DATA_SERVICE)
            if (data == null) { log("data service missing; services=${g.services.map { it.uuid }}"); main.post { dropLink("no data service") }; return }
            writeChar = data.getCharacteristic(DATA_WRITE)
            unlockChar = auth?.getCharacteristic(UNLOCK_STATUS)
            keyChar = auth?.getCharacteristic(KEY)
            unlockAttempts = 0
            enqueue { g.requestMtu(185) }
            val u = unlockChar
            if (u != null) enqueue { g.readCharacteristic(u) } else subscribe(g)
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, st: Int) { log("mtu $mtu"); opDone() }

        @Deprecated("pre-33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, st: Int) =
            handleRead(g, c, c.value ?: ByteArray(0), st)

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, st: Int) =
            handleRead(g, c, value, st)

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, st: Int) { opDone() }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, st: Int) {
            log("notify ${d.characteristic.uuid.toString().substring(4, 8)} status $st")
            opDone()
        }

        @Deprecated("pre-33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) =
            handleNotify(c, c.value ?: ByteArray(0))

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) =
            handleNotify(c, value)
    }

    // ---- unlock ------------------------------------------------------------

    private fun handleRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, v: ByteArray, st: Int) {
        opDone()
        if (c.uuid != UNLOCK_STATUS) return
        log("unlock status: ${hex(v)} (status $st)")
        val text = String(v, Charsets.UTF_8)
        when {
            text.contains("Unlocked", ignoreCase = true) -> { log("unlocked"); subscribe(g) }
            v.size == 4 && v.all { it.toInt() == 0 } -> main.post { dropLink("gateway not ready (zero challenge)") }
            v.size == 4 && unlockAttempts < 3 -> {
                unlockAttempts++
                val key = Tea.step1Key(v)
                val k = keyChar ?: run { subscribe(g); return }
                enqueue { writeChar(k, key, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) }
                main.postDelayed({ unlockChar?.let { u -> enqueue { g.readCharacteristic(u) } } }, 500)
            }
            else -> { log("unexpected unlock reply; continuing"); subscribe(g) }
        }
    }

    private fun subscribe(g: BluetoothGatt) {
        val data = g.getService(DATA_SERVICE)
        val auth = g.getService(AUTH_SERVICE)
        listOfNotNull(
            data?.getCharacteristic(DATA_READ),
            auth?.getCharacteristic(SEED),
            auth?.getCharacteristic(AUTH_STATUS),
        ).forEach { c -> enqueue { enableNotify(c) } }
        enqueue {
            ready = true
            reconnectDelay = 5000L
            lastEventAt = System.currentTimeMillis()
            status("Connected")
            table = prefs.getInt(PREF_TABLE, 0)
            // Never guess the table: a request to the wrong one jams the gateway.
            if (table != 0) requestDevices() else log("waiting for gateway to announce its device table")
            main.postDelayed(heartbeat, HEARTBEAT_MS)
            false // not a GATT op; let the queue continue
        }
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            if (!ready) return
            if (System.currentTimeMillis() - lastEventAt > STALE_MS) {
                gatt?.disconnect(); dropLink("no data for ${STALE_MS / 1000}s"); return
            }
            if (table != 0 && pendingMetadata.isEmpty()) requestDevices()
            main.postDelayed(this, HEARTBEAT_MS)
        }
    }

    // ---- data in -----------------------------------------------------------

    private fun handleNotify(c: BluetoothGattCharacteristic, v: ByteArray) {
        if (c.uuid != DATA_READ) { log("notify ${c.uuid.toString().substring(4, 8)}: ${hex(v)}"); return }
        val frames = synchronized(decoder) { decoder.feed(v) }
        if (frames.isEmpty()) return
        main.post {
            lastEventAt = System.currentTimeMillis()
            var changed = false
            for (f in frames) {
                val t = f[0].toInt() and 0xFF
                if (seenTypes.add(t)) log("first event 0x%02X: %s".format(t, hex(f)))
                for (e in Event.parse(f)) changed = applyEvent(e, f) || changed
            }
            if (changed) listener.onDevicesChanged()
        }
    }

    private fun dev(t: Int, id: Int): Device = devices.getOrPut((t shl 8) or id) { Device(t, id) }

    /** Returns true if the device list changed. Runs on main thread. */
    private fun applyEvent(e: Event, frame: ByteArray): Boolean {
        when (e) {
            is Event.GatewayInfo -> {
                if (e.table != 0 && e.table != table) {
                    table = e.table; log("device table $table, ${e.deviceCount} devices")
                    prefs.edit().putInt(PREF_TABLE, table).apply()
                    requestDevices()
                }
                return false
            }
            is Event.RvStatus -> { listener.onBattery(e.volts); return false }
            is Event.Relay -> dev(e.table, e.device).apply {
                if (kind == Device.Kind.UNKNOWN || kind == Device.Kind.RELAY) kind = Device.Kind.RELAY
                report(e.on)
                if (e.faultCode != fault) {
                    fault = e.faultCode
                    if (fault != 0) log("$name reports stored code $fault")
                }
            }
            is Event.Dimmer -> dev(e.table, e.device).apply {
                kind = Device.Kind.DIMMER; report(e.on); if (e.brightness > 0) brightness = e.brightness
            }
            is Event.Tank -> dev(e.table, e.device).apply { kind = Device.Kind.TANK; percent = e.percent }
            is Event.Motor -> dev(e.table, e.device).apply { kind = Device.Kind.MOTOR; motorStatus = e.status }
            is Event.Online -> dev(e.table, e.device).apply { online = e.online }
            is Event.CommandPart -> {
                if (e.cmdId !in pendingMetadata) return false
                val md = Event.parseMetadata(frame) ?: return false
                for (en in md.entries) dev(md.table, en.device).apply {
                    function = en.function
                    name = FunctionNames.name(en.function, en.instance)
                    if (kind == Device.Kind.UNKNOWN && FunctionNames.isTank(en.function)) kind = Device.Kind.TANK
                }
                log("names for ${md.entries.size} devices")
            }
            is Event.CommandDone -> {
                if (pendingMetadata.remove(e.cmdId)) { metadataLoaded = true; log("device names loaded") }
                val t = pendingGetDevices.remove(e.cmdId)
                if (t != null) {
                    log("device list done (table $t)")
                    if (t == table && !metadataLoaded && pendingMetadata.isEmpty()) requestMetadata()
                }
                return false
            }
            is Event.CommandFailed -> {
                val wasMeta = pendingMetadata.remove(e.cmdId)
                val wasList = pendingGetDevices.remove(e.cmdId) != null
                if (wasMeta || wasList || refusalsLogged.add(e.cmdId))
                    log("command ${e.cmdId} refused, code 0x%02X%s".format(e.code,
                        if (wasMeta) " (names)" else if (wasList) " (device list)" else ""))
                if (wasMeta) main.postDelayed({ if (ready && !metadataLoaded && pendingMetadata.isEmpty()) requestMetadata() }, 10_000)
                return false
            }
            is Event.Metadata -> return false
        }
        return true
    }


    private fun requestDevices() {
        if (table == 0) return
        val c = cmd.getDevices(table)
        val id = (c[0].toInt() and 0xFF) or ((c[1].toInt() and 0xFF) shl 8)
        pendingGetDevices[id] = table
        if (pendingGetDevices.size > 20) pendingGetDevices.remove(pendingGetDevices.keys.first())
        send(c)
    }

    private fun requestMetadata() {
        log("asking for device names (table $table)")
        val c = cmd.getDevicesMetadata(table)
        val id = (c[0].toInt() and 0xFF) or ((c[1].toInt() and 0xFF) shl 8)
        pendingMetadata.add(id)
        send(c)
        // If the gateway never confirms, allow another try on a later GatewayInfo.
        main.postDelayed({ if (pendingMetadata.remove(id)) log("name request got no answer; will retry") }, 10_000)
    }

    // ---- commands out ------------------------------------------------------

    private fun send(raw: ByteArray) {
        val c = writeChar ?: return
        if (!ready) return
        val wire = Cobs.encode(raw)
        enqueue { writeChar(c, wire, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) }
    }

    val isReady get() = ready

    fun setOn(d: Device, on: Boolean) {
        d.pendingOn = on
        d.pendingUntil = System.currentTimeMillis() + 3000
        listener.onDevicesChanged()
        when (d.kind) {
            Device.Kind.DIMMER -> send(cmd.dimmable(d.table, d.id, if (on) d.brightness.takeIf { it > 0 } ?: 255 else 0))
            else -> send(cmd.switch(d.table, d.id, on))
        }
    }

    // Momentary motor control: keep resending while the button is held.
    private var motorDev: Device? = null
    private var motorMotion = Commands.Motion.STOP
    private var motorStartedAt = 0L

    private val motorTick = object : Runnable {
        override fun run() {
            val d = motorDev ?: return
            if (!ready || System.currentTimeMillis() - motorStartedAt > MOTOR_MAX_MS) { stopMotor(); return }
            send(cmd.hbridge(d.table, d.id, motorMotion))
            main.postDelayed(this, MOTOR_REPEAT_MS)
        }
    }

    fun startMotor(d: Device, motion: Commands.Motion) {
        main.removeCallbacks(motorTick)
        motorDev = d; motorMotion = motion; motorStartedAt = System.currentTimeMillis()
        motorTick.run()
    }

    fun stopMotor() {
        main.removeCallbacks(motorTick)
        val d = motorDev ?: return
        motorDev = null
        repeat(2) { send(cmd.hbridge(d.table, d.id, Commands.Motion.STOP)) }
    }
}
