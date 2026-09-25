# Web Fetch 微服务部署约定

在 `10.10.0.3` 服务器上，以独立 Python 服务用 Trafilatura 下载网页并提取正文。QAPI3 通过同一服务器上的 [DuckDuckGo 搜索中介](DUCKDUCKGO_SEARCH_SERVICE.md)搜索，并使用 `web_fetch` 工具按下述接口读取网页。

## 服务接口

- 监听：`10.10.0.3:9124`，只允许 QAPI3 所在主机访问。
- `GET /health`：返回 `200` 和 `{"status":"ok"}`。
- `POST /fetch`：请求体 `{"url":"https://example.org/article"}`，`Content-Type: application/json`。
- 成功返回 `200`：

```json
{
  "url": "https://example.org/article",
  "title": "文章标题",
  "date": "2026-09-18",
  "content": "提取后的正文……",
  "truncated": false
}
```

`title`、`date` 可为 `null`。`content` 最多 12,000 个字符；超出时截断并将 `truncated` 设为 `true`。失败统一返回 `{"error":"代码","message":"简短说明"}`：非法或内网 URL 用 `400`，无可提取正文用 `422`，下载失败用 `502`，超时用 `504`。

## Python 实现要点

1. 使用 Python 3.10+、Trafilatura 2.2.0。HTTP 层可用 FastAPI + Uvicorn；正文下载与提取交给 Trafilatura 的 `fetch_response()` 和 `extract(..., output_format="json", with_metadata=True, include_comments=False)`。[Trafilatura 官方用法](https://trafilatura.readthedocs.io/en/latest/corefunctions.html)
2. 只接受 `http`、`https` URL；拒绝本机、内网、链路本地、组播地址及其他非公网解析结果。Trafilatura 配置 `MAX_REDIRECTS = 0`，不跟随跳转，以免绕过地址检查。[Trafilatura 配置说明](https://trafilatura.readthedocs.io/en/latest/settings.html)
3. 下载超时设为 10 秒，输入页面最多 2 MB；服务端并发限制为 4。不要将网页正文写入日志；它是不可信外部文本。
4. 服务不接收搜索关键词，只读取 URL。QAPI3 负责先通过 DuckDuckGo 中介搜索，并按会话记录 15 分钟内的结果。Web 渠道可见 URL；QQ、Minecraft 等渠道只给模型不含 URL 的结果编号。`web_fetch` 在 QAPI3 内部把编号还原成 URL，再交给 `/fetch`，且不向非 Web 渠道返回 URL。

## 部署与验收

在 `10.10.0.3` 上创建独立目录和 Python 虚拟环境，安装固定版本的 `trafilatura==2.2.0`、FastAPI、Uvicorn。写好服务后用 systemd 托管，工作目录和 Python 路径都指向该虚拟环境；防火墙仅向 QAPI3 主机开放 TCP 9124。

部署后从 QAPI3 主机验证：

```bash
curl http://10.10.0.3:9124/health
curl -X POST http://10.10.0.3:9124/fetch \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://trafilatura.readthedocs.io/en/latest/quickstart.html"}'
```

验收标准：第二个请求返回非空正文；内网 URL 被拒绝；超时和不可提取页面返回约定错误。部署完成后，从 QAPI3 发起一次需要先搜索再读取正文的对话，确认 `web_fetch` 返回内容。

## 2026-09-25 在线排查记录

- `/health` 返回 200；Trafilatura Quickstart、Example Domain、Python.org HTTPS 页面均能提取正文。
- 同一测试站点的静态文章返回 200，而仅含 JavaScript 渲染逻辑、没有静态正文的页面返回 `422 no_content`。
- 指向可正常抓取页面的 302 跳转入口返回 `502 download_failed`；Python.org HTTP 入口也失败，而 HTTPS 页面成功。该行为与上述禁止跳转的部署约定一致，修改跳转策略前仍需检查实际服务实现，并逐跳验证目标地址。
- 切换前的 SearXNG 对两次普通查询返回 HTTP 200、`results: []`，同时在 `unresponsive_engines` 报告 Brave/Google CSE 限流、DuckDuckGo/Startpage 验证码。HTTP 200 本身不能证明搜索成功。
- 切换前 QAPI3 对“无可用结果且引擎报错”返回 `search_unavailable`，在服务器日志保留引擎诊断；部分引擎失败但仍有结果时继续返回可用结果。新中介将验证码和未知页面格式作为错误返回，不把它们当作空结果。

排查单次抓取失败仍需对应的原始 URL 和服务日志。`result_id` 是会话内临时引用，不能从历史 UUID 反推 URL。以上探测复现了错误类型，不能证明历史的每一条失败都由同一原因引起。
