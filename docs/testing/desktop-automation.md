# 电脑测试与自动回归

使用两层验证：电脑跑可重复的识别回归，真实手机核对第三方广告行为。模拟器不能替代真实广告验收。

## 环境

需要 JDK 17/21、Android SDK API 35、可丢弃模拟器和 Python 3。设置自己的 `JAVA_HOME`、`ANDROID_HOME`，将 Android SDK 的 platform-tools、emulator 加入 PATH。以下命令在项目根目录执行，Windows 使用 `gradlew.bat`，其他平台使用 `gradlew`。

## 单元测试和模拟广告

```powershell
./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew.bat -p tools/testing/fixture assembleDebug
emulator -avd <自己的测试AVD名称> -read-only -no-window -no-audio -no-snapshot -port 5580
adb -s emulator-5580 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5580 install -r tools/testing/fixture/build/outputs/apk/debug/fixture-debug.apk
python tools/testing/run_regression.py
```

夹具 APK 的具体文件名也可在 `tools/testing/fixture/build/outputs/apk/debug/` 查看。

回归驱动仅接受 `emulator-` 序列号，会替换模拟器中的助手测试偏好和诊断，只能用于可丢弃模拟器。结束后恢复无障碍设置，不恢复原测试偏好。不要针对真实手机绕过此检查。

可用 `--cases ready_badge countdown_ignored_by_player normal_video` 选择场景，用 `--adb <adb路径>` 或 `--serial <模拟器序列号>` 指定环境。完整回归约数分钟，上限测试约98秒。结果保存在本地 `.diagnostics/ad-loop-regression/`，该目录不提交。

覆盖广告就绪、倒计时重试、普通视频、隐藏/描述标记、空文字剧集、短暂消失、连续广告、观察模式、切出应用、横竖屏和上限。纯状态机测试还覆盖冻结/中断预算、完整读取与直播推广结构。夹具不包含真实手机截图。

退出自己的测试模拟器：

```powershell
adb -s emulator-5580 emu kill
```

## 手机核对

```powershell
adb devices
adb -s <自己的手机序列号> install -r app/build/outputs/apk/debug/app-debug.apk
scrcpy -s <自己的手机序列号>
```

分别核对普通视频是否误滑、广告是否被识别、手势是否发送及是否真的回到剧集。重点检查横竖屏、倒计时、直播推广、连续广告、切出返回和后台限制。系统回调完成不能单独证明广告退出。

安装包签名不同会阻止直接覆盖；正式包的 `run-as` 读取不可用。不要为采集诊断直接卸载已有应用而丢失配置。

## 本地诊断采集

在 Debug 构建中主动开启开发诊断后，可使用：

```powershell
python tools/testing/capture_phone.py --serial <自己的手机序列号> --seconds 180 --out .diagnostics/phone-capture
```

脚本只读诊断和进程状态，不发送手势、不更改设置、不截图。临时 JSON 解析失败会重试；有执行时限，Ctrl+C 可停止。诊断包含目标页面文字，可能含个人信息；只保存在本地，分享前需要脱敏，完成后关闭并清空。

UIAutomator 可能暂时影响无障碍服务，不要在核对助手自身手势时频繁并行导出节点。后台限制因手机系统不同而异，需用户按自己设备调整。
