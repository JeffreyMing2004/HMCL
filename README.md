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
| `HMCL/src/main/java/org/jackhuang/hmcl/Metadata.java` | `NAME` / `FULL_NAME` → NorthStar Client(驱动窗口标题、左上角标题、游戏崩溃报告中的启动器名);`HMCL_UPDATE_URL` → `https://northstar.mingpixel.net/update` |
| `HMCL/src/main/java/org/jackhuang/hmcl/upgrade/IntegrityChecker.java` | 禁用 HMCL 官方签名自校验(自构建包无上游签名;更新包仍按服务器下发的 SHA-1 校验) |
| `HMCL/src/main/java/org/jackhuang/hmcl/ui/main/AboutPage.java` | 关于页启动器条目改为 NorthStar Client,链接指向本 Fork 仓库 |
| `HMCL/src/main/java/org/jackhuang/hmcl/ui/download/NorthStarClientInstaller.java` | 新增:侧边栏「下载」按钮 → 从服务器获取最新客户端整合包并进入安装向导 |
| `HMCL/src/main/java/org/jackhuang/hmcl/ui/main/RootPage.java` | 侧边栏「下载」入口改为调用 NorthStarClientInstaller;移除「通用」分类下的「多人联机」与「官方群组」入口 |
| `HMCL/src/main/resources/assets/lang/I18N*.properties` | 「开源」声明改为"基于 Hello Minecraft! Launcher (HMCL) 修改"(简/繁/英);新增 `northstar.*` 提示文案 |
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

1. 默认游戏目录 / 配置锁定为 NorthStar 服务器专用
2. 预置服务器地址,一键进入
3. 关闭或自建遥测、公告接口
