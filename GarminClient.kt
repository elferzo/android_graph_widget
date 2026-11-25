package com.example.graphwidget

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Сетевой слой Garmin Connect. Единственное место, откуда в приложении ходят
 * HTTP-запросы. Пароль не хранится нигде: живём на OAuth1 (≈год) + OAuth2
 * (приложение обновляет само). Исходный блок токена кладётся один раз в
 * SharedPreferences("garmin_auth", "token_blob") — см. garmin_token.py.
 *
 * Формат блока токена (одна строка JSON):
 * {
 *   "consumer_key":    "…",
 *   "consumer_secret": "…",
 *   "oauth1_token":    "…",
 *   "oauth1_token_secret": "…",
 *   "client_id": "GARMIN_CONNECT_MOBILE_ANDROID_DI"   // опционально
 * }
 */
object GarminClient {

    const val PREFS = "garmin_auth"
    const val KEY_BLOB = "token_blob"

    private const val EXCHANGE_URL = "https://connectapi.garmin.com/oauth-service/oauth/exchange/user/2.0"
    private const val REFRESH_URL = "https://connectapi.garmin.com/di-oauth2-service/oauth/token"
    private const val API = "https://connectapi.garmin.com"
    private const val DEFAULT_CLIENT_ID = "GARMIN_CONNECT_MOBILE_ANDROID_DI"
    private const val UA = "GCM-Android-5.8"

    /** Авторизация сломана — вызывающий должен уйти в кэш, а не рисовать нули. */
    class AuthException(message: String) : IOException(message)

    private val lock = Any()
    private var cachedOAuth2: OAuth2? = null
    private var cachedDisplayName: String? = null

    private class OAuth2(val token: String, val expiresAt: Long)

    // ─────────────────────────────────────────────────────────────
    //  Публичное API для виджетов: всегда 7 значений, [6] = сегодня
    // ─────────────────────────────────────────────────────────────

    /** Training Readiness (0..100) и Sleep Score (0..100). */
    fun fetchScores(ctx: Context): Pair<FloatArray, FloatArray> {
        val days = lastDays(7)
        val rec = FloatArray(7)
        val slp = FloatArray(7)
        token(ctx)                                  // падаем сразу, если токена нет
        for (i in days.indices) {
            rec[i] = safe { trainingReadiness(ctx, days[i]) } ?: 0f
            slp[i] = safe { sleepScore(ctx, days[i]) } ?: 0f
        }
        return Pair(rec, slp)
    }

    /** Шаги за 7 дней. */
    fun fetchSteps(ctx: Context): FloatArray {
        val days = lastDays(7)
        val out = FloatArray(7)
        token(ctx)
        for (i in days.indices) {
            out[i] = safe {
                dailySummary(ctx, days[i])?.optDouble("totalSteps", 0.0)?.toFloat()
            } ?: 0f
        }
        return out
    }

    /** Калории за 7 дней. */
    fun fetchCalories(ctx: Context): FloatArray {
        val days = lastDays(7)
        val out = FloatArray(7)
        token(ctx)
        for (i in days.indices) {
            out[i] = safe {
                dailySummary(ctx, days[i])?.optDouble("totalKilocalories", 0.0)?.toFloat()
            } ?: 0f
        }
        return out
    }

    // ─────────────────────────────────────────────────────────────
    //  Управление токеном (для MainActivity)
    // ─────────────────────────────────────────────────────────────

    fun tokenConfigured(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_BLOB, null)?.let { runCatching { JSONObject(it) }.getOrNull() } != null

    /** Проверяет и сохраняет блок токена. Бросает IllegalArgumentException с текстом для экрана. */
    fun saveToken(ctx: Context, raw: String) {
        val blob = try {
            JSONObject(raw.trim())
        } catch (e: Exception) {
            throw IllegalArgumentException("Не JSON: ${e.message}")
        }
        val required = listOf("consumer_key", "consumer_secret", "oauth1_token", "oauth1_token_secret")
        val missing = required.filter { blob.optString(it).isBlank() }
        if (missing.isNotEmpty()) {
            throw IllegalArgumentException("Нет полей: ${missing.joinToString(", ")}")
        }
        synchronized(lock) {
            cachedOAuth2 = null
            cachedDisplayName = null
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_BLOB, blob.toString()).apply()
    }

    /** Проверка токена до сохранения: пробует живьём получить профиль. */
    fun verifyToken(blobRaw: String): String {
        val blob = JSONObject(blobRaw.trim())
        val oauth2 = exchange(blob, clientId(blob))
        val body = request("$API/userprofile-service/socialProfile", "GET", { oauth2.token }, null, null)
        return JSONObject(body).optString("displayName").ifBlank { "(без имени)" }
    }

    // ─────────────────────────────────────────────────────────────
    //  Токен
    // ─────────────────────────────────────────────────────────────

    private fun loadBlob(ctx: Context): JSONObject {
        val s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_BLOB, null)
            ?: throw AuthException("Garmin-токен не настроен")
        return try {
            JSONObject(s)
        } catch (e: Exception) {
            throw AuthException("Блок токена повреждён: ${e.message}")
        }
    }

    private fun clientId(blob: JSONObject): String =
        blob.optString("client_id").ifBlank { DEFAULT_CLIENT_ID }

    private fun token(ctx: Context): OAuth2 = synchronized(lock) {
        val now = System.currentTimeMillis()
        cachedOAuth2?.let { if (it.expiresAt - 5 * 60_000L > now) return it }

        val blob = loadBlob(ctx)
        blob.optJSONObject("oauth2")?.let { saved ->
            val refresh = saved.optString("refresh_token")
            val access = saved.optString("access_token")
            val expires = saved.optLong("expires_at", 0L)
            if (access.isNotEmpty() && expires - 5 * 60_000L > now) {
                return OAuth2(access, expires).also { cachedOAuth2 = it }
            }
            if (refresh.isNotEmpty()) {
                val refreshed = try {
                    refreshOAuth2(refresh, clientId(blob))
                } catch (e: Exception) {
                    null
                }
                if (refreshed != null) {
                    persist(ctx, blob, refreshed)
                    return refreshed.also { cachedOAuth2 = it }
                }
            }
        }

        // OAuth1 → OAuth2 (первый запуск или refresh-токен протух)
        val fresh = exchange(blob, clientId(blob))
        persist(ctx, blob, fresh)
        cachedOAuth2 = fresh
        return fresh
    }

    private fun persist(ctx: Context, blob: JSONObject, o: OAuth2) {
        val saved = blob.optJSONObject("oauth2") ?: JSONObject()
        saved.put("access_token", o.token)
        saved.put("expires_at", o.expiresAt)
        blob.put("oauth2", saved)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_BLOB, blob.toString()).apply()
    }

    private fun refreshOAuth2(refreshToken: String, clientId: String): OAuth2? {
        val body = "grant_type=refresh_token" +
                "&client_id=" + enc(clientId) +
                "&refresh_token=" + enc(refreshToken)
        val res = try {
            request(REFRESH_URL, "POST", null, body, null)
        } catch (e: Exception) {
            null
        } ?: return null
        val json = JSONObject(res)
        val access = json.optString("access_token")
        if (access.isEmpty()) return null
        val ttl = json.optLong("expires_in", 3600L)
        return OAuth2(access, System.currentTimeMillis() + ttl * 1000L)
    }

    private fun exchange(blob: JSONObject, clientId: String): OAuth2 {
        val ck = blob.optString("consumer_key")
        val cs = blob.optString("consumer_secret")
        val tk = blob.optString("oauth1_token")
        val ts = blob.optString("oauth1_token_secret")
        val auth = oauth1Header(ck, cs, tk, ts)

        // Garmin принимает обмен и POST'ом, и GET'ом — пробуем оба.
        var body: String? = null
        for (method in listOf("POST", "GET")) {
            body = try {
                request(EXCHANGE_URL, method, null, if (method == "POST") "" else null, auth)
            } catch (e: Exception) {
                null
            }
            if (body != null) break
        }
        if (body == null) throw AuthException("Обмен OAuth1→OAuth2 не удался")

        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw AuthException("Неожиданный ответ обмена: ${body.take(120)}")
        }
        val access = json.optString("access_token")
        if (access.isEmpty()) throw AuthException("В ответе обмена нет access_token")

        val ttl = json.optLong("expires_in", 3600L)
        if (json.optString("refresh_token").isNotEmpty()) {
            val saved = blob.optJSONObject("oauth2") ?: JSONObject()
            saved.put("refresh_token", json.optString("refresh_token"))
            blob.put("oauth2", saved)
        }
        return OAuth2(access, System.currentTimeMillis() + ttl * 1000L)
    }

    // ─────────────────────────────────────────────────────────────
    //  Данные
    // ─────────────────────────────────────────────────────────────

    private fun displayName(ctx: Context): String? = synchronized(lock) {
        cachedDisplayName?.let { return it }
        val body = apiGet(ctx, "$API/userprofile-service/socialProfile")
        val name = JSONObject(body).optString("displayName")
        if (name.isNotBlank()) cachedDisplayName = name
        name.ifBlank { null }
    }

    /** Garmin отдаёт либо массив, либо объект — разбираем оба варианта. */
    private fun trainingReadiness(ctx: Context, date: String): Float? {
        val body = apiGet(ctx, "$API/metrics-service/metrics/trainingreadiness/$date")
        val trimmed = body.trim()

        fun scoreOf(o: JSONObject): Float? {
            o.optJSONObject("trainingReadiness")?.let { tr ->
                val v = tr.optDouble("score", Double.NaN)
                if (!v.isNaN()) return v.toFloat()
            }
            val direct = o.optDouble("score", Double.NaN)
            return if (direct.isNaN()) null else direct.toFloat()
        }

        if (trimmed.startsWith("[")) {
            val arr = JSONArray(trimmed)
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { scoreOf(it)?.let { s -> return s } }
            }
            return null
        }
        if (trimmed.startsWith("{")) {
            val o = JSONObject(trimmed)
            scoreOf(o)?.let { return it }
            o.optJSONObject("trainingReadinessList")?.let { list ->
                for (i in 0 until list.length()) {
                    list.optJSONObject(i)?.let { scoreOf(it)?.let { s -> return s } }
                }
            }
        }
        return null
    }

    private fun sleepScore(ctx: Context, date: String): Float? {
        val dn = displayName(ctx) ?: return null
        val url = "$API/wellness-service/wellness/dailySleepData/$dn?date=$date&nonSleepBufferMinutes=60"
        val json = JSONObject(apiGet(ctx, url))
        val v = json.optJSONObject("sleepScores")
            ?.optJSONObject("overall")
            ?.optDouble("value", Double.NaN) ?: return null
        return if (v.isNaN()) null else v.toFloat()
    }

    private fun dailySummary(ctx: Context, date: String): JSONObject? {
        val dn = displayName(ctx) ?: return null
        val url = "$API/usersummary-service/usersummary/daily/$dn?calendarDate=$date"
        return JSONObject(apiGet(ctx, url))
    }
            }
        }
        throw IOException("HTTP $lastCode")
    }

    private fun invalidateToken() = synchronized(lock) { cachedOAuth2 = null }

    private fun currentTokenOrNull(): String? = synchronized(lock) { cachedOAuth2?.token }

    // ─────────────────────────────────────────────────────────────
    //  OAuth1 (HMAC-SHA1)
    // ─────────────────────────────────────────────────────────────

    private fun oauth1Header(ck: String, cs: String, tk: String, ts: String): String {
        val oauth = sortedMapOf(
            "oauth_consumer_key" to ck,
            "oauth_nonce" to UUID.randomUUID().toString().replace("-", ""),
            "oauth_signature_method" to "HMAC-SHA1",
            "oauth_timestamp" to (System.currentTimeMillis() / 1000L).toString(),
            "oauth_token" to tk,
            "oauth_version" to "1.0"
        )
        val paramString = oauth.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        val base = "POST&${enc(EXCHANGE_URL)}&${enc(paramString)}"
        val signature = hmacSha1("${enc(cs)}&${enc(ts)}", base)

        val all = sortedMapOf<String, String>()
        all.putAll(oauth)
        all["oauth_signature"] = signature
        return "OAuth " + all.entries.joinToString(", ") { "${enc(it.key)}=\"${enc(it.value)}\"" }
    }

    private fun hmacSha1(key: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        return Base64.getEncoder().encodeToString(mac.doFinal(data.toByteArray(Charsets.UTF_8)))
    }

    private fun enc(s: String): String =
        URLEncoder.encode(s, "UTF-8")
            .replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~")

    // ─────────────────────────────────────────────────────────────
    //  Даты
    // ─────────────────────────────────────────────────────────────

    private fun lastDays(n: Int): List<String> {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val base = Calendar.getInstance()
        return (0 until n).map { i ->
            val c = base.clone() as Calendar
            c.add(Calendar.DAY_OF_YEAR, -(n - 1 - i))
            sdf.format(c.time)
        }
    }
}
