package net.rvremote

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.*
import net.rvremote.protocol.Commands
import net.rvremote.protocol.FunctionNames

class MainActivity : Activity(), Gateway.Listener {

    // ---- palette -----------------------------------------------------------
    private object C {
        val BG = Color.parseColor("#101317")
        val CARD = Color.parseColor("#1A1F26")
        val CARD_HI = Color.parseColor("#232A33")
        val LINE = Color.parseColor("#2C343F")
        val TEXT = Color.parseColor("#E8ECF1")
        val MUTED = Color.parseColor("#8A94A3")
        val ON = Color.parseColor("#F2B544")        // warm amber for "on"
        val ON_TEXT = Color.parseColor("#1A1305")
        val OK = Color.parseColor("#4CC38A")
        val WARN = Color.parseColor("#E0A63A")
        val BAD = Color.parseColor("#E5604D")
        val FRESH = Color.parseColor("#4DA3FF")
        val GREY = Color.parseColor("#9AA5B4")
        val BLACK = Color.parseColor("#B58A5A")
    }

    private lateinit var gateway: Gateway
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var content: LinearLayout
    private val logLines = ArrayDeque<String>()

    private var volts: Double? = null
    private var builtSignature = ""
    private val updaters = HashMap<Int, (Device) -> Unit>()
    private var batteryUpdater: (() -> Unit)? = null

    private val dp get() = resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()

    // ---- lifecycle ---------------------------------------------------------

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gateway = Gateway(applicationContext, this)
        window.decorView.setBackgroundColor(C.BG)
        if (Build.VERSION.SDK_INT < 35) {
            window.statusBarColor = C.BG
            window.navigationBarColor = C.BG
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C.BG)
        }
        root.addView(buildHeader())
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(4), px(16), px(24))
        }
        root.addView(ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }, LinearLayout.LayoutParams(-1, 0, 1f))

        // Keep content clear of the status bar, notch and gesture bar.
        root.setOnApplyWindowInsetsListener { v, insets ->
            val (top, bottom) = if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                i.top to i.bottom
            } else {
                insets.systemWindowInsetTop to insets.systemWindowInsetBottom
            }
            v.setPadding(0, top, 0, bottom)
            insets
        }

        setContentView(root)
        setStatus("Starting…")
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
        else setStatus("Bluetooth permission is needed")
    }

    // ---- header ------------------------------------------------------------

    private fun buildHeader(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(20), px(14), px(8), px(10))

        addView(TextView(context).apply {
            text = "MeRV"; textSize = 26f; setTextColor(C.TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))

        val chip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(10), px(5), px(12), px(5))
            background = rounded(C.CARD, 100)
        }
        statusDot = View(context).apply { background = oval(C.MUTED) }
        statusText = TextView(context).apply { textSize = 13f; setTextColor(C.MUTED) }
        chip.addView(statusDot, LinearLayout.LayoutParams(px(8), px(8)).apply { rightMargin = px(7) })
        chip.addView(statusText)
        addView(chip)

        addView(TextView(context).apply {
            text = "⋮"; textSize = 24f; setTextColor(C.MUTED); gravity = Gravity.CENTER
            setOnClickListener { showMenu(it) }
        }, LinearLayout.LayoutParams(px(44), px(44)))
    }

    private fun showMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "Debug log")
            menu.add(0, 2, 0, "Forget this RV")
            menu.add(0, 3, 0, "About & credits")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> showLog()
                    3 -> showAbout()
                    2 -> AlertDialog.Builder(this@MainActivity)
                        .setMessage("Forget this RV? You'll need to press CONNECT on the panel to pair again. " +
                            "Also remove it under Android's Bluetooth settings.")
                        .setPositiveButton("Forget") { _, _ -> gateway.forget(); volts = null; render() }
                        .setNegativeButton("Cancel", null).show()
                }
                true
            }
            show()
        }
    }

    private fun showLog() {
        val text = TextView(this).apply {
            textSize = 11f; typeface = Typeface.MONOSPACE; setTextIsSelectable(true)
            setPadding(px(16), px(8), px(16), px(8))
            this.text = logLines.joinToString("\n")
        }
        val scroll = ScrollView(this).apply { addView(text) }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        AlertDialog.Builder(this)
            .setTitle("Debug log")
            .setView(scroll)
            .setPositiveButton("Copy") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("MeRV log", logLines.joinToString("\n")))
                Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showAbout() {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) { "?" }
        val text = TextView(this).apply {
            textSize = 15f; setLineSpacing(0f, 1.2f)
            setPadding(px(22), px(8), px(22), px(8))
            autoLinkMask = android.text.util.Linkify.WEB_URLS
            linksClickable = true
            this.text = """MeRV $version
A local Bluetooth remote for Lippert OneControl / WeRV panels.

MeRV is built on the reverse-engineering work of Pete Hurth (phurth), who worked out how the OneControl gateway authenticates and communicates and published it as open source under the MIT License.

ha-onecontrol
https://github.com/phurth/ha-onecontrol
Copyright (c) 2026 Pete Hurth, MIT License.
Source of MeRV's unlock math, message framing, command and event formats, and device-name table.

ble-plugin-bridge
https://github.com/phurth/ble-plugin-bridge
Copyright (c) 2025 phurth, MIT License.
Source of MeRV's Android connection, pairing and unlock sequence.

MeRV source:
https://github.com/bryanlyon/MeRV"""
        }
        AlertDialog.Builder(this)
            .setTitle("About & credits")
            .setView(ScrollView(this).apply { addView(text) })
            .setPositiveButton("Close", null)
            .setNeutralButton("Full license") { _, _ -> showLicense() }
            .show()
    }

    private fun showLicense() {
        val body = try {
            assets.open("LICENSE.txt").bufferedReader().use { it.readText() }
        } catch (_: Exception) { "License file missing from this build." }
        val text = TextView(this).apply {
            textSize = 11f; typeface = Typeface.MONOSPACE; setTextIsSelectable(true)
            setPadding(px(16), px(8), px(16), px(8))
            autoLinkMask = android.text.util.Linkify.WEB_URLS
            this.text = body
        }
        AlertDialog.Builder(this)
            .setTitle("Licenses")
            .setView(ScrollView(this).apply { addView(text) })
            .setPositiveButton("Close", null)
            .show()
    }

    private fun setStatus(text: String) {
        val color = when {
            gateway.isReady -> C.OK
            text.contains("off", true) || text.contains("lost", true) ||
                text.contains("fail", true) || text.contains("refused", true) ||
                text.contains("permission", true) -> C.BAD
            else -> C.WARN
        }
        statusText.text = if (gateway.isReady) "Connected" else text
        statusText.setTextColor(if (gateway.isReady) C.TEXT else C.MUTED)
        (statusDot.background as GradientDrawable).setColor(color)
        // Long instructions (e.g. "press CONNECT") also go in the body while nothing is loaded.
        if (gateway.devices.isEmpty()) render()
    }

    // ---- Gateway.Listener --------------------------------------------------

    private var lastStatus = ""

    override fun onStatus(text: String) { lastStatus = text; setStatus(text) }

    override fun onBattery(volts: Double?) {
        this.volts = volts
        batteryUpdater?.invoke() ?: render()
    }

    override fun onDevicesChanged() {
        // Only rebuild when the set of rows changes; otherwise update in place so a
        // held awning button is never torn down mid-press.
        if (layoutSignature() != builtSignature) render()
        else gateway.devices.values.forEach { d -> updaters[d.key]?.invoke(d) }
    }

    override fun onLog(line: String) {
        logLines.addLast(line)
        while (logLines.size > 1000) logLines.removeFirst()
    }

    private fun layoutSignature() = gateway.devices.values.joinToString("|") {
        "${it.key}:${it.kind}:${it.name}:${it.function}:${it.percent >= 0}"
    } + "|v=${volts != null}"

    // ---- drawing helpers ---------------------------------------------------

    private fun rounded(color: Int, radiusDp: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = px(radiusDp).toFloat()
        if (stroke != null) setStroke(px(1), stroke)
    }

    private fun oval(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    private fun card(title: String? = null, body: LinearLayout.() -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(C.CARD, 18)
            setPadding(px(16), px(14), px(16), px(16))
            if (title != null) addView(TextView(context).apply {
                text = title; textSize = 12f; setTextColor(C.MUTED); letterSpacing = 0.12f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(0, 0, 0, px(12))
            })
            body()
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(12) }
        }

    // ---- main render -------------------------------------------------------

    private fun render() {
        content.removeAllViews()
        updaters.clear()
        batteryUpdater = null
        builtSignature = layoutSignature()

        val all = gateway.devices.values.toList()
        if (all.isEmpty() && volts == null) {
            content.addView(card {
                addView(TextView(context).apply {
                    text = if (lastStatus.contains("CONNECT")) lastStatus
                    else "First time? Press CONNECT on the panel while this screen is open, " +
                        "then accept Android's pairing prompt."
                    textSize = 16f; setTextColor(C.TEXT); setLineSpacing(0f, 1.25f)
                })
            })
            return
        }

        if (volts != null) content.addView(batteryCard())

        val tanks = all.filter { it.kind == Device.Kind.TANK && it.percent >= 0 }
        if (tanks.isNotEmpty()) content.addView(tankCard(tanks))

        val switches = all.filter { (it.kind == Device.Kind.RELAY || it.kind == Device.Kind.DIMMER) && it.function != 323 }
        val (lights, systems) = switches.partition { FunctionNames.isLight(it.function) }
        // Controls in order: lights, awning, then everything else.
        if (lights.isNotEmpty()) content.addView(tileCard("LIGHTS", lights.sortedBy { it.name }))
        all.filter { it.kind == Device.Kind.MOTOR }.sortedBy { it.name }.forEach { content.addView(motorCard(it)) }
        if (systems.isNotEmpty()) content.addView(tileCard("SYSTEMS", systems.sortedBy { it.name }))
    }

    // ---- battery -----------------------------------------------------------

    private fun batteryLevel(v: Double): Pair<String, Int> = when {
        v >= 12.6 -> "Charged" to C.OK
        v >= 12.4 -> "Good" to C.OK
        v >= 12.2 -> "Fair" to C.WARN
        else -> "Low" to C.BAD
    }

    private fun batteryCard(): View = card("BATTERY") {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        val value = TextView(context).apply {
            textSize = 34f; setTextColor(C.TEXT); typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
        val label = TextView(context).apply {
            textSize = 15f; setPadding(px(10), 0, 0, px(7))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        row.addView(value); row.addView(label)
        addView(row)

        // Four segments, like the panel's LEDs.
        val segs = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, px(12), 0, 0) }
        val segViews = List(4) { View(context) }
        segViews.forEachIndexed { i, s ->
            segs.addView(s, LinearLayout.LayoutParams(0, px(6), 1f).apply { if (i > 0) leftMargin = px(5) })
        }
        addView(segs)

        val update = {
            val v = volts
            if (v != null) {
                val (name, color) = batteryLevel(v)
                value.text = "%.1f V".format(v)
                label.text = name; label.setTextColor(color)
                val lit = when (name) { "Charged" -> 4; "Good" -> 3; "Fair" -> 2; else -> 1 }
                segViews.forEachIndexed { i, s -> s.background = rounded(if (i < lit) color else C.LINE, 3) }
            }
        }
        update()
        batteryUpdater = update
    }

    // ---- tanks -------------------------------------------------------------

    private fun tankColor(d: Device) = when {
        d.name.contains("Fresh") -> C.FRESH
        d.name.contains("Black") -> C.BLACK
        else -> C.GREY
    }

    private fun tankOrder(d: Device) = when {
        d.name.contains("Fresh") -> 0
        d.name.contains("Grey") || d.name.contains("Gray") -> 1
        d.name.contains("Black") -> 2
        else -> 3
    }

    private fun tankCard(tanks: List<Device>): View = card("TANKS") {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        tanks.sortedWith(compareBy({ tankOrder(it) }, { it.name })).forEachIndexed { i, d ->
            row.addView(tankGauge(d), LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = px(12) })
        }
        addView(row)
    }

    private fun tankGauge(d: Device): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val color = tankColor(d)

        val pct = TextView(context).apply {
            textSize = 20f; setTextColor(C.TEXT); gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
        addView(pct)

        // Vertical gauge: a rounded track with a fill that grows from the bottom.
        val height = px(96)
        val track = FrameLayout(context).apply { background = rounded(C.CARD_HI, 12) }
        val fill = View(context).apply { background = rounded(color, 12) }
        track.addView(fill, FrameLayout.LayoutParams(-1, 0, Gravity.BOTTOM))
        addView(track, LinearLayout.LayoutParams(px(56), height).apply { topMargin = px(8); bottomMargin = px(8) })

        addView(TextView(context).apply {
            text = d.name.replace(" Tank", ""); textSize = 13f; setTextColor(C.MUTED); gravity = Gravity.CENTER
        })

        val update = { x: Device ->
            pct.text = if (x.percent <= 0) "Empty" else "${x.percent}%"
            val h = if (x.percent <= 0) 0 else maxOf(px(10), height * x.percent / 100)
            fill.layoutParams = (fill.layoutParams as FrameLayout.LayoutParams).apply { this.height = h }
            fill.requestLayout()
        }
        update(d)
        updaters[d.key] = update
    }

    // ---- lights & systems tiles -------------------------------------------

    private fun shortName(d: Device) = d.name
        .replace("Electric Water Heater", "Water Heater\n(electric)")
        .replace("Gas Water Heater", "Water Heater\n(gas)")

    private fun tileCard(title: String, items: List<Device>): View = card(title) {
        items.chunked(2).forEachIndexed { r, pair ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { i, d ->
                row.addView(tile(d), LinearLayout.LayoutParams(0, px(92), 1f).apply { if (i > 0) leftMargin = px(10) })
            }
            if (pair.size == 1) row.addView(View(context), LinearLayout.LayoutParams(0, px(92), 1f).apply { leftMargin = px(10) })
            addView(row, LinearLayout.LayoutParams(-1, -2).apply { if (r > 0) topMargin = px(10) })
        }
    }

    private fun tile(d: Device): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(14), px(10), px(14), px(10))
        isClickable = true

        val name = TextView(context).apply {
            text = shortName(d); textSize = 15f; maxLines = 2
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val state = TextView(context).apply { textSize = 12f; letterSpacing = 0.1f; setPadding(0, px(4), 0, 0) }
        addView(name); addView(state)

        setOnClickListener { gateway.setOn(d, !d.shownOn) }

        val update = { x: Device ->
            val on = x.shownOn
            background = if (on) rounded(C.ON, 14) else rounded(C.CARD_HI, 14, C.LINE)
            name.setTextColor(if (on) C.ON_TEXT else C.TEXT)
            state.setTextColor(if (on) C.ON_TEXT else C.MUTED)
            state.text = when {
                !x.online -> "OFFLINE"
                x.pendingOn != null -> if (on) "TURNING ON…" else "TURNING OFF…"
                on -> "ON"
                else -> "OFF"
            }
            alpha = if (x.online) 1f else 0.45f
            isEnabled = x.online
        }
        update(d)
        updaters[d.key] = update
    }

    // ---- awning ------------------------------------------------------------

    private fun motionText(status: Int) = when (status and 0x0F) {
        2 -> "Extending…"
        3 -> "Retracting…"
        else -> "Stopped"
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun motorCard(d: Device): View = card(d.name.uppercase()) {
        val state = TextView(context).apply { textSize = 14f; setTextColor(C.MUTED); setPadding(0, 0, 0, px(12)) }
        addView(state)

        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        fun holdButton(label: String, motion: Commands.Motion) = TextView(context).apply {
            text = label; textSize = 17f; gravity = Gravity.CENTER; setTextColor(C.TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = rounded(C.CARD_HI, 14, C.LINE)
            setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.background = rounded(C.ON, 14); (v as TextView).setTextColor(C.ON_TEXT)
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                        gateway.startMotor(d, motion)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.background = rounded(C.CARD_HI, 14, C.LINE); (v as TextView).setTextColor(C.TEXT)
                        gateway.stopMotor()
                    }
                }
                true
            }
        }
        row.addView(holdButton("Retract", Commands.Motion.RETRACT), LinearLayout.LayoutParams(0, px(76), 1f))
        row.addView(holdButton("Extend", Commands.Motion.EXTEND), LinearLayout.LayoutParams(0, px(76), 1f).apply { leftMargin = px(10) })
        addView(row)

        addView(TextView(context).apply {
            text = "Hold to move · release to stop"; textSize = 12f; setTextColor(C.MUTED)
            gravity = Gravity.CENTER; setPadding(0, px(10), 0, 0)
        }, ViewGroup.LayoutParams(-1, -2))

        val update = { x: Device ->
            val t = motionText(x.motorStatus)
            state.text = t
            state.setTextColor(if (t == "Stopped") C.MUTED else C.ON)
        }
        update(d)
        updaters[d.key] = update
    }
}
