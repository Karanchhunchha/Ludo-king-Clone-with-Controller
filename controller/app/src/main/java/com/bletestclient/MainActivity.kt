package com.bletestclient

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bletestclient.ble.BleClient
import com.bletestclient.ble.BleClientListener
import com.bletestclient.databinding.ActivityMainBinding
import com.bletestclient.ui.DiceView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), BleClientListener {

    private lateinit var binding: ActivityMainBinding
    private var bleClient: BleClient? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val prefs by lazy { getSharedPreferences("controller_prefs", Context.MODE_PRIVATE) }

    // Order per user request: Blue first, then Red, Green, Yellow.
    enum class LudoColor(val key: String, val displayName: String, val hexColor: String) {
        BLUE("BLUE", "Blue", "#3B7DDD"),
        RED("RED", "Red", "#E5484D"),
        GREEN("GREEN", "Green", "#2FA866"),
        YELLOW("YELLOW", "Yellow", "#F0B429")
    }

    data class DeviceEntry(var name: String, val address: String, var rssi: Int, var isLudoHost: Boolean)

    private val deviceMap = HashMap<String, DeviceEntry>()
    private var listRefreshPending = false

    private var selectedColor: LudoColor = LudoColor.BLUE
    private var isConnected: Boolean = false
    private var isScanning: Boolean = false
    private var pendingAddress: String? = null
    private var pendingName: String? = null

    private val rssiPoller = object : Runnable {
        override fun run() {
            bleClient?.readRssi()
            mainHandler.postDelayed(this, 3000)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val denied = results.filterValues { !it }.keys
        if (denied.isEmpty()) {
            startScan()
        } else {
            showToast("Permissions required for BLE")
        }
    }

    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            checkAndRequestPermissions()
        } else {
            showToast("Bluetooth is required")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bleClient = BleClient(this, this)

        setupColorPickers()
        setupDice()
        setupButtons()

        selectColor(LudoColor.BLUE)
        refreshQuickReconnect()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        bleClient?.stopScan()
        bleClient?.disconnect()
    }

    // ─── UI setup ────────────────────────────────────────────────────────────

    private fun setupButtons() {
        binding.btnScan.setOnClickListener {
            if (isScanning) {
                bleClient?.stopScan()
            } else if (isConnected) {
                showToast("Disconnect first to scan again")
            } else {
                checkAndRequestPermissions()
            }
        }

        binding.btnDisconnect.setOnClickListener {
            bleClient?.disconnect()
        }

        binding.btnQuick.setOnClickListener {
            val addr = prefs.getString("last_address", null) ?: return@setOnClickListener
            stopRssiPoller()
            pendingAddress = addr
            pendingName = prefs.getString("last_name", null)
            bleClient?.connectDevice(addr)
            updateConnectionUI(connected = false, title = "Connecting…", sub = addr)
        }

        binding.btnClearHistory.setOnClickListener {
            binding.layoutLogList.removeAllViews()
            binding.layoutLogList.addView(binding.tvEmptyLog)
            binding.tvEmptyLog.visibility = View.VISIBLE
        }
    }

    private fun setupColorPickers() {
        binding.btnColBlue.setOnClickListener { selectColor(LudoColor.BLUE) }
        binding.btnColRed.setOnClickListener { selectColor(LudoColor.RED) }
        binding.btnColGreen.setOnClickListener { selectColor(LudoColor.GREEN) }
        binding.btnColYellow.setOnClickListener { selectColor(LudoColor.YELLOW) }
    }

    private fun selectColor(color: LudoColor) {
        selectedColor = color

        val colorCards = listOf(
            Pair(binding.btnColBlue, LudoColor.BLUE),
            Pair(binding.btnColRed, LudoColor.RED),
            Pair(binding.btnColGreen, LudoColor.GREEN),
            Pair(binding.btnColYellow, LudoColor.YELLOW)
        )

        for ((view, c) in colorCards) {
            val isSelected = (c == color)
            view.alpha = if (isSelected) 1.0f else 0.55f
            view.scaleX = if (isSelected) 1.03f else 1.0f
            view.scaleY = if (isSelected) 1.03f else 1.0f
        }

        val stageColor = Color.parseColor(color.hexColor)
        binding.tvStageDot.setTextColor(stageColor)
        binding.tvStageTitle.text = "${color.displayName}'s dice"
        binding.tvLastAction.setTextColor(stageColor)
        binding.tvHint.text = "Tap a dice face to send ${color.displayName} + value to the game."

        val diceList = listOf(binding.die1, binding.die2, binding.die3, binding.die4, binding.die5, binding.die6)
        for (die in diceList) {
            die.pipColor = stageColor
        }
    }

    private fun setupDice() {
        val diceViews = listOf(
            Pair(binding.die1, 1),
            Pair(binding.die2, 2),
            Pair(binding.die3, 3),
            Pair(binding.die4, 4),
            Pair(binding.die5, 5),
            Pair(binding.die6, 6)
        )

        for ((die, value) in diceViews) {
            die.diceValue = value
            die.setOnClickListener {
                onDiceTapped(value)
            }
        }
    }

    private fun onDiceTapped(diceValue: Int) {
        binding.root.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

        if (!isConnected) {
            showToast("Connect to the game phone first")
            return
        }

        val cmd = "${selectedColor.key}:$diceValue"
        val success = bleClient?.sendCommand(cmd) ?: false
        if (success) {
            binding.tvLastAction.text = "Sent $cmd"
            addLogRow(cmd, selectedColor, "Pending ACK…")
        } else {
            showToast("Failed to transmit $cmd")
        }
    }

    private fun addLogRow(command: String, color: LudoColor, status: String) {
        binding.tvEmptyLog.visibility = View.GONE

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 16, 24, 16)
            setBackgroundColor(Color.parseColor("#1A1D25"))
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 8
            }
            layoutParams = params
        }

        val dot = View(this).apply {
            val dSize = (12 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(dSize, dSize).apply {
                marginEnd = 16
            }
            setBackgroundColor(Color.parseColor(color.hexColor))
        }
        row.addView(dot)

        val tvCmd = TextView(this).apply {
            text = command
            setTextColor(Color.parseColor("#F2F3F7"))
            typeface = Typeface.MONOSPACE
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(tvCmd)

        val time = timeFormat.format(Date())
        val tvStatus = TextView(this).apply {
            text = "$time • $status"
            setTextColor(Color.parseColor("#9AA0B2"))
            textSize = 12f
        }
        row.addView(tvStatus)

        binding.layoutLogList.addView(row, 0)

        // Cap history so the list never grows unbounded.
        while (binding.layoutLogList.childCount > 50) {
            binding.layoutLogList.removeViewAt(binding.layoutLogList.childCount - 1)
        }
    }

    // ─── Connection state UI ─────────────────────────────────────────────────

    private fun updateConnectionUI(connected: Boolean, title: String, sub: String) {
        isConnected = connected
        binding.tvDevName.text = title
        binding.tvDevSub.text = sub

        if (connected) {
            binding.tvBadge.text = "Connected"
            binding.tvBadge.setTextColor(Color.parseColor("#3DDC84"))
            binding.tvBadge.setBackgroundColor(Color.parseColor("#263DDC84"))
            binding.btnDisconnect.visibility = View.VISIBLE
            setDiceEnabled(true)
            refreshQuickReconnect()
        } else {
            binding.tvBadge.text = "Offline"
            binding.tvBadge.setTextColor(Color.parseColor("#FF7A59"))
            binding.tvBadge.setBackgroundColor(Color.parseColor("#33FF7A59"))
            binding.btnDisconnect.visibility = View.GONE
            binding.tvSignal.text = ""
            setDiceEnabled(false)
        }
    }

    private fun setDiceEnabled(enabled: Boolean) {
        val diceList = listOf(binding.die1, binding.die2, binding.die3, binding.die4, binding.die5, binding.die6)
        for (die in diceList) {
            die.isEnabled = enabled
        }
    }

    private fun setScanningUI(scanning: Boolean) {
        isScanning = scanning
        binding.btnScan.text = if (scanning) "Stop scan" else "Scan devices"
        if (scanning) {
            binding.tvBadge.text = "Scanning"
            binding.tvBadge.setTextColor(Color.parseColor("#9FC4F5"))
            binding.tvBadge.setBackgroundColor(Color.parseColor("#263B7DDD"))
        } else if (!isConnected) {
            binding.tvBadge.text = "Offline"
            binding.tvBadge.setTextColor(Color.parseColor("#FF7A59"))
            binding.tvBadge.setBackgroundColor(Color.parseColor("#33FF7A59"))
        }
    }

    private fun refreshQuickReconnect() {
        val addr = prefs.getString("last_address", null)
        val name = prefs.getString("last_name", null)
        if (addr != null && !isConnected) {
            binding.btnQuick.visibility = View.VISIBLE
            binding.btnQuick.text = "↻ Reconnect ${name ?: addr.takeLast(17)}"
        } else {
            binding.btnQuick.visibility = View.GONE
        }
    }

    // ─── Device list ─────────────────────────────────────────────────────────

    private fun scheduleListRefresh() {
        if (listRefreshPending) return
        listRefreshPending = true
        mainHandler.postDelayed({
            listRefreshPending = false
            renderDeviceList()
        }, 300)
    }

    private fun renderDeviceList() {
        val entries = deviceMap.values
            .sortedWith(compareByDescending<DeviceEntry> { it.isLudoHost }.thenByDescending { it.rssi })
            .take(8)

        val total = deviceMap.size
        binding.tvDeviceCount.text = if (total == 0) {
            "No devices found yet…"
        } else {
            "$total found • showing strongest ${entries.size}"
        }

        binding.layoutDeviceList.removeAllViews()
        if (entries.isEmpty()) {
            val tv = TextView(this).apply {
                text = "Devices will appear here while scanning."
                setTextColor(Color.parseColor("#9AA0B2"))
                textSize = 13f
                setPadding(0, 8, 0, 8)
            }
            binding.layoutDeviceList.addView(tv)
            return
        }
        for (entry in entries) {
            binding.layoutDeviceList.addView(buildDeviceRow(entry))
        }
    }

    private fun buildDeviceRow(entry: DeviceEntry): View {
        val density = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * density).toInt(), (12 * density).toInt(), (14 * density).toInt(), (12 * density).toInt())
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E222C"))
                cornerRadius = 14 * density
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (8 * density).toInt()
            }
            isClickable = true
            isFocusable = true
        }

        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val tvName = TextView(this).apply {
            text = entry.name.ifEmpty { "Unnamed device" }
            setTextColor(Color.parseColor("#F2F3F7"))
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleRow.addView(tvName)

        if (entry.isLudoHost) {
            val badge = TextView(this).apply {
                text = "LUDO"
                setTextColor(Color.parseColor("#3DDC84"))
                textSize = 10f
                setTypeface(typeface, Typeface.BOLD)
                setPadding((8 * density).toInt(), (2 * density).toInt(), (8 * density).toInt(), (2 * density).toInt())
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#263DDC84"))
                    cornerRadius = 6 * density
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = (8 * density).toInt() }
            }
            titleRow.addView(badge)
        }
        left.addView(titleRow)

        val tvAddr = TextView(this).apply {
            text = entry.address
            setTextColor(Color.parseColor("#8B93A7"))
            textSize = 12f
        }
        left.addView(tvAddr)
        row.addView(left)

        val tvRssi = TextView(this).apply {
            text = "${entry.rssi} dBm ${rssiBars(entry.rssi)}"
            setTextColor(if (entry.isLudoHost) Color.parseColor("#3DDC84") else Color.parseColor("#9AA0B2"))
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.END
        }
        row.addView(tvRssi)

        row.setOnClickListener {
            binding.root.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            if (isScanning) bleClient?.stopScan()
            pendingAddress = entry.address
            pendingName = entry.name.ifEmpty { entry.address }
            deviceMap.clear()
            renderDeviceList()
            binding.tvDeviceCount.text = "Tap Scan to look for devices"
            stopRssiPoller()
            bleClient?.connectDevice(entry.address)
            updateConnectionUI(connected = false, title = "Connecting…", sub = "${entry.name.ifEmpty { entry.address }} (${entry.address})")
        }
        return row
    }

    private fun rssiBars(rssi: Int): String = when {
        rssi >= -55 -> "▂▄▆█"
        rssi >= -67 -> "▂▄▆"
        rssi >= -80 -> "▂▄"
        else -> "▂"
    }

    private fun rssiLabel(rssi: Int): String = when {
        rssi >= -55 -> "Excellent"
        rssi >= -67 -> "Good"
        rssi >= -80 -> "Fair"
        else -> "Weak"
    }

    // ─── Permissions & scan entry ────────────────────────────────────────────

    private fun checkAndRequestPermissions() {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        if (!manager.adapter.isEnabled) {
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }

        val required = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            startScan()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startScan() {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        deviceMap.clear()
        renderDeviceList()
        updateConnectionUI(connected = false, title = "Scanning…", sub = "Pick a device below when it appears")
        bleClient?.startScan(manager.adapter)
    }

    private fun startRssiPoller() {
        mainHandler.removeCallbacks(rssiPoller)
        mainHandler.postDelayed(rssiPoller, 1500)
    }

    private fun stopRssiPoller() {
        mainHandler.removeCallbacks(rssiPoller)
    }

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ─── BleClientListener Callbacks ─────────────────────────────────────────

    override fun onScanStarted() {
        runOnUiThread {
            setScanningUI(true)
        }
    }

    override fun onScanStopped() {
        runOnUiThread {
            setScanningUI(false)
            if (!isConnected) {
                updateConnectionUI(connected = false, title = "No device", sub = "${deviceMap.size} device(s) found — tap one above")
            }
        }
    }

    override fun onDeviceDiscovered(name: String, address: String, rssi: Int, isLudoHost: Boolean) {
        runOnUiThread {
            val existing = deviceMap[address]
            if (existing != null) {
                existing.rssi = rssi
                if (name.isNotEmpty()) existing.name = name
                existing.isLudoHost = existing.isLudoHost || isLudoHost
            } else {
                deviceMap[address] = DeviceEntry(name, address, rssi, isLudoHost)
            }
            scheduleListRefresh()
        }
    }

    override fun onConnecting(address: String) {
        runOnUiThread {
            updateConnectionUI(connected = false, title = "Connecting…", sub = address)
        }
    }

    override fun onConnected() {
        runOnUiThread {
            updateConnectionUI(connected = false, title = "Connecting…", sub = "Discovering services…")
        }
    }

    override fun onReadyForCommands() {
        runOnUiThread {
            updateConnectionUI(connected = true, title = "Game host connected", sub = "Ready — tap a dice face to roll")
            binding.tvSignal.text = "Signal: measuring…"
            pendingAddress?.let { addr ->
                prefs.edit()
                    .putString("last_address", addr)
                    .putString("last_name", pendingName ?: "game host")
                    .apply()
            }
            startRssiPoller()
            showToast("Connected — ready to roll!")
        }
    }

    override fun onCommandSent(command: String) {}

    override fun onResponseReceived(response: String) {
        runOnUiThread {
            binding.tvLastAction.text = "ACK: $response"
            if (binding.layoutLogList.childCount > 0) {
                val firstRow = binding.layoutLogList.getChildAt(0) as? LinearLayout
                if (firstRow != null && firstRow.childCount >= 3) {
                    val statusTv = firstRow.getChildAt(2) as? TextView
                    val time = timeFormat.format(Date())
                    statusTv?.text = "$time • $response"
                }
            }
        }
    }

    override fun onRssiUpdated(rssi: Int) {
        runOnUiThread {
            binding.tvSignal.text = "Signal: $rssi dBm • ${rssiLabel(rssi)} ${rssiBars(rssi)}"
        }
    }

    override fun onDisconnected(reason: String) {
        runOnUiThread {
            stopRssiPoller()
            updateConnectionUI(connected = false, title = "Disconnected", sub = reason)
            refreshQuickReconnect()
            showToast("Disconnected: $reason")
        }
    }

    override fun onError(message: String) {
        runOnUiThread {
            showToast(message)
            if (!isConnected) {
                binding.tvDevSub.text = message
            }
        }
    }
}
