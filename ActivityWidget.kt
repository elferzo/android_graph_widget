package com.example.graphwidget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.os.Bundle
import android.widget.RemoteViews
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

class ActivityWidget : AppWidgetProvider() {

    companion object {
        const val ACTION_UPDATE       = "com.example.graphwidget.ACTION_UPDATE_ACTIVITY"
        const val SERVER_URL_ACTIVITY = "http://178.208.86.99:5001/activity"
        const val SERVER_URL_STEPS    = "http://178.208.86.99:5001/steps"
        const val PREFS_NAME          = "activitywidget_prefs"
        const val KEY_CALORIES        = "last_calories"
        const val KEY_STEPS           = "last_steps"
        const val COLOR_CAL           = "#FF6D00"
        const val COLOR_STP           = "#29B6F6"

        val DEFAULT_CAL = floatArrayOf(2200f, 2400f, 2100f, 2300f, 2500f, 2200f, 2000f)
        val DEFAULT_STP = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f)

        fun updateWidgets(context: Context) {
            val awm = context.getSystemService(Context.APPWIDGET_SERVICE) as AppWidgetManager
            val ids = awm.getAppWidgetIds(ComponentName(context, ActivityWidget::class.java))
            for (id in ids) updateWidget(context, awm, id)
        }

        fun updateWidget(context: Context, awm: AppWidgetManager, widgetId: Int) {
            Thread {
                val cal = fetchArray(context, SERVER_URL_ACTIVITY, "calories", KEY_CALORIES, DEFAULT_CAL)
                val stp = fetchArray(context, SERVER_URL_STEPS, "steps", KEY_STEPS, DEFAULT_STP)
                val rv = RemoteViews(context.packageName, R.layout.widget_activity_chart)
                rv.setImageViewBitmap(R.id.activity_chart_image, buildChartBitmap(cal, stp))
                awm.updateAppWidgetOptions(widgetId, Bundle())
                awm.updateAppWidget(widgetId, rv)
            }.start()
        }

        fun fetchArray(
            context: Context, url: String, key: String,
            cacheKey: String, default: FloatArray
        ): FloatArray {
            return try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000; conn.readTimeout = 5000
                conn.requestMethod = "GET"
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().readText()
                    val arr  = JSONObject(body).getJSONArray(key)
                    val result = FloatArray(arr.length()) { arr.getDouble(it).toFloat() }
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putString(cacheKey, body).apply()
                    result
                } else loadCached(context, cacheKey, key, default)
            } catch (e: Exception) { loadCached(context, cacheKey, key, default) }
        }

        fun loadCached(context: Context, cacheKey: String, key: String, default: FloatArray): FloatArray {
            val s = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(cacheKey, null) ?: return default
            return try {
                val arr = JSONObject(s).getJSONArray(key)
                FloatArray(arr.length()) { arr.getDouble(it).toFloat() }
            } catch (e: Exception) { default }
        }

        fun buildChartBitmap(
            calData: FloatArray = DEFAULT_CAL,
            stpData: FloatArray = DEFAULT_STP
        ): Bitmap {
            val W = 800; val H = 360
            val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

            val sdf = SimpleDateFormat("dd.MM", Locale.getDefault())
            val dates = Array(7) { i ->
                sdf.format(Calendar.getInstance().apply {
                    add(Calendar.DAY_OF_YEAR, -(6 - i))
                }.time)
            }

            val titleH = 52f
            val padL   = 12f; val padR = 12f   // убираем место для Y подписей
            val padT   = titleH + 4f; val padB = 10f
            val plotRect = RectF(padL, padT, W - padR, H - padB)
            val cW = plotRect.width(); val cH = plotRect.height()

            val n = 7
            val todayRightExtra = 0.55f
            val xRange = (n - 1).toFloat() + todayRightExtra

            // Общий диапазон Y — объединяем оба ряда
            val allValues = calData.toList() + stpData.toList()
            val validValues = allValues.filter { it > 0f }
            val yMin = 0f
            val yMax = if (validValues.isEmpty()) 10000f
                       else (validValues.max() * 1.15f).coerceAtLeast(100f)

            fun yPx(v: Float) = plotRect.top + cH * (1f - (v - yMin) / (yMax - yMin))
            fun xPx(i: Float) = plotRect.left + (i / xRange) * cW

            // ── Заголовок ─────────────────────────────────────────────────
            val uPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#888899"); textSize = 23f; typeface = Typeface.MONOSPACE
            }
            val calPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor(COLOR_CAL); textSize = 23f; typeface = Typeface.MONOSPACE
            }
            val stpPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor(COLOR_STP); textSize = 23f; typeface = Typeface.MONOSPACE
            }
            val leftU = "___"; val leftUW = uPaint.measureText(leftU)
            val calWordW = calPaint.measureText("calories")
            val charW = uPaint.measureText("_")
            val rightCnt = ((W - padR - padL - leftUW - calWordW) / charW).toInt()

            canvas.drawText(leftU, padL, padT - 26f, uPaint)
            canvas.drawText("calories", padL + leftUW, padT - 26f, calPaint)
            canvas.drawText("_".repeat(rightCnt), padL + leftUW + calWordW, padT - 26f, uPaint)
            canvas.drawText("steps", padL + leftUW, padT - 4f, stpPaint)

            // ── Заливка + рамка ───────────────────────────────────────────
            Paint(Paint.ANTI_ALIAS_FLAG).also {
                it.color = Color.argb(128, 0x22, 0x22, 0x2E); it.style = Paint.Style.FILL
                canvas.drawRect(plotRect, it)
            }
            Paint(Paint.ANTI_ALIAS_FLAG).also {
                it.color = Color.BLACK; it.style = Paint.Style.STROKE; it.strokeWidth = 1.2f
                canvas.drawRect(plotRect, it)
            }

            // ── Сетка горизонтальная (без подписей) ───────────────────────
            val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#55444455"); strokeWidth = 0.8f
                pathEffect = DashPathEffect(floatArrayOf(9f, 8f), 0f)
            }
            for (pct in listOf(0.2f, 0.4f, 0.6f, 0.8f)) {
                val gy = plotRect.top + cH * pct
                canvas.drawLine(plotRect.left, gy, plotRect.right, gy, gridPaint)
            }

            // ── TODAY box ─────────────────────────────────────────────────
            val todayIdx = n - 1
            val todayXc  = xPx(todayIdx.toFloat())
            val halfL = cW / xRange * 0.46f; val halfR = cW / xRange * 0.52f
            val boxRF = RectF(todayXc - halfL, plotRect.top, todayXc + halfR, plotRect.bottom)
            for ((lw, alpha) in listOf(14f to 20, 9f to 40, 5f to 80, 2.5f to 140)) {
                Paint(Paint.ANTI_ALIAS_FLAG).also {
                    it.color = Color.argb(alpha, 0x4C, 0xAF, 0x50)
                    it.style = Paint.Style.STROKE; it.strokeWidth = lw
                    canvas.drawRoundRect(boxRF, 6f, 6f, it)
                }
            }
            Paint(Paint.ANTI_ALIAS_FLAG).also {
                it.color = Color.parseColor("#4CAF50"); it.style = Paint.Style.STROKE
                it.strokeWidth = 2f; it.pathEffect = DashPathEffect(floatArrayOf(8f, 5f), 0f)
                canvas.drawRoundRect(boxRF, 6f, 6f, it)
            }

            // ── Даты ─────────────────────────────────────────────────────
            val labelY = plotRect.bottom - 8f
            Paint(Paint.ANTI_ALIAS_FLAG).also {
                it.color = Color.parseColor("#4CAF50"); it.textSize = 19f
                it.typeface = Typeface.DEFAULT_BOLD; it.textAlign = Paint.Align.CENTER
                canvas.drawText("today", (boxRF.left + boxRF.right) / 2f, labelY, it)
            }
            val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; textSize = 18f; textAlign = Paint.Align.CENTER
            }
            for (i in 1..5) canvas.drawText(dates[i], xPx(i.toFloat()), labelY, datePaint)

            // ── Steps линия + подписи НАД ─────────────────────────────────
            val stpPath = Path()
            for (i in 0 until n) {
                val px = xPx(i.toFloat()); val py = yPx(stpData[i])
                if (i == 0) stpPath.moveTo(px, py) else stpPath.lineTo(px, py)
            }
            Paint(Paint.ANTI_ALIAS_FLAG).also {
                it.color = Color.parseColor(COLOR_STP); it.strokeWidth = 1.8f
                it.style = Paint.Style.STROKE; it.alpha = 220
                canvas.drawPath(stpPath, it)
            }
            val stpLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor(COLOR_STP); textSize = 17f; textAlign = Paint.Align.CENTER
            }
            for (i in 0 until n) {
                val px = xPx(i.toFloat()); val py = yPx(stpData[i])
                val lbl = if (stpData[i] > 0f) "${stpData[i].toInt()}" else "-"
                canvas.drawText(lbl, px, py - 8f, stpLabel)
            }

            // ── Calories линия + подписи ПОД ──────────────────────────────
            val calPath = Path()
            for (i in 0 until n) {
                val px = xPx(i.toFloat()); val py = yPx(calData[i])
                if (i == 0) calPath.moveTo(px, py) else calPath.lineTo(px, py)
            }
            Paint(Paint.ANTI_ALIAS_FLAG).also {
                it.color = Color.parseColor(COLOR_CAL); it.strokeWidth = 2.2f
                it.style = Paint.Style.STROKE; it.alpha = 220
                canvas.drawPath(calPath, it)
            }
            val calLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor(COLOR_CAL); textSize = 17f; textAlign = Paint.Align.CENTER
            }
            for (i in 0 until n) {
                val px = xPx(i.toFloat()); val py = yPx(calData[i])
                val lbl = if (calData[i] > 0f) "${calData[i].toInt()}" else "-"
                canvas.drawText(lbl, px, py + 24f, calLabel)
            }

            return bmp
        }

        fun scheduleAlarm(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                context, 1,
                Intent(context, ActivityWidget::class.java).apply { action = ACTION_UPDATE },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val interval = 30 * 60 * 1000L
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + interval, pi)
            } catch (e: SecurityException) {
                am.setInexactRepeating(AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + interval, interval, pi)
            }
        }
    }

    override fun onUpdate(context: Context, awm: AppWidgetManager, ids: IntArray) {
        for (id in ids) updateWidget(context, awm, id)
        scheduleAlarm(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_UPDATE) { updateWidgets(context); scheduleAlarm(context) }
    }

    override fun onEnabled(context: Context) { scheduleAlarm(context) }

    override fun onDisabled(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(PendingIntent.getBroadcast(
            context, 1,
            Intent(context, ActivityWidget::class.java).apply { action = ACTION_UPDATE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        ))
    }
}
