# NorthStar 服务端接口部署

> **当前方案：由 northstar_backend（Spring Boot）提供四个接口**，OpenResty 反代根路径到后端。
> 本目录的静态 JSON 与 nginx 片段是**无后端时的降级备份方案**，与反代方案二选一，不要混用。

## 接口一览

| 接口 | 反代目标 | 数据来源 |
| --- | --- | --- |
| `GET /announcement` | 后端 `/api/launcher/announcement` | 数据库，管理后台「公告管理」页维护 |
| `GET /client` | 后端 `/api/launcher/client` | 环境变量 `LAUNCHER_CLIENT_*` |
| `GET /update` | 后端 `/api/launcher/update` | 环境变量 `LAUNCHER_UPDATE_*` |
| `GET /anticheat` | 后端 `/api/launcher/anticheat` | 数据库，管理后台「启动器」页维护 |

反代配置：northstar_frontend 仓库 `deploy/openresty/northstar.mingpixel.net.conf`
（`/announcement` 与 `/update` 两个 location 为本次新增，`/client`、`/anticheat` 已有）。

## 部署步骤

1. 后端配置（`.env` / 环境变量）：
   - 整合包：`LAUNCHER_CLIENT_VERSION` / `LAUNCHER_CLIENT_URL` / `LAUNCHER_CLIENT_SHA1`
     （zip 上传到服务器 `/downloads/` 静态目录，URL 填公网直链）；
   - 自更新：`LAUNCHER_UPDATE_VERSION` / `LAUNCHER_UPDATE_JAR` / `LAUNCHER_UPDATE_JARSHA1` /
     `LAUNCHER_UPDATE_FORCE`（当前已按 v1.0.0 Release 实测值填好模板，见 `.env.example`；
     jar 指向 GitHub Release，国内慢可镜像到 `/downloads/` 后改 URL）；
   - 改完重启后端生效（启动器清单为启动时读取）。
2. OpenResty：更新 `northstar.mingpixel.net.conf`（加入新 location）后 reload。
3. 验证：
   ```bash
   curl -s "https://northstar.mingpixel.net/announcement"
   curl -s "https://northstar.mingpixel.net/client"
   curl -s "https://northstar.mingpixel.net/update?version=1.0.0&channel=stable"
   curl -s "https://northstar.mingpixel.net/anticheat"
   ```
4. 公告发布：管理后台 → 公告管理 → 新建/编辑/删除，发布后启动器最迟 1 分钟生效。

## 待补内容

- 整合包 zip 上传到 `/downloads/` 后，把 `LAUNCHER_CLIENT_*` 三项配齐（未配置前玩家点
  「下载」提示"暂无可用的客户端"，属预期降级）。
- DAC 反作弊组件就绪后，在管理后台「启动器」页启用并填写组件清单。
- 公告内容日常更新直接在管理后台操作，无需动服务器。

## 备份方案（静态 JSON，无后端时使用）

`announcement.json` / `client.json` / `update.json` / `anticheat.json` +
`nginx-northstar.conf`（`location =` + `try_files`）。注意静态方案的
`update.json`/`anticheat.json` 需要手工维护，与后端方案的管理后台互不相通。
