package org.qo.services.llmServices.tools

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.reactor.awaitSingle
import org.qo.services.llmServices.LLMToolContext
import org.qo.services.loginService.KotshiPrivacyService
import org.qo.utils.UserProcess
import org.springframework.stereotype.Component

@Component
class GetQoPlayerProfileTool(
    private val userProcess: UserProcess,
    private val kotshiPrivacyService: KotshiPrivacyService,
) : Tools {
    override val id = "get_qo_player_profile"
    override val definition = ToolSupport.functionTool(
        name = id,
        description = "按 Minecraft 用户名确认 QO 玩家是否存在。用户要求查看玩家卡或玩家资料时必须先调用；只有 found=true 才能输出 qo-player-card。",
        properties = linkedMapOf(
            "username" to ToolSupport.property(
                type = "string",
                description = "要查询的完整 Minecraft 用户名，3 至 16 位英文字母、数字或下划线。",
            ),
        ),
        required = listOf("username"),
    )

    override suspend fun execute(args: JsonObject, context: LLMToolContext): String {
        val username = args.get("username")
            ?.takeIf { !it.isJsonNull }
            ?.asString
            ?.trim()
            ?.takeIf { it.matches(MINECRAFT_USERNAME) }
            ?: return ToolSupport.errorResult("invalid_username", "请输入有效的 Minecraft 用户名")

        if (!kotshiPrivacyService.isQueryEnabled(username)) {
            return ToolSupport.gson.toJson(JsonObject().apply {
                addProperty("found", false)
                addProperty("username", username)
            })
        }

        val registry = runCatching {
            JsonParser.parseString(userProcess.queryReg(username).awaitSingle()).asJsonObject
        }.getOrElse {
            return ToolSupport.errorResult("lookup_failed", "玩家资料查询暂时不可用")
        }
        val missing = registry.get("code")?.asInt == 1
        val affiliated = registry.get("affiliated")?.takeIf { !it.isJsonNull }?.asBoolean == true
        return ToolSupport.gson.toJson(JsonObject().apply {
            addProperty("found", !missing && !affiliated)
            addProperty("username", username)
        })
    }

    private companion object {
        val MINECRAFT_USERNAME = Regex("^[A-Za-z0-9_]{3,16}$")
    }
}
