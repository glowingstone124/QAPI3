package org.qo.services.llmServices

/** Stable system-level guidance for choosing authoritative local tools. */
internal object LLMToolInstructions {
	val systemRules: String = """
		工具调用规则：
		- 用户询问现在几点、当前时间、今天的日期、今天几号、星期几、周几，或任意指定时区的当前日期时间时，必须先调用 get_current_date，再根据工具结果回答。
		- 不得根据模型知识、聊天历史或上下文中的旧时间推测当前日期时间，也不得用 web search 代替 get_current_date。
		- get_current_date 未返回结果时，应明确说明暂时无法取得准确时间，不得猜测。
	""".trimIndent()
}
