# 构建签名 Release APK

仓库工作流：`.github/workflows/release-on-version-bump.yml`。

## 一次性签名配置

在本 fork 的 Settings → Secrets and variables → Actions 添加：

| Secret | 内容 |
| --- | --- |
| `KEYSTORE_BASE64` | 完整 JKS/PKCS12 签名密钥库的 Base64 |
| `KEYSTORE_PASSWORD` | 密钥库密码 |
| `KEY_ALIAS` | 私钥条目别名 |
| `KEY_PASSWORD` | 私钥密码 |

Fork 不继承上游 Secrets。四项缺一时工作流明确失败，不会替换成 Debug APK。
密钥和密码保存在仓库外并备份；同一个 fork 后续版本应继续使用同一套密钥。
新密钥签名的 APK 无法覆盖安装其他密钥签名的原版应用。
`SF_FREE_API_KEY`、`POCKETBASE_BASE_URL` 是可选业务配置，不影响签名构建。

## 手动构建（默认仅下载产物）

1. 在 fork 的 Actions 页面启用工作流。
2. 选择 **Release on Version Bump** → **Run workflow**，选择要构建的分支。
3. 保持 `publish_release` 关闭，运行后从本次运行的 **Artifacts** 下载。

手动构建不要求修改版本号。产物包含分别签名的 `arm64-v8a`、`armeabi-v7a`
Release APK 和 SHA-256 校验文件，保留 14 天。工作流会校验 APK 签名。
此模式不创建 Release、不写 `version.json`、不向分支提交文件。
工作流需存在于默认分支才能显示手动入口；选择其他分支会使用该分支的工作流版本。

## 正式发布

先增加 `app/build.gradle.kts` 中的 `versionCode` 和 `versionName`，
再手动勾选 `publish_release`，或沿用主分支版本号变化后的自动发布。
发布会创建公开 GitHub Release 并更新当前分支 `version.json`。
下载地址自动使用当前 `GITHUB_REPOSITORY`，不写死上游仓库。
Release 标签固定指向实际构建的提交；同版本标签已属于其他提交时拒绝覆盖。
写回元数据仅允许快进推送，分支在构建期间变化时会失败，避免重置其他人的提交。

GitHub Actions 支持文档：
- https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manually-run-a-workflow
- https://docs.github.com/en/actions/security-for-github-actions/security-guides/using-secrets-in-github-actions
