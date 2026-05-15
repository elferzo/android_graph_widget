package com.example.graphwidget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.widget.RemoteViews
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

class ActivityWidget : AppWidgetProvider() {

    companion object {
        const val ACTION_UPDATE  = "com.example.graphwidget.ACTION_UPDATE_ACTIVITY"
        const val SERVER_URL     = "http://178.208.86.99:5001/activity"
        const val PREFS_NAME     = "activitywidget_prefs"
        const val KEY_DATA       = "last_data"
        const val COLOR_CAL      = "#FF6D00"
        const val COLOR_STP      = "#29B6F6"
        const val STEPS_GOAL     = 10_000f
        const val CALORIES_GOAL  = 2500f

        val DEFAULT_CAL = floatArrayOf(2200f, 2400f, 2100f, 2300f, 2500f, 2200f, 2000f)
        val DEFAULT_STP = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f)

        fun calPct(kcal: Float)    = (kcal / CALORIES_GOAL * 100f).coerceIn(0f, 100f)
        fun stepsPct(steps: Float) = (steps / STEPS_GOAL * 100f).coerceIn(0f, 100f)

        fun updateWidgets(context: Context) {
            val awm = context.getSystemService(Context.APPWIDGET_SERVICE) as AppWidgetManager
            val ids = awm.getAppWidgetIds(ComponentName(context, ActivityWidget::class.java))
            for (id in ids) updateWidget(context, awm, id)
        }

        fun updateWidget(context: Context, awm: AppWidgetManager, widgetId: Int) {
            CoroutineScope(Dispatchers.IO).launch {
                val cal = fetchCalories(context)
                val stp = fetchStepsContentResolver(context)
                val rv = RemoteViews(context.packageName, R.layout.widget_activity_chart)
                rv.setImageViewBitmap(R.id.activity_chart_image, buildChartBitmap(cal, stp))
                awm.updateAppWidget(widgetId, rv)
            }
        }

        // ── Калории с сервера ─────────────────────────────────────────────
        fun fetchCalories(context: Context): FloatArray {
            return try {
                val conn = URL(SERVER_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000; conn.readTimeout = 5000
                conn.requestMethod = "GET"
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().readText()
                    val json = JSONObject(body)
                    val arr  = json.getJSONArray("calories")
                    val result = FloatArray(arr.length()) { arr.getDouble(it).toFloat() }
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putString(KEY_DATA, body).apply()
                    result
                } else loadCachedCalories(context)
            } catch (e: Exception) { loadCachedCalories(context) }
        }

        fun loadCachedCalories(context: Context): FloatArray {
            val s = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_DATA, null) ?: return DEFAULT_CAL
            return try {
                val arr = JSONObject(s).getJSONArray("calories")
                FloatArray(arr.length()) { arr.getDouble(it).toFloat() }
            } catch (e: Exception) { DEFAULT_CAL }
        }

        // ── Шаги через ContentResolver из Health Connect ──────────────────
        fun fetchStepsContentResolver(context: Context): FloatArray {
            val result = FloatArray(7) { 0f }
            return try {
                val cal = Calendar.getInstance()
                for (i in 6 downTo 0) {
                    val dayEnd = Calendar.getInstance().apply {
                        add(Calendar.DAY_OF_YEAR, -(6 - i) * -1 + 6 - i - (6 - i))
                        set(Calendar.HOUR_OF_DAY, 23)
                        set(Calendar.MINUTE, 59)
                        set(Calendar.SECOND, 59)
                        set(Calendar.MILLISECOND, 999)
                        add(Calendar.DAY_OF_YEAR, -(6 - i))
                    }
                    val dayStart = Calendar.getInstance().apply {
                        timeInMillis = dayEnd.timeInMillis
                        set(Calendar.HOUR_OF_DAY, 0)
                        set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }

                    // Health Connect ContentResolver URI
                    val uri = Uri.parse("content://androidx.health.platform.client.provider/health_data")
                    var cursor: Cursor? = null
                    try {
                        cursor = context.contentResolver.query(
                            uri,
                            null,
                            "data_type = ? AND start_time >= ? AND end_time <= ?",
                            arrayOf("Steps", dayStart.timeInMillis.toString(), dayEnd.timeInMillis.toString()),
                            null
                        )
                        var total = 0L
                        cursor?.use {
                            while (it.moveToNext()) {
                                val idx = it.getColumnIndex("count")
                                if (idx >= 0) total += it.getLong(idx)
                            }
                        }
                        result[6 - i] = total.toFloat()
                    } catch (e: Exception) {
                        // попробуем альтернативный URI
                        try {
                            val uri2 = Uri.parse("content://com.google.android.apps.healthdata/data_type/steps_daily_summary")
                            val c2 = context.contentResolver.query(uri2, null,
                                "start_time >= ? AND end_time <= ?",
                                arrayOf(dayStart.timeInMillis.toString(), dayEnd.timeInMillis.toString()), null)
                            var total2 = 0L
                            c2?.use { while (it.moveToNext()) { total2 += it.getLong(0) } }
                            result[6 - i] = total2.toFloat()
                        } catch (e2: Exception) { /* оставляем 0 */ }
                    }
                }
                result
            } catch (e: Exception) { DEFAULT_STP }
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
            val padL   = 58f; val padR = 12f
            val padT   = titleH + 4f; val padB = 10f
            val plotRect = RectF(padL, padT, W - padR, H - padB)
            val cW = plotRect.width(); val cH = plotRect.height()
            val yMin = 0f; val yMax = 100f
            val n = 7
            val todayRightExtra = 0.55f
            val xRange = (n - 1).toFloat() + todayRightExtra

            fun yPx(v: Float) = plotRect.top + cH * (1f - (v - yMin) / (yMax - yMin))
            fun xPx(i: Float) = plotRect.left + (i / xRange) * cW

            val calPcts = FloatArray(7) { calPct(calData[it]) }
            val stpPcts = FloatArray(7) { stepsPct(stpData[it]) }

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

            // ── Сетка ─────────────────────────────────────────────────────
            val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#55444455"); strokeWidth = 0.8f
                pathEffect = DashPathEffect(floatArrayOf(9f, 8f), 0f)
            }
            val yLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#778899"); textSize = 19f; textAlign = Paint.Align.RIGHT
            }
            var g = 0f
            while (g <= 100f) {
                val gy = yPx(g)
                canvas.drawLine(plotRect.left, gy, plotRect.right, gy, gridPaint)
                if (g == 0f || g == 60f || g == 100f)
                    canvas.drawText(if (g == 0f) "0" else "${g.toInt()}%", padL - 6f, gy + 7f, yLabelPaint)
                g += 20f
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

            // ── Steps линия ───────────────────────────────────────────────
            val stpPath = Path()
            for (i in 0 until n) {
                val px = xPx(i.toFloat()); val py = yPx(stpPcts[i])
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
                val px = xPx(i.toFloat()); val py = yPx(stpPcts[i])
                val lbl = if (stpData[i] > 0f) "${stpData[i].toInt()}" else "-"
                canvas.drawText(lbl, px, py - 8f, stpLabel)
            }

            // ── Calories линия ────────────────────────────────────────────
            val calPath = Path()
            for (i in 0 until n) {
                val px = xPx(i.toFloat()); val py = yPx(calPcts[i])
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
                val px = xPx(i.toFloat()); val py = yPx(calPcts[i])
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
