# DuckDuckGo 搜索中介部署

QAPI3 的 `web_search` 固定调用 `http://10.10.0.3:9123/search?q=...`，中介向 `https://lite.duckduckgo.com/lite/` 发起 POST 请求并解析结果。参考 [Agora 的 DuckDuckGoScraper](https://github.com/newo-ether/Agora/blob/master/app/src/main/java/com/newoether/agora/api/DuckDuckGoScraper.kt) 的协议行为；本仓库的 Python 实现为独立编写，不复制其 GPL-3.0 代码。中介部署在 QAPI3 以外的 `10.10.0.3` 服务器，取代原先占用 9123 端口的 SearXNG。现有 `web_fetch` 服务仍位于 `10.10.0.3:9124`。

## 接口

- `GET /health` → `200 {"status":"ok"}`。
- `GET /search?q=关键词` → `200 {"results":[{"title":"...","url":"https://...","snippet":"..."}]}`，最多 8 条。
- 参数不合法返回 400；DuckDuckGo 验证码或限流返回 429；上游故障或页面格式无法识别返回 502；超时返回 504。错误体为 `{"error":"代码","message":"说明"}`。验证码不能当成 `results: []`。
- 中介不记录查询词、网页内容或结果 URL。QAPI3 保留原有的会话级 URL 映射和非 Web 渠道的 `result_id` 隐藏规则。

## 部署

先在 `10.10.0.3` 上查明占用 TCP 9123 的 SearXNG systemd 单元或容器，停止并禁用它，再按实际部署方式移除旧服务。保持 `10.10.0.3:9124` 的 `web_fetch` 服务运行。将 [`services/duckduckgo_search.py`](../services/duckduckgo_search.py) 复制到 `/opt/qapi-ddg/duckduckgo_search.py`，使用 Python 3.10+，不需要第三方 Python 依赖。以 `python3 /opt/qapi-ddg/duckduckgo_search.py --host 10.10.0.3 --port 9123` 启动，用专用非特权账号和 systemd 托管，`Restart=on-failure`。防火墙只向 QAPI3 主机开放 TCP 9123。

QAPI3 已固定使用 `http://10.10.0.3:9123/search`，无需额外配置。中介不可用时，`web_search` 返回 `search_unavailable`；不会退回模型提供商的托管搜索。

从 QAPI3 主机验收：

```bash
curl --noproxy '*' -fsS http://10.10.0.3:9123/health
curl --noproxy '*' -fsSG --data-urlencode 'q=Python 3 documentation' http://10.10.0.3:9123/search
```

第二个请求必须返回至少一条含 `title`、`url`、`snippet` 的真实结果。再用明显无结果的查询和重复查询验证空结果、限流/验证码时返回不同状态。最后经 QAPI3 发起一次 `web_search` 和一次 `web_fetch`，确认 QQ/Minecraft 渠道只看到 `result_id`，Web 渠道看到 URL。DuckDuckGo Lite 可能改变页面格式或触发反爬，部署后仍需在线验证。

本地离线测试：`python3 -m unittest discover -s services -p 'test_duckduckgo_search.py'`。

2026-09-26 从 QAPI3 主机验收：`/health` 返回 `{"status":"ok"}`；上述搜索返回 8 条含标题、URL、摘要的结果。该检查确认搜索接口可用；QAPI3 的实际 LLM 对话仍需在部署新版本后验证。
