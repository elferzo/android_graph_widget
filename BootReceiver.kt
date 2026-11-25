package com.example.graphwidget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Восстанавливает будильники виджетов после перезагрузки и после обновления APK.
 *
 * Второе обязательно: setExactAndAllowWhileIdle — одноразовый, сам себя не
 * переставляет. Если процесс убьют до следующего onUpdate, виджет замолчит
 * навсегда, пока пользователь не тронет его вручную.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> refresh(context)
        }
    }

    private fun refresh(context: Context) {
        // Будильники ставятся всегда: от токена они не зависят.
        GraphWidget.scheduleAlarm(context)
        ActivityWidget.scheduleAlarm(context)

        // Сеть дёргаем только при сохранённом токене. updateWidgets() запускает
        // Thread без try/catch, а fetchScores() вызывает token() вне safe {} —
        // при пустом хранилище исключение убьёт процесс (FATAL EXCEPTION в фоне).
        // tokenConfigured возвращает Boolean?, поэтому сравниваем с true явно.
        if (GarminClient.tokenConfigured(context) != true) return

        GraphWidget.updateWidgets(context)
        ActivityWidget.updateWidgets(context)
    }
}
