package com.example.bluetag

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.LocationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    private lateinit var txtStatus: TextView
    private lateinit var txtRegistered: TextView
    private lateinit var txtRssi: TextView

    private lateinit var txtLastLocation: TextView
    private lateinit var btnOpenMap: Button

    private lateinit var btnRegister: Button
    private lateinit var btnSearch: Button
    private lateinit var btnFindKey: Button
    private lateinit var btnUnpair: Button

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var scanner: BluetoothLeScanner? = null
    private lateinit var prefs: SharedPreferences

    private var registeredAddress: String? = null
    private var registeredName: String? = null

    private var isRegistering = false
    private var autoSearch = false
    private var isMonitoring = false
    private var isAutoConnecting = false
    private var isFindingKey = false
    private var isScanning = false

    private var findKeyGatt: BluetoothGatt? = null
    private var autoSearchGatt: BluetoothGatt? = null

    companion object {
        private const val TAG_NAME = "BLUETAG"
        private const val SERVICE_UUID = "12345678-1234-1234-1234-1234567890ab"
    }

    private val enterThreshold = -75
    private val exitThreshold = -88
    private val lostTimeoutMs = 5000L
    private val triggerCooldownMs = 4000L
    private val rssiUiIntervalMs = 2000L

    private val separationDelayMs = 30000L
    private var isLostNotified = false
    private var lastScanRestartMs = 0L

    private var latestRssi = -127
    private var lastSeenMs = 0L
    private var lastTriggerMs = 0L
    private var lastRssiUiMs = 0L

    private var tagInRange = false
    private var autoSearchArmed = true
    private var locationDialogShowing = false

    private var savedLat: Float = 0f
    private var savedLng: Float = 0f

    private val mainHandler = Handler(Looper.getMainLooper())

    private val proximityWatcher = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()

            if ((autoSearch || isMonitoring) && !isFindingKey) {
                val timeSinceLastSeen = now - lastSeenMs
                val lost = timeSinceLastSeen > lostTimeoutMs

                if (lost && registeredAddress != null) {
                    if (tagInRange) {
                        tagInRange = false
                        saveLastLocation()
                    }
                    autoSearchArmed = true

                    txtRssi.text = "- dBm  ▯▯▯▯▯"

                    txtStatus.text = "Disconnected"
                    txtStatus.setTextColor(Color.parseColor("#F44336"))

                    if (timeSinceLastSeen > separationDelayMs && !isLostNotified && (autoSearch || isMonitoring)) {
                        showLostNotification()
                        isLostNotified = true
                    }

                    if (autoSearch && (now - lastScanRestartMs > 10000L)) {
                        lastScanRestartMs = now
                        stopScan()
                        startScan()
                    }
                }

                if (isAutoConnecting && (now - lastTriggerMs > 8000L)) {
                    isAutoConnecting = false
                    closeAutoGatt()
                    if (autoSearch) {
                        txtStatus.text = "Searching..."
                        txtStatus.setTextColor(Color.parseColor("#FFD700"))
                        startScan()
                    }
                }
            }
            mainHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        txtStatus = findViewById(R.id.txtStatus)
        txtRegistered = findViewById(R.id.txtRegistered)
        txtRssi = findViewById(R.id.txtRssi)

        txtLastLocation = findViewById(R.id.txtLastLocation)
        btnOpenMap = findViewById(R.id.btnOpenMap)

        btnRegister = findViewById(R.id.btnRegister)
        btnSearch = findViewById(R.id.btnSearch)
        btnFindKey = findViewById(R.id.btnFindKey)
        btnUnpair = findViewById(R.id.btnUnpair)

        prefs = getSharedPreferences("bluetag_prefs", MODE_PRIVATE)
        registeredAddress = prefs.getString("registered_address", null)
        registeredName = prefs.getString("registered_name", "Unknown Tag")

        updateRegisteredText()
        loadSavedLocationUi()

        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = manager.adapter
        scanner = bluetoothAdapter.bluetoothLeScanner

        requestPermissions()
        lastSeenMs = System.currentTimeMillis()
        mainHandler.post(proximityWatcher)

        startMonitoringIfNeeded()

        btnRegister.setOnClickListener {
            if (!ensureReadyForScan(showDialog = true)) return@setOnClickListener
            resetAllStates()
            isRegistering = true
            btnSearch.text = "Start Auto Search"
            txtStatus.text = "Searching..."
            txtStatus.setTextColor(Color.parseColor("#FFD700"))
            startScan()
        }

        btnSearch.setOnClickListener {
            if (registeredAddress == null || isFindingKey) return@setOnClickListener
            if (!autoSearch) {
                if (!ensureReadyForScan(showDialog = true)) return@setOnClickListener
                stopMonitoring()
                autoSearch = true
                isRegistering = false
                autoSearchArmed = true
                tagInRange = false
                isLostNotified = false
                lastSeenMs = System.currentTimeMillis()
                lastScanRestartMs = System.currentTimeMillis()

                btnSearch.text = "Stop Auto Search"
                txtStatus.text = "Searching..."
                txtStatus.setTextColor(Color.parseColor("#FFD700"))

                startScan()
            } else {
                autoSearch = false
                btnSearch.text = "Start Auto Search"
                closeAutoGatt()

                startMonitoringIfNeeded()
            }
        }

        btnFindKey.setOnClickListener {
            if (registeredAddress == null) return@setOnClickListener
            if (!ensureReadyForScan(showDialog = true)) return@setOnClickListener
            if (!isFindingKey) startFindKey() else stopFindKey()
        }

        btnUnpair.setOnClickListener {
            if (tagInRange) {
                saveLastLocation()
            }

            resetAllStates()
            prefs.edit()
                .remove("registered_address")
                .remove("registered_name")
                .apply()

            registeredAddress = null

            updateRegisteredText()
            txtStatus.text = "Unpaired"
            txtStatus.setTextColor(Color.parseColor("#AAAAAA"))

            txtRssi.text = "- dBm  ▯▯▯▯▯"
            latestRssi = -127
        }

        btnOpenMap.setOnClickListener {
            if (savedLat != 0f && savedLng != 0f) {
                val uri = Uri.parse("geo:$savedLat,$savedLng?q=$savedLat,$savedLng(BlueTag Last Seen)")
                val intent = Intent(Intent.ACTION_VIEW, uri)
                intent.setPackage("com.google.android.apps.maps")

                if (intent.resolveActivity(packageManager) != null) {
                    startActivity(intent)
                } else {
                    val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com/?q=$savedLat,$savedLng"))
                    startActivity(webIntent)
                }
            }
        }
    }

    private fun getSignalBars(rssi: Int): String {
        return when {
            rssi == -127 -> "▯▯▯▯▯"
            rssi >= -60 -> "▮▮▮▮▮"
            rssi >= -70 -> "▮▮▮▮▯"
            rssi >= -80 -> "▮▮▮▯▯"
            rssi >= -90 -> "▮▮▯▯▯"
            else -> "▮▯▯▯▯"
        }
    }

    private fun updateRssiUi(now: Long) {
        if (now - lastRssiUiMs >= rssiUiIntervalMs) {
            if (latestRssi == -127) {
                txtRssi.text = "- dBm  ▯▯▯▯▯"
            } else {
                val bars = getSignalBars(latestRssi)
                txtRssi.text = "$latestRssi dBm  $bars"
            }
            lastRssiUiMs = now
        }
    }

    private fun updateRegisteredText() {
        txtRegistered.text = if (registeredAddress == null) "None"
        else registeredAddress
    }

    @SuppressLint("MissingPermission")
    private fun saveLastLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

            val location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

            if (location != null) {
                val lat = location.latitude.toFloat()
                val lng = location.longitude.toFloat()

                val timeFormat = SimpleDateFormat("dd MMM HH:mm", Locale.ENGLISH)
                val timeStr = timeFormat.format(Date())

                prefs.edit()
                    .putFloat("last_lat", lat)
                    .putFloat("last_lng", lng)
                    .putString("last_time", timeStr)
                    .apply()

                runOnUiThread { loadSavedLocationUi() }
            }
        }
    }

    private fun loadSavedLocationUi() {
        savedLat = prefs.getFloat("last_lat", 0f)
        savedLng = prefs.getFloat("last_lng", 0f)
        val savedTime = prefs.getString("last_time", null)

        if (savedLat != 0f && savedLng != 0f && savedTime != null) {
            txtLastLocation.text = "Last Seen: $savedTime"
            btnOpenMap.visibility = View.VISIBLE
        } else {
            txtLastLocation.text = "Last Seen: Unknown"
            btnOpenMap.visibility = View.GONE
        }
    }

    private fun resetAllStates() {
        isRegistering = false
        autoSearch = false
        isMonitoring = false
        isAutoConnecting = false
        isFindingKey = false
        tagInRange = false
        autoSearchArmed = true
        isLostNotified = false
        stopScan()
        closeAutoGatt()
        closeFindKeyGatt()
    }

    private fun startScan() {
        if (!ensureReadyForScan(false) || isScanning) return
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val filters = mutableListOf<ScanFilter>()
        if (isRegistering) {
            filters.add(ScanFilter.Builder().setServiceUuid(ParcelUuid(UUID.fromString(SERVICE_UUID))).build())
        } else if (registeredAddress != null) {
            filters.add(ScanFilter.Builder().setDeviceAddress(registeredAddress).build())
        }

        try {
            scanner?.stopScan(scanCallback)
            isScanning = true
            scanner?.startScan(filters, settings, scanCallback)
        } catch (e: Exception) {
            isScanning = false
            e.printStackTrace()
        }
    }

    private fun stopScan() {
        try { scanner?.stopScan(scanCallback) } catch (e: Exception) {}
        isScanning = false
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return
            val address = device.address
            val rssi = result.rssi
            val now = System.currentTimeMillis()

            if (isRegistering) {
                val name = result.scanRecord?.deviceName ?: ""
                if (name.contains(TAG_NAME, ignoreCase = true)) {
                    registeredAddress = address
                    registeredName = name.ifBlank { TAG_NAME }
                    prefs.edit().putString("registered_address", address).putString("registered_name", registeredName).apply()
                    updateRegisteredText()
                    txtStatus.text = "Connected"
                    txtStatus.setTextColor(Color.parseColor("#4CAF50"))
                    alert()
                    isRegistering = false
                    stopScan()
                    startMonitoringIfNeeded()
                }
                return
            }

            if (address != registeredAddress) return

            if (!tagInRange) {
                tagInRange = true
                runOnUiThread {
                    txtStatus.text = "Connected"
                    txtStatus.setTextColor(Color.parseColor("#4CAF50"))
                }
            }

            lastSeenMs = now
            latestRssi = rssi
            updateRssiUi(now)

            if (rssi >= enterThreshold) {

                runOnUiThread {
                    txtLastLocation.text = "Tag is nearby!"
                    btnOpenMap.visibility = View.GONE
                }

                if (autoSearch && !isFindingKey && !isAutoConnecting) {
                    isLostNotified = false

                    val cooldownPassed = (now - lastTriggerMs) >= triggerCooldownMs
                    if (autoSearchArmed && cooldownPassed) {
                        autoSearchArmed = false
                        isAutoConnecting = true
                        lastTriggerMs = now
                        runOnUiThread {
                            txtStatus.text = "Connected (Alerting)"
                            txtStatus.setTextColor(Color.parseColor("#4CAF50"))
                        }

                        stopScan()

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            device.connectGatt(this@MainActivity, false, autoGattCallback, BluetoothDevice.TRANSPORT_LE)
                        } else {
                            device.connectGatt(this@MainActivity, false, autoGattCallback)
                        }
                    }
                }
            } else if (rssi <= exitThreshold) {
                if (autoSearch && !isFindingKey && !isAutoConnecting) {
                    if (!autoSearchArmed) {
                        autoSearchArmed = true
                        runOnUiThread {
                            txtStatus.text = "Connected"
                            txtStatus.setTextColor(Color.parseColor("#4CAF50"))
                        }
                    }
                }
            }
        }
    }

    private val autoGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                runOnUiThread { alert() }

                Handler(Looper.getMainLooper()).postDelayed({
                    try { gatt?.disconnect() } catch (e: Exception) {}
                }, 500)

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                try { gatt?.close() } catch (e: Exception) {}
                autoSearchGatt = null
                isAutoConnecting = false

                runOnUiThread {
                    if (autoSearch) {
                        autoSearchArmed = true
                        txtStatus.text = "Searching..."
                        txtStatus.setTextColor(Color.parseColor("#FFD700"))
                        startScan()
                    } else {
                        startMonitoringIfNeeded()
                    }
                }
            }
        }
    }

    private fun showLostNotification() {
        val channelId = "bluetag_alert_channel"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                channelId, "BlueTag Alerts",
                android.app.NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }

        val notification = androidx.core.app.NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Did you forgot Bluetag?")
            .setContentText("The signal has been lost for more than 30 seconds. Please check your key!")
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(1001, notification)
        alert()
    }

    private fun alert() {
        try {
            ToneGenerator(AudioManager.STREAM_ALARM, 100).startTone(ToneGenerator.TONE_PROP_BEEP, 300)
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE))
            } else { @Suppress("DEPRECATION") vibrator.vibrate(400) }
        } catch (e: Exception) {}
    }

    private fun startFindKey() {
        stopMonitoring()
        autoSearch = false
        isAutoConnecting = false
        isFindingKey = true
        btnFindKey.text = "STOP FIND"
        stopScan()
        closeAutoGatt()

        val device = bluetoothAdapter.getRemoteDevice(registeredAddress)
        findKeyGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(this, false, findKeyCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(this, false, findKeyCallback)
        }
    }

    private fun stopFindKey() {
        isFindingKey = false
        btnFindKey.text = "FIND KEY"
        closeFindKeyGatt()
        if (autoSearch) startScan() else startMonitoringIfNeeded()
    }

    private val findKeyCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                runOnUiThread {
                    txtStatus.text = "Connected (Ringing)"
                    txtStatus.setTextColor(Color.parseColor("#4CAF50"))
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                try { gatt?.close() } catch (e: Exception) {}
                findKeyGatt = null
                if (isFindingKey) runOnUiThread { stopFindKey() }
            }
        }
    }

    private fun startMonitoringIfNeeded() {
        if (registeredAddress != null && !autoSearch && !isFindingKey) {
            isMonitoring = true
            txtStatus.text = "Searching..."
            txtStatus.setTextColor(Color.parseColor("#FFD700"))
            startScan()
        } else if (registeredAddress == null) {
            txtStatus.text = "Unpaired"
            txtStatus.setTextColor(Color.parseColor("#AAAAAA"))
        }
    }

    private fun stopMonitoring() {
        isMonitoring = false
        stopScan()
    }

    private fun closeAutoGatt() {
        try { autoSearchGatt?.disconnect() } catch (e: Exception) {}
        try { autoSearchGatt?.close() } catch (e: Exception) {}
        autoSearchGatt = null
    }

    private fun closeFindKeyGatt() {
        try { findKeyGatt?.disconnect() } catch (e: Exception) {}
        try { findKeyGatt?.close() } catch (e: Exception) {}
        findKeyGatt = null
    }

    private fun requestPermissions() {
        val perms = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        if (perms.isNotEmpty()) ActivityCompat.requestPermissions(this, perms.toTypedArray(), 1001)
    }

    private fun hasPermissions(): Boolean {
        val hasLoc = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasLoc && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else { hasLoc }
    }

    private fun isLocationEnabled(): Boolean {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return try { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) } catch (e: Exception) { false }
    }

    private fun ensureReadyForScan(showDialog: Boolean): Boolean {
        if (!hasPermissions()) { requestPermissions(); return false }
        if (!bluetoothAdapter.isEnabled) { if (showDialog) startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); return false }
        if (!isLocationEnabled()) {
            if (showDialog && !locationDialogShowing) {
                locationDialogShowing = true
                AlertDialog.Builder(this)
                    .setTitle("Location is OFF")
                    .setMessage("Please enable Location (GPS) to scan.")
                    .setPositiveButton("Turn ON") { _, _ -> locationDialogShowing = false; startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                    .setNegativeButton("Cancel") { _, _ -> locationDialogShowing = false }
                    .setOnDismissListener { locationDialogShowing = false }
                    .show()
            }
            return false
        }
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        resetAllStates()
    }
}