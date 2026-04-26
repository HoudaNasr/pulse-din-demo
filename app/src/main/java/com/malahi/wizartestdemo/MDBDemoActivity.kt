package com.malahi.wizartestdemo

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.os.SystemClock
import android.text.Spannable
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.cloudpos.DeviceException
import com.cloudpos.POSTerminal
import com.cloudpos.sdk.common.SystemProperties
import com.cloudpos.serialport.SerialPortDevice
import com.malahi.wizartestdemo.Utils.ByteConvertStringUtil
import com.malahi.wizartestdemo.Utils.EnumMDBCommands
import com.malahi.wizartestdemo.Utils.FileLogger
import com.malahi.wizartestdemo.Utils.HandleCallBack
import com.malahi.wizartestdemo.Utils.HandleCallbackImpl
import com.malahi.wizartestdemo.Utils.Item
import com.malahi.wizartestdemo.Utils.MDBUtils
import com.malahi.wizartestdemo.Utils.MDBValues
import com.malahi.wizartestdemo.Utils.StmGpioInterface
import java.math.BigInteger
import java.util.Arrays
import kotlin.concurrent.Volatile


class MDBDemoActivity : ComponentActivity() {

    private var isReaderEnabled: Boolean = false
    private var TAG = "MDBDemoActivity"
    private var handler: Handler? = null
    private var callBack: HandleCallBack? = null
    private lateinit var btnVendRequest: Button
    private lateinit var btnLockScreen: Button
    private lateinit var btnUnLockScreen: Button
    private lateinit var btnVendCancel: Button
    private lateinit var tvMdbStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var tvDeviceData: TextView
    private lateinit var tvMessage: TextView
    private var serialPortDevice: SerialPortDevice? = null
    private var subModel: String? = null

    private var subThreadHandler: Handler? = null
    val SUBMODEL_Q3MINI: String = "q3mini"
    val SUBMODEL_Q3A7: String = "q3a7"
    val SUBMODEL_Q3V: String = "q3v"

    @Volatile
    private var running = true

    @Volatile
    private var open = false

    private var mdbThread: Thread? = null
    @Volatile
    private var isMdbRunning = false

    private val mdbValues: MDBValues = MDBValues()

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
        if (serialPortDevice == null) {
            try {
                serialPortDevice = POSTerminal.getInstance(this@MDBDemoActivity)
                    .getDevice("cloudpos.device.serialport") as SerialPortDevice?
                subModel = SystemProperties.get("ro.wp.product.submodel")
                log("SerialPort opened successfully. ${subModel.toString()}")
                Toast.makeText(this, "Device opened successfully.", Toast.LENGTH_LONG).show()
            } catch (e: DeviceException) {
                e.printStackTrace()
                Toast.makeText(this, "DeviceException" + e.message, Toast.LENGTH_LONG).show()
                log("Failed to open SerialPort: " + e.message)
            } catch (e: java.lang.Exception) {
                e.printStackTrace()
                Toast.makeText(this, "Exception " + e.message, Toast.LENGTH_LONG).show()
                log("Unexpected error while opening SerialPort: " + e.message)
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
        tvDeviceData = findViewById(R.id.tvDeviceData)
        tvMdbStatus = findViewById(R.id.tvMdbStatus)
        tvMessage = findViewById(R.id.messageTextView)

        tvMessage.setText("")
        showSnInfo()
    }

    private fun initListeners() {
        btnVendRequest.setOnClickListener {
            logFile("Manual start transaction")
            if (isReaderEnabled) {
                startTransaction()
            } else {
                Toast.makeText(this, "Reader isn't enabled", Toast.LENGTH_SHORT).show()
            }
        }
        btnLockScreen.setOnClickListener {
            startLockTask()
            callBack!!.sendResponse("\nstart lock task\n")
        }
        btnUnLockScreen.setOnClickListener {
            stopLockTask()
            callBack!!.sendResponse("\nstop lock task success\n")
        }
    }

    private fun showVendDialog(item: Item) {
        runOnUiThread {
            val message =
                "Item: ${item.getItemId()}\namount: ${item.getItemAmount()}\nPrice: ${item.itemPrice}"

            android.app.AlertDialog.Builder(this)
                .setTitle("Vend Request")
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton("Approve") { _, _ ->
                    logFile("User APPROVED vend")
                    sendVendApproved(item)
                }
                .setNegativeButton("Deny") { _, _ ->
                    logFile("User DENIED vend")
                    sendVendDenied()
                }
                .show()
        }
    }

    private fun showSnInfo() {
        val roSerialNo = getSystemProperty("ro.serialno")
        tvDeviceData.setText(safe(roSerialNo))
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


    ///////////MDB
    fun startMdbSafely() {
        if (isMdbRunning) {
            logFile("Already running, ignore")
            return
        }

        isMdbRunning = true

        mdbThread = Thread {
            try {
                logFile("Starting MDB...")

                // 1. Open serial ONLY ONCE
                if (!open()) {
                    logFile("Failed to open serial port")
                    isMdbRunning = false
                    return@Thread
                }
                logFile("opened serial port")

                // 2. Init handshake (ONLY ONCE)
                if (mdbValues.getCurrentVersion() == 0) {
                    sendVersionRequest()
                    readGetVersionResp()
                    logFile("MDB Version: ${mdbValues.getCurrentVersion()}")
                }

                // 3. Activate cashless (IMPORTANT)
                val version = mdbValues.getCurrentVersion()
                val supported = (isWhiteDemon() && version >= 28) ||
                        (!isWhiteDemon() && version >= 5)

                if (supported) {
                    if (mdbValues.getDefaultActiveStatus() == -1) {
                        getDefaultActiveStatus()
                    }

                    if (mdbValues.getDefaultActiveStatus() == 0) {
                        mdbValues.setDefaultActiveStatus(1)
                        setDefaultActiveStatus()
                    }

                    activeCashless(true)
                    Log.d("MDB", "Cashless activated")
                }

                // 4. Start listening loop (IMPORTANT)
                running = true
                processMdb()

            } catch (e: Exception) {
                logFile("Error in MDB thread: ${e.message}")
            } finally {
                logFile("MDB thread ended")
                isMdbRunning = false
            }
        }

        mdbThread?.start()
    }

    fun stopMdbSafely() {
        Log.d("MDB", "Stopping MDB...")

        isMdbRunning = false
        running = false

        try {
            activeCashless(false)
        } catch (e: Exception) {
            Log.e("MDB", "Error disabling cashless: ${e.message}")
        }

        close()

        mdbThread?.interrupt()
        mdbThread = null
    }

    private fun processMdb() {
        val item: Item = Item()
        handler!!.obtainMessage(MDBUtils.BLACK_LOG, "Init, waiting master command...")
            .sendToTarget()
        try {
            while (!Thread.currentThread().isInterrupted() && running) {
                // read start flag 0x09, 1 bit
                var byteArr = readFromSerialPortDevice(1, -1)
                if (byteArr != null) {
                    logFile(
                        "read 1 byte success: " + ByteConvertStringUtil.buf2StringCompact(byteArr)
                    )
                    if (ByteConvertStringUtil.buf2StringCompact(byteArr).equals("0D")) {
                        continue
                    }
                    if (ByteConvertStringUtil.buf2StringCompact(byteArr).equals("09")) {
                        // read data length, 1 bit
                        byteArr = readFromSerialPortDevice(1, 200)
                        if (byteArr != null) {
                            logFile(
                                "read length success: " + ByteConvertStringUtil.buf2StringCompact(
                                    byteArr
                                )
                            )
                            val lenthHex = ByteConvertStringUtil.buf2StringCompact(byteArr)
                            val length = BigInteger(lenthHex, 16)
                            // read data
                            byteArr = readFromSerialPortDevice(length.toInt(), 200)
                            if (byteArr != null) {
                                logFile(
                                    "read success: " + ByteConvertStringUtil.buf2StringCompact(
                                        byteArr
                                    )
                                )
                            } else {
                                logFile("read failed")
                            }
                            processData(item, byteArr!!)
                        } else {
                            logFile("read length failed!")
                        }
                    }
                }
            }
            //                close();
        } catch (e: java.lang.Exception) {
            if (Thread.currentThread().isInterrupted()) {
                logFile("taskMdb interrupted")
            } else {
                logFile("taskMdb run failed: " + e.message)
            }
        }
    }

    private fun processData(item: Item, readBytes: ByteArray) {
        handler!!.obtainMessage(MDBUtils.BLUE_LOG, "read: ").sendToTarget()
        handler!!.obtainMessage(
            MDBUtils.BLACK_LOG,
            ByteConvertStringUtil.buf2StringCompact(readBytes)
        ).sendToTarget()
        if (compareByteArrayHeadByDevice(readBytes, byteArrayOf(0x00, 0x10), 2)) {
            sendReset()
        } else if (compareByteArrayHeadByDevice(
                readBytes,
                byteArrayOf(0x00, 0x11, 0x00),
                3
            )
        ) { //Setup config data
            mdbValues.setVmcLevel(readBytes[3].toInt() and 0xFF)
            sendSetupConfig(item)
        } else if (compareByteArrayHeadByDevice(
                readBytes,
                byteArrayOf(0x00, 0x11, 0x01),
                3
            )
        ) { // Setup Max/Min Price
            sendSetupPrice()
        } else if (compareByteArrayHeadByDevice(
                readBytes,
                byteArrayOf(0x00, 0x17, 0x00),
                3
            )
        ) { //Expansion ID
            logFile(mdbValues.getOptionalFeature().toString())
            sendExpansionRequestID()
        } else if (compareByteArrayHeadByDevice(
                readBytes,
                byteArrayOf(0x00, 0x17, 0x04),
                3
            )
        ) { //Expansion optional feature bits
            mdbValues.getOptionalFeature().setOptionalFeatureBitsVmc(readBytes[6])
            logFile(
                "mdbValues.getOptionalFeature().setOptionalFeatureBitsMdb(readBytes[6]): "
                        + readBytes[6]
            )
            mdbValues.getOptionalFeature().setOptionalFeatureBitsMdb(readBytes[6])
        } else if (compareByteArrayHeadByDevice(
                readBytes,
                byteArrayOf(0x00, 0x14, 0x00),
                3
            )
        ) { // Disable Reader
            sendDisableReaderAck()
            isReaderEnabled = false
        } else if (compareByteArrayHeadByDevice(
                readBytes,
                byteArrayOf(0x00, 0x14, 0x01),
                3
            )
        ) { //Enable Reader
            sendEnableReaderAck()
            isReaderEnabled = true
        } else if (compareByteArrayHead(readBytes, byteArrayOf(0x01, 0x00), 2)) {
            Log.d(TAG, "Ready! Please input item ID")
            //            handler.obtainMessage(MDBUtils.BLACK_LOG, "Ready! Please input item ID").sendToTarget();
        } else if (compareByteArrayHeadByDevice(
                readBytes,
                byteArrayOf(0x00, 0x15, 0x00),
                3
            )
        ) { //Revalue approved
            sendRevalueApproved()
        } else if (compareByteArrayHeadByDevice(
                readBytes,
                byteArrayOf(0x00, 0x15, 0x01),
                3
            )
        ) { //Revalue limited
            sendRevalueLimit()
        } else if (compareByteArrayHeadByDevice(
                readBytes,
                byteArrayOf(0x00, 0x13, 0x00),
                3
            )
        ) { //vend request
            //e.g. 00 13 00 00 A0 00 01 B4 98
            //amount: 00 A0
            //id: 00 01
            if (mdbValues.getOptionalFeature()
                    .isMonetaryFormat32Vmc() && mdbValues.getOptionalFeature()
                    .isMonetaryFormat32Mdb()
            ) {
                item.setItemAmount(
                    ByteConvertStringUtil.buf2StringCompact(
                        byteArrayOf(
                            readBytes[3],
                            readBytes[4],
                            readBytes[5],
                            readBytes[6]
                        )
                    ).replace(" ", "")
                )
                item.setItemId(
                    ByteConvertStringUtil.buf2StringCompact(
                        byteArrayOf(
                            readBytes[7],
                            readBytes[8]
                        )
                    ).replace(" ", "")
                )
                item.calculatePrice()
                val itemid = BigInteger(item.getItemId(), 16)
                item.setItemId(itemid.toString())
                logFile("Vend request received: item=${item.getItemId()} price=${item.itemPrice}")
            } else {
                item.setItemAmount(
                    ByteConvertStringUtil.buf2StringCompact(
                        byteArrayOf(
                            readBytes[3],
                            readBytes[4]
                        )
                    ).replace(" ", "")
                )
                item.setItemId(
                    ByteConvertStringUtil.buf2StringCompact(
                        byteArrayOf(
                            readBytes[5],
                            readBytes[6]
                        )
                    ).replace(" ", "")
                )
                item.calculatePrice()
                val itemid = BigInteger(item.getItemId(), 16)
                item.setItemId(itemid.toString())
            }
            showVendDialog(item)
        } else if (compareByteArrayHeadByDevice(
                readBytes,
                byteArrayOf(0x00, 0x13.toByte(), 0x01),
                3
            )
        ) {  // vend denied/canceled
            handler!!.obtainMessage(MDBUtils.DIALOG_READCARD_CLOSE).sendToTarget()
            Log.d(TAG, "00 13 01, vending auto cancel, denied, cardDialog closed")
            //            sendVendDenied();
            handler!!.obtainMessage(MDBUtils.DIALOG_WAIT).sendToTarget()
        } else if (compareByteArrayHeadByDevice(
                readBytes,
                byteArrayOf(0x00, 0x13, 0x02),
                3
            )
        ) { //vend success
            sendVendSuccess(readBytes)
        } else if (compareByteArrayHeadByDevice(
                readBytes,
                byteArrayOf(0x00, 0x13, 0x04),
                3
            )
        ) { //vend session end
            sendSessionEnd()
        } else if (compareByteArrayHeadByDevice(
                readBytes,
                byteArrayOf(0x00, 0x13, 0x05),
                3
            )
        ) { //cash sale
            sendCashSaleAck()
        } else if (compareByteArrayHeadByDevice(
                readBytes,
                byteArrayOf(0x00, 0x13, 0x06),
                3
            )
        ) { //negative vend request
            if (mdbValues.getMdbLevel() === 3 && mdbValues.getVmcLevel() === 3) {
                val item1: Item = Item()
                if (!mdbValues.getOptionalFeature().isMonetaryFormat32Vmc()) {
                    item1.setItemAmount(
                        ByteConvertStringUtil.buf2StringCompact(
                            byteArrayOf(
                                readBytes[3],
                                readBytes[4]
                            )
                        ).replace(" ", "")
                    )
                    item1.setItemId(
                        ByteConvertStringUtil.buf2StringCompact(
                            byteArrayOf(
                                readBytes[5],
                                readBytes[6]
                            )
                        ).replace(" ", "")
                    )
                    item1.calculatePrice()
                    val itemid = BigInteger(item1.getItemId(), 16)
                    item1.setItemId(itemid.toString())
                } else {
                    item1.setItemAmount(
                        ByteConvertStringUtil.buf2StringCompact(
                            byteArrayOf(
                                readBytes[3],
                                readBytes[4],
                                readBytes[5],
                                readBytes[6]
                            )
                        ).replace(" ", "")
                    )
                    item1.setItemId(
                        ByteConvertStringUtil.buf2StringCompact(
                            byteArrayOf(
                                readBytes[7],
                                readBytes[8]
                            )
                        ).replace(" ", "")
                    )
                    item1.calculatePrice()
                    val itemid = BigInteger(item1.getItemId(), 16)
                    item1.setItemId(itemid.toString())
                }
                sendNegativeVendApproved(readBytes, item1)
            }
        } else if (compareByteArrayHeadByDevice(
                readBytes,
                byteArrayOf(0x00, 0x14, 0x02),
                3
            )
        ) { //reader cancel
            handler!!.obtainMessage(MDBUtils.DIALOG_READCARD_CLOSE).sendToTarget()
            handler!!.obtainMessage(MDBUtils.DIALOG_WAIT).sendToTarget()
            handler!!.obtainMessage(MDBUtils.BLACK_LOG, "Reader cancelled").sendToTarget()
        } else if (compareByteArrayHead(
                readBytes,
                byteArrayOf(0x01, 0x94.toByte()),
                2
            )
        ) { //diagnose hardware
            if (compareByteArrayHead(readBytes, byteArrayOf(0x01, 0x94.toByte(), 0x00), 3)) {
                handler!!.obtainMessage(MDBUtils.BLACK_LOG, "hardware diagnose test received")
                    .sendToTarget()
            } else {
                //Ps： if ACK after CMDID（0x94）is not 0x00, this means MDB communication timeout
                handler!!.obtainMessage(MDBUtils.BLACK_LOG, "hardware diagnose test open failed")
                    .sendToTarget()
            }
        } else if (compareByteArrayHead(readBytes, byteArrayOf(0x0d), 1)) {
            Log.d(TAG, "0D, end of data")
        } else {
            Log.d(TAG, "read failed: " + ByteConvertStringUtil.buf2StringCompact(readBytes))
        }
    }

    private fun startTransaction() {
        logFile(
            "mdb: " + mdbValues.getOptionalFeature()
                .isAlwaysIdleMdb() + ", vmc: " + mdbValues.getOptionalFeature().isAlwaysIdleVmc()
        )
        if (mdbValues.getOptionalFeature().isAlwaysIdleMdb() && mdbValues.getOptionalFeature()
                .isAlwaysIdleVmc()
        ) {
            logFile("always idle")
            handler!!.obtainMessage(MDBUtils.BLACK_LOG, "always idle").sendToTarget()
        } else {
            SystemClock.sleep(500)
            val wbytes = EnumMDBCommands.getRequestOfMdbCommands(
                EnumMDBCommands.MDB_REQUEST.BEGIN_SESSION,
                mdbValues
            )
            try {
                serialPortDevice!!.write(wbytes, 0, wbytes.size)
                Log.d(
                    TAG,
                    "begin session. wbytes: " + ByteConvertStringUtil.buf2StringCompact(wbytes)
                )
                logFile("begin session")
            } catch (e: DeviceException) {
                logFile(
                    "sendBeginSession(startTransaction) failed: " + e.message + ", wbytes: " + ByteConvertStringUtil.buf2StringCompact(
                        wbytes
                    )
                )
            }
        }
    }

    private fun compareByteArrayHeadByDevice(
        byteArray: ByteArray,
        bytesHead: ByteArray?,
        nBytes: Int
    ): Boolean {
        if (mdbValues.getDeviceType() === 1) {
            return compareByteArrayHead(byteArray, bytesHead, nBytes)
        } else {
            val tmpBytesHead = Arrays.copyOfRange(byteArray, 0, nBytes)
            tmpBytesHead[1] = ((byteArray[1].toInt() and 0x0F) or 0x10).toByte()
            return tmpBytesHead.contentEquals(bytesHead)
        }
    }

    fun activeCashless(active: Boolean) {
        sendActiveCashless(active)
        readActiveCashlessResp()
    }

    private fun sendActiveCashless(active: Boolean) {
        val wbytes = EnumMDBCommands.getRequestOfMdbCommands(
            EnumMDBCommands.MDB_REQUEST.ACTIVE_CASHLESS,
            active
        )
        try {
            serialPortDevice!!.write(wbytes, 0, wbytes.size)
            Log.d(
                TAG,
                "send active cashless succeed ! wbytes:" + ByteConvertStringUtil.buf2StringCompact(
                    wbytes
                )
            )
            //            handler.obtainMessage(MDBUtils.BLACK_LOG, "send quit factory succeed !").sendToTarget();
        } catch (e: DeviceException) {
            Log.e(
                TAG,
                "sendActiveCashless failed: " + e.message + ", wbytes: " + ByteConvertStringUtil.buf2StringCompact(
                    wbytes
                )
            )
            //            throw new RuntimeException(e);
        }
        SystemClock.sleep(200)
    }

    private fun readActiveCashlessResp() {
        var activeSucceed = false
        var totalWaitTime: Long = 0
        do {
            val startTime = System.currentTimeMillis()
            val cmdLength = readCmdLength(false)
            if (cmdLength > 0) {
                val byteArr =
                    readFromSerialPortDevice(cmdLength + 1, 1000) // + 0x0D 100ms should be enough
                if (byteArr != null) {
                    if (byteArr[0].toInt() == 0x01 && compareByteArrayHead(
                            byteArr,
                            byteArrayOf(0x01, 0x96.toByte()),
                            2
                        )
                    ) {
                        if (compareByteArrayHead(
                                byteArr,
                                byteArrayOf(0x01, 0x96.toByte(), 0x00),
                                3
                            )
                        ) {
                            logFile("active cashless succeed")
                        } else {
                            logFile("active cashless failed!")
                        }
                        activeSucceed = true
                    }
                } else {
                    logFile("active cashless failed, read byte array is null!")
                }
            } else {
                Log.e(TAG, "ActiveCashlessTask: read cmd length failed")
            }
            val endTime = System.currentTimeMillis()
            totalWaitTime += (endTime - startTime)
        } while (!activeSucceed && totalWaitTime < 3000)
    }


    fun getDefaultActiveStatus() {
        sendGetParamDefaultActiveStatus()
        readGetParamDefaultActiveStatus()
    }

    fun setDefaultActiveStatus() {
        sendSetParamDefaultActiveStatus()
        readSetParamDefaultStatusResp()
    }


    //time consuming operation, cannot be used in UI thread
    private fun readSetParamDefaultStatusResp() {
        val cmdLength = readCmdLength(false)
        if (cmdLength > 0) {
            val byteArr = readFromSerialPortDevice(cmdLength + 1, 5000) // + 0x0D
            if (byteArr != null) {
                if (compareByteArrayHead(
                        byteArr,
                        byteArrayOf(0x01, 0x90.toByte(), 0x07, 0x00),
                        4
                    )
                ) {
                    logFile("set param default status succeed")
                } else {
                    logFile("set param default status failed!")
                }
            } else {
                logFile("set param default status failed, read byte array is null!")
            }
        } else {
            Log.e(TAG, "taskSetParamDefaultStatus: read cmd length failed")
        }
    }

    private fun sendSetParamDefaultActiveStatus() {
        val wbytes = EnumMDBCommands.getRequestOfMdbCommands(
            EnumMDBCommands.MDB_REQUEST.SET_PARAM_DEFAULT_ACTIVE_STATUS,
            mdbValues.getDefaultActiveStatus()
        )
        try {
            serialPortDevice!!.write(wbytes, 0, wbytes.size)
            Log.d(
                TAG,
                "send set default active status succeed ! wbytes:" + ByteConvertStringUtil.buf2StringCompact(
                    wbytes
                )
            )
            //            handler.obtainMessage(MDBUtils.BLACK_LOG, "send quit factory succeed !").sendToTarget();
        } catch (e: DeviceException) {
            Log.e(
                TAG,
                "sendSetDefaultActiveStatus failed: " + e.message + ", wbytes: " + ByteConvertStringUtil.buf2StringCompact(
                    wbytes
                )
            )
            //            throw new RuntimeException(e);
        }
        SystemClock.sleep(200)
    }

    private fun sendGetParamDefaultActiveStatus() {
        val wbytes =
            EnumMDBCommands.getRequestOfMdbCommands(EnumMDBCommands.MDB_REQUEST.GET_PARAM_DEFAULT_ACTIVE_STATUS)
        try {
            serialPortDevice!!.write(wbytes, 0, wbytes.size)
            Log.d(
                TAG,
                "send get default active status succeed ! wbytes:" + ByteConvertStringUtil.buf2StringCompact(
                    wbytes
                )
            )
            //            handler.obtainMessage(MDBUtils.BLACK_LOG, "send quit factory succeed !").sendToTarget();
        } catch (e: DeviceException) {
            Log.e(
                TAG,
                "sendGetCashlessAddress failed: " + e.message + ", wbytes: " + ByteConvertStringUtil.buf2StringCompact(
                    wbytes
                )
            )
            //            throw new RuntimeException(e);
        }
        SystemClock.sleep(200)
    }

    //time consuming operation, cannot be used in UI thread
    private fun readGetParamDefaultActiveStatus() {
        val cmdLength = readCmdLength(false)
        if (cmdLength > 0) {
            val byteArr = readFromSerialPortDevice(cmdLength + 1, 300) // + 0x0D
            if (byteArr != null) {
                if (compareByteArrayHead(
                        byteArr,
                        byteArrayOf(0x01, 0x91.toByte(), 0x07, 0x00),
                        4
                    )
                ) {
                    logFile("get default active status succeed")
                    val baActiveStatus: ByteArray =
                        subByteArrayIgnore(
                            byteArr,
                            4,
                            4
                        )
                    val nActiveStatus = MDBUtils.byteArrayToIntLittleEndian(baActiveStatus)
                    mdbValues.setDefaultActiveStatus(nActiveStatus)
                } else {
                    logFile("get default active status failed!")
                }
            } else {
                logFile("get default active status failed, read byte array is null!")
            }
        } else {
            Log.e(TAG, "taskGetDefaultActiveStatus: read cmd length failed")
        }
    }


    private fun compareByteArrayHead(
        byteArray: ByteArray,
        bytesHead: ByteArray?,
        nBytes: Int
    ): Boolean {
        val tmpBytesHead = Arrays.copyOfRange(byteArray, 0, nBytes)
        return tmpBytesHead.contentEquals(bytesHead)
    }

    fun isWhiteDemon(): Boolean {
        if ((subModel.equals(
                SUBMODEL_Q3A7,
                ignoreCase = true
            ) || subModel.equals(
                SUBMODEL_Q3V,
                ignoreCase = true
            ))
        ) {
            return true
        } else if (subModel.equals(
                SUBMODEL_Q3MINI,
                ignoreCase = true
            )
        ) {
            return false
        } else {
            Log.e(TAG, "isWhiteDemon: unknown subModel: " + subModel)
            return false
        }
    }

    fun subByteArray(byteArray: ByteArray, length: Int): ByteArray {
        val arrySub = ByteArray(length)
        if (length >= 0) System.arraycopy(byteArray, 0, arrySub, 0, length)
        return arrySub
    }

    fun subByteArrayIgnore(byteArray: ByteArray, startIndex: Int, length: Int): ByteArray {
        val arrySub = ByteArray(length)
        if (length >= 0) System.arraycopy(byteArray, startIndex, arrySub, 0, length)
        return arrySub
    }

    private fun sendVersionRequest() {
        val wbytes: ByteArray =
            EnumMDBCommands.getRequestOfMdbCommands(EnumMDBCommands.MDB_REQUEST.VERSION_REQUEST)
        handler!!.obtainMessage(MDBUtils.BLACK_LOG, "Getting version, please wait... ")
            .sendToTarget()
        try {
            serialPortDevice!!.write(wbytes, 0, wbytes.size)
            logFile(
                "send get version request succeed !" + ByteConvertStringUtil.buf2StringCompact(
                    wbytes
                )
            )
        } catch (e: DeviceException) {
            logFile(
                "sendVersionRequest failed: " + e.message + ", wbytes: " + ByteConvertStringUtil.buf2StringCompact(
                    wbytes
                )
            )
        }
        SystemClock.sleep(200)
    }

    fun printVersionName(readBytes: ByteArray) {
        val high = readBytes[3].toInt()
        val low = readBytes[4].toInt()
        val version = ((high and 0xFF) shl 8) or (low and 0xFF)
        logFile("current ver    sion = " + version)
        mdbValues.setCurrentVersion(version)
        handler!!.obtainMessage(MDBUtils.GREEN_LOG, "version = " + version).sendToTarget()
    }

    private fun readGetVersionResp() {
        val readDone = false
        var sendCount = 0
        while (!readDone && sendCount < 3) {
            ++sendCount
            val cmdLength: Int = readCmdLength(true)
            if (cmdLength > 0) {
                val byteArr: ByteArray? = readFromSerialPortDevice(cmdLength + 1, 5000) // + 0x0D
                if (byteArr != null) {
                    handler!!.obtainMessage(
                        MDBUtils.BLACK_LOG, "get result:" +
                                ByteConvertStringUtil.buf2StringCompact(byteArr)
                    ).sendToTarget()
                    printVersionName(byteArr)
                    break
                }
            } else {
                if (sendCount < 3) {
                    logFile("get version failed, trying again: " + sendCount)
                    reset()
                    sendVersionRequest()
                } else {
                    logFile("get version failed!")
                }
            }
        }
    }

    fun reset() {
        var nResult = -1
        nResult = StmGpioInterface.ispReset()
        if (nResult < 0) logFile("reset failed: " + nResult)
        else logFile("reset success")
    }

    //read only once: readDone is true
    //keep reading: readDone is false
    fun readCmdLength(readDone: Boolean): Int {
        var readDone = readDone
        var ret = -1
        do {
            var byteArr: ByteArray? = readFromSerialPortDevice(1, 5000)
            if (byteArr == null) {
                Log.e(TAG, "read cmd length failed, read byte array is null")
                return ret
            }
            if (byteArr.contentEquals(byteArrayOf(0x09))) {
                byteArr = readFromSerialPortDevice(1, 5000)
                if (byteArr != null && byteArr.size == 1) {
                    ret = byteArr[0].toInt()
                    Log.d(TAG, "cmd length: " + ret)
                    readDone = true
                } else {
                    Log.e(TAG, "read cmd length failed, read byte array is null or length is not 1")
                }
            } else {
                Log.e(TAG, "read cmd length failed, read byte array is not start with 0x09")
            }
        } while (!readDone)
        return ret
    }

    //time consuming operation, cannot be used in UI thread
    private fun readFromSerialPortDevice(byteLength: Int, timeout: Int): ByteArray? {
        var arryData = ByteArray(byteLength)
        try {
            val serialPortOperationResult = serialPortDevice!!.waitForRead(arryData.size, timeout)
            val data = serialPortOperationResult.getData()
            val dataLength = serialPortOperationResult.getDataLength()
            if (data != null) {
                Log.d(TAG, "data: " + ByteConvertStringUtil.buf2StringCompact(data))
                arryData = subByteArray(
                    data,
                    dataLength
                )
                return arryData
            } else {
                Log.e(
                    TAG,
                    "readFromSerialPortDevice data is null, byteLength: " + byteLength + ", timeout: " + timeout
                )
                return null
            }
        } catch (e: DeviceException) {
            e.printStackTrace()
            Log.e(
                TAG,
                "readFromSerialPortDevice failed: " + e.message + ", byteLength: " + byteLength + ", timeout: " + timeout
            )
            return null
        }
    }

    private fun sendReset() {
        val wbytes: ByteArray =
            EnumMDBCommands.getResponseOfMdbCommands(EnumMDBCommands.MDB_RESPONSE.RESET)
        try {
            serialPortDevice!!.write(wbytes, 0, wbytes.size)
            Log.d(TAG, "Reset wbytes:" + ByteConvertStringUtil.buf2StringCompact(wbytes))
            logFile("Reset" + wbytes)
        } catch (e: DeviceException) {
            Log.e(
                TAG,
                "sendReset failed: " + e.message + ", wbytes: " + ByteConvertStringUtil.buf2StringCompact(
                    wbytes
                )
            )
            //            throw new RuntimeException(e);
        }
    }

    //readBytes = "00 11 00 02 00 00 01 14"
    private fun sendSetupConfig(item: Item?) {
        val wbytes: ByteArray = EnumMDBCommands.getResponseOfMdbCommands(
            EnumMDBCommands.MDB_RESPONSE.SETUP_CONFIG,
            mdbValues,
            item
        )
        try {
            serialPortDevice!!.write(wbytes, 0, wbytes.size)
            logFile(
                "Setup config data. wbytes:" + ByteConvertStringUtil.buf2StringCompact(wbytes)
            )
            logFile("Setup config data" + wbytes)
        } catch (e: DeviceException) {
            logFile(
                "sendSetupConfig failed: " + e.message + ", wbytes: " + ByteConvertStringUtil.buf2StringCompact(
                    wbytes
                )
            )
        }
    }

    private fun sendSetupPrice() {
        val wbytes: ByteArray =
            EnumMDBCommands.getResponseOfMdbCommands(EnumMDBCommands.MDB_RESPONSE.SETUP_PRICE)
        try {
            serialPortDevice!!.write(wbytes, 0, wbytes.size)
            logFile(
                "Setup Max/Min Price. wbytes: " + ByteConvertStringUtil.buf2StringCompact(wbytes)
            )
            logFile("Setup Max/Min Price" + wbytes)
        } catch (e: DeviceException) {
            Log.e(
                TAG,
                "sendSetupPrice failed: " + e.message + ", wbytes: " + ByteConvertStringUtil.buf2StringCompact(
                    wbytes
                )
            )
        }
    }

    private fun sendExpansionRequestID() {
        val wbytes: ByteArray = EnumMDBCommands.getResponseOfMdbCommands(
            EnumMDBCommands.MDB_RESPONSE.EXPANSION_REQUESTID,
            mdbValues
        )
        try {
            serialPortDevice!!.write(wbytes, 0, wbytes.size)
            Log.d(TAG, "Expansion ID. wbytes: " + ByteConvertStringUtil.buf2StringCompact(wbytes))
            logFile("Expansion ID" + wbytes)
        } catch (e: DeviceException) {
            logFile(
                "sendExpansionRequestID failed: " + e.message + ", wbytes: " + ByteConvertStringUtil.buf2StringCompact(
                    wbytes
                )
            )
            //            throw new RuntimeException(e);
        }
    }

    private fun sendDisableReaderAck() {
        val wbytes: ByteArray =
            EnumMDBCommands.getResponseOfMdbCommands(EnumMDBCommands.MDB_RESPONSE.COMMON_ACK)
        try {
            serialPortDevice!!.write(wbytes, 0, wbytes.size)
            logFile(
                "Disable Reader Ack. wbytes: " + ByteConvertStringUtil.buf2StringCompact(wbytes)
            )
            logFile("Disable Reader" + wbytes)
        } catch (e: DeviceException) {
            logFile(
                "sendDisableReaderAck failed: " + e.message + ", wbytes: " + ByteConvertStringUtil.buf2StringCompact(
                    wbytes
                )
            )
            //            throw new RuntimeException(e);
        }
    }

    private fun sendEnableReaderAck() {
        val wbytes: ByteArray =
            EnumMDBCommands.getResponseOfMdbCommands(EnumMDBCommands.MDB_RESPONSE.COMMON_ACK)
        try {
            serialPortDevice!!.write(wbytes, 0, wbytes.size)
            Log.d(
                TAG,
                "Enable Reader Ack. wbytes: " + ByteConvertStringUtil.buf2StringCompact(wbytes)
            )
            logFile("Enable Reader" + wbytes)
        } catch (e: DeviceException) {
            Log.e(
                TAG,
                "sendEnableReaderAck failed: " + e.message + ", wbytes: " + ByteConvertStringUtil.buf2StringCompact(
                    wbytes
                )
            )
            //            throw new RuntimeException(e);
        }
    }

    private fun sendRevalueApproved() {
        val wbytes: ByteArray =
            EnumMDBCommands.getResponseOfMdbCommands(EnumMDBCommands.MDB_RESPONSE.REVALUE_APPROVED)
        try {
            serialPortDevice!!.write(wbytes, 0, wbytes.size)
            Log.d(
                TAG,
                "Revalue approved. wbytes: " + ByteConvertStringUtil.buf2StringCompact(wbytes)
            )
            logFile("Revalue approved" + wbytes)
        } catch (e: DeviceException) {
            Log.e(
                TAG,
                "sendRevalueApproved failed: " + e.message + ", wbytes: " + ByteConvertStringUtil.buf2StringCompact(
                    wbytes
                )
            )
            //            throw new RuntimeException(e);
        }
    }

    private fun sendRevalueLimit() {
        val wbytes: ByteArray =
            EnumMDBCommands.getResponseOfMdbCommands(EnumMDBCommands.MDB_RESPONSE.REVALUE_LIMIT_AMOUNT)
        try {
            serialPortDevice!!.write(wbytes, 0, wbytes.size)
            Log.d(
                TAG,
                "Revalue limit request. wbytes: " + ByteConvertStringUtil.buf2StringCompact(wbytes)
            )
            logFile("Revalue limit request" + wbytes)
        } catch (e: DeviceException) {
            Log.e(
                TAG,
                "sendRevalueLimit failed: " + e.message + ", wbytes: " + ByteConvertStringUtil.buf2StringCompact(
                    wbytes
                )
            )
            //            throw new RuntimeException(e);
        }
    }

    private fun sendVendSuccess(readBytes: ByteArray) {

        val wbytes: ByteArray =
            EnumMDBCommands.getResponseOfMdbCommands(EnumMDBCommands.MDB_RESPONSE.VEND_SUCCESS)
        try {
            serialPortDevice!!.write(wbytes, 0, wbytes.size)
            logFile("Vend success! wbytes: " + ByteConvertStringUtil.buf2StringCompact(wbytes))
            val itemIdHigh = readBytes[3].toInt()
            val itemIdLow = readBytes[4].toInt()
            val itemId = ((itemIdHigh and 0xFF) shl 8) or (itemIdLow and 0xFF)
            handler!!.obtainMessage(
                MDBUtils.DIALOG_ITEM,
                ("Vend success! " + ByteConvertStringUtil.buf2StringCompact(wbytes)
                        + " item:" + itemId)
            ).sendToTarget()
        } catch (e: DeviceException) {
            logFile(
                "sendVendSuccess failed: " + e.message + ", wbytes: " + ByteConvertStringUtil.buf2StringCompact(
                    wbytes
                )
            )
            //            throw new RuntimeException(e);
        }
    }

    private fun sendVendDenied() {
        val wbytes: ByteArray =
            EnumMDBCommands.getResponseOfMdbCommands(EnumMDBCommands.MDB_RESPONSE.VEND_DENIED)
        try {
            serialPortDevice!!.write(wbytes, 0, wbytes.size)
            Log.d(TAG, "Vend denied! wbytes: " + ByteConvertStringUtil.buf2StringCompact(wbytes))
            logFile("Vend denied" + wbytes)
        } catch (e: DeviceException) {
            Log.e(
                TAG,
                "sendVendDenied failed: " + e.message + ", wbytes: " + ByteConvertStringUtil.buf2StringCompact(
                    wbytes
                )
            )
            //            throw new RuntimeException(e);
        }
    }

    private fun sendCashSaleAck() {
        val wbytes: ByteArray =
            EnumMDBCommands.getResponseOfMdbCommands(EnumMDBCommands.MDB_RESPONSE.COMMON_ACK)
        try {
            serialPortDevice!!.write(wbytes, 0, wbytes.size)
            Log.d(TAG, "Cash Sale Ack. wbytes: " + ByteConvertStringUtil.buf2StringCompact(wbytes))
            logFile("Enable Reader" + wbytes)
        } catch (e: DeviceException) {
            Log.e(
                TAG,
                "sendCashSaleAck failed: " + e.message + ", wbytes: " + ByteConvertStringUtil.buf2StringCompact(
                    wbytes
                )
            )
            //            throw new RuntimeException(e);
        }
    }

    private fun sendSessionEnd() {
        val wbytes: ByteArray =
            EnumMDBCommands.getResponseOfMdbCommands(EnumMDBCommands.MDB_RESPONSE.END_SESSION)
        try {
            serialPortDevice!!.write(wbytes, 0, wbytes.size)
            Log.d(TAG, "finish. wbytes: " + ByteConvertStringUtil.buf2StringCompact(wbytes))
            logFile("finish" + wbytes)
        } catch (e: DeviceException) {
            Log.e(
                TAG,
                "sendSessionEnd failed: " + e.message + ", wbytes: " + ByteConvertStringUtil.buf2StringCompact(
                    wbytes
                )
            )
        }
    }

    private fun sendVendApproved(item: Item?) {
        val wbytes: ByteArray = EnumMDBCommands.getResponseOfMdbCommands(
            EnumMDBCommands.MDB_RESPONSE.VEND_APPROVED,
            item
        )
        try {
            serialPortDevice!!.write(wbytes, 0, wbytes.size)
            Log.d(TAG, "approved! wbytes: " + ByteConvertStringUtil.buf2StringCompact(wbytes))
            logFile("approved" + wbytes)
        } catch (e: DeviceException) {
            Log.e(
                TAG,
                "sendVendApproved failed: " + e.message + ", wbytes: " + ByteConvertStringUtil.buf2StringCompact(
                    wbytes
                )
            )
            //            throw new RuntimeException(e);
        }
    }

    //not tested yet
    private fun sendNegativeVendApproved(readBytes: ByteArray?, item: Item?) {
        val wbytes: ByteArray = EnumMDBCommands.getResponseOfMdbCommands(
            EnumMDBCommands.MDB_RESPONSE.NEGATIVE_VEND_APPROVED,
            mdbValues,
            item
        )
        try {
            serialPortDevice!!.write(wbytes, 0, wbytes.size)
            logFile(
                "negative vend approved! wbytes: " + ByteConvertStringUtil.buf2StringCompact(wbytes)
            )
            log("negative vend approved" + wbytes)
        } catch (e: DeviceException) {
            Log.e(
                TAG,
                "sendNegativeVendApproved failed: " + e.message + ", wbytes: " + ByteConvertStringUtil.buf2StringCompact(
                    wbytes
                )
            )
            //            throw new RuntimeException(e);
        }
    }

    fun open(): Boolean {
        var ret = false
        try {
            serialPortDevice!!.open(SerialPortDevice.ID_SERIAL_EXT)
            logFile("serial port opened success!")
            ret = true
            open = true
        } catch (e: DeviceException) {
            logFile("seriaport open failed! ${e.message!!}")
            //			throw new RuntimeException(e);
        }
        if (ret) {
            try {
                serialPortDevice!!.changeSerialPortParams(
                    115200,
                    SerialPortDevice.DATABITS_8,
                    SerialPortDevice.STOPBITS_1,
                    SerialPortDevice.PARITY_NONE
                )
            } catch (e: DeviceException) {
                Log.e(TAG, "change serial port params failed: " + e.message)
                //                throw new RuntimeException(e);
            }
            SystemClock.sleep(100)
        }
        return ret
    }

    fun close() {
        running = false

        if (open) {
            try {
                serialPortDevice!!.close()
                Log.d(TAG, "serial port close success!")
                open = false
            } catch (e: DeviceException) {
                Log.e(TAG, "serial port close failed! ${e.message}")
            }
        }
    }

    override fun onResume() {
        super.onResume()
    }


    override fun onStart() {
        super.onStart()
        startMdbSafely()
    }

    override fun onStop() {
        super.onStop()
        stopMdbSafely()
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
