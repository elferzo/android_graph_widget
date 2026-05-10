package com.example.graphwidget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.ContentUris
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
        const val KEY_CACHE      = "last_activity"
        const val STEPS_GOAL     = 10_000f
        const val CALORIES_GOAL  = 2500f

        val DEFAULT_CALORIES = floatArrayOf(2200f, 2400f, 2100f, 2300f, 2500f, 2200f, 2000f)
        val DEFAULT_STEPS    = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f)

        fun calPct(kcal: Float)    = (kcal / CALORIES_GOAL * 100f).coerceIn(0f, 100f)
        fun stepsPct(steps: Float) = (steps / STEPS_GOAL * 100f).coerceIn(0f, 100f)

        fun updateWidgets(context: Context) {
            val awm = context.getSystemService(Context.APPWIDGET_SERVICE) as AppWidgetManager
            val ids = awm.getAppWidgetIds(ComponentName(context, ActivityWidget::class.java))
            for (id in ids) updateWidget(context, awm, id)
        }

        fun updateWidget(context: Context, awm: AppWidgetManager, widgetId: Int) {
            CoroutineScope(Dispatchers.IO).launch {
                val calories = fetchCalories(context)
                val steps    = fetchSteps(context)
                val rv = RemoteViews(context.packageName, R.layout.widget_activity_chart)
                rv.setImageViewBitmap(R.id.activity_chart_image,
                    buildChartBitmap(calories, steps))
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
                    val arr  = JSONObject(body).getJSONArray("calories")
                    val result = FloatArray(arr.length()) { arr.getDouble(it).toFloat() }
                    val cached = try {
                        JSONObject(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .getString(KEY_CACHE, "{}") ?: "{}")
                    } catch (e: Exception) { JSONObject() }
                    cached.put("calories", arr)
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putString(KEY_CACHE, cached.toString()).apply()
                    result
                } else loadCachedCalories(context)
            } catch (e: Exception) { loadCachedCalories(context) }
        }

        fun loadCachedCalories(context: Context): FloatArray {
            val s = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_CACHE, null) ?: return DEFAULT_CALORIES
            return try {
                val arr = JSONObject(s).getJSONArray("calories")
                FloatArray(arr.length()) { arr.getDouble(it).toFloat() }
            } catch (e: Exception) { DEFAULT_CALORIES }
        }

        // ── Шаги из Health Connect через ContentResolver ──────────────────
        // Health Connect URI для шагов (работает на Android 9+ с установленным HC)
        fun fetchSteps(context: Context): FloatArray {
            val result = FloatArray(7) { 0f }
            return try {
                val cal = Calendar.getInstance()
                // Сброс до конца сегодняшнего дня
                cal.set(Calendar.HOUR_OF_DAY, 23)
                cal.set(Calendar.MINUTE, 59)
                cal.set(Calendar.SECOND, 59)

                val authority = "com.google.android.apps.healthdata"
                val stepsUri = Uri.parse("content://$authority/data_type/steps_aggregate")

                for (i in 6 downTo 0) {
                    val endTime   = cal.timeInMillis
                    val startTime = endTime - (i * 86_400_000L)
                    val dayEnd    = endTime - ((i - 1).coerceAtLeast(0) * 86_400_000L)

                    val dayStart = run {
                        val c = Calendar.getInstance()
                        c.timeInMillis = endTime - (i * 86_400_000L)
                        c.set(Calendar.HOUR_OF_DAY, 0)
                        c.set(Calendar.MINUTE, 0)
                        c.set(Calendar.SECOND, 0)
                        c.timeInMillis
                    }
                    val dayEnd2 = run {
                        val c = Calendar.getInstance()
                        c.timeInMillis = endTime - (i * 86_400_000L)
                        c.set(Calendar.HOUR_OF_DAY, 23)
                        c.set(Calendar.MINUTE, 59)
                        c.set(Calendar.SECOND, 59)
                        c.timeInMillis
                    }

                    val cursor = context.contentResolver.query(
                        stepsUri,
                        arrayOf("steps"),
                        "start_time >= ? AND end_time <= ?",
                        arrayOf(dayStart.toString(), dayEnd2.toString()),
                        null
                    )
                    cursor?.use {
                        var total = 0L
                        while (it.moveToNext()) {
                            total += it.getLong(0)
                        }
                        result[6 - i] = total.toFloat()
                    }
                }
                result
            } catch (e: Exception) {
                DEFAULT_STEPS
            }
        }

        fun buildChartBitmap(
            caloriesRaw: FloatArray = DEFAULT_CALORIES,
            stepsRaw: FloatArray    = DEFAULT_STEPS
        ): Bitmap {
            val W = 800; val H = 340
            val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

            val sdf = SimpleDateFormat("dd.MM", Locale.getDefault())
            val dates = Array(7) { i ->
                sdf.format(Calendar.getInstance().apply {
                    add(Calendar.DAY_OF_YEAR, -(6 - i))
                }.time)
            }

            // ── Геометрия идентична GraphWidget ───────────────────────────
            val titleH = 36f
            val padL   = 58f; val padR = 12f
            val padT   = titleH + 4f
            val padB   = 10f
            val plotRect = RectF(padL, padT, W - padR, H - padB)
            val cW = plotRect.width(); val cH = plotRect.height()
            val yMin = 0f; val yMax = 100f
            val n = 7
            val todayRightExtra = 0.55f
            val xRange = (n - 1).toFloat() + todayRightExtra

            fun yPx(v: Float) = plotRect.top + cH * (1f - (v - yMin) / (yMax - yMin))
            fun xPx(i: Float) = plotRect.left + (i / xRange) * cW

            val calPcts   = FloatArray(7) { calPct(caloriesRaw[it]) }
            val stepsPcts = FloatArray(7) { stepsPct(stepsRaw[it]) }

            // ── Заголовок ─────────────────────────────────────────────────
            val uPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#888899"); textSize = 24f
                typeface = Typeface.MONOSPACE
            }
            val calTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FF6D00"); textSize = 24f
                typeface = Typeface.MONOSPACE
            }
            val stepsTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#00BCD4"); textSize = 24f
                typeface = Typeface.MONOSPACE
            }

            val titleY   = padT - 8f
            val leftU    = "___"
            val leftUW   = uPaint.measureText(leftU)
            val wordCalW = calTitlePaint.measureText("calories")
            val charW    = uPaint.measureText("_")
            val rightCount = ((W - padR - padL - leftUW - wordCalW) / charW).toInt()

            canvas.drawText("___", padL, titleY, uPaint)
            canvas.drawText("calories", padL + leftUW, titleY, calTitlePaint)
            canvas.drawText("_".repeat(rightCount), padL + leftUW + wordCalW, titleY, uPaint)
            canvas.drawText("steps", padL + leftUW, titleY + 22f, stepsTitlePaint)

            // ── Заливка + рамка ───────────────────────────────────────────
            Paint(Paint.ANTI_ALIAS_FLAG).also {
                it.color = Color.argb(128, 0x22, 0x22, 0x2E)
                it.style = Paint.Style.FILL
                canvas.drawRect(plotRect, it)
            }
            Paint(Paint.ANTI_ALIAS_FLAG).also {
                it.color = Color.BLACK; it.style = Paint.Style.STROKE; it.strokeWidth = 1.2f
                canvas.drawRect(plotRect, it)
            }

            // ── Сетка + подписи Y ─────────────────────────────────────────
            val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#55444455"); strokeWidth = 0.8f
                pathEffect = DashPathEffect(floatArrayOf(9f, 8f), 0f)
            }
            val yLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#778899"); textSize = 19f
                textAlign = Paint.Align.RIGHT
            }
            var g = 0f
            while (g <= 100f) {
                val gy = yPx(g)
                canvas.drawLine(plotRect.left, gy, plotRect.right, gy, gridPaint)
                if (g == 0f || g == 60f || g == 100f) {
                    val label = if (g == 0f) "0" else "${g.toInt()}%"
                    canvas.drawText(label, padL - 6f, gy + 7f, yLabelPaint)
                }
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

            // ── "today" + даты ─────────────────────────────────────────────
            val labelY = plotRect.bottom - 8f
            val labelPaintGreen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#4CAF50"); textSize = 19f
                typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
            }
            val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; textSize = 18f; textAlign = Paint.Align.CENTER
            }
            canvas.drawText("today", (boxRF.left + boxRF.right) / 2f, labelY, labelPaintGreen)
            for (i in 1..5) {
                canvas.drawText(dates[i], xPx(i.toFloat()), labelY, datePaint)
            }

            // ── Линия calories (оранжевая) ────────────────────────────────
            val calPath = Path()
            for (i in 0 until n) {
                val px = xPx(i.toFloat()); val py = yPx(calPcts[i])
                if (i == 0) calPath.moveTo(px, py) else calPath.lineTo(px, py)
            }
            Paint(Paint.ANTI_ALIAS_FLAG).also {
                it.color = Color.parseColor("#FF6D00"); it.strokeWidth = 2.2f
                it.style = Paint.Style.STROKE
                canvas.drawPath(calPath, it)
            }

            // ── Линия steps (голубая) ─────────────────────────────────────
            val stepsPath = Path()
            for (i in 0 until n) {
                val px = xPx(i.toFloat()); val py = yPx(stepsPcts[i])
                if (i == 0) stepsPath.moveTo(px, py) else stepsPath.lineTo(px, py)
            }
            Paint(Paint.ANTI_ALIAS_FLAG).also {
                it.color = Color.parseColor("#00BCD4"); it.strokeWidth = 2.2f
                it.style = Paint.Style.STROKE
                canvas.drawPath(stepsPath, it)
            }

            // ── Ромбы + подписи calories (над линией) ─────────────────────
            val calDiamond = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FF6D00"); style = Paint.Style.FILL
            }
            for (i in 0 until n) {
                val px = xPx(i.toFloat()); val py = yPx(calPcts[i])
                val isToday = (i == todayIdx)
                drawDiamond(canvas, px, py, if (isToday) 11f else 8f, calDiamond)
                Paint(Paint.ANTI_ALIAS_FLAG).also {
                    it.color = Color.parseColor("#FF6D00")
                    it.textSize = if (isToday) 21f else 18f
                    it.typeface = if (isToday) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    it.textAlign = Paint.Align.CENTER
                    val offY = if (calPcts[i] >= 85f) py + 26f else py - 14f
                    val label = if (caloriesRaw[i] > 0f) "${caloriesRaw[i].toInt()}" else "-"
                    canvas.drawText(label, px, offY, it)
                }
            }

            // ── Ромбы + подписи steps (под линией) ───────────────────────
            val stepsDiamond = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#00BCD4"); style = Paint.Style.FILL
            }
            for (i in 0 until n) {
                val px = xPx(i.toFloat()); val py = yPx(stepsPcts[i])
                val isToday = (i == todayIdx)
                drawDiamond(canvas, px, py, if (isToday) 11f else 8f, stepsDiamond)
                Paint(Paint.ANTI_ALIAS_FLAG).also {
                    it.color = Color.parseColor("#00BCD4")
                    it.textSize = if (isToday) 21f else 18f
                    it.typeface = if (isToday) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    it.textAlign = Paint.Align.CENTER
                    val offY = if (stepsPcts[i] <= 15f) py - 14f else py + 26f
                    val label = if (stepsRaw[i] > 0f) "${stepsRaw[i].toInt()}" else "-"
                    canvas.drawText(label, px, offY, it)
                }
            }

            return bmp
        }

        private fun drawDiamond(canvas: Canvas, cx: Float, cy: Float, r: Float, p: Paint) {
            canvas.drawPath(Path().apply {
                moveTo(cx, cy - r); lineTo(cx + r, cy)
                lineTo(cx, cy + r); lineTo(cx - r, cy); close()
            }, p)
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
