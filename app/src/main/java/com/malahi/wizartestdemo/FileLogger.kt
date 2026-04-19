package com.malahi.wizartestdemo
import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.*

object FileLogger {
    private val mainHandler = Handler(Looper.getMainLooper())

    private const val LOG_FILE_NAME = "uptpos_log.txt"
    private const val MAX_LOG_SIZE = 500 * 1024 * 1024  // 500 MB

    fun log(context: Context, tag: String, message: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // Already on main thread
            writeLog(context, tag, message)
        } else {
            // Post to main thread
            mainHandler.post {
                writeLog(context, tag, message)
            }
        }
    }

    private fun writeLog(context: Context, tag: String, message: String) {
        try {
            val logFile = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                LOG_FILE_NAME
            )

//            val logFile = File(
//                context.getExternalFilesDir(null),
//                LOG_FILE_NAME
//            )

            // Rotate if needed
            rotateLogsIfNeeded(logFile)

            val writer = FileWriter(logFile, true)
            val printWriter = PrintWriter(writer)
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

            printWriter.println("$timestamp [$tag]: $message")
            printWriter.flush()
            printWriter.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun rotateLogsIfNeeded(logFile: File) {
        if (logFile.exists() && logFile.length() > MAX_LOG_SIZE) {
            val backupFile = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "app_log_backup.txt"
            )
            logFile.renameTo(backupFile)
            logFile.createNewFile()
        }
    }
}
