# Release 构建与签名

当前版本：0.5.10，versionCode 29，包名 `com.example.autoswipeassistant`。

## 准备自己的签名

官方发布密钥不在仓库中。自行构建正式包需要创建并保管自己的密钥，或恢复自己之前的发布密钥。后续升级必须使用同一密钥，不要重新生成来替代。

在项目根目录创建本机私密文件 `.signing/release.properties`：

```properties
storeFile=.signing/release.p12
storeType=PKCS12
storePassword=<自己的密钥库密码>
keyAlias=<自己的密钥别名>
keyPassword=<自己的密钥密码>
```

`.signing/` 与常见密钥扩展名已忽略。不要把密码写入源码、提交到 Git、上传为 Release 附件或复制到公共日志中。密钥与密码配置应一起备份到自己控制的离线或加密存储。

## 构建

需要 JDK 17/21、Android SDK API 35。将 `JAVA_HOME`、`ANDROID_HOME` 指向自己的安装位置。

```powershell
./gradlew.bat :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease --console=plain
```

输出为 `app/build/outputs/apk/release/app-release.apk`。缺少签名配置时，Release 任务会直接报错；Debug 构建不需要发布密钥。

Release 关闭调试和 JNI 调试，使用 APK v2/v3 签名，维持现有不混淆配置。开发诊断默认关闭，仍可由用户按需开启。没有增加 OCR、网络或截图权限。

## 校验

以下命令假定 Android Build Tools 已加入 PATH：

```powershell
apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
zipalign -c -P 16 -v 4 app/build/outputs/apk/release/app-release.apk
```

发布附件应包含 Debug、Release APK 及 SHA-256 校验文件。只有 APK 和校验信息适合公开分发，签名私钥与本机密码配置不能上传。

## 安装与升级

Debug、官方 Release、自行签名 Release 使用不同签名，不能相互直接覆盖。首次切换前保留自己的目标列表、学习方案等配置。后续同签名升级保持包名，递增 versionCode。

真实第三方广告需要单独手机验收；构建、单元测试与模拟器检查不代表已兼容全部广告。
