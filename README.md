# NorthStar Client

NorthStar 服务器专用启动器,基于 [HMCL (Hello Minecraft! Launcher)](https://github.com/HMCL-dev/HMCL) 修改。

> **修改声明(GNU GPL v3)**:本项目为 HMCL(版权所有 © 2013-2026 huangyuhui 及贡献者)的修改版,继续以 [GPL v3](LICENSE) 开源;HMCL 的版权信息与贡献者鸣谢保留在启动器「关于」页面。本 Fork:<https://github.com/JeffreyMing2004/HMCL>

## 功能

- **专用品牌**:NorthStar Client 名称与图标,关于页注明基于 HMCL 修改
- **主页公告卡**:启动时从服务器拉取公告;接口不可用时静默跳过
- **一键进入服务器**:主页按钮通过 Quick Play 直连 `cod.mingpixel.net`(需 Minecraft 1.20+),未安装游戏时先自动安装最新正式版
- **次元反作弊自动配置**:启动前按服务器下发的清单自动安装次元反作弊(DAC)客户端组件,Agent 模式自动注入 JVM 参数并切换配套 Java;接口未部署时静默跳过
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

## 与上游同步

```bash
git remote add upstream https://github.com/HMCL-dev/HMCL
git fetch upstream && git merge upstream/main
```

冲突一般集中在 `Metadata.java`、`AboutPage.java`、图标与语言文件,均为本 Fork 的品牌改动点。
