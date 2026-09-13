# AI Weekly Limits and Paid Credits

## 配置

免费配额通过 QQ UID 跨入口共享，默认 QQ 30 Units/周、注册 90 Units/周。周一北京时间 00:00 以周账本自然切换。Paid Credits 不过期。

服务端 `data/llm/providers.json` 支持顶层路由：

```json
"routes": { "fast": "deepseek", "thinking": "google" }
```

每个 provider 继续声明 `fast`、`thinking` 模型与各协议 endpoint。`models.<alias>.pricing` 必须配置准确的人民币价格：

```json
"pricing": {
  "inputCnyPerMillion": 1,
  "outputCnyPerMillion": 3,
  "cachedInputCnyPerMillion": 0.1,
  "callCostCny": 0
}
```

以上数字只说明字段格式，不是模型报价。部署前按实际 API 供应商账单配置全部模型（含摘要模型），外币价格需先换算为人民币。`callCostCny` 用于每次调用固定成本；token 之外的搜索等费用须包含在供应商计价配置中。价格在一次请求内保持同一配置快照。缺失模型计价时阻止聊天调用，摘要则跳过。

Fast 默认关闭思考；Thinking 默认 medium。只有显式 high/max 参数才提升到 high，不通过关键词识别任务。QQ 入口始终关闭思考，切换档位不改变此策略。前端只显示能力档位，响应的 model 使用档位别名。

官方参考：[DeepSeek models/pricing](https://api-docs.deepseek.com/quick_start/pricing)、[Gemini OpenAI compatibility](https://ai.google.dev/gemini-api/docs/openai)、[Afdian API](https://guide.afdian.com/creator/developer)。Webhook RSA 公钥和 `custom_order_id` 行为依据本次提供的开发者文档。

## 爱发电

配置固定读取服务端工作目录下的 `data/afdian.json`，不读取爱发电环境变量或 Spring properties。可从 [配置示例](examples/afdian.json) 复制并填写：

```json
{
  "userId": "",
  "apiToken": "",
  "queryUrl": "https://ifdian.net/api/open/query-order",
  "packs": {
    "5": {
      "skuId": "",
      "checkoutUrl": ""
    },
    "10": {
      "skuId": "",
      "checkoutUrl": ""
    },
    "20": {
      "skuId": "",
      "checkoutUrl": ""
    }
  }
}
```

`userId` 和 `apiToken` 来自开发者后台。`packs` 的键为价格，`skuId` 和 `checkoutUrl` 对应该档商品；Credits 数量仍固定为 350、800、1800。`queryUrl` 可省略，默认使用 `https://ifdian.net/api/open/query-order`。

配置在服务启动时读取，修改后重启后端生效。文件缺失或对应 SKU/购买链接留空时，该档购买不可用；已有文件格式或 URL 无效时启动报错。实际配置文件已加入 `.gitignore`，仓库保留无凭据示例。

Checkout URL 是每档商品的 HTTPS 购买链接，必须指向 ifdian.net、afdian.com 或 afdian.net，不能预含 custom_order_id 或 fragment。后台将购买意向 UUID 添加为 custom_order_id；不使用客户端提供的 QQ ID 作为充值归属。爱发电商品应为售卖类型，订单只有一个 SKU、数量 1、实付金额分别为 5.00、10.00、20.00；不接受折扣金额或兑换码替代现金。

开发者后台回调固定配置为 `https://<API域名>/hooks/afdian`。查询 API 地址可配置为原 afdian.com 域名。凭据只保存在服务端，Webhook 内容不参与最终余额更新。验签、官方查询在数据库事务之前进行；购买意向、订单、余额、credit ledger 在一个事务内提交。

保存地址时不含订单对象的连通性通知返回 `{"ec":200,"em":""}`，不查询订单、不改变余额；含商品订单的通知仍需通过完整验签和官方查询。格式或签名无效返回 400，官方查询或数据库不可用返回 503，并记录不含凭据的失败类别。

普通赞助通知（`product_type: 0`，包括后台的普通赞助测试样例）直接确认收达，不参与 Credits 充值。售卖商品通知仍需完整验签及官方查询。已验签通知的订单暂时不在查询结果中时，写入 `ai_afdian_pending_order` 后返回 `ec=200`，不按通知内容入账；队列写入失败仍返回 503。

后台每 30 秒检查待核验订单，每批最多 10 个，失败后逐步延长重试间隔至最多 1 小时；队列跨进程重启保留。查询到真实订单后执行原有 SKU、金额和购买意向验证与幂等充值。验证不匹配的记录标记为 `review` 供人工核对，其他查询失败继续重试。测试订单查询为空也只会排队，不增加 Credits。

三档充值为 ¥5/350、¥10/800、¥20/1800，没有 ¥50 档。

## 结算和预算

使用 `BigDecimal` 计算成本，Units 向上取整且至少 1。缓存命中 token 单独计价；输出 token 已包含 reasoning，不再次相加。Chat Completions、Responses、Anthropic 多轮工具调用累计 Usage。

`ai_quota_account` 保存 Paid Credits；`ai_weekly_usage` 按用户/周保存已用及预留 Units；`ai_quota_reservation` 保存预留池拆分及真实 provider/model。`ai_usage` 保存真实 token、成本与最终扣费；`ai_credit_ledger` 保存充值、消费、退款。所有用户扣费操作先锁用户账户，再锁周账本与月预算。

`ai_free_budget.actual_cost` 是免费补贴真实成本，`reserved_cost` 防止并行请求超售预算。跨池请求按免费 Units 占全部 Units 的比例拆分真实成本，Paid 部分不计入免费补贴。摘要等后台模型请求也通过 `ai_system_usage` 计入月预算。

- 80 元：日志预警，大于 30 Units 的免费 Thinking 请求转用 Paid Credits。
- 90 元：Thinking 预留全部使用 Paid Credits。
- 95 元：所有预留使用 Paid Credits。
- 接近上限时允许预留跨免费预算和 Paid 池，避免已有 Paid Credits 的用户被免费预算阻断。

预估不能保证和供应商实际计费完全一致（尤其图片、工具新增上下文、搜索费用和缺失 Usage）。正常低估在余额与预算允许时补扣；无法覆盖或缺失 Usage 则保留预留为 pending，并记录已知成本。已知 Usage 可在充值后通过 reconcile 接口重试。进程崩溃、Usage 缺失及未知供应商费用需运营对账；不要直接删除预留或自动按估值收费。免费硬限制是调用准入预算，不能撤销供应商已经发生的成本。

失败退款与结算幂等，退款始终操作请求原所属周及原月预留，不影响新周。当前未提供历史付费余额迁移，因为原系统没有付费账本。
