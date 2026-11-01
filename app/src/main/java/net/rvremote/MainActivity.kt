package net.rvremote

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.*
import net.rvremote.protocol.Commands
import net.rvremote.protocol.FunctionNames

class MainActivity : Activity(), Gateway.Listener {

    private lateinit var gateway: Gateway
    private lateinit var statusView: TextView
    private lateinit var batteryView: TextView
    private lateinit var content: LinearLayout
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private val logLines = ArrayDeque<String>()
    private var showLog = false

    private val dp get() = resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gateway = Gateway(applicationContext, this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(12), px(16), px(12))
        }
        statusView = TextView(this).apply { textSize = 15f; setTextColor(Color.LTGRAY) }
        batteryView = TextView(this).apply { textSize = 22f; setTypeface(typeface, Typeface.BOLD) }
        root.addView(statusView)
        root.addView(batteryView)

        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))

        logView = TextView(this).apply { textSize = 11f; typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        logScroll = ScrollView(this).apply { addView(logView); visibility = View.GONE }
        root.addView(logScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        setContentView(root)
        statusView.text = "Starting…"
        render()
    }

    override fun onStart() {
        super.onStart()
        if (hasPermissions()) gateway.start() else requestPermissions(neededPermissions(), 1)
    }

    override fun onPause() {
        super.onPause()
        gateway.stopMotor() // never leave a motor running when the screen goes away
    }

    override fun onStop() {
        super.onStop()
        gateway.stop()
    }

    // ---- permissions -------------------------------------------------------

    private fun neededPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasPermissions() = neededPermissions().all {
        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        if (hasPermissions()) gateway.start()
        else statusView.text = "Bluetooth permission is needed to talk to the RV."
    }

    // ---- menu --------------------------------------------------------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Debug log")
        menu.add(0, 2, 0, "Copy log")
        menu.add(0, 3, 0, "Forget this RV")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> { showLog = !showLog; logScroll.visibility = if (showLog) View.VISIBLE else View.GONE }
            2 -> {
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("RV Remote log", logLines.joinToString("\n")))
                Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show()
            }
            3 -> AlertDialog.Builder(this)
                .setMessage("Forget this RV? You'll need to press CONNECT on the panel to pair again. " +
                    "Also remove it under Android's Bluetooth settings.")
                .setPositiveButton("Forget") { _, _ -> gateway.forget(); render() }
                .setNegativeButton("Cancel", null).show()
        }
        return true
    }

    // ---- Gateway.Listener --------------------------------------------------

    override fun onStatus(text: String) { statusView.text = text }

    override fun onBattery(volts: Double?) {
        batteryView.text = volts?.let { "Battery %.2f V".format(it) } ?: ""
    }

    override fun onDevicesChanged() {
        // Only rebuild when the set of rows changes; otherwise update in place so a
        // held awning button is never torn down mid-press.
        if (layoutSignature() != builtSignature) render()
        else gateway.devices.values.forEach { d -> updaters[d.key]?.invoke(d) }
    }

    private var builtSignature = ""
    private val updaters = HashMap<Int, (Device) -> Unit>()

    private fun layoutSignature() = gateway.devices.values.joinToString("|") {
        "${it.key}:${it.kind}:${it.name}:${it.function}:${it.percent >= 0}"
    }

    override fun onLog(line: String) {
        logLines.addLast(line)
        while (logLines.size > 500) logLines.removeFirst()
        if (showLog) {
            logView.text = logLines.joinToString("\n")
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    // ---- UI ----------------------------------------------------------------

    private fun header(t: String) = TextView(this).apply {
        text = t; textSize = 13f; setTextColor(Color.GRAY)
        setTypeface(typeface, Typeface.BOLD); setPadding(0, px(18), 0, px(6))
    }

    private fun render() {
        content.removeAllViews()
        updaters.clear()
        builtSignature = layoutSignature()
        val all = gateway.devices.values.toList()
        if (all.isEmpty()) {
            content.addView(TextView(this).apply {
                text = "\nFirst time: press CONNECT on the panel while this screen is open. " +
                    "Android will ask to pair; accept it."
                textSize = 15f
            })
            return
        }

        val tanks = all.filter { it.kind == Device.Kind.TANK && it.percent >= 0 }
        if (tanks.isNotEmpty()) {
            content.addView(header("TANKS"))
            tanks.sortedBy { it.name }.forEach { content.addView(tankRow(it)) }
        }

        val switches = all.filter { it.kind == Device.Kind.RELAY || it.kind == Device.Kind.DIMMER }
            .filter { it.function != 323 } // the gateway itself
        val (lights, other) = switches.partition { FunctionNames.isLight(it.function) }
        if (lights.isNotEmpty()) {
            content.addView(header("LIGHTS"))
            lights.sortedBy { it.name }.forEach { content.addView(switchRow(it)) }
        }
        if (other.isNotEmpty()) {
            content.addView(header("SYSTEMS"))
            other.sortedBy { it.name }.forEach { content.addView(switchRow(it)) }
        }

        val motors = all.filter { it.kind == Device.Kind.MOTOR }
        if (motors.isNotEmpty()) {
            content.addView(header("AWNING — HOLD TO MOVE"))
            motors.sortedBy { it.name }.forEach { content.addView(motorRow(it)) }
        }
    }

    private fun tankRow(d: Device) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, px(4), 0, px(4))
        addView(TextView(context).apply { text = d.name; textSize = 16f }, LinearLayout.LayoutParams(0, -2, 1f))
        val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = d.percent
        }
        val pct = TextView(context).apply {
            text = "${d.percent}%"; textSize = 16f; gravity = Gravity.END
        }
        addView(bar, LinearLayout.LayoutParams(0, -2, 1f))
        addView(pct, LinearLayout.LayoutParams(px(56), -2))
        updaters[d.key] = { x -> bar.progress = x.percent; pct.text = "${x.percent}%" }
    }

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private fun switchRow(d: Device) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, px(6), 0, px(6))
        val label = TextView(context).apply { textSize = 17f }
        val sw = Switch(context).apply { setOnClickListener { gateway.setOn(d, isChecked) } }
        addView(label, LinearLayout.LayoutParams(0, -2, 1f))
        addView(sw)
        val update = { x: Device ->
            label.text = x.name + if (x.fault != 0) "  ⚠ fault ${x.fault}" else ""
            label.setTextColor(if (x.online) Color.WHITE else Color.GRAY)
            sw.isChecked = x.on
            sw.isEnabled = x.online
        }
        update(d)
        updaters[d.key] = update
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun motorRow(d: Device) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, px(6), 0, px(6))
        addView(TextView(context).apply { text = d.name; textSize = 17f })
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        fun holdButton(label: String, motion: Commands.Motion) = Button(context).apply {
            text = label; textSize = 18f
            setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { v.isPressed = true; gateway.startMotor(d, motion) }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.isPressed = false; gateway.stopMotor() }
                }
                true
            }
        }
        row.addView(holdButton("◀ Retract", Commands.Motion.RETRACT), LinearLayout.LayoutParams(0, px(72), 1f))
        row.addView(holdButton("Extend ▶", Commands.Motion.EXTEND), LinearLayout.LayoutParams(0, px(72), 1f))
        addView(row)
    }
}
