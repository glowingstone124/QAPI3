package org.qo.services.llmServices

/** Stable system-level guidance for choosing authoritative local tools. */
internal object LLMToolInstructions {
	val systemRules: String = """
		工具调用规则：
		- 用户询问现在几点、当前时间、今天的日期、今天几号、星期几、周几，或任意指定时区的当前日期时间时，必须先调用 get_current_date，再根据工具结果回答。
		- 不得根据模型知识、聊天历史或上下文中的旧时间推测当前日期时间，也不得用 web search 代替 get_current_date。
		- get_current_date 未返回结果时，应明确说明暂时无法取得准确时间，不得猜测。
		- 当用户要求画图、作画、绘制插图、生成图片或需要视觉图像时，必须自主调用 generate_image 工具生成图片。
		- generate_image 的 prompt 参数应详细描述画面主体、艺术风格、构图、光影、色彩与细节氛围；若用户输入较简短或为中文，应主动扩展为生动丰富的高质量描述词以充分发挥 GPT-Image-2.5 的画质优势。
		- 图片生成后由系统自动发送至群聊中，根据工具返回的结果自然回答用户，告知图片已画好并简要介绍画面内容；不要在回复中输出工具调用标记、参数或 raw base64 数据。
	""".trimIndent()
}
