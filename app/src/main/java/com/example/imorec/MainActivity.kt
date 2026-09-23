package com.example.imorec

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.imorec.forceSpeaker
import com.example.imorec.imoOnly
import com.example.imorec.monitorEnabled
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var checks: TextView
    private lateinit var list: ListView
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private var files: List<File> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        checks = findViewById(R.id.checks)
        list = findViewById(R.id.list)
        startBtn = findViewById(R.id.start)
        stopBtn = findViewById(R.id.stop)

        findViewById<Button>(R.id.perms).setOnClickListener { requestPerms() }
        findViewById<Button>(R.id.notifAccess).setOnClickListener {
            openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }
        findViewById<Button>(R.id.battery).setOnClickListener { requestBatteryExemption() }

        startBtn.setOnClickListener {
            if (!hasMicPermission()) {
                requestPerms()
                return@setOnClickListener
            }
            monitorEnabled = true
            RecorderService.start(this)
            Toast.makeText(this, "Watching for calls", Toast.LENGTH_SHORT).show()
        }

        stopBtn.setOnClickListener {
            monitorEnabled = false
            RecorderService.stop(this)
        }

        val imoBox = findViewById<CheckBox>(R.id.imoOnly)
        imoBox.isChecked = imoOnly
        imoBox.setOnCheckedChangeListener { _, v ->
            if (v && !CallNotificationListener.isEnabled(this)) {
                imoBox.isChecked = false
                AlertDialog.Builder(this)
                    .setTitle("Notification access needed")
                    .setMessage(
                        "Filtering to IMO calls only works if this app can see IMO's " +
                            "ongoing call notification. Grant notification access first."
                    )
                    .setPositiveButton("Open settings") { _, _ ->
                        openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                imoOnly = v
            }
        }

        val spkBox = findViewById<CheckBox>(R.id.forceSpeaker)
        spkBox.isChecked = forceSpeaker
        spkBox.setOnCheckedChangeListener { _, v -> forceSpeaker = v }

        findViewById<Button>(R.id.refresh).setOnClickListener { loadFiles() }

        list.setOnItemClickListener { _, _, pos, _ -> shareFile(files[pos]) }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            confirmDelete(files[pos])
            true
        }
    }

    override fun onResume() {
        super.onResume()
        loadFiles()
        handler.post(refreshStatus)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshStatus)
        super.onPause()
    }

    private val refreshStatus = object : Runnable {
        override fun run() {
            status.text = RecorderService.lastStatus
            startBtn.isEnabled = !RecorderService.isMonitoring
            stopBtn.isEnabled = RecorderService.isMonitoring
            checks.text = readiness()
            handler.postDelayed(this, 1000L)
        }
    }

    /** A plain checklist, because every one of these silently breaks recording. */
    private fun readiness(): String {
        val sb = StringBuilder()
        sb.append(mark(hasMicPermission())).append(" Microphone permission\n")
        sb.append(mark(notificationsAllowed())).append(" Notifications allowed\n")
        sb.append(mark(CallNotificationListener.isEnabled(this)))
            .append(" Notification access (needed for IMO-only)\n")
        sb.append(mark(isIgnoringBatteryOptimizations()))
            .append(" Exempt from battery optimisation\n")
        sb.append(mark(RecorderService.isMonitoring)).append(" Watcher running")
        return sb.toString()
    }

    private fun mark(ok: Boolean) = if (ok) "[OK]" else "[ - ]"

    // ---- permissions --------------------------------------------------------

    private fun hasMicPermission() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

    private fun notificationsAllowed(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    private fun requestPerms() {
        val want = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            want.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestPermissions(want.toTypedArray(), 1)
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestBatteryExemption() {
        if (isIgnoringBatteryOptimizations()) {
            Toast.makeText(this, "Already exempt", Toast.LENGTH_SHORT).show()
            return
        }
        // The targeted intent needs a permission some OEM builds refuse, so fall
        // back to the plain settings list rather than crashing.
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + packageName)
                )
            )
        } catch (t: Throwable) {
            openSettings(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        }
    }

    private fun openSettings(action: String) {
        try {
            startActivity(Intent(action))
        } catch (t: Throwable) {
            Toast.makeText(this, "Could not open that settings screen", Toast.LENGTH_LONG).show()
        }
    }

    // ---- recordings ---------------------------------------------------------

    private fun loadFiles() {
        files = Prefs.recordingsDir(this)
            .listFiles { f -> f.isFile && f.name.endsWith(".m4a") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

        val fmt = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
        val rows = files.map { f ->
            val kb = f.length() / 1024
            val mins = kb / 480.0
            f.name + "\n" + fmt.format(Date(f.lastModified())) +
                "  -  " + kb + " KB  (~" + String.format(Locale.US, "%.1f", mins) + " min)"
        }
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rows)

        findViewById<TextView>(R.id.listHint).text =
            if (rows.isEmpty()) "No recordings yet. Tap a recording to share it, long-press to delete."
            else rows.size.toString() + " recording(s). Tap to share, long-press to delete."
    }

    private fun shareFile(f: File) {
        val uri: Uri = FileProvider.getUriForFile(this, packageName + ".files", f)
        val send = Intent(Intent.ACTION_SEND)
            .setType("audio/mp4")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Share recording"))
    }

    private fun confirmDelete(f: File) {
        AlertDialog.Builder(this)
            .setTitle("Delete recording?")
            .setMessage(f.name)
            .setPositiveButton("Delete") { _, _ ->
                f.delete()
                loadFiles()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
