package org.qo.mcsmanager

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.qo.Logger
import org.qo.Request
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class InstanceUtil {
    val instance_id = "bae6e76b155c4f799c7ba8179d97eb45"
    val gson: Gson = Gson()
    var main_Version: Double = 0.0
    val target_main_version = 1.21
    var revision_version: Int = 0
    val api_endpoint = "https://api.purpurmc.org/v2/purpur/$target_main_version"
    val instance_config_location = "D:/MCSManager-v10-windows-x64/mcsmanager/daemon/data/InstanceConfig/"
    var instance_folder_location: String = "cwd"
    val request = Request()
    fun readConfig() {
        val fullPath = Path.of(instance_config_location, "$instance_id.json")
        val config = JsonParser.parseString(Files.readString(fullPath))
        val startup_args: List<String> = config.asJsonObject.get("startCommand").asString.split(" ")
        instance_folder_location = config.asJsonObject.get("folder").asString
        startup_args.forEach {
            val pattern = Regex("""purpur-(\d+\.\d+)-(\d+)\.jar""")
            val matchResult = pattern.find(it)
            if (matchResult != null) {
                val (mainVersion, revisionVersion) = matchResult.destructured
                main_Version = mainVersion.toDouble()
                revision_version = revisionVersion.toInt()
            }
        }
        println("Read config: main_Version=$main_Version, revision_version=$revision_version")
    }

    fun compare() {
        val latest_ver = JsonParser.parseString(request.sendGetRequest(api_endpoint).get()).asJsonObject.get("builds").asJsonObject.get("latest").asInt
        println("Latest version from API: $latest_ver")
        if (latest_ver > revision_version) {
            if (revision_version == 0) {
                println("Unable to get local game version")
                return
            }
            request.download("$api_endpoint/$latest_ver/download", instance_folder_location)
            revision_version = latest_ver
            println("Downloaded new version: $revision_version")
            Logger.log("Downloaded new version: $latest_ver", Logger.LogLevel.INFO)
            Logger.log("Updating...", Logger.LogLevel.INFO)
            change()
        } else {
            println("No new version available")
        }
    }

    fun change() {
        val fullPath = Path.of(instance_config_location, "$instance_id.json")
        val config = JsonParser.parseString(Files.readString(fullPath)).asJsonObject
        val startup_args: List<String> = config.get("startCommand").asString.split(" ")
        val updated_args = startup_args.map { arg ->
            arg.replace(Regex("""purpur-(\d+\.\d+)-(\d+)\.jar""")) { matchResult ->
                val (mainVersion, revisionVersion) = matchResult.destructured
                "purpur-$target_main_version-$revision_version.jar"
            }
        }
        config.addProperty("startCommand", updated_args.joinToString(" "))
        Files.writeString(fullPath, gson.toJson(config))
        println("Updated start command: ${updated_args.joinToString(" ")}")
    }
    fun run() {
        val scheduler = Executors.newScheduledThreadPool(1)

        val task = Runnable {
            readConfig()
            compare()
        }

        val initialDelay = 0L
        val period = 24L

        scheduler.scheduleAtFixedRate(task, initialDelay, period, TimeUnit.HOURS)
    }
}
