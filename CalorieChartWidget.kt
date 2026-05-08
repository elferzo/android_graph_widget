package com.example.graphwidget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.widget.RemoteViews

class CalorieChartWidget : AppWidgetProvider() {

    companion object {
        const val ACTION_UPDATE = "com.example.graphwidget.ACTION_UPDATE"

        // ═══════════════════════════════════════════════════════════════════
        //  WHOOP DATA STUB
        //  Phase 2: заменить реальными данными с Whoop API
        //  Ровно 7 значений 0–100 (%), индекс 6 = сегодня
        // ═══════════════════════════════════════════════════════════════════
        var weekData = floatArrayOf(62f, 78f, 45f, 88f, 71f, 55f, 83f)
        // ═══════════════════════════════════════════════════════════════════

        fun updateWidgets(context: Context) {
            val awm = context.getSystemService(Context.APPWIDGET_SERVICE) as AppWidgetManager
            val ids = awm.getAppWidgetIds(ComponentName(context, CalorieChartWidget::class.java))
            for (id in ids) updateWidget(context, awm, id)
        }

        fun updateWidget(context: Context, awm: AppWidgetManager, widgetId: Int) {
            val rv = RemoteViews(context.packageName, R.layout.widget_calorie_chart)
            rv.setImageViewBitmap(R.id.chart_image, buildChartBitmap())
            awm.updateAppWidget(widgetId, rv)
        }

        fun buildChartBitmap(): Bitmap {
            val W = 800; val H = 300
            val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

            // ── Фон виджета ──────────────────────────────────────────────
            Paint(Paint.ANTI_ALIAS_FLAG).also {
                it.color = Color.parseColor("#1A1A2E"); it.style = Paint.Style.FILL
                canvas.drawRoundRect(0f, 0f, W.toFloat(), H.toFloat(), 18f, 18f, it)
            }

            // ── Геометрия ────────────────────────────────────────────────
            val padL = 58f; val padR = 12f; val padT = 22f; val padB = 18f
            val cW = W - padL - padR
            val cH = H - padT - padB

            val yMin = 0f; val yMax = 100f
            val n = weekData.size  // 7
            // rightPad в единицах шага — бокс today занимает 0.55 шага справа
            val todayRightExtra = 0.55f
            val xRange = (n - 1).toFloat() + todayRightExtra

            fun yPx(v: Float) = padT + cH * (1f - (v - yMin) / (yMax - yMin))
            fun xPx(i: Float) = padL + (i / xRange) * cW

            // ── Серая заливка области построения ────────────────────────
            val plotRect = RectF(padL, padT, padL + cW, padT + cH)
            Paint(Paint.ANTI_ALIAS_FLAG).also {
                it.color = Color.parseColor("#22222E"); it.style = Paint.Style.FILL
                canvas.drawRect(plotRect, it)
            }

            // ── Тонкая чёрная рамка области ──────────────────────────────
            Paint(Paint.ANTI_ALIAS_FLAG).also {
                it.color = Color.BLACK; it.style = Paint.Style.STROKE; it.strokeWidth = 1.2f
                canvas.drawRect(plotRect, it)
            }

            // ── Горизонтальные пунктиры (тихие, каждые 20%) ─────────────
            val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#55444455"); strokeWidth = 0.8f
                pathEffect = DashPathEffect(floatArrayOf(9f, 8f), 0f)
            }
            val yLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#778899"); textSize = 19f
                textAlign = Paint.Align.RIGHT
            }
            // Подписи только для 0, 60, 100
            val labeledTicks = setOf(0f, 60f, 100f)
            var g = 0f
            while (g <= 100f) {
                val gy = yPx(g)
                canvas.drawLine(padL + 1f, gy, padL + cW - 1f, gy, gridPaint)
                if (g in labeledTicks) {
                    val label = if (g == 0f) "0" else "${g.toInt()}%"
                    canvas.drawText(label, padL - 8f, gy + 7f, yLabelPaint)
                }
                g += 20f
            }

            // ── TODAY box: светящийся контур, без заливки ────────────────
            val todayIdx = n - 1
            val todayXc = xPx(todayIdx.toFloat())
            val halfL = cW / xRange * 0.46f
            val halfR = cW / xRange * 0.52f
            val boxRF = RectF(todayXc - halfL, padT, todayXc + halfR, padT + cH)

            // Слои свечения
            for ((lw, alpha) in listOf(14f to 20, 9f to 40, 5f to 80, 2.5f to 140)) {
                Paint(Paint.ANTI_ALIAS_FLAG).also {
                    it.color = Color.argb(alpha, 0x4C, 0xAF, 0x50)
                    it.style = Paint.Style.STROKE; it.strokeWidth = lw
                    canvas.drawRoundRect(boxRF, 6f, 6f, it)
                }
            }
            // Пунктирный контур
            Paint(Paint.ANTI_ALIAS_FLAG).also {
                it.color = Color.parseColor("#4CAF50"); it.style = Paint.Style.STROKE
                it.strokeWidth = 2f
                it.pathEffect = DashPathEffect(floatArrayOf(8f, 5f), 0f)
                canvas.drawRoundRect(boxRF, 6f, 6f, it)
            }
            // "today" внутри бокса, сверху по центру
            Paint(Paint.ANTI_ALIAS_FLAG).also {
                it.color = Color.parseColor("#4CAF50"); it.textSize = 21f
                it.typeface = Typeface.DEFAULT_BOLD; it.textAlign = Paint.Align.CENTER
                val centerX = (boxRF.left + boxRF.right) / 2f
                canvas.drawText("today", centerX, padT + 22f, it)
            }

            // ── Линия — все 7 точек соединены ────────────────────────────
            val linePath = Path()
            for (i in 0 until n) {
                val px = xPx(i.toFloat()); val py = yPx(weekData[i])
                if (i == 0) linePath.moveTo(px, py) else linePath.lineTo(px, py)
            }
            Paint(Paint.ANTI_ALIAS_FLAG).also {
                it.color = Color.parseColor("#DDFFB300"); it.strokeWidth = 2.2f
                it.style = Paint.Style.STROKE
                canvas.drawPath(linePath, it)
            }

            // ── Ромбы + подписи значений ──────────────────────────────────
            val diamondFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FFB300"); style = Paint.Style.FILL
            }
            for (i in 0 until n) {
                val px = xPx(i.toFloat()); val py = yPx(weekData[i])
                val isToday = (i == todayIdx)
                val r = if (isToday) 11f else 8f
                drawDiamond(canvas, px, py, r, diamondFill)

                val txt = "${weekData[i].toInt()}%"
                val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#FFB300")
                    textSize = if (isToday) 21f else 18f
                    typeface = if (isToday) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    textAlign = Paint.Align.CENTER
                }
                // подпись: сверху если точка выше 70%, снизу если ниже
                val offY = if (weekData[i] >= 70f) py + 26f else py - 14f
                canvas.drawText(txt, px, offY, lp)
            }

            return bmp
        }

        private fun drawDiamond(canvas: Canvas, cx: Float, cy: Float, r: Float, p: Paint) {
            val path = Path().apply {
                moveTo(cx, cy - r); lineTo(cx + r, cy)
                lineTo(cx, cy + r); lineTo(cx - r, cy); close()
            }
            canvas.drawPath(path, p)
        }

        fun scheduleAlarm(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                context, 0,
                Intent(context, CalorieChartWidget::class.java).apply { action = ACTION_UPDATE },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val fiveMin = 5 * 60 * 1000L
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + fiveMin, pi)
            } catch (e: SecurityException) {
                am.setInexactRepeating(AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + fiveMin, fiveMin, pi)
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
            Intent(context, CalorieChartWidget::class.java).apply { action = ACTION_UPDATE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        ))
    }
}
