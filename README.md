# PrivateIme — 私密输入模式参考实现

Android 输入法在联想/纠错/云学习时会把用户输入收进词库，密码、验证码、银行卡号因此被学进去，并在其它应用里被联想出来。

本项目实现"私密输入模式"的完整链路：**准确识别敏感输入框 → 分级关闭记录/云端/剪贴板 → 找出并清理已泄漏的词**。

- 架构与取舍：[DESIGN.md](DESIGN.md)
- 判定引擎在 `core/`，纯 Kotlin 无 Android 依赖，136 个 JVM 测试覆盖判定、策略矩阵、清理与并发边界。
- Android 壳在 `app/`。

## 一句话设计

三层信号加权（`inputType` / 标签 / 内容）+ 字段粘性记忆 + 三级策略（开放 / 保护 / 私密），所有写入与出网都收敛到单一闸门，且一次会话内判定只升不降。

## 构建

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=~/toolchain/android-sdk
export GRADLE_USER_HOME=~/toolchain/gradle-home
gradle test assembleDebug
```
