package com.gamedrop.alert

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var log: TextView
    private val prefs by lazy { getSharedPreferences("gamedrop", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createChannel()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 77)
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(36, 42, 36, 30); setBackgroundColor(0xFF101522.toInt()) }
        val title = TextView(this).apply { text = "🎮 GameDrop Alert"; textSize = 27f; setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER; setPadding(0, 8, 0, 22) }
        status = TextView(this).apply { textSize = 16f; setTextColor(0xFFB9C7DA.toInt()); setPadding(0, 8, 0, 18) }
        val start = Button(this).apply { text = "تشغيل المراقبة كل 15 دقيقة"; setOnClickListener { Monitor.schedule(this@MainActivity); refresh() } }
        val stop = Button(this).apply { text = "إيقاف المراقبة"; setOnClickListener { WorkManager.getInstance(this@MainActivity).cancelUniqueWork(Monitor.WORK); refresh() } }
        val scan = Button(this).apply { text = "فحص الآن"; setOnClickListener { Monitor.runNow(this@MainActivity); status.text = "جاري فحص الموقعين..." } }
        val heading = TextView(this).apply { text = "آخر التحديثات"; textSize = 19f; setTextColor(0xFFFFFFFF.toInt()); setPadding(0, 18, 0, 10) }
        log = TextView(this).apply { textSize = 14f; setTextColor(0xFFE2E8F0.toInt()); setPadding(0, 8, 0, 8) }
        root.addView(title); root.addView(status); root.addView(start); root.addView(stop); root.addView(scan); root.addView(heading)
        val scroll = ScrollView(this).apply { addView(log) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        refresh()
    }

    override fun onResume() { super.onResume(); refresh() }
    private fun refresh() {
        val enabled = WorkManager.getInstance(this).getWorkInfosForUniqueWork(Monitor.WORK).get().any { !it.state.isFinished }
        status.text = if (enabled) "🟢 المراقبة مفعّلة — فحص دوري كل 15 دقيقة" else "⚪ المراقبة متوقفة"
        log.text = prefs.getString("history", "لسه مفيش تحديثات مسجلة. أول فحص هيحفظ الوضع الحالي، وبعد كده هيبلغك بالتغييرات الجديدة.")
    }
    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel("drops", "تحديثات الألعاب", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "تنبيهات الألعاب والمفاتيح الجديدة" }
            val reminders = NotificationChannel("reminders", "تذكيرات مواعيد السحب", NotificationManager.IMPORTANCE_HIGH).apply { description = "تذكير قبل بداية طوابير Lenovo بيوم وبساعتين" }
            getSystemService(NotificationManager::class.java).apply {
                createNotificationChannel(channel)
                createNotificationChannel(reminders)
            }
        }
    }
}
