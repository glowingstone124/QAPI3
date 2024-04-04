
import com.google.gson.Gson
import org.qo.Request

class MinecraftTool {
    data class GameStats(
        val onlinecount: Int,
        val mspt: Double
    )

    val timer: Timer = Timer()


    fun getStat(): GameStats {
        val apiQueryTime: Long = timer.measure{
            val json = Request.sendGetRequest("http://qoriginal.vip:8080/qo/download/status").trimIndent()
            val gson = Gson()
            val gameStats = gson.fromJson(json, GameStats::class.java)
        }
        val gameStats: GameStats by lazy {
            val json = Request.sendGetRequest("http://qoriginal.vip:8080/qo/download/status").trimIndent()
            val gson = Gson()
            gson.fromJson(json, GameStats::class.java)
        }
        return gameStats
    }
}
