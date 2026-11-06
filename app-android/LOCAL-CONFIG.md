# 本地私有配置说明（**不要**复制成 gradle.properties）

`gradle.properties` 已经入库，里面只有公共构建参数，是编得起来的前提。**任何密钥或私有地址都不要写进它**——
一旦写进去，`git commit` 就会把密钥推到公开仓库。

需要正式签名或自定义升级地址时，用下面任一方式提供（Gradle 会自动识别）：

## 方式 A：命令行参数（一次性，最直观）

```bash
gradlew.bat :app:assembleRelease \
  -PRELEASE_STORE_FILE=../keystore/release.jks \
  -PRELEASE_STORE_PASSWORD=<你的密码> \
  -PRELEASE_KEY_ALIAS=<你的别名> \
  -PRELEASE_KEY_PASSWORD=<你的密码> \
  -PUPDATE_JSON_URL=https://<你的主源>/update.json \
  -PUPDATE_JSON_URL_FALLBACK=https://<你的备源>/update.json
```

## 方式 B：环境变量（Gradle 把 `ORG_GRADLE_PROJECT_<名称>` 映射为 project property）

```powershell
$env:ORG_GRADLE_PROJECT_RELEASE_STORE_PASSWORD = "<你的密码>"
$env:ORG_GRADLE_PROJECT_UPDATE_JSON_URL = "https://<你的主源>/update.json"
$env:ORG_GRADLE_PROJECT_UPDATE_JSON_URL_FALLBACK = "https://<你的备源>/update.json"
```

## 方式 C：用户级 `~/.gradle/gradle.properties`（全局、不入库，最省事）

把上面几个键写进用户主目录下的 `.gradle/gradle.properties`，Gradle 会自动加载，且永远不进仓库。

---

- 四个签名属性**必须同时提供**，`release` 构建才会启用正式签名；否则产出未签名包（仅供编译验证）。
- 应用采用**双源**：先试 `UPDATE_JSON_URL`（主源），失败再试 `UPDATE_JSON_URL_FALLBACK`（备源）。
  不提供时默认先试本项目 Gitee 仓库固定文件、再试 GitHub Releases 附件；fork 后请把两个默认地址都改成自己的仓库路径。
- 签名密钥永远不要放进仓库。自己编译的包与官方发布包签名不同，不能覆盖安装、也不会接收官方升级。
