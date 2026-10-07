# NorthStar Client

NorthStar 服务器专用启动器,基于 [HMCL (Hello Minecraft! Launcher)](https://github.com/HMCL-dev/HMCL) 修改。

> **修改声明(GNU GPL v3)**:本项目为 HMCL(版权所有 © 2013-2026 huangyuhui 及贡献者)的修改版,继续以 [GPL v3](LICENSE) 开源;HMCL 的版权信息与贡献者鸣谢保留在启动器「关于」页面。本 Fork:<https://github.com/JeffreyMing2004/HMCL>

## 功能

- **专用品牌**:NorthStar Client 名称与图标,关于页注明基于 HMCL 修改
- **主页公告卡**:启动时从服务器拉取公告;接口不可用时静默跳过
- **一键进入服务器**:主页按钮通过 Quick Play 直连 `cod.mingpixel.net`(需 Minecraft 1.20+),未安装游戏时先自动安装最新正式版
- **客户端整合包下载**:侧边栏「下载」获取服务器最新整合包,进入 HMCL 安装向导
- **启动器自更新**:更新检查与更新包走自建接口,按服务器下发的 SHA-1 校验
- **微软正版登录**:内置完整登录流程,Client ID 由构建注入

## 构建

需要 JDK 17+(构建已通过 `gradle/gradle-daemon-jvm.properties` 锁定,与 JAVA_HOME 无关):

```bash
./gradlew :HMCL:compileJava        # 编译
./gradlew :HMCL:run                # 本地运行
./gradlew :HMCL:makeExecutables    # 打包 Windows 可执行文件
```

微软登录 Client ID 二选一:在 `gradle.properties` 填 `microsoft.auth.id=<客户端 ID>`,或设置环境变量 `MICROSOFT_AUTH_ID`。不配置则微软登录被禁用。

## 服务端接口

域名 `northstar.mingpixel.net`。三个接口均未部署或返回为空时,对应功能静默降级,不影响启动器使用。

**启动器自更新** `GET /update?version=<当前版本>&channel=<stable|nightly|dev>`

```json
{
  "version": "3.5.x-northstar.1",
  "jar": "https://northstar.mingpixel.net/download/northstar-client-3.5.x.jar",
  "jarsha1": "新 jar 的 SHA-1(小写十六进制)",
  "force": false
}
```

`force: true` 时用户无法跳过本次更新。

**客户端整合包** `GET /client`

```json
{
  "version": "1.0.0",
  "url": "https://northstar.mingpixel.net/download/northstar-client-1.0.0.zip",
  "sha1": "整合包 zip 的 SHA-1(可选,建议提供)"
}
```

`url` 必填,整合包支持 HMCL / CurseForge / MultiMC / Modrinth 格式,由安装向导自动识别。

**服务器公告** `GET /announcement`

```json
{
  "announcements": [
    {
      "title": "公告标题",
      "content": "正文,支持 <a href=\"https://…\">链接</a>",
      "date": "2026-10-07"
    }
  ]
}
```

`title`、`content` 必填,`date` 可选(拼接在标题后显示)。

## 微软正版登录

登录链路使用个人 Microsoft 账户(`login.microsoftonline.com/consumers`),Azure 应用要求:

- 受支持的账户类型:**个人 Microsoft 帐户**
- 重定向 URI(「移动和桌面应用程序」平台):`http://localhost:29111/auth-response` 至 `29115` 共五条,启动器回调固定尝试这五个端口
- 「高级设置 → 允许公共客户端流」设为**启用**(设备码登录需要);无需客户端密码,无需 API 权限

注意:Mojang 对 `login_with_xbox` 实施应用注册白名单,未注册的 Client ID 会在登录最后一步返回 403 `Invalid app registration`,启动器会显示「应用未在 Mojang 注册」提示。申请入口:[aka.ms/AppRegInfo](https://aka.ms/AppRegInfo)。

## 与上游同步

```bash
git remote add upstream https://github.com/HMCL-dev/HMCL
git fetch upstream && git merge upstream/main
```

冲突一般集中在 `Metadata.java`、`AboutPage.java`、图标与语言文件,均为本 Fork 的品牌改动点。
