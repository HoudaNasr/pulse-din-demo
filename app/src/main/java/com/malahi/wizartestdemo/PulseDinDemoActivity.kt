package com.malahi.wizartestdemo

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.cloudpos.DeviceException
import com.cloudpos.OperationListener
import com.cloudpos.OperationResult
import com.cloudpos.POSTerminal
import com.cloudpos.extboard.ExtBoardDevice
import com.cloudpos.extboard.ExtBoardOperationResult
import com.cloudpos.sdk.impl.DeviceName


class PulseDinDemoActivity : ComponentActivity() {

    private var TAG = "PulseDinDemoActivity"
    private var extBoardDevice: ExtBoardDevice? = null
    private lateinit var btnTrigger: Button
    private lateinit var btnListen: Button
    private lateinit var btnReadDin: Button
    private lateinit var tvDinStatePort3: TextView
    private lateinit var tvDinStatePort4: TextView
    private lateinit var tvLog: TextView
    private val manageAllFilesAccessLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    Log.d("StoragePermission", "All files access GRANTED")
                } else {
                    Log.e("StoragePermission", "All files access DENIED")
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pulse_din_demo)
        initViews()
        initDevice()
        initListeners()
    }

    override fun onDestroy() {
        super.onDestroy()
        closePort()
    }

    private fun initDevice() {
        if (extBoardDevice == null) {
            extBoardDevice =
                POSTerminal.getInstance(this).getDevice(DeviceName.EXT_BOARD) as? ExtBoardDevice
            try {
                extBoardDevice?.open()
                log("Device opened successfully.")
                Toast.makeText(this, "Device opened successfully.", Toast.LENGTH_LONG).show()
            } catch (e: DeviceException) {
                e.printStackTrace()
                Toast.makeText(this, "DeviceException" + e.message, Toast.LENGTH_LONG).show()
                log("Failed to open device: " + e.message)
            } catch (e: java.lang.Exception) {
                e.printStackTrace()
                Toast.makeText(this, "Exception " + e.message, Toast.LENGTH_LONG).show()
                log("Unexpected error while opening device: " + e.message)
            }
        }
    }

    private fun closePort() {
        extBoardDevice?.close()
    }

    private fun initViews() {
        btnTrigger = findViewById(R.id.btnTrigger)
        btnListen = findViewById(R.id.btnListen)
        btnReadDin = findViewById(R.id.btnReadDin)
        tvDinStatePort3 = findViewById(R.id.tvDinStatePort3)
        tvDinStatePort4 = findViewById(R.id.tvDinStatePort4)
        tvLog = findViewById(R.id.tvLog)
    }

    private fun initListeners() {

        btnTrigger.setOnClickListener {
            val port = getSelectedPort()
            val voltage = getVoltage()
            val duration = getInt(findViewById(R.id.etDuration), 200)
            val interval = getInt(findViewById(R.id.etInterval), 300)
            val count = getInt(findViewById(R.id.etPulseCount), 1)

            if (isRelay()) {
                triggerRelay(port, duration)
            } else {
                startPollingDinPort3()
                startPollingDinPort4()
                Thread.sleep(100)
                triggerPulse(port, voltage, duration, interval, count)
            }
        }

        btnListen.setOnClickListener {
//            listenForDin()
//            waitForDinEvent()

        }

        btnReadDin.setOnClickListener {
//            startPollingDinPort3()
//            startPollingDinPort4()
        }
    }

    private fun getSelectedPort(): Int {
        return if (findViewById<RadioButton>(R.id.port0).isChecked) 0 else 1
    }

    private fun getVoltage(): Int {
        return if (findViewById<RadioButton>(R.id.vol0).isChecked) 0 else 1
    }

    private fun isRelay(): Boolean {
        return findViewById<RadioButton>(R.id.radioRelay).isChecked
    }

    private fun getInt(et: EditText, def: Int): Int {
        val value = et.text.toString()
        return if (value.isEmpty()) def else value.toInt()
    }


    private fun triggerRelay(port: Int, duration: Int) {
        Thread {
            try {


                extBoardDevice?.triggerRelay(port, duration, 0, 1)

                log("Relay → port=$port duration=$duration")

            } catch (e: Exception) {
                log("Relay error: ${e.message}")
            } finally {
                extBoardDevice?.close()
            }
        }.start()
    }

    private fun triggerPulse(
        port: Int,
        voltage: Int,
        duration: Int,
        interval: Int,
        count: Int
    ) {
        Thread {
            try {
                extBoardDevice?.triggerPulse(port, voltage, duration, interval, count)
                log("Pulse sent")

            } catch (e: DeviceException) {
                runOnUiThread {
                    Toast.makeText(this, "DeviceException" + e.message, Toast.LENGTH_LONG).show()
                }
                log("DeviceException : ${e.message}")
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Exception" + e.message, Toast.LENGTH_LONG).show()

                }

                log("Exception error: ${e.message}")
            }
        }.start()
    }


    private fun waitForDinEvent() {
        Thread {
            try {
                log("🔌 Opening device...")

                log("👂 Waiting for DIN event...")

                val result = extBoardDevice?.waitForRead(100, 30)

                val code = result?.resultCode ?: -999
                val data = result?.data

                runOnUiThread {
                    if (code < 0) {
                        tvDinStatePort3.text = "❌ Error: $code"
                        log("❌ waitForRead error: $code")
                    } else {
                        tvDinStatePort3.text = "✅ DIN Triggered: (code=$code) (data=$data)"

                        if (data != null && data.isNotEmpty()) {

                            val hex = data.joinToString(" ") { "%02X".format(it) }

                            val dinValue = data.getOrNull(data.size - 3)?.toInt() ?: -1

                            runOnUiThread {
                                tvDinStatePort3.text = "DIN RAW: $hex\nDIN Value: $dinValue"
                                log("DIN RAW: $hex")
                                log("DIN Value: $dinValue")
                            }
                        }
                    }
                }

            } catch (e: Exception) {
                runOnUiThread {
                    tvDinStatePort3.text = "❌ Exception: ${e.message}"
                    log("❌ Exception: ${e.message}")
                }
            }
        }.start()
    }

    private fun startPollingDinPort3() {
        Thread {
            try {
                log("Opening device for DIN Port 3 polling...")
                val timeout = 60000L
                val startTime = System.currentTimeMillis()

                var lastState = -1

                log("👂 Start polling DIN Port 3...")

                while (System.currentTimeMillis() - startTime < timeout) {

                    val din = extBoardDevice?.readDIN(0) ?: -1
                    if (din != lastState) {
                        log("DIN Port 3 value before : $lastState")
                        lastState = din

                        runOnUiThread {
                            tvDinStatePort3.text = "DIN Port 3 State: $din"
                            log("DIN Port 3 changed: $din")
                        }

                        // Detect machine response
                        if (din == 0) {
                            log("✅ Machine responded! Port 3  signal received")
                        }
                    }

                    Thread.sleep(10)
                }

                log("⏳ Polling Port 3 finished (timeout)")

            } catch (e: Exception) {
                log("❌ Polling Port 3 error: ${e.message}")
            }
        }.start()
    }

    private fun startPollingDinPort4() {
        Thread {
            try {
                log("Opening device for DIN Port 4 polling...")
                val timeout = 60000L
                val startTime = System.currentTimeMillis()

                var lastState = -1

                log("👂 Start polling DIN Port 4...")

                while (System.currentTimeMillis() - startTime < timeout) {

                    val din = extBoardDevice?.readDIN(1) ?: -1

                    if (din != lastState) {
                        log("DIN Port 4 value before : $lastState")
                        lastState = din

                        runOnUiThread {
                            tvDinStatePort4.text = "DIN Port 4 State: $din"
                            log("DIN Port 4 changed: $din")
                        }

                        // Detect machine response
                        if (din == 0) {
                            log("✅ Machine responded!  Port 4 signal received")
                        }
                    }

                    Thread.sleep(10)
                }

                log("⏳ Polling Port 4 finished (timeout)")

            } catch (e: Exception) {
                log("❌ Polling Port 4 error: ${e.message}")
            }
        }.start()
    }

    private fun listenForDin() {
        Thread {
            try {
                log("Opening device for DIN listening...")

                log("👂 Start listening for DIN...")

                extBoardDevice?.listenForRead(
                    100,
                    object : OperationListener {
                        override fun handleResult(result: OperationResult) {
                            val extResult = result as ExtBoardOperationResult?
                            val code = result.resultCode

                            runOnUiThread {
                                tvDinStatePort3.text = "DIN Triggered: $code"
                                log("⚡ DIN event received: $code")
                            }
                        }
                    },
                    30
                )

                log("⏳ Listening finished or timeout reached")

            } catch (e: Exception) {
                log("❌ Listen error: ${e.message}")
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !Environment.isExternalStorageManager()
        ) {
            requestAllFilesAccess()
        }
    }

    fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
                ).apply {
                    data = Uri.fromParts(
                        "package",
                        `package`,
                        null
                    )
                }
                manageAllFilesAccessLauncher.launch(intent)

            } catch (e: Exception) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                )
                manageAllFilesAccessLauncher.launch(intent)
            }
        }
    }


    private fun log(message: String) {
        runOnUiThread {
            tvLog.append("\n$message")

            val scrollView = tvLog.parent as? ScrollView
            scrollView?.post {
                scrollView.fullScroll(View.FOCUS_DOWN)
            }
        }
        logFile(message = message)
    }

    private fun logFile(message: String) {
        FileLogger.log(this@PulseDinDemoActivity, TAG, message = message)
    }
}
