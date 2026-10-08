# NorthStar 服务端静态接口部署包

启动器四个接口全部以**静态 JSON 文件**实现,由 nginx 直接返回;`/update` 带查询参数请求,
通过 `location = /update` + `try_files` 忽略参数统一返回 `update.json`。

## 文件清单

| 文件 | 接口 | 用途 |
| --- | --- | --- |
| `announcement.json` | `GET /announcement` | 主页公告卡 |
| `client.json` | `GET /client` | 侧边栏「下载」的客户端整合包清单 |
| `update.json` | `GET /update` | 启动器自更新清单 |
| `anticheat.json` | `GET /anticheat` | 次元反作弊清单(当前 `enabled:false`,空配置静默跳过) |
| `nginx-northstar.conf` | — | nginx location 配置片段 |

## 部署步骤(nginx)

1. 把四个 JSON 上传到站点根目录(与 `northstar.mingpixel.net` 现有站点同一 root),
   例如 `/var/www/northstar/`:
   ```bash
   scp server/*.json user@server:/var/www/northstar/
   ```
2. 把 `nginx-northstar.conf` 里的 `location` 块合并进 `northstar.mingpixel.net` 的 server 块:
   ```bash
   scp server/nginx-northstar.conf user@server:/etc/nginx/snippets/northstar-api.conf
   # 在 server 块内 include /etc/nginx/snippets/northstar-api.conf;
   nginx -t && systemctl reload nginx
   ```
3. 验证(本机或任意机器):
   ```bash
   curl -s "https://northstar.mingpixel.net/announcement"
   curl -s "https://northstar.mingpixel.net/client"
   curl -s "https://northstar.mingpixel.net/update?version=1.0.0&channel=stable"
   curl -s "https://northstar.mingpixel.net/anticheat"
   ```

## 发新版本流程

1. `git tag v1.0.1 && git push origin v1.0.1` → CI 自动构建并发布 Release。
2. 下载 Release 里的 `HMCL-1.0.1.jar`,算出 SHA-1:
   `sha1sum HMCL-1.0.1.jar`
3. 修改 `update.json`:`version` 改为 `1.0.1`,`jar` 指向新 jar 直链(建议同时镜像一份到
   `northstar.mingpixel.net/download/`,国内玩家下载更快),`jarsha1` 填新值;`force: true`
   可强制玩家升级。上传覆盖服务器上的 `update.json` 即完成推送,存量客户端下次启动收到提示。

## 待补内容(部署后仍需人工提供)

- `client.json` 的 `url` 指向 `northstar-client-1.0.0.zip`——**整合包 zip 尚未上传**,
  上传前玩家点「下载」会提示"暂无可用的客户端"(预期降级,不报错)。
- `anticheat.json` 当前 `enabled:false`;DAC 组件就绪后按客户端
  `NorthStarAntiCheat` 的清单格式填入 `files`(target: `MODS`/`GAME`)、
  `agentFile`、`javaDirectory`,再改为 `true`。
- 公告内容更新直接改 `announcement.json`(`title`/`content` 必填,`content` 支持
  `<a href>` 链接,`date` 可选)。
