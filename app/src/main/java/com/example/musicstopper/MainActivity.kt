package com.example.musicstopper

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.material.materialswitch.MaterialSwitch

class MainActivity : AppCompatActivity() {

    private lateinit var btnToggle: Button
    private lateinit var tvStatus: TextView
    private lateinit var prefs: android.content.SharedPreferences

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val intent = Intent(this, MusicStopperService::class.java).apply {
                action = MusicStopperService.ACTION_START
                putExtra(MusicStopperService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(MusicStopperService.EXTRA_RESULT_DATA, result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            tvStatus.text = "الحالة: يعمل"
            btnToggle.text = "إيقاف المراقبة"
        } else {
            Toast.makeText(this, "تم رفض الصلاحية", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        btnToggle = findViewById(R.id.btnToggle)
        tvStatus = findViewById(R.id.tvStatus)

        bindSwitch(R.id.switchDnd, "opt_dnd")
        bindSwitch(R.id.switchMute, "opt_mute")
        bindSwitch(R.id.switchMediaKey, "opt_media_key")
        bindSwitch(R.id.switchNoise, "opt_noise")

        btnToggle.setOnClickListener {
            if (MusicStopperService.isRunning) {
                stopService(Intent(this, MusicStopperService::class.java).apply {
                    action = MusicStopperService.ACTION_STOP
                })
                tvStatus.text = "الحالة: متوقف"
                btnToggle.text = "بدء المراقبة"
            } else {
                startMonitoring()
            }
        }

        requestDndPermissionIfNeeded()
    }

    private fun bindSwitch(id: Int, key: String) {
        val sw = findViewById<MaterialSwitch>(id)
        sw.isChecked = prefs.getBoolean(key, sw.isChecked)
        sw.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(key, checked).apply()
        }
    }

    private fun startMonitoring() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.RECORD_AUDIO), 100)
            return
        }
        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(pm.createScreenCaptureIntent())
    }

    private fun requestDndPermissionIfNeeded() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !nm.isNotificationPolicyAccessGranted) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty()
            && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startMonitoring()
        }
    }
}
