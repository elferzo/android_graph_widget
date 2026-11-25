package com.example.graphwidget

import android.app.AlarmManager
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Экран настройки: поле для блока токена Garmin, живая проверка токена
 * и обновление обоих виджетов сразу после сохранения.
 *
 * Layout строится кодом — тогда setup_project.sh править не нужно,
 * а экран остаётся одним файлом.
 *
 * Тема приложения — Theme.AppCompat.NoActionBar (тёмная), поэтому цвета
 * текста не переопределяются: иначе чёрный по тёмному фону не читается.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var saveButton: Button

    private val cOk = Color.parseColor("#66BB6A")
    private val cErr = Color.parseColor("#EF5350")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (20 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "GraphWidget — токен Garmin"
            textSize = 20f
            setPadding(0, 0, 0, pad)
        })

        root.addView(TextView(this).apply {
            text = "Токен выпускается один раз на компьютере:\n" +
                    "    pip install garth\n" +
                    "    python garmin_token.py\n\n" +
                    "Выведенную строку (начинается с {) вставьте сюда целиком.\n\n" +
                    "Пароль Garmin в приложении не хранится. OAuth1-токен действует " +
                    "около года, OAuth2 приложение обновляет само."
            textSize = 13f
            setPadding(0, 0, 0, pad)
        })

        input = EditText(this).apply {
            hint = "{\"consumer_key\":\"...\",\"consumer_secret\":\"...\"," +
                    "\"oauth1_token\":\"...\",\"oauth1_token_secret\":\"...\"}"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            isSingleLine = false
            minLines = 4
            gravity = Gravity.TOP or Gravity.START
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(input)

        saveButton = Button(this).apply {
            text = "Сохранить и обновить виджеты"
            setOnClickListener { onSaveClicked() }
        }
        root.addView(saveButton)

        status = TextView(this).apply {
            textSize = 13f
            setPadding(0, pad, 0, 0)
        }
        root.addView(status)

        val alreadySaved = GarminClient.tokenConfigured(this)

        root.addView(Button(this).apply {
            text = "Подставить сохранённый токен в поле"
            isEnabled = alreadySaved
            setOnClickListener {
                // Блок нужен, чтобы перевыпустить/поправить токен, не бегая
                // за ним заново на компьютер.
                val blob = getSharedPreferences("garmin_auth", MODE_PRIVATE)
                    .getString("token_blob", null)
                if (blob != null) {
                    input.setText(blob)
                    say("Сохранённый токен подставлен. Правьте и сохраняйте.", null)
                }
            }
        })

        root.addView(Button(this).apply {
            text = "Разрешить точные обновления"
            setOnClickListener { requestExactAlarms() }
        })

        setContentView(ScrollView(this).apply { addView(root) })

        say(
            if (alreadySaved) "Токен сохранён. Вставьте новый, чтобы заменить."
            else "Токен не настроен — виджеты показывают заглушки.",
            null
        )
    }

    private fun onSaveClicked() {
        val raw = input.text.toString().trim()
        if (raw.isEmpty()) { say("Поле пустое.", cErr); return }

        saveButton.isEnabled = false
        say("Проверяю токен у Garmin…", null)

        Thread {
            try {
                // Проверяем до записи: неверный блок не вытеснит рабочий.
                val name = GarminClient.verifyToken(raw)
                GarminClient.saveToken(this, raw)
                runOnUiThread {
                    saveButton.isEnabled = true
                    say("Готово, аккаунт: $name.\nОбновляю виджеты…", cOk)
                    refreshWidgets()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    saveButton.isEnabled = true
                    say("Не принят: ${e.message ?: e.javaClass.simpleName}", cErr)
                }
            }
        }.start()
    }

    private fun refreshWidgets() {
        try {
            GraphWidget.scheduleAlarm(this)
            GraphWidget.updateWidgets(this)
            ActivityWidget.scheduleAlarm(this)
            ActivityWidget.updateWidgets(this)
        } catch (e: Exception) {
            say("Токен сохранён, но обновление не запустилось: ${e.message}", cErr)
        }
    }

    private fun requestExactAlarms() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = getSystemService(ALARM_SERVICE) as AlarmManager
            if (!am.canScheduleExactAlarms()) {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                return
            }
        }
        say("Точные обновления уже разрешены.", cOk)
    }

    private fun say(text: String, color: Int?) {
        status.text = text
        if (color != null) status.setTextColor(color)
    }
}
