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
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.cloudpos.OperationListener
import com.cloudpos.OperationResult
import com.cloudpos.POSTerminal
import com.cloudpos.extboard.ExtBoardDevice
import com.cloudpos.sdk.impl.DeviceName

class PulseDinDemoActivity : ComponentActivity() {

    private var TAG = "PulseDinDemoActivity"
    private var extBoardDevice: ExtBoardDevice? = null
    private lateinit var btnTrigger: Button
    private lateinit var btnListen: Button
    private lateinit var tvDinState: TextView
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
        extBoardDevice =
            POSTerminal.getInstance(this).getDevice(DeviceName.EXT_BOARD) as? ExtBoardDevice
        initViews()
        initListeners()
    }

    private fun initViews() {
        btnTrigger = findViewById(R.id.btnTrigger)
        btnListen = findViewById(R.id.btnListen)
        tvDinState = findViewById(R.id.tvDinState)
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
                triggerPulse(port, voltage, duration, interval, count)
            }
        }

        btnListen.setOnClickListener {
            listenForDin()
            startPollingDin()
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
                extBoardDevice?.open()

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
                extBoardDevice?.open()

                extBoardDevice?.triggerPulse(port, voltage, duration, interval, count)

                log("Pulse → port=$port vol=$voltage dur=$duration int=$interval count=$count")

            } catch (e: Exception) {
                log("Pulse error: ${e.message}")
            } finally {
                extBoardDevice?.close()
            }
        }.start()
    }

    private fun startPollingDin() {
        Thread {
            try {
                log("Opening device for DIN polling...")
                extBoardDevice?.open()

                val timeout = 5000L
                val startTime = System.currentTimeMillis()

                var lastState = -1

                log("👂 Start polling DIN...")

                while (System.currentTimeMillis() - startTime < timeout) {

                    val din = extBoardDevice?.readDIN(0) ?: -1

                    if (din != lastState) {
                        lastState = din

                        runOnUiThread {
                            tvDinState.text = "DIN State: $din"
                            log("DIN changed: $din")
                        }
                    }

                    Thread.sleep(50)
                }

                log("⏳ Polling finished (timeout)")

            } catch (e: Exception) {
                log("❌ Polling error: ${e.message}")
            } finally {
                extBoardDevice?.close()
                log("Device closed after polling")
            }
        }.start()
    }

    private fun listenForDin() {
        Thread {
            try {
                log("Opening device for DIN listening...")
                extBoardDevice?.open()

                log("👂 Start listening for DIN...")

                extBoardDevice?.listenForRead(
                    100,
                    object : OperationListener {
                        override fun handleResult(result: OperationResult) {

                            val code = result.resultCode

                            runOnUiThread {
                                tvDinState.text = "DIN Triggered: $code"
                                log("⚡ DIN event received: $code")
                            }
                        }
                    },
                    30
                )

                log("⏳ Listening finished or timeout reached")

            } catch (e: Exception) {
                log("❌ Listen error: ${e.message}")
            } finally {
                extBoardDevice?.close()
                log("Device closed after listening")
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
        FileLogger.log(this@PulseDinDemoActivity , TAG , message = message)
    }
}
