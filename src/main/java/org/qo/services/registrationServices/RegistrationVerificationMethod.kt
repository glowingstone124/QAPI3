package org.qo.services.registrationServices

enum class RegistrationVerificationMethod(
	val id: String,
	val displayName: String,
	val description: String,
	val available: Boolean,
	val legacy: Boolean
) {
	QUIZ(
		id = "quiz",
		displayName = "网页答题验证",
		description = "完成现有网页选择题并达到配置的通过分数。",
		available = true,
		legacy = true
	),
	MINECRAFT(
		id = "minecraft",
		displayName = "Minecraft 世界测试",
		description = "进入 Minecraft 世界完成交互测试。",
		available = false,
		legacy = false
	);

	companion object {
		@JvmStatic
		fun parse(value: String?): RegistrationVerificationMethod? {
			if (value.isNullOrBlank()) return QUIZ
			return entries.firstOrNull { it.id.equals(value.trim(), ignoreCase = true) }
		}
	}
}

data class MinecraftVerificationSessionRequest(
	val name: String?,
	val uid: Long?
)

data class MinecraftVerificationResultRequest(
	val sessionId: String?,
	val name: String?,
	val passed: Boolean?
)
