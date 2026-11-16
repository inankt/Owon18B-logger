package com.example.owonlogger

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*
import kotlin.math.abs
import kotlin.math.pow

class MainActivity : AppCompatActivity() {

    private val SERVICE_UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    private val CHAR_NOTIFY_UUID = UUID.fromString("0000fff4-0000-1000-8000-00805f9b34fb")
    private val CLIENT_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothGatt: BluetoothGatt? = null

    private lateinit var tvStatus: TextView
    private lateinit var tvLiveValue: TextView
    private lateinit var lvRecords: ListView
    private lateinit var adapter: ArrayAdapter<String>

    // Lưu trữ dữ liệu: Pair(Giá trị số, Đơn vị)
    private val recordedData = mutableListOf<Pair<String, String>>()
    private val displayList = mutableListOf<String>()

    // Biến phục vụ thuật toán bắt điểm chạm que đo
    private var lastRawValue: Double = Double.NaN
    private var stableCount = 0
    private var isCapturedForCurrentTouch = false
    private val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 100)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvLiveValue = findViewById(R.id.tvLiveValue)
        lvRecords = findViewById(R.id.lvRecords)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, displayList)
        lvRecords.adapter = adapter

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        findViewById<Button>(R.id.btnConnect).setOnClickListener {
            checkPermissionsAndScan()
        }

        findViewById<Button>(R.id.btnExportCsv).setOnClickListener {
            exportToCsv()
        }

        findViewById<Button>(R.id.btnClear).setOnClickListener {
            recordedData.clear()
            displayList.clear()
            adapter.notifyDataSetChanged()
        }
    }

    private fun checkPermissionsAndScan() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (permissions.any { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            ActivityCompat.requestPermissions(this, permissions, 101)
            return
        }
        startBleScan()
    }

    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        tvStatus.text = "Trạng thái: Đang tìm OWON..."
        bluetoothAdapter.bluetoothLeScanner.startScan(scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val deviceName = result.device.name ?: return
            // OWON 18B thường quảng bá tên là "BDM" hoặc chứa chữ "OWON"
            if (deviceName.contains("BDM", true) || deviceName.contains("OWON", true)) {
                bluetoothAdapter.bluetoothLeScanner.stopScan(this)
                tvStatus.text = "Trạng thái: Đang kết nối tới $deviceName..."
                bluetoothGatt = result.device.connectGatt(this@MainActivity, false, gattCallback)
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                runOnUiThread { tvStatus.text = "Trạng thái: Đã kết nối. Đang đọc dữ liệu..." }
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                runOnUiThread { tvStatus.text = "Trạng thái: Mất kết nối" }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val service = gatt.getService(SERVICE_UUID) ?: return
            val characteristic = service.getCharacteristic(CHAR_NOTIFY_UUID) ?: return
            gatt.setCharacteristicNotification(characteristic, true)

            val descriptor = characteristic.getDescriptor(CLIENT_CONFIG_UUID)
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val data = characteristic.value
            if (data != null && data.size >= 6) {
                parseOwonPacket(data)
            }
        }
    }

    /**
     * Giải mã gói tin 6-byte chuẩn giao thức OWON BLE (18B / B35T+ / B41T+)
     */
    private fun parseOwonPacket(bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val word0 = buffer.short.toInt() and 0xFFFF
        val word1 = buffer.short.toInt() and 0xFFFF
        val rawReading = buffer.short.toInt() // Signed 16-bit

        // Vị trí dấu thập phân (3 bit thấp của word0)
        val decimalPos = word0 and 0x07
        // Chế độ đo (bit 6-9 của word0)
        val functionCode = (word0 shr 6) and 0x0F
        // Tiền tố đơn vị (kilo, mega, milli, micro...)
        val scaleCode = (word1 shr 3) and 0x07

        val unit = decodeUnit(functionCode, scaleCode)

        // Kiểm tra trạng thái Overload (OL) - chưa chạm que đo ở thang Ohm/Diode/Thông mạch
        if (rawReading == 0x7FFF || rawReading == -32768 || decimalPos == 7) {
            runOnUiThread { tvLiveValue.text = "OL $unit" }
            resetProbeState()
            return
        }

        val divisor = 10.0.pow(decimalPos.toDouble())
        val actualValue = rawReading / divisor
        val formattedValue = String.format(Locale.US, "%.${decimalPos}f", actualValue)

        runOnUiThread {
            tvLiveValue.text = "$formattedValue $unit"
        }

        processProbeTouch(actualValue, formattedValue, unit)
    }

    /**
     * Thuật toán phát hiện chạm que đo:
     * - Bỏ qua khi giá trị gần 0 (chưa chạm que ở thang V/A)
     * - Khi giá trị ổn định qua 3 lần đọc liên tiếp -> Chốt số và kêu Bíp
     * - Khóa không lưu trùng lặp cho tới khi nhấc que đo ra
     */
    private fun processProbeTouch(value: Double, formattedValue: String, unit: String) {
        // Ngưỡng coi như đang hở mạch (nhấc que đo)
        val isOpenCircuit = abs(value) < 0.005

        if (isOpenCircuit) {
            resetProbeState()
            return
        }

        if (isCapturedForCurrentTouch) return

        // Kiểm tra độ ổn định (sai số dưới 0.5% so với mẫu trước đó)
        if (!lastRawValue.isNaN() && abs(value - lastRawValue) <= abs(value * 0.005) + 0.001) {
            stableCount++
        } else {
            stableCount = 0
        }
        lastRawValue = value

        // Đạt 3 lần ổn định liên tiếp (~1 giây chạm que) -> Ghi nhận kết quả
        if (stableCount >= 2) {
            isCapturedForCurrentTouch = true
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 150)

            runOnUiThread {
                val index = recordedData.size + 1
                recordedData.add(Pair(formattedValue, unit))
                displayList.add(0, "#$index:   $formattedValue $unit") // Đưa số mới nhất lên đầu
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun resetProbeState() {
        stableCount = 0
        lastRawValue = Double.NaN
        isCapturedForCurrentTouch = false
    }

    private fun decodeUnit(func: Int, scale: Int): String {
        val prefix = when (scale) {
            1 -> "n"
            2 -> "u"
            3 -> "m"
            5 -> "k"
            6 -> "M"
            else -> ""
        }
        val base = when (func) {
            0, 1 -> "V"
            2, 3 -> "A"
            4 -> "Ohm"
            5 -> "F"
            6 -> "Hz"
            7 -> "%"
            8 -> "C"
            9 -> "F"
            10 -> "V (Diode)"
            11 -> "Ohm (Cont)"
            else -> ""
        }
        return "$prefix$base"
    }

    /**
     * Xuất file CSV vào thư mục Downloads của điện thoại
     * Cấu trúc: STT, Gia_tri, Don_vi (Không thời gian, không biểu đồ)
     */
    private fun exportToCsv() {
        if (recordedData.isEmpty()) {
            Toast.makeText(this, "Chưa có dữ liệu để xuất!", Toast.LENGTH_SHORT).show()
            return
        }

        val csvBuilder = StringBuilder()
        csvBuilder.append("STT,Gia_tri,Don_vi\n")
        recordedData.forEachIndexed { index, pair ->
            csvBuilder.append("${index + 1},${pair.first},${pair.second}\n")
        }

        val fileName = "OWON_Data_${System.currentTimeMillis() % 10000}.csv"

        try {
            val contentValues = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
            }

            val resolver = contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            uri?.let {
                val outputStream: OutputStream? = resolver.openOutputStream(it)
                outputStream?.write(csvBuilder.toString().toByteArray())
                outputStream?.close()
                Toast.makeText(this, "Đã lưu vào Downloads/$fileName", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Lỗi xuất file: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
