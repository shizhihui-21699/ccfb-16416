# 私密输入模式（Private Input Mode）

Android 输入法在联想、纠错、云同步的过程中会记录用户输入。问题在于**识别**：很多应用的密码框根本没有声明 `inputType`，网页输入框能拿到的元数据更少，而"宁可错杀"地关闭全部联想会让输入法不可用。同时，泄漏往往在修复上线前就已经发生——词库里已经躺着用户的旧密码。

本项目实现一套分层的判定与执行架构，并给出可运行的参考实现（Kotlin，`core` 纯 JVM 可测 + `app` Android 壳）。

---

## 1. 设计要点（为什么不是简单的 if-else）

### 1.1 三种信号各自不可靠，所以用加权证据

| 信号 | 可靠性 | 典型失败方式 |
|---|---|---|
| `EditorInfo.inputType` | 正确时最权威 | 大量真实密码框声明为 `TYPE_CLASS_TEXT/NORMAL`（开发者忘写 `android:inputType`，或写在错的 View 上） |
| 标签 / hint / 资源 id | 明确但可被误导 | 银行的聊天框标签是"输入内容"；密码管理器的**搜索框**标签是"搜索密码" |
| 输入内容 | 精确但太晚 | 等输完 16 位卡号，前几位可能已经被学走 |

因此每个信号变成带权重的 `Evidence`，按类别累加，负向证据（搜索/评论/昵称）可以抵消，阈值映射到 `Confidence`。**硬证据**（明确的密码 type、无障碍 `isPassword`）短路负向证据——平台说是密码，就不能因为标签里有"搜索"而当成搜索框。

### 1.2 三级策略，而不是"敏感就全关"

"拿不准就关联想"正是让用户卸载输入法的原因。

| 级别 | 本地学习 | 自适应统计 | 云端 | 个人词典读取 | 剪贴板 | 候选词 |
|---|---|---|---|---|---|---|
| `OPEN` | 用户开关 | 用户开关 | 用户开关 | 是 | 用户开关 | 全部 |
| `GUARDED`（PII） | PII 需显式开启 | 否 | 否 | **是** | 否 | 仅本机词 |
| `LOCKED`（凭据） | 否 | 否 | 否 | 否 | 否 | 无 |

两个反直觉的格子及其理由：

- **GUARDED 仍允许读取个人词典**：邮箱/地址框恰恰是用户最想用自己词的地方。只切断"写入"和"上云"。
- **GUARDED 关闭自适应统计**：键盘统计是一种难以"定点清除"的内容存储，一旦写入无法只删某个词。

### 1.3 内容证据只能"保护"，不能"锁定"

六位数字可能是验证码，也可能是价格、年份、数量。因此纯内容证据对 `PIN/OTP/PASSWORD` **封顶在 `WEAK`**（→ GUARDED），只有元数据佐证才能升级到 LOCKED。银行卡号例外：Luhn 校验是 1/10 的随机误判率、对真实输入的误判率远低于此，代价是漏掉一张卡的损失很高，所以 Luhn 单独可以锁定。

对应地，`Tier` 在**一次会话内只能升不能降**：用户输完卡号又删掉一位，挡板仍应在——否则下一次尝试就漏了。

---

## 2. 判定流水线

```
EditorInfo ──┐
             ├─► SignalCollector ─► FieldSignals ─► SensitivityDetector ─► Verdict
无障碍树 ────┘        (合并)                          (加权证据)          │
                                                                          ▼
                                          FieldMemory(粘性记忆) ──► PrivacyPolicy ──► PrivacyDecision
                                                                          │
                                        ┌─────────────────────────────────┼──────────────────────────┐
                                        ▼                                 ▼                          ▼
                                   LearningGate                   CloudLexiconClient         ClipboardGuard
                                  (每个词必经)                    (云端联想)                 (剪贴板捕获)
```

`FieldPrivacyEngine` 是唯一的编排点：`onFieldFocused` / `onTextChanged`（只升级）/ `onFieldLeft`。

---

## 3. 难点逐条对应

### 3.1 "很多应用的密码框没有正确标注输入类型"

1. **标签挖掘**（`LabelParser`）：`hintText` → `contentDescription` → `labeledBy` → 相邻 `TextView` → 父容器描述 → 资源 id（`et_confirm_password` 会被拆成词）→ autofill hints → 网页标题。中文词表单独维护且按子串匹配（中文没有词边界）。
2. **无障碍增强**（可选）：`AccessibilityNodeInfo.isPassword`、资源 id、`labelFor` 关联。见 §5 的取舍。
3. **字段记忆**（`FieldMemory`）：同一 `signatureKey`（包名 + 资源 id，**不含输入内容、不含 inputType**）第二次访问直接命中上次判定。这是对抗"首次布局给普通 type、开始输入后再翻转"这类反自动填充技巧的关键。
4. **内容兜底**（`ContentClassifier`）：标签全无时，靠 Luhn / 邮箱 / 身份证 / 六位数字形状识别。

### 3.2 "网页里的输入框信息更少"

- 网页走 `strictWebMode`：`WEAK` 的 PII 也升到 GUARDED。
- 网页的 `name`/`id`（如 `password`）从 `viewIdResourceName` 拿到；Chrome 的 `privateImeOptions` 里可能带 `fn=` 字段名。
- 关键词表覆盖英文金融/登录词汇（"one-time code"、"cvv"、"card number"）。
- 网页的强判定标为 `sticky`，跨会话保留。

### 3.3 "关闭联想会影响打字体验"

- 只有 LOCKED 才完全无候选；GUARDED 保留 `LOCAL_GENERIC`（本机、当前词、无历史）。
- 所有档位**键位布局完全相同**——会改布局的隐私模式会被用户关掉。
- 每类应用可单独覆盖：`trustedSignatures`（"这里可以正常联想"）/ `alwaysPrivateSignatures` / 按包名覆盖。
- 金融类应用注册表**故意排除微信、支付宝这类超级 App**：它们既是银行也是主要聊天工具，全域升到 GUARDED 会毁掉最常用场景。它们的支付页靠自己的标签（"支付密码"）识别。

### 3.4 "已经学进去的敏感词要能找出来清理"

`ResidueScanner` 扫描词库，按风险分三档处置，**区分"确定"和"疑似"**：

| 发现 | 处置 | 理由 |
|---|---|---|
| Luhn 通过的卡号 | 自动删除 | 误删代价≈0，漏掉代价极高 |
| 身份证号 | 自动删除 | 同上 |
| 邮箱 | 待确认 | 可能是用户有意保存 |
| 六位独立数字 | 待确认 | 可能是验证码，也可能是邮编/价格 |
| 随机形状 token | 待确认 | 可能是密码，也可能是 Wi-Fi 密钥 |

清理必须同时删**两处**：本应用词库 **和** `android.provider.UserDictionary`（系统词典，其它输入法也能读，正是事故被放大的原因）。UI 只显示遮挡后的预览——把泄漏的密码明文显示在可被截屏的界面上，等于换个地方再泄漏一次。

---

## 4. 关键不变量（有测试保护）

1. **LOCKED 档在所有设置组合下都封死所有 sink**（`PrivacyMatrixTest`）。
2. **GUARDED 档在所有设置组合下都不允许云端与剪贴板捕获**，但保留个人词典读取。
3. **Tier 在一次会话内单调不减**。
4. **`LearningGate` 是唯一写入路径**；即使调用方误传 `Category.NONE`，内容分类器仍会拒绝凭据形状的字符串（纵深防御）。
5. **审计日志没有内容字段**，只有包名 / 形状 / 判定 / 规则代号。
6. **剪贴板历史加密存储**；Keystore 不可用时历史功能自我禁用，绝不降级为明文。

---

## 5. 取舍与已知边界

- **无障碍服务是可选增强**。它需要用户显式授权，因此产品在**没有它时也必须可用**（靠 EditorInfo + 内容 + 记忆）。服务只读取当前焦点输入框及其相邻标签，且只在 IME 请求的包名匹配时读取。这是平台限制下的最小权限设计，但确实是一次"高权限换取识别率"的交易，必须向用户讲清楚。
- **`labelFor` 的语义陷阱**：`node.labelFor` 是"本节点**标注**的节点"，不是"标注本节点的标签"。读错会拿到无关字段，代码里显式注释了这一点。
- **网页标题**：IME 拿不到窗口标题，`screenTitle()` 返回 null 而不是去猜测屏幕内容——宁可少一个信号，不多读一块屏幕。
- **云端默认不可达**：`CLOUD_ENDPOINT` 为空时 `CloudLexiconClient.transport` 恒为 NoopTransport。网络路径必须是构建期显式开启，而不是"碰巧可达"。
- **金融应用注册表**只是"更谨慎"的提示，永远不会降级判定，也不会锁定字段。

---

## 6. 构建与测试

需要 JDK 17、Gradle 8.9+、Android SDK 36。AGP 9.x 自带 Kotlin 支持，因此 `app` 模块不额外应用 `kotlin-android` 插件。

```bash
export ANDROID_HOME=/Users/shizhihui/toolchain/android-sdk
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export GRADLE_USER_HOME=/Users/shizhihui/toolchain/gradle-home   # 离线依赖缓存

gradle :core:test                 # 判定/策略/清理 的纯 JVM 测试
gradle :app:testDebugUnitTest     # EditorInfo 映射、常量一致性、设置编码
gradle :app:assembleDebug         # 产出可安装 APK
gradle :app:lintDebug             # 无 error
```

`core` 模块**不依赖 Android**：`InputType` 等常量是镜像的，并由 `EditorInfoExtractorTest` 断言与 SDK 常量逐一对齐——漂移会被测试抓住，而不是在设备上悄悄改变判定含义。

---

## 7. 目录

```
core/                         纯 Kotlin，无 Android 依赖
  model/        FieldSignals, Verdict, Evidence, Category, SignatureKeys
  detect/       SensitivityDetector, SensitiveLexicon, LabelParser, ContentClassifier
  privacy/      Sink, Tier, PrivacyPolicy, AppRegistry, ClipboardGuard
  lexicon/      LearningStore 接口, LearningGate(唯一写路径), InMemoryLearningStore
  cleanup/      ResidueScanner
  engine/       FieldPrivacyEngine, AuditLog
  text/         TextNormalizer, WordTokenizer

app/                          Android 壳
  ime/          PrivateImeService(编排+强制), SignalCollector
  input/        EditorInfoExtractor, AccessibilityFieldProbe, PrivacyAccessibilityService
  data/         SqliteLearningStore(含系统词典镜像与双删), PrivacySettingsStore, AuditRepository
  clipboard/    ClipboardService, ClipboardHistory(AES-GCM), KeystoreCrypto
  cloud/        CloudLexiconClient(唯一出口), HttpCloudTransport
  ui/           KeyboardUiController, SettingsActivity, CleanupActivity, AuditActivity, ImeSetup
```
