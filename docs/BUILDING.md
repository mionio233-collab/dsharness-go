# Android 构建

环境要求：Android Studio、JDK 25、Android SDK Platform 36 与 Android SDK Build Tools。应用最低支持 Android 8.0（API 26）。

1. 克隆本仓库，在 Android Studio 中打开根目录。
2. 安装 SDK 36，将 SDK 路径写入本机 `local.properties`（`sdk.dir=...`），或设置 `ANDROID_HOME`。
3. 执行：

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

Windows 可使用 `gradlew.bat`。调试构建输出位于 `app/build/outputs/apk/debug/`。首次构建需要网络下载 Gradle 和 Maven 依赖。

Release 构建：`./gradlew :app:assembleRelease`。发布签名由维护者单独管理，签名密钥、令牌和 API Key 不提交仓库。使用自己的发布密钥时，可在 Android Studio 的 Generate Signed Bundle / APK 中配置。

API 供应商配置保存在 Android Keystore 支持的加密存储中。不要把真实密钥写进代码、测试或日志。测试使用 MockWebServer 和本地测试数据，无需真实供应商账户。
