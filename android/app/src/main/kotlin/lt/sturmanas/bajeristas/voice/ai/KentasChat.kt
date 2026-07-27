package lt.sturmanas.bajeristas.voice.ai

import android.util.Log
import lt.sturmanas.bajeristas.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object KentasChat {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val history = mutableListOf<JSONObject>()
    private const val MAX_HISTORY = 3

    private const val SYSTEM_PROMPT = """
Tu esi Kentas — lietuviškas bachūras, vairuotojo draugas kelionėje.

Charakteris:
- Pasitikintis, greito proto, gatviškai išmintingas, laikysena be protokolų.
- Kalbi kaip draugas, kuris sėdi šalia ir žino apie ką kalba — ne kaip asistentas.
- Gali lengvai pašiepti situaciją ar vairuotoją — su šypsena, ne su pykčiu.
- Juodas humoras priimtinas kai tinka. Slengas — saikingai ir natūraliai.
- Nesi AI ir to niekada neminėk. Nesi korporatyvas: jokie "žinoma!", "mielai padėsiu!", "ar dar ko reikia?".
- Niekada nekartok tų pačių atsako frazių per pokalbį.

Atsakymų forma:
- Pirmiausia — esmė. Komentaras — tik jei prideda vertę.
- Tikslas: 1–3 sakiniai, iki 35 žodžių. Ilgiau — tik jei vartotojas aiškiai prašo detalių.
- Trumpų klausimų — trumpi atsakymai.

Vairavimas ir sauga:
- Artėjant posūkiui ar sudėtingai situacijai — trumpink atsakymą, neblaškok.
- Neišgalvok duomenų (atstumai, laikas, oras, eismas), kurių nebuvo pateikta.
- Jei duomenų nėra — sakyk tiesiai, ne fantastiką.
- Sauga visada svarbiau už pokalbį ir humorą.
"""

    fun askKentas(query: String, callback: (String) -> Unit) {
        val apiKey = BuildConfig.OPENAI_API_KEY
        if (apiKey.isBlank()) {
            callback("Susitvarkyk savo API raktą, tada plepėsim.")
            return
        }

        if (history.isEmpty()) {
            history.add(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
        }

        history.add(JSONObject().put("role", "user").put("content", query))
        if (history.size > (MAX_HISTORY * 2) + 1) {
            val toRemove = history.size - ((MAX_HISTORY * 2) + 1)
            repeat(toRemove) {
                if (history[1].getString("role") != "system") {
                    history.removeAt(1)
                }
            }
        }

        val requestBody = JSONObject()
            .put("model", "gpt-4o-mini")
            .put("messages", JSONArray(history))
            .toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                Log.e("KentasChat", "OpenAI API Error", e)
                callback("Internetas nulūžo. Pabandom vėliau.")
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val bodyString = response.body?.string()
                if (response.isSuccessful && bodyString != null) {
                    val json = JSONObject(bodyString)
                    val content = json.getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                    history.add(JSONObject().put("role", "assistant").put("content", content))
                    callback(content)
                } else {
                    Log.e("KentasChat", "OpenAI API Unsuccessful: $bodyString")
                    callback("Kažkas negerai. Pabandom dar kartą.")
                }
            }
        })
    }

    fun getOpener(): String {
        val openers = listOf(
            "Ko tyli kaip per laidotuves? Vairuok ramiai.",
            "Gal nori, kad papasakočiau kokį nors bajerį?",
            "Vairuok, nesidairyk, bet galim ir paplepėti.",
            "Ei, neužmik už vairo, dar manęs prireiks.",
            "Kelias lygus, laikas yra — klausk ko nori.",
        )
        return openers.random()
    }
}
