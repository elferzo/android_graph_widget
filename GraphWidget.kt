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

class GraphWidget : AppWidgetProvider() {

    companion object {
        const val ACTION_UPDATE  = "com.example.graphwidget.ACTION_UPDATE"
        const val SERVER_URL     = "http://178.208.86.99:5001/recovery"
        const val PREFS_NAME     = "graphwidget_prefs"
        const val KEY_DATA       = "last_data"
        const val COLOR_REC      = "#FFB300"
        const val COLOR_SLP      = "#29B6F6"

        val DEFAULT_REC = floatArrayOf(42f, 38f, 48f, 55f, 80f, 77f, 84f)
        val DEFAULT_SLP = floatArrayOf(65f, 72f, 55f, 80f, 60f, 88f, 70f)

        fun updateWidgets(context: Context) {
            val awm = context.getSystemService(Context.APPWIDGET_SERVICE) as AppWidgetManager
            val ids = awm.getAppWidgetIds(ComponentName(context, GraphWidget::class.java))
            for (id in ids) updateWidget(context, awm, id)
        }

        fun updateWidget(context: Context, awm: AppWidgetManager, widgetId: Int) {
            Thread {
                val (rec, slp) = fetchScores(context)
                val rv = RemoteViews(context.packageName, R.layout.widget_calorie_chart)
                rv.setImageViewBitmap(R.id.chart_image, buildChartBitmap(rec, slp))
                awm.updateAppWidgetOptions(widgetId, Bundle())
                awm.updateAppWidget(widgetId, rv)
            }.start()
        }

        fun fetchScores(context: Context): Pair<FloatArray, FloatArray> {
            return try {
                val conn = URL(SERVER_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000; conn.readTimeout = 5000
                conn.requestMethod = "GET"
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().readText()
                    val json = JSONObject(body)
                    val rec  = json.getJSONArray("scores")
                    val slp  = json.optJSONArray("sleep")
                    val recArr = FloatArray(rec.length()) { rec.getDouble(it).toFloat() }
                    val slpArr = if (slp != null) FloatArray(slp.length()) { slp.getDouble(it).toFloat() }
                                 else DEFAULT_SLP
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putString(KEY_DATA, body).apply()
                    Pair(recArr, slpArr)
                } else loadCached(context)
            } catch (e: Exception) { loadCached(context) }
        }

        fun loadCached(context: Context): Pair<FloatArray, FloatArray> {
            val s = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_DATA, null) ?: return Pair(DEFAULT_REC, DEFAULT_SLP)
            return try {
                val json = JSONObject(s)
                val rec  = json.getJSONArray("scores")
                val slp  = json.optJSONArray("sleep")
                val recArr = FloatArray(rec.length()) { rec.getDouble(it).toFloat() }
                val slpArr = if (slp != null) FloatArray(slp.length()) { slp.getDouble(it).toFloat() }
                             else DEFAULT_SLP
                Pair(recArr, slpArr)
            } catch (e: Exception) { Pair(DEFAULT_REC, DEFAULT_SLP) }
        }

        fun buildChartBitmap(
            recData: FloatArray = DEFAULT_REC,
            slpData: FloatArray = DEFAULT_SLP
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

            // ── Геометрия ─────────────────────────────────────────────────
            val titleH = 52f   // 2 строки заголовка
            val padL   = 58f; val padR = 12f
            val padT   = titleH + 4f; val padB = 10f
            val plotRect = RectF(padL, padT, W - padR, H - padB)
            val cW = plotRect.width(); val cH = plotRect.height()

            val yMin = 0f; val yMax = 100f
            val n = recData.size
            val todayRightExtra = 0.55f
            val xRange = (n - 1).toFloat() + todayRightExtra

            fun yPx(v: Float) = plotRect.top + cH * (1f - (v - yMin) / (yMax - yMin))
            fun xPx(i: Float) = plotRect.left + (i / xRange) * cW

            // ── Заголовок строка 1: ___recovery___... ────────────────────
            val uPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#888899"); textSize = 23f
                typeface = Typeface.MONOSPACE
            }
            val recPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor(COLOR_REC); textSize = 23f
                typeface = Typeface.MONOSPACE
            }
            val slpPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor(COLOR_SLP); textSize = 23f
                typeface = Typeface.MONOSPACE
            }
            val charW    = uPaint.measureText("_")
            val leftU    = "___"
            val leftUW   = uPaint.measureText(leftU)
            val recWordW = recPaint.measureText("recovery")
            val rightCnt = ((W - padR - padL - leftUW - recWordW) / charW).toInt()
            val line1Y   = padT - 26f
            val line2Y   = padT - 4f

            canvas.drawText(leftU, padL, line1Y, uPaint)
            canvas.drawText("recovery", padL + leftUW, line1Y, recPaint)
            canvas.drawText("_".repeat(rightCnt), padL + leftUW + recWordW, line1Y, uPaint)

            // ── Заголовок строка 2:        sleep ─────────────────────────
            canvas.drawText("sleep", padL + leftUW, line2Y, slpPaint)

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

            // ── Sleep линия + ромбы + подписи ─────────────────────────────
            val slpLinePath = Path()
            for (i in 0 until n) {
                val px = xPx(i.toFloat()); val py = yPx(slpData[i])
                if (i == 0) slpLinePath.moveTo(px, py) else slpLinePath.lineTo(px, py)
            }
            Paint(Paint.ANTI_ALIAS_FLAG).also {
                it.color = Color.parseColor(COLOR_SLP); it.strokeWidth = 1.8f
                it.style = Paint.Style.STROKE; it.alpha = 220
                canvas.drawPath(slpLinePath, it)
            }
            val slpLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor(COLOR_SLP); textSize = 17f; textAlign = Paint.Align.CENTER
            }
            for (i in 0 until n) {
                val px = xPx(i.toFloat()); val py = yPx(slpData[i])
                canvas.drawText("${slpData[i].toInt()}%", px, py - 8f, slpLabel)
            }

            // ── Recovery линия (без маркеров) + подписи ПОД линией ───────
            val recLinePath = Path()
            for (i in 0 until n) {
                val px = xPx(i.toFloat()); val py = yPx(recData[i])
                if (i == 0) recLinePath.moveTo(px, py) else recLinePath.lineTo(px, py)
            }
            Paint(Paint.ANTI_ALIAS_FLAG).also {
                it.color = Color.parseColor(COLOR_REC); it.strokeWidth = 2.2f
                it.style = Paint.Style.STROKE; it.alpha = 220
                canvas.drawPath(recLinePath, it)
            }
            val recLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor(COLOR_REC); textSize = 17f; textAlign = Paint.Align.CENTER
            }
            for (i in 0 until n) {
                val px = xPx(i.toFloat()); val py = yPx(recData[i])
                // подпись всегда ПОД линией
                canvas.drawText("${recData[i].toInt()}%", px, py + 24f, recLabel)
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
                context, 0,
                Intent(context, GraphWidget::class.java).apply { action = ACTION_UPDATE },
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
            context, 0,
            Intent(context, GraphWidget::class.java).apply { action = ACTION_UPDATE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        ))
    }
}
