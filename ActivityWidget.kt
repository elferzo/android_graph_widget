package com.example.graphwidget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
        const val COLOR_CAL      = "#FF6D00"   // оранжевый — calories
        const val COLOR_STP      = "#29B6F6"   // голубой — steps (тот же что sleep)
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
                val (cal, stp) = fetchData(context)
                val rv = RemoteViews(context.packageName, R.layout.widget_activity_chart)
                rv.setImageViewBitmap(R.id.activity_chart_image, buildChartBitmap(cal, stp))
                awm.updateAppWidget(widgetId, rv)
            }
        }

        fun fetchData(context: Context): Pair<FloatArray, FloatArray> {
            return try {
                val conn = URL(SERVER_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000; conn.readTimeout = 5000
                conn.requestMethod = "GET"
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().readText()
                    val json = JSONObject(body)
                    val cal  = json.getJSONArray("calories")
                    val calArr = FloatArray(cal.length()) { cal.getDouble(it).toFloat() }
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putString(KEY_DATA, body).apply()
                    val stpArr = fetchSteps(context)
                    Pair(calArr, stpArr)
                } else loadCached(context)
            } catch (e: Exception) { loadCached(context) }
        }

        fun loadCached(context: Context): Pair<FloatArray, FloatArray> {
            val s = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_DATA, null) ?: return Pair(DEFAULT_CAL, DEFAULT_STP)
            return try {
                val json   = JSONObject(s)
                val cal    = json.getJSONArray("calories")
                val calArr = FloatArray(cal.length()) { cal.getDouble(it).toFloat() }
                Pair(calArr, DEFAULT_STP)
            } catch (e: Exception) { Pair(DEFAULT_CAL, DEFAULT_STP) }
        }

        // ── Шаги из Health Connect через ContentResolver ──────────────────
        fun fetchSteps(context: Context): FloatArray {
            val result = FloatArray(7) { 0f }
            return try {
                val stepsUri = Uri.parse(
                    "content://com.google.android.apps.healthdata/data_type/steps_aggregate"
                )
                for (i in 6 downTo 0) {
                    val cal = Calendar.getInstance()
                    cal.add(Calendar.DAY_OF_YEAR, -i)
                    cal.set(Calendar.HOUR_OF_DAY, 0)
                    cal.set(Calendar.MINUTE, 0)
                    cal.set(Calendar.SECOND, 0)
                    cal.set(Calendar.MILLISECOND, 0)
                    val dayStart = cal.timeInMillis
                    cal.set(Calendar.HOUR_OF_DAY, 23)
                    cal.set(Calendar.MINUTE, 59)
                    cal.set(Calendar.SECOND, 59)
                    val dayEnd = cal.timeInMillis

                    val cursor = context.contentResolver.query(
                        stepsUri,
                        arrayOf("steps"),
                        "start_time >= ? AND end_time <= ?",
                        arrayOf(dayStart.toString(), dayEnd.toString()),
                        null
                    )
                    cursor?.use {
                        var total = 0L
                        while (it.moveToNext()) total += it.getLong(0)
                        result[6 - i] = total.toFloat()
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

            // ── Даты ─────────────────────────────────────────────────────
            val sdf = SimpleDateFormat("dd.MM", Locale.getDefault())
            val dates = Array(7) { i ->
                sdf.format(Calendar.getInstance().apply {
                    add(Calendar.DAY_OF_YEAR, -(6 - i))
                }.time)
            }

            // ── Геометрия (идентична GraphWidget) ─────────────────────────
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

            // Нормализуем к %
            val calPcts = FloatArray(7) { calPct(calData[it]) }
            val stpPcts = FloatArray(7) { stepsPct(stpData[it]) }

            // ── Заголовок строка 1: ___calories___... ─────────────────────
            val uPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#888899"); textSize = 23f
                typeface = Typeface.MONOSPACE
            }
            val calPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor(COLOR_CAL); textSize = 23f
                typeface = Typeface.MONOSPACE
            }
            val stpPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor(COLOR_STP); textSize = 23f
                typeface = Typeface.MONOSPACE
            }
            val charW    = uPaint.measureText("_")
            val leftU    = "___"
            val leftUW   = uPaint.measureText(leftU)
            val calWordW = calPaint.measureText("calories")
            val rightCnt = ((W - padR - padL - leftUW - calWordW) / charW).toInt()
            val line1Y   = padT - 26f
            val line2Y   = padT - 4f

            canvas.drawText(leftU, padL, line1Y, uPaint)
            canvas.drawText("calories", padL + leftUW, line1Y, calPaint)
            canvas.drawText("_".repeat(rightCnt), padL + leftUW + calWordW, line1Y, uPaint)

            // ── Заголовок строка 2: steps ─────────────────────────────────
            canvas.drawText("steps", padL + leftUW, line2Y, stpPaint)

            // ── Полупрозрачная заливка 50% ────────────────────────────────
            Paint(Paint.ANTI_ALIAS_FLAG).also {
                it.color = Color.argb(128, 0x22, 0x22, 0x2E); it.style = Paint.Style.FILL
                canvas.drawRect(plotRect, it)
            }
            // ── Тонкая чёрная рамка ───────────────────────────────────────
            Paint(Paint.ANTI_ALIAS_FLAG).also {
                it.color = Color.BLACK; it.style = Paint.Style.STROKE; it.strokeWidth = 1.2f
                canvas.drawRect(plotRect, it)
            }

            // ── Горизонтальные пунктиры + подписи Y ──────────────────────
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
            val halfL = cW / xRange * 0.46f
            val halfR = cW / xRange * 0.52f
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

            // ── "today" + даты на одном уровне ───────────────────────────
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

            // ── Steps линия + подписи НАД линией ─────────────────────────
            val stpLinePath = Path()
            for (i in 0 until n) {
                val px = xPx(i.toFloat()); val py = yPx(stpPcts[i])
                if (i == 0) stpLinePath.moveTo(px, py) else stpLinePath.lineTo(px, py)
            }
            Paint(Paint.ANTI_ALIAS_FLAG).also {
                it.color = Color.parseColor(COLOR_STP); it.strokeWidth = 1.8f
                it.style = Paint.Style.STROKE; it.alpha = 220
                canvas.drawPath(stpLinePath, it)
            }
            val stpLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor(COLOR_STP); textSize = 17f; textAlign = Paint.Align.CENTER
            }
            for (i in 0 until n) {
                val px  = xPx(i.toFloat()); val py = yPx(stpPcts[i])
                val lbl = if (stpData[i] > 0f) "${stpData[i].toInt()}" else "-"
                canvas.drawText(lbl, px, py - 8f, stpLabel)
            }

            // ── Calories линия + подписи ПОД линией ──────────────────────
            val calLinePath = Path()
            for (i in 0 until n) {
                val px = xPx(i.toFloat()); val py = yPx(calPcts[i])
                if (i == 0) calLinePath.moveTo(px, py) else calLinePath.lineTo(px, py)
            }
            Paint(Paint.ANTI_ALIAS_FLAG).also {
                it.color = Color.parseColor(COLOR_CAL); it.strokeWidth = 2.2f
                it.style = Paint.Style.STROKE; it.alpha = 220
                canvas.drawPath(calLinePath, it)
            }
            val calLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor(COLOR_CAL); textSize = 17f; textAlign = Paint.Align.CENTER
            }
            for (i in 0 until n) {
                val px  = xPx(i.toFloat()); val py = yPx(calPcts[i])
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
