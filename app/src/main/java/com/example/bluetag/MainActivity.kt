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
import android.location.LocationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var txtStatus: TextView
    private lateinit var txtRegistered: TextView
    private lateinit var txtRssi: TextView

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
    private var isScanning = false // ป้องกันการสั่ง Scan ซ้อนทับกัน

    private var findKeyGatt: BluetoothGatt? = null
    private var autoSearchGatt: BluetoothGatt? = null

    companion object {
        private const val TAG_NAME = "BLUETAG"
        private const val SERVICE_UUID = "12345678-1234-1234-1234-1234567890ab"
    }

    // ===== ปรับค่าให้เสถียร =====
    private val enterThreshold = -75      // เข้าใกล้กว่านี้ = ดัง
    private val exitThreshold = -88       // ห่างกว่านี้ = รีเซ็ตให้พร้อมดังใหม่
    private val lostTimeoutMs = 4500L     // ถ้าหายไป 4.5 วิ = สัญญาณหลุด
    private val triggerCooldownMs = 4000L // พักหลังดังเสร็จ 4 วิ
    private val rssiUiIntervalMs = 2000L  // อัปเดตเลข RSSI บนจอสม่ำเสมอขึ้น
    // =================================

    private var latestRssi = -127
    private var lastSeenMs = 0L
    private var lastTriggerMs = 0L
    private var lastRssiUiMs = 0L

    private var tagInRange = false
    private var autoSearchArmed = true
    private var locationDialogShowing = false

    private val mainHandler = Handler(Looper.getMainLooper())

    private val proximityWatcher = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()

            if ((autoSearch || isMonitoring) && !isFindingKey) {
                val lost = (now - lastSeenMs) > lostTimeoutMs

                if (lost && registeredAddress != null) {
                    if (tagInRange) {
                        tagInRange = false
                    }
                    // สำคัญ: บังคับให้พร้อมเตือนใหม่เสมอเมื่อสัญญาณหลุด
                    autoSearchArmed = true

                    txtRssi.text = "RSSI: -"
                    txtStatus.text = if (autoSearch) "Status: Tag out of range" else "Status: Monitoring..."
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

        btnRegister = findViewById(R.id.btnRegister)
        btnSearch = findViewById(R.id.btnSearch)
        btnFindKey = findViewById(R.id.btnFindKey)
        btnUnpair = findViewById(R.id.btnUnpair)

        prefs = getSharedPreferences("bluetag_prefs", MODE_PRIVATE)
        registeredAddress = prefs.getString("registered_address", null)
        registeredName = prefs.getString("registered_name", "Unknown Tag")

        updateRegisteredText()

        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = manager.adapter
        scanner = bluetoothAdapter.bluetoothLeScanner

        requestPermissions()
        mainHandler.post(proximityWatcher)

        btnRegister.setOnClickListener {
            if (!ensureReadyForScan(showDialog = true)) return@setOnClickListener
            resetAllStates()
            isRegistering = true
            btnSearch.text = "Start Auto Search"
            txtStatus.text = "Status: Scanning for BLUETAG..."
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
                lastSeenMs = System.currentTimeMillis() // รีเซ็ตเวลาเริ่มต้น
                btnSearch.text = "Stop Auto Search"
                txtStatus.text = "Status: Auto search ON"
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
            resetAllStates()
            prefs.edit().clear().apply()
            registeredAddress = null
            updateRegisteredText()
            txtStatus.text = "Status: Unpaired"
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
        stopScan()
        closeAutoGatt()
        closeFindKeyGatt()
    }

    @SuppressLint("MissingPermission")
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

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        try { scanner?.stopScan(scanCallback) } catch (e: Exception) {}
        isScanning = false
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
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
                    txtStatus.text = "Status: Tag Registered!"
                    alert()
                    isRegistering = false
                    stopScan()
                    startMonitoringIfNeeded()
                }
                return
            }

            if (address != registeredAddress) return

            lastSeenMs = now
            latestRssi = rssi
            updateRssiUi(now)

            if (autoSearch && !isFindingKey && !isAutoConnecting) {
                if (rssi >= enterThreshold) {
                    if (!tagInRange) tagInRange = true

                    val cooldownPassed = (now - lastTriggerMs) >= triggerCooldownMs
                    if (autoSearchArmed && cooldownPassed) {
                        autoSearchArmed = false
                        isAutoConnecting = true
                        lastTriggerMs = now
                        txtStatus.text = "Status: Tag nearby! Alerting..."

                        // หยุดสแกนชั่วคราวขณะเชื่อมต่อ
                        stopScan()

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            device.connectGatt(this@MainActivity, false, autoGattCallback, BluetoothDevice.TRANSPORT_LE)
                        } else {
                            device.connectGatt(this@MainActivity, false, autoGattCallback)
                        }
                    }
                } else if (rssi <= exitThreshold) {
                    if (tagInRange) {
                        tagInRange = false
                        autoSearchArmed = true // รีเซ็ตให้พร้อมดังใหม่เมื่อเดินห่างออกมาแล้ว
                        txtStatus.text = "Status: Moved away (Armed)"
                    }
                }
            }
        }
    }

    private val autoGattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                runOnUiThread { alert() }

                // ตัดการเชื่อมต่อหลังจากดังไป 500ms เพื่อให้บอร์ดกลับไปปล่อยสัญญาณ
                Handler(Looper.getMainLooper()).postDelayed({
                    try { gatt?.disconnect() } catch (e: Exception) {}
                }, 500)

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                try { gatt?.close() } catch (e: Exception) {}
                autoSearchGatt = null
                isAutoConnecting = false

                // กลับมาเริ่มสแกนใหม่ทันทีที่หลุด
                runOnUiThread {
                    if (autoSearch) {
                        // 🟢 เพิ่มบรรทัดนี้: รีเซ็ตให้พร้อมดังใหม่เสมอแม้ยืนอยู่กับที่! 🟢
                        autoSearchArmed = true

                        txtStatus.text = "Status: Resuming Auto Search..."
                        startScan()
                    } else {
                        startMonitoringIfNeeded()
                    }
                }
            }
        }
    }

    private fun updateRssiUi(now: Long) {
        if (now - lastRssiUiMs >= rssiUiIntervalMs) {
            txtRssi.text = "RSSI: $latestRssi dBm"
            lastRssiUiMs = now
        }
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

    @SuppressLint("MissingPermission")
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

    @SuppressLint("MissingPermission")
    private fun stopFindKey() {
        isFindingKey = false
        btnFindKey.text = "FIND KEY"
        closeFindKeyGatt()
        if (autoSearch) startScan() else startMonitoringIfNeeded()
    }

    private val findKeyCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                runOnUiThread { txtStatus.text = "Status: Tag Ringing!" }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                try { gatt?.close() } catch (e: Exception) {}
                findKeyGatt = null
                if (isFindingKey) runOnUiThread { stopFindKey() }
            }
        }
    }

    private fun updateRegisteredText() {
        txtRegistered.text = if (registeredAddress == null) "Registered Tag: None"
        else "Registered Tag: $registeredName\n($registeredAddress)"
    }

    private fun startMonitoringIfNeeded() {
        if (registeredAddress != null && !autoSearch && !isFindingKey) {
            isMonitoring = true
            txtStatus.text = "Status: Monitoring..."
            startScan()
        }
    }

    private fun stopMonitoring() {
        isMonitoring = false
        stopScan()
    }

    @SuppressLint("MissingPermission")
    private fun closeAutoGatt() {
        try { autoSearchGatt?.disconnect() } catch (e: Exception) {}
        try { autoSearchGatt?.close() } catch (e: Exception) {}
        autoSearchGatt = null
    }

    @SuppressLint("MissingPermission")
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