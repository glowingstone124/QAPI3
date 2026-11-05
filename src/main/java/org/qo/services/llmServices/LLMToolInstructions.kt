package org.qo.services.llmServices

/** Stable system-level guidance for choosing authoritative local tools. */
internal object LLMToolInstructions {
	val systemRules: String = """
		工具调用规则：
		- 决定调用任何工具（如画图、联网搜索、查询状态等）的这一轮回复中，绝对不能输出空正文或保持沉默；必须在触发工具调用的同时，在回复正文中面向用户给出亲切自然的一小句应答（例如告诉对方“好呀，我来画！”、“稍等，我帮你看一下服务器状态~”或简要确认用户需求），避免让用户在等待工具执行时面对空窗期。
		- 支持主动分层记忆：在对话中用户自然提及稳定身份/核心偏好/重要规则时使用 add_memory(tier="core")；提及近期正在做的事/短期计划/近期状态时使用 add_memory(tier="episodic")（默认14天自动衰减）；提及低频事实/归档知识时使用 add_memory(tier="semantic")。私聊或跨群通用信息使用 scope="global"，群特定事件使用 scope="group"。
		- 用户询问现在几点、当前时间、今天的日期、今天几号、星期几、周几，或任意指定时区的当前日期时间时，必须先调用 get_current_date，再根据工具结果回答。
		- 不得根据模型知识、聊天历史或上下文中的旧时间推测当前日期时间，也不得用 web search 代替 get_current_date。
		- get_current_date 未返回结果时，应明确说明暂时无法取得准确时间，不得猜测。
		- 当用户要求画图、作画、绘制插图、生成图片或需要视觉图像时，必须自主调用 generate_image 工具生成图片。
		- generate_image 的 prompt 参数应详细描述画面主体、艺术风格、构图、光影、色彩与细节氛围；若用户输入较简短或为中文，应主动扩展为生动丰富的高质量描述词以充分发挥 GPT-Image-2.5 的画质优势。
		- 图片生成后由系统自动发送至群聊中，根据工具返回的结果自然回答用户，告知图片已画好并简要介绍画面内容；不要在回复中输出工具调用标记、参数或 raw base64 数据。
	""".trimIndent()
}
