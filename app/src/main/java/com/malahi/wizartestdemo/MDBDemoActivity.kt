package com.malahi.wizartestdemo

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.text.Spannable
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.cloudpos.DeviceException
import com.cloudpos.POSTerminal
import com.cloudpos.extboard.ExtBoardDevice
import com.cloudpos.sdk.impl.DeviceName


class MDBDemoActivity : ComponentActivity() {

    private var TAG = "MDBDemoActivity"
    private var handler: Handler? = null
    private var callBack: HandleCallBack? = null
    private var extBoardDevice: ExtBoardDevice? = null
    private lateinit var btnVendRequest: Button
    private lateinit var btnLockScreen: Button
    private lateinit var btnUnLockScreen: Button
    private lateinit var btnVendCancel: Button
    private lateinit var tvMdbStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var tvMessage: TextView


    private val handleCallBack = Handler.Callback { msg: Message? ->
        when (msg!!.what) {
            HandleCallbackImpl.SUCCESS_CODE -> setTextcolor(msg.obj.toString(), Color.BLUE)
            HandleCallbackImpl.ERROR_CODE -> setTextcolor(msg.obj.toString(), Color.RED)
            else -> setTextcolor(msg.obj.toString(), Color.BLACK)
        }
        false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mdb_demo_layout)
        initViews()
        initDevice()
        initListeners()
    }

    override fun onDestroy() {
        super.onDestroy()
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

    private fun initViews() {
        handler = Handler(handleCallBack)
        callBack = HandleCallbackImpl(this, handler)

        btnVendRequest = findViewById(R.id.btnVendRequest)
        btnLockScreen = findViewById(R.id.btnLockScreen)
        btnUnLockScreen = findViewById(R.id.btnUnLockScreen)
        btnVendCancel = findViewById(R.id.btnVendCancel)
        tvLog = findViewById(R.id.tvLog)
        tvMdbStatus = findViewById(R.id.tvMdbStatus)
        tvMessage = findViewById(R.id.messageTextView)

        tvMessage.setText("")
        showSnInfo()
    }

    private fun initListeners() {
        btnVendRequest.setOnClickListener {

        }
        btnVendCancel.setOnClickListener {

        }
        btnLockScreen.setOnClickListener {
            startLockTask()
            callBack!!.sendResponse("\nstart lock task\n")
        }
        btnLockScreen.setOnClickListener {
            stopLockTask()
            callBack!!.sendResponse("\nstop lock task success\n")
        }
    }

    private fun showSnInfo() {
        val roSerialNo = getSystemProperty("ro.serialno")

        callBack!!.sendResponse(safe(roSerialNo))
    }


    private fun safe(s: String?): String {
        var s = s
        if (s == null) return ""
        s = s.trim { it <= ' ' }
        if (s.isEmpty()) return ""
        return s
    }

    private fun setTextcolor(msg: String?, color: Int) {
        val span = Spannable.Factory.getInstance().newSpannable(msg)
        val colorSpan = ForegroundColorSpan(color)
        span.setSpan(colorSpan, 0, span.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        tvMessage.append(span)
    }

    override fun onResume() {
        super.onResume()
    }


    private fun getBuildSerial(): String? {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                return Build.getSerial() // may throw SecurityException / return UNKNOWN
            } else {
                return Build.SERIAL
            }
        } catch (t: Throwable) {
            return "ERROR/NO_PERMISSION"
        }
    }

    private fun getSystemProperty(key: String?): String? {
        try {
            val sp = Class.forName("android.os.SystemProperties")
            val get = sp.getMethod("get", String::class.java, String::class.java)
            return get.invoke(null, key, "") as String?
        } catch (t: Throwable) {
            return ""
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
        FileLogger.log(this@MDBDemoActivity, TAG, message = message)
    }
}
