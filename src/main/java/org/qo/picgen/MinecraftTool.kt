import com.google.gson.Gson
import org.qo.Request

class MinecraftTool {
    data class GameStats(
        val onlinecount: Int,
        val mspt: Double
    )

    val timer: Timer = Timer()

    fun getStat(): GameStats {
        val json = Request.sendGetRequest("http://qoriginal.vip:8080/qo/download/status").trimIndent()
        val gson = Gson()
        return gson.fromJson(json, GameStats::class.java)
    }
    fun getPing(): Int{
        val json = Request.sendGetRequest("https://uapis.cn/api/ping?host=43.248.96.196").trimIndent()
        val jsonParser :Gson = Gson()
        val result = jsonParser.toJson(json)
        return 0
    }
}
