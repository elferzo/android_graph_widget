package com.dualclock.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.Log
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.*

class DualClockWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        Log.e("DUALCLOCK", "onUpdate ids=${appWidgetIds.toList()}")
        for (id in appWidgetIds) update(context, appWidgetManager, id)
        schedule(context)
    }

    override fun onEnabled(context: Context) {
        Log.e("DUALCLOCK", "onEnabled")
        schedule(context)
    }

    override fun onDisabled(context: Context) {
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
            .cancel(pendingIntent(context))
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == TICK) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, DualClockWidget::class.java))
            for (id in ids) update(context, mgr, id)
            schedule(context)
        }
    }

    companion object {
        const val TICK = "com.dualclock.TICK"

        private fun buildText(city: String, time: String): SpannableString {
            val s = SpannableString("$city\n$time")
            val ts = city.length + 1
            val te = s.length
            s.setSpan(StyleSpan(Typeface.BOLD), 0, te, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            s.setSpan(RelativeSizeSpan(2.8f), ts, te, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return s
        }

        fun update(context: Context, mgr: AppWidgetManager, id: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_layout)
            val now = Date()
            val tf = SimpleDateFormat("HH:mm", Locale.getDefault())

            tf.timeZone = TimeZone.getTimeZone("Europe/Moscow")
            views.setTextViewText(R.id.city1_block, buildText("Москва", tf.format(now)))

            tf.timeZone = TimeZone.getTimeZone("GMT+5")
            views.setTextViewText(R.id.city2_block, buildText("Когалым", tf.format(now)))

            mgr.updateAppWidget(id, views)
        }

        fun schedule(context: Context) {
            val cal = Calendar.getInstance().apply {
                add(Calendar.MINUTE, 1)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
                .setExactAndAllowWhileIdle(AlarmManager.RTC, cal.timeInMillis, pendingIntent(context))
        }

        fun pendingIntent(context: Context): PendingIntent {
            val i = Intent(context, DualClockWidget::class.java).apply { action = TICK }
            return PendingIntent.getBroadcast(
                context, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
