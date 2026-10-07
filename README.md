# NorthStar Client

NorthStar 服务器专用启动器,基于 **HMCL (Hello Minecraft! Launcher)** 修改。

> ## 修改声明(依据 GNU GPL v3)
> 本启动器由 Hello Minecraft! Launcher (HMCL) 修改而来:
>
> - 原项目:[https://github.com/HMCL-dev/HMCL](https://github.com/HMCL-dev/HMCL)(版权所有 © 2013-2026 huangyuhui 及贡献者)
> - 本 Fork:[https://github.com/JeffreyMing2004/HMCL](https://github.com/JeffreyMing2004/HMCL)
> - 本程序继续遵循 **GNU General Public License v3** 开源,上游 [LICENSE](LICENSE) 未作修改;HMCL 的版权信息与贡献者鸣谢均保留在启动器「关于」页面中。
> - 当前修改内容:品牌更名(NorthStar Client,窗口左上角与标题栏显示 NorthStar)、替换图标、关于页声明基于 HMCL 修改。

## 构建

需要 JDK 17+(本机 JDK 25 可用):

```bash
./gradlew :HMCL:compileJava   # 编译主程序
./gradlew :HMCL:run           # 启动器跑起来(开发模式)
./gradlew :HMCL:makeExecutables   # 打包 Windows 可执行文件
```

## 本次修改的文件

| 文件 | 修改 |
| --- | --- |
| `HMCL/src/main/java/org/jackhuang/hmcl/Metadata.java` | `NAME` / `FULL_NAME` → NorthStar Client(驱动窗口标题、左上角标题、游戏崩溃报告中的启动器名);`HMCL_UPDATE_URL` / `NORTHSTAR_CLIENT_URL` / `NORTHSTAR_ANNOUNCEMENT_URL` / `NORTHSTAR_SERVER_ADDRESS` 四个服务器接口地址 |
| `HMCL/src/main/java/org/jackhuang/hmcl/upgrade/IntegrityChecker.java` | 禁用 HMCL 官方签名自校验(自构建包无上游签名;更新包仍按服务器下发的 SHA-1 校验) |
| `HMCL/src/main/java/org/jackhuang/hmcl/ui/main/AboutPage.java` | 关于页启动器条目改为 NorthStar Client,链接指向本 Fork 仓库 |
| `HMCL/src/main/java/org/jackhuang/hmcl/ui/download/NorthStarClientInstaller.java` | 新增:侧边栏「下载」按钮 → 从服务器获取最新客户端整合包并进入安装向导 |
| `HMCL/src/main/java/org/jackhuang/hmcl/ui/main/NorthStarAnnouncements.java` | 新增:从服务器 API 拉取公告并显示在主页顶部;接口未就绪时静默跳过 |
| `HMCL/src/main/java/org/jackhuang/hmcl/ui/main/MainPage.java` | 移除上游预览版/开发版提示条,改为加载 NorthStar 公告卡;启动区新增「一键进入服务器」按钮(Quick Play 直连 `NORTHSTAR_SERVER_ADDRESS`,无实例时先装最新正式版再直连) |
| `HMCL/src/main/java/org/jackhuang/hmcl/ui/instances/Instances.java` | 新增 `launchAndJoinServer`:启动实例并通过 Quick Play 连入指定服务器 |
| `HMCL/src/main/java/org/jackhuang/hmcl/util/io/HttpRequest.java` | 请求重试增加指数退避:429 限流按 2s→4s→8s→15s 退避重试,其余错误间隔 1s,4xx(除 429)不再重试 |
| `HMCL/src/main/java/org/jackhuang/hmcl/setting/Accounts.java` | 微软登录错误提示细化:429 限流与 403 应用未注册(Mojang 白名单)分别显示专门文案,不再笼统提示"网络问题" |
| `HMCL/build.gradle.kts`、`gradle.properties` | 支持 `microsoft.auth.id` / 环境变量注入微软登录 Client ID(见「微软正版登录」) |
| `HMCL/src/main/java/org/jackhuang/hmcl/ui/main/RootPage.java` | 侧边栏「下载」入口改为调用 NorthStarClientInstaller;移除「通用」分类下的「多人联机」与「官方群组」入口 |
| `HMCL/src/main/resources/assets/lang/I18N*.properties` | 「开源」声明改为"基于 Hello Minecraft! Launcher (HMCL) 修改"(简/繁/英);新增 `northstar.*` 提示文案;新增 `account.failed.rate_limited` / `account.failed.invalid_app_registration` 错误文案 |
| `HMCL/src/main/resources/assets/img/icon*.png` | 全部图标替换为 NorthStar 星形标志(24/32/48/64/128/256/512) |
| `gradle/gradle-daemon-jvm.properties` | 锁定构建用 JDK 17+,不受 JAVA_HOME 指向旧 JDK 影响 |

## 启动器自更新接口

更新地址:`https://northstar.mingpixel.net/update`

启动器以 `GET ?version=<当前版本>&channel=<stable|nightly|dev>` 请求,服务端返回:

```json
{
  "version": "3.5.x-northstar.1",
  "jar": "https://northstar.mingpixel.net/download/northstar-client-3.5.x.jar",
  "jarsha1": "新 jar 的 SHA-1(小写十六进制)",
  "force": false
}
```

`jar` 指向 NorthStar 自行构建的完整启动器 jar;`force: true` 时用户无法跳过本次更新。

## 客户端整合包下载接口(侧边栏「下载」按钮)

点击左侧「下载」时,启动器请求 `GET https://northstar.mingpixel.net/client`,期望返回:

```json
{
  "version": "1.0.0",
  "url": "https://northstar.mingpixel.net/client/download/northstar-client-1.0.0.zip",
  "sha1": "整合包 zip 的 SHA-1(可选,建议提供)"
}
```

- `url` 必填,指向整合包 zip(HMCL / CurseForge / MultiMC / Modrinth 格式均可,由 HMCL 整合包安装向导自动识别)。
- 请求失败、返回内容不含 `url` 时,提示"暂无可用的客户端"。
- 下载完成后进入整合包安装向导,玩家选择游戏目录与实例名即可完成安装;文件缓存在 `.hmcl/northstar-client/` 下。

## 服务器地址与一键进入

服务器连接地址预置在 `Metadata.NORTHSTAR_SERVER_ADDRESS`(当前为 `cod.mingpixel.net`,如端口非 25565 写成 `host:port`)。主页启动区新增手柄图标按钮:

- 已选中实例时:通过 Minecraft Quick Play(`--quickPlayMultiplayer`,1.20+ 支持)启动并直连服务器;
- 未选中实例时:自动安装最新正式版游戏后直连。

## 服务器公告接口(主页顶部公告卡)

启动器启动后请求 `GET https://northstar.mingpixel.net/announcement`,期望返回:

```json
{
  "announcements": [
    {
      "title": "服务器维护公告",
      "content": "10 月 8 日 2:00-4:00 例行维护。<a href=\"https://northstar.mingpixel.net\">详情</a>",
      "date": "2026-10-07"
    }
  ]
}
```

- `title`、`content` 必填;`content` 支持 `<a href="...">链接</a>` 标记;`date` 可选,会拼在标题后显示。
- **接口未编写/不可用/返回为空时,主页不显示任何内容,不影响使用**(接口已预留,见 `NorthStarAnnouncements`)。

## 微软正版登录

HMCL 内置了微软登录的全部流程,但官方构建把 Azure 应用的 **Client ID** 只打包进 HMCL 官方发布的 jar(`hmcl.microsoft.auth.id`),非官方构建里它是空的,所以会提示「请下载官方版本来登录微软账户」并被拒绝。

解决方式:NorthStar 注册**自己的** Azure 应用,把 Client ID 配进构建。以下步骤已按实际操作验证(2026-10):

1. 用任意微软账号打开 [Microsoft Entra 管理中心](https://entra.microsoft.com) → 「标识 → 应用程序 → 应用注册」→ 新注册
   - 受支持的账户类型:选择 **「个人 Microsoft 帐户」**(登录链路走 `login.microsoftonline.com/consumers` 端点,Minecraft 只能用个人账户)
   - 重定向 URI:平台选 **「移动和桌面应用程序」**,填 `http://localhost:29111/auth-response`(注册后到「身份验证」页补齐 29112–29115 共五条,启动器回调固定尝试这五个端口)
2. 注册完成后,在「概述」页复制 **应用程序(客户端)ID**
3. 在应用的「身份验证」页底部,把「高级设置 → 允许公共客户端流」设为 **启用** 并保存(设备码登录需要)

无需创建客户端密码,无需添加 API 权限。

把 Client ID 配进启动器(二选一):

- **方式一(推荐)**:仓库根目录 `gradle.properties` 中取消注释并填入 `microsoft.auth.id=<你的 Client ID>`,重新构建后自动打进启动器
- **方式二**:构建时设置环境变量 `MICROSOFT_AUTH_ID=<你的 Client ID>`

配置后重新构建,登录微软账户、刷新令牌均正常;登录时的黄色「非官方构建」提示仅为提醒、不影响使用。

## Mojang 接口白名单(重要!)

自 2024 年起,Mojang 对第三方应用访问 Java 版游戏服务 API 实施白名单:登录流程最后一步 `api.minecraftservices.com/authentication/login_with_xbox` 会校验发起登录的 Azure Client ID,**不在名单内的应用一律返回 403 `Invalid app registration`**。已获准的既有启动器(HMCL、Prism、PCL 等)不受影响。

这意味着:自己注册的 Azure 应用即使配置完全正确,微软登录、Xbox Live 认证都会成功,但最后拿 Minecraft 会话一步会被拒绝。此时启动器会显示「本启动器使用的微软登录应用未在 Mojang 注册」。

出路的唯一正道是提交申请:

- 申请入口:[aka.ms/AppRegInfo](https://aka.ms/AppRegInfo)(指向 [help.minecraft.net 的说明文章](https://help.minecraft.net/hc/en-us/articles/16254801392141)),按表单提交 Client ID、用途说明(Justification)等信息,等待人工审核。社区反馈审核门槛不低(涉及组织验证),周期较长。

在申请通过之前,该 Client ID 无法完成微软登录;设备码流程走同一接口,同样受限。

## 常见问题

- **`Gradle requires JVM 17 or later`**:构建已通过 `gradle/gradle-daemon-jvm.properties` 锁定 JDK 17+,机器上需装有任一 JDK 17/21/25(Gradle 会自动挑选,与 JAVA_HOME 无关)。

## 与上游保持同步

```bash
git remote add upstream https://github.com/HMCL-dev/HMCL
git fetch upstream
git merge upstream/main
```

合并冲突大概率集中在 `Metadata.java`、`AboutPage.java`、图标与语言文件,均是本 Fork 的品牌改动点。

## 后续计划(NorthStar 服务器专用化)

~~原三项计划,2026-10 已全部落地:~~

1. ~~默认游戏目录/配置锁定~~ ✅ 新版设置系统默认即选择启动器旁的相对 `.minecraft` 目录(便携式,随启动器目录走),玩家仍可在设置中增删游戏目录;
2. ~~预置服务器地址,一键进入~~ ✅ 服务器地址预置于 `Metadata.NORTHSTAR_SERVER_ADDRESS`,主页一键按钮通过 Quick Play 直连(见「服务器地址与一键进入」);
3. ~~关闭或自建遥测、公告接口~~ ✅ HMCL 本身无遥测上报;版本更新检查已指向自建 `northstar.mingpixel.net/update`;公告接口客户端侧已完成并验证(接口未部署时静默跳过),等待服务端部署三个接口(update / client / announcement,格式见上文)。
