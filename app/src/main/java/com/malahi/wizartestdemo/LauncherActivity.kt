package com.malahi.wizartestdemo

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class LauncherActivity : ComponentActivity() {

    private var dialogShown = false
    private var requestedOnce = false
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
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                showDialogOnce()
            } else if (!requestedOnce) {
                requestedOnce = true
                requestAllFilesAccess()
            }
        } else {
            showDialogOnce()
        }
    }

    private fun showDialogOnce() {
        if (dialogShown) return
        dialogShown = true
        showSelectionDialog()
    }

    private fun showSelectionDialog() {

        val options = arrayOf("DigitalIO (Pulse)", "MDB")

        android.app.AlertDialog.Builder(this)
            .setTitle("Select Screen")
            .setCancelable(false)
            .setItems(options) { _, which ->

                when (which) {
                    0 -> startActivity(Intent(this, PulseDinDemoActivity::class.java))
                    1 -> startActivity(Intent(this, MDBDemoActivity::class.java))
                }

                finish()
            }
            .show()
    }
    fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
                ).apply {
                    data = Uri.fromParts(
                        "package",
                        packageName,
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
}

