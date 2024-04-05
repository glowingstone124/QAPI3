import com.google.gson.Gson
import org.qo.Request
import java.util.function.DoubleToLongFunction

class MinecraftTool {
    data class GameStats(
        val onlinecount: Int,
        val mspt: Double
    )
    data class PingResponse(
        val avg: Double
    )
    val timer: Timer = Timer()

    fun getStat(): GameStats {
        val json = Request.sendGetRequest("http://qoriginal.vip:8080/qo/download/status").trimIndent()
        val gson = Gson()
        return gson.fromJson(json, GameStats::class.java)
    }
    fun getPing(): Double{
        val json = Request.sendGetRequest("https://uapis.cn/api/ping?host=43.248.96.196").trimIndent()
        val jsonParser :Gson = Gson()
        val result = jsonParser.fromJson(json, PingResponse::class.java);
        return result.avg
    }
}
