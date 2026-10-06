# 交接文档 · 搜狗输入法 26 键「长按符号自定义」LSPosed 模块

> 最后更新：2026-10-05（下午补：新增搜狗 v20.17.0 兼容 + 卡死根因结论）

## 2026-10-06 修复记录

本节覆盖下文关于符号列表误替换、仅上划长按显示无字形的旧结论。

- 实机复现中文 S 配置 `！→+` 时，符号列表选择 `！` 也变成 `+`。v20 的列表和上划都会经过 `BaseInputLogic.D0`，因此该方法不能单独作为按键手势的依据；列表栈没有包含 `symbol` 的类名，原先的排除判断未生效。
- v20 上划实测路径：`PinyinInputLogic.B0 → BaseInputLogic.I0 → D0 → D → commitText`；符号列表路径：`PinyinInputLogic.t0 → J0 → BaseInputLogic.I0 → D0 → D → commitText`。
- `isSlideUp` 现在要求中文栈同时包含按键手势入口和次级提交方法：v20 为 `PinyinInputLogic.B0` + `BaseInputLogic.D0`，v12 为 `PinyinInputLogic.x0` + `BaseInputLogic.z0`。英文保留 `Interface.handleSecondaryInput` 判断；实测英文符号选择走 `Interface.handleInput`。
- 仅上划模式的长按哨兵按键位及中英模式分别分配，提交时直接还原原符号。v20 气泡仅在 `KeyboardPopupView.onDraw` 内临时替换显示字段，绘制后恢复，避免修改长按提交缓存。
- 当前连接设备（搜狗 v20）已安装调试 APK 并完成实际输入验证：仅上划、仅长按、都生效三种模式，中文页及常用页选择 `！` 均输出 U+FF01；仅上划常用页连续选择三次通过，返回键盘上划仍输出 `+`，长按气泡显示并提交 `！`。
- 构建 `:app:assembleDebug` 通过；临时 `SOURCE` 栈日志已移除。测试结束恢复原配置 `#mode=swipe`，保留中文 S 与英文逗号的用户配置。v12 当前没有设备回归。

## 一句话
给手机搜狗输入法（`com.sohu.inputmethod.sogou`）的 26 键加一个 LSPosed 模块：**App 内像键盘一样给每个键填自定义符号，长按该键即输入它**，并在角标上同时显示原符号（方便对照）。**同时支持旧版 v12.0 和新版 v20.17.0（按运行时探测自动选 hook 集）**。

## 当前状态
| 项 | 状态 |
|---|---|
| 长按字母 | ✅ 生效，且**可在 App 内自定义**（实测 `s`→`$`，中/英模式均生效） |
| 角标显示 | ✅ 自定义 + 空格 + 原符号，如 `§ ！`；**三种生效方式都这样显示**（`setSymbol` 无条件写 `O2`） |
| 字母本身显示 | ✅ 正常（`S`/`Q`…） |
| App 内面板 | ✅ QWERTY 排布（贴合 26 键键盘），支持横屏；**只借 superMi 的配色**（白卡 + primary/soft/ghost 按钮）；**一个功能整体一行**（纯文字标题 + 控件同行，无图标瓦片：测试框 / 生效方式 / 应用修改 / root / 快捷入口）；顶栏=**纯色底**（`bg_header`，普通 App 的 app bar 样式）＋标题/副标题＋小圆「!」(22dp)＋「横屏/竖屏」；**顶栏三个控件（`搜` 方块 / `!` 圆钮 / `横屏` 胶囊）为深蓝底(`header_ctl_bg`)+白字**（2026-10-05 从「半透明白 → 纯白 → 深蓝」调过三轮，纯白太扎眼）；`重启搜狗生效` 文字明确；**26 键标题右侧：「大键/标准」尺寸开关(在重置左边) + 「重置」**（大键=**放弃键盘排布，改 4 列大方块**，但**仍按原键盘的三行分组**（Q行/A行/Z行各自成块、逗号句号一组），组间用分隔线隔开，`#bigKey` 持久化；标准=键盘排布+行分隔线）；**26 键卡片竖屏近满宽(6dp 边距)/横屏 24dp 边距**；分段控件高亮块与灰底等高；键位=淡灰圆角小块（改过变淡蓝），触摸高度 34dp；底部无多余留白 |
| 状态栏留白 | ✅ Android 15+ 强制 edge-to-edge（targetSdk 36），顶栏会被画到状态栏底下。`onCreate` 里给根 `ScrollView` 装 `setOnApplyWindowInsetsListener`：把 `systemBars` 的 top 加进顶栏的上内边距、bottom 加进内容区下内边距（API 30+ 用 `WindowInsets.Type.systemBars()`，更低用已废弃的 `getSystemWindowInset*`），并 `requestApplyInsets()` 主动触发一次。旧系统 inset 被 decor 消费 → 回调拿到 0 → 老设备布局不变（2026-10-05） |
| 暗色模式 | ✅ 支持（2026-10-05）：`values-night/colors.xml` 整套调色板 + `values-night/styles.xml`（同为 `AppTheme`，父主题换成 `Theme.Material.NoActionBar`）。顶栏在暗色下变成**普通深色 app bar**（不再亮蓝），控件为深底+浅蓝字。manifest 里 `uiMode` 被声明为自行处理，所以 `onConfigurationChanged` 里检测 night 位变化后 `recreate()` 才会重新取色 |
| 说明横幅 | ✅ 标题下一条黄色内联横幅（一段话 + `✕`），由顶栏的小圆 `!` 按钮展开/收起；`✕` 写入独立的 `ui` pref，之后默认不显示 |
| 重启后自动弹键盘 | ✅ 点「重启搜狗生效」= **先自动保存**再重启；成功后自动把键盘唤起并聚焦到测试输入框（持续重试 ~8s，因为 IME 刚被强杀需时间重启） |
| 中英分别设置 | ✅ **全部 28 个键**都有独立「中」「英」两框（2026-10-05 放开）。段名不同的键（s/h/j/l、逗号句号）直接用段名当配置键；**段名共用的键**（Z/X/B/N/C/V/M 等）自动派生 `_PY`/`_EN` 两个配置键，hook 端按当前中/英模式取（见 §5.12）。实测共享键 C：中文长按/上划出 `q`、英文出 `w` |
| 已改键高亮 | ✅ 填了自定义符号的键显示蓝底+蓝框+蓝字母；**「中」「英」标签各自变色**（只看中文/英文改没改） |
| 生效方式 | ✅ 面板三种：**仅长按 / 仅上划 / 都生效**。⚠️ 「仅上划」用哨兵实现：快划出自定义符号、长按保持原符号；但**长按气泡会显示无字形方块**（引擎把哨兵当字画），且「按住再滑」被引擎当长按 → 出原符号（详见 §5.7 末） |
| 重置 | ✅ **26 键标题右侧「重置」按钮**，确认弹窗三个动作：**取消 / 仅清空 / 清空并应用**（后者=清空后直接重启搜狗，一步恢复原键盘）；**长按某个键**只清空该键 |
| root 自检 | ✅ 面板显示「已授权」（绿）/「未授权」（红） |
| 快捷跳转 | ✅「输入法设置」+「搜狗·强行停止」（打开应用信息页手动强行停止） |
| 逗号 / 句号 | ✅ 已能自定义**输出**（点按就出你设的符号，实测 `，`→`。`）；⚠️ 但**键面仍显示 `，`/`。`**（引擎按 CODES 画，改不了，见 §6） |
| 上划字母 | ✅ 可选是否生效（「长按+上划」模式下上划出你设的符号，实测 `！`→`7`） |
| 面板内重启 | ✅ 已授权可用（2026-10-05 已把本 App 加入 Magisk SuList） |
| 模块包名 | `com.qoder.sogousym`（App 名「搜狗符号」） |
| 启动图标 | ✅ 自适应图标：浅色（近白冰蓝）底 + 扁平鲜蓝键帽 + 白色 `@` + 深蓝铅笔角标；另含各密度 PNG 兜底（资源见 §2） |
| 版本 | versionCode 96 / versionName 0.86 |
| 作用域 | `com.sohu.inputmethod.sogou` |
| 兼容的搜狗版本 | **v12.0（旧）/ v20.17.0（新，versionCode 2620）**——按运行时探测选 hook 集（见 §5.16）。v20 实测：角标「自定义 + 原符号」、长按、上划输出自定义符号均生效 |
| 测试环境 | Android 13 + Magisk(白名单 SuList) + LSPosed 2.1.1 |

---

## 1. 工程与构建

- 工程位置：本仓库根目录（Android Studio 直接 `File → Open`）
- 工具链：**AGP 9.2.1 / Gradle 9.4.1 / compileSdk 37.1 / minSdk 24 / Java 11**
- Xposed 依赖：`compileOnly("de.robv.android.xposed:api:82")`，仓库 `https://api.xposed.info/`
- 构建：AS 里直接 **Run**；或命令行 `./gradlew :app:assembleDebug`
- 产物：`app/build/outputs/apk/debug/app-debug.apk`

### 首次部署
1. **先卸载旧版**（签名不同会冲突）：`adb uninstall com.qoder.sogousym`
2. 安装：AS Run，或 `adb install -r app-debug.apk`
3. 打开 App「搜狗符号」，确认 LSPosed 里模块**已启用**、作用域勾选「搜狗输入法」
4. 点 App 内「重启搜狗输入法」按钮（或 `adb shell su -c 'am force-stop com.sohu.inputmethod.sogou'`）
5. 打开任意输入框，长按 `s` 应输入 `§`

### root 说明（重要）
设备 Magisk 是**白名单(SuList)模式**：**未加白名单的应用连 su 文件都看不到**（`File("/product/bin/su").exists()` 返回 false，exec 报 ENOENT），而不是"看得到但被拒绝"。所以 App 内「重启搜狗生效」按钮在未授权时会失败。
- 授权方法：Magisk → 设置 → SuList/白名单 → 勾选「搜狗符号」（包名 `com.qoder.sogousym`）。授权后 su 才对本应用可见、按钮才可用。
- 授权记录在 `/data/adb/magisk.db` 的 `policies` 表（按应用 uid 记录；重装后 uid 会变）。
- 判据：以应用身份跑 `run-as com.qoder.sogousym /product/bin/su -c id` —— 未授权时输出 `Permission denied`（exit 13）。
- 不想授权也可以用 adb：`adb shell su -c 'am force-stop com.sohu.inputmethod.sogou'`，或用面板「搜狗·强行停止」按钮手动停。
- 面板内 **root 状态** 区只显示「已授权 / 未授权」（绿/红），点「检测 root」重新判定（判定逻辑：依次试各 su 路径跑 `id`，看是否 `uid=0`）。

---

## 2. 代码结构

```
app/src/main/
├─ AndroidManifest.xml          # xposedmodule / xposedscope、MainActivity、ConfigProvider
├─ assets/xposed_init           # 内容：com.qoder.sogousym.MainHook
├─ res/values/xposed.xml        # xposedscope 数组（作用域）
└─ java/com/qoder/sogousym/
   ├─ Mapping.java              # 内置键表：26 字母 + 逗号/句号，含中/英段名与各自原符号
   ├─ MainHook.java             # Xposed 入口：hook 搜狗主题解析
   ├─ Prefs.java                # 配置读写：App 侧 SharedPreferences / hook 侧走 provider
   ├─ ConfigProvider.java       # 导出的只读 ContentProvider（跨进程传配置）
   └─ MainActivity.java         # 界面：卡片式面板（QWERTY 键位 + 测试框 + 生效方式 + 保存/重置/重启 + root 卡 + 跳转）
app/src/main/res/values/colors.xml   # 配色（accent/page_bg/card_bg/blue_*/text_*/ok/danger/header_*，照抄 superMi）
app/src/main/res/values-night/*.xml  # 暗色：同名额外的 colors.xml + styles.xml（AppTheme 换成深色父主题）
app/src/main/res/values/styles.xml   # AppTheme（Material.Light.NoActionBar，accent 状态栏/主色，page_bg 窗口底）
app/src/main/res/drawable/*.xml      # 形状集：bg_header / bg_card / bg_btn_primary·soft·ghost / bg_circle_header
                                     #        / bg_hint / bg_seg(+active) / bg_icon_tile / bg_input_selector / bg_key(+on)
app/src/main/res/drawable/ic_launcher_background.xml        # 自适应图标背景（把原图位图铺满 108dp 层）
app/src/main/res/drawable-nodpi/ic_launcher_art.png         # 图标原图（水印已修补，768px）
app/src/main/res/mipmap-anydpi-v26/ic_launcher(_round).xml  # 自适应图标（API 26+：背景=原图、前景透明）
app/src/main/res/mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher(_round).png  # 旧版方形/圆形兜底（主体再裁剪放大）
vibe_images/build_icon.py       # 派生根图标资源：python build_icon.py <原图>（缺省取最新 icon_*.png）
app/proguard-rules.pro          # keep MainHook / Mapping / Prefs / ConfigProvider
```

- `Mapping.KEYS`：全部可改键（26 字母按行 + 逗号/句号），每项含字母、中/英**运行时段名**、中/英**配置键**（`pyKey()`/`enKey()`）、各自原符号（唯一"键表"）
- 用户配置存在模块自己的 SharedPreferences（prefs 名 `config`），键 = 配置键，值 = 自定义符号（仅 1 字符，空 = 不修改）+ `#mode`/`#bigKey`
- 配置键例：`Key_S_PY`/`Key_S_EN`（段名本就不同，直接用）、`Key_Q_PY`/`Key_Q_EN`（段名共用 → 派生）、`Key_UDSymbol1_Qwerty`/`Key_CommaEn_T`（逗号/句号）

---

## 3. 关键实现（MainHook）

- `handleLoadPackage`：仅对 `com.sohu.inputmethod.sogou` 生效 → 后台线程 `refreshMapAsync()` 读配置 + `hookLoaderDiscovery()` + `installAll(lp.classLoader)`
- 配置来源：`refreshMapAsync()` 在**后台线程**等 `AndroidAppHelper.currentApplication()` 拿到 Context，然后经 `ConfigProvider` 查配置 → 存进 `MAP`（**绝不能在 loadClass 回调里读**，见 §5）
- `hookLoaderDiscovery()`：`hookAllMethods(ClassLoader,"loadClass")`；一旦发现 **`DelegateLastClassLoader`**（或 Path/DexClassLoader），就对**那个加载器** `installAll(...)` ← **核心**
- `installAll(cl)`（按加载器 identity 去重）挂这些 hook：
  - `defpackage.on#e(String, androidx.collection.ArrayMap, tn)`：**before** 往该段属性 map 注入 `MINOR_LABEL=自定义`
  - `defpackage.hp#j(...)` / `defpackage.k1#B(b, String key, String attr, String val, tn)`：**after** 对解析出的 key 对象调 `setSymbol`
  - 其余为诊断/兜底：`hp.h`、`tn.P`、`b.<init>`、6 个解析器的 `e`、`b.F0`、`commitText`、若干 getter
- `setSymbol(b, key, sym)`：
  - **角标（显示）**：`b.O2(sym + " " + 原符号)` → 显示 `§ ！`（原符号按段名从 `Mapping.origOf(key)` 取）
  - **输入**：`b.f0()` 的 public 字段 `g/h/i/j/l` 只写 `sym`
  - 显示与输入分开写，所以"角标双显示"不会污染长按输入

### 加/改映射
面板里改 → 保存 → 点「重启搜狗生效」。或在 `Mapping.KEYS` 里增删键（段名 + 中/英原符号）。例：
```java
new Key(2, "S", "Key_S_PY", "\uFF01", "Key_S_EN", "!");
new Key(2, "Q", "Key_Q",    "1",      "Key_Q",    "1");
```

---

## 4. 内置键表怎么来的（已做完，供以后搜狗升级后重取）

1. 运行时主题资源：`/data/data/com.sohu.inputmethod.sogou/tinker/patch-af771747/res/resources.apk`
2. 26 键键位：`assets/theme/theme_default/1080/port/26.ini` 的 `KEYS=`（中文）；英文用 `assets/foreign/1/theme/1080/phone/port/65536.ini`
3. 每键原符号：**以实测「长按实际提交的字符」为准**，不要直接用 `layout/template.ini` 的 `MINOR_LABEL`
   - 中文行2：`Key_A Key_S_PY Key_D Key_F Key_G Key_H_PY Key_J_PY Key_K Key_L_PY`
   - 英文行2：`Key_A Key_S_EN Key_D Key_F Key_G Key_H_EN Key_J_EN Key_K Key_L_EN`
   - `Key_Z/Key_X/Key_B/Key_N`（以及 `C/V/M`）**中英共用同一段名**，其余 22 个字母同理
   - ⚠️ **共用段名 ≠ 输出相同**：主题角标给的是半角（Z 的 `MINOR_LABEL=(`），但**中文模式提交的是全角**（实测 Z→`\uFF08`、X→`\uFF09`、B→`\uFF1A`、N→`\uFF1B`；`C -` / `V _` / `M /` 中英相同）。`Mapping` 的 `pyOrig` 存中文实际输出、`enOrig` 存英文实际输出。
   - 实测方法：`adb logcat | grep SogouSym` 读 `COMMIT[<字符>]`（长按每个键一次），或读 `sentinel -> <字符>`。
4. 逗号/句号：`Key_UDSymbol1_Qwerty` / `Key_UDSymbol2_Qwerty`（`CODES=-10001/-10002`，**无 MINOR_LABEL**，见 §6）

---

## 5. 技术背景（逆向结论，务必保留）

1. **hook 落点（最重要的坑）**：搜狗用 `dalvik.system.DelegateLastClassLoader`（热修复加载器）加载真正的业务类，**不是** `lp.classLoader`(PathClassLoader)。只在 `lp.classLoader` 上 `findClass` 装 hook **永远不触发**——这是前期大量尝试失败的根本原因。
2. **主题解析链**：`template.ini` 的 `MINOR_LABEL` → `defpackage.on#e` → `defpackage.k1#B` `MINOR_LABEL` → `b.O2()`。
3. **运行时资源**：来自 `tinker/patch-af771747/res/resources.apk`（不是 base.apk 的资源）。进程同时映射 base.apk 的 dex 与 tinker 补丁 dex，**实际代码以 tinker 补丁 dex 为准**。
4. **key 对象**：`com.sogou.theme.data.key.b`；`b.f0()` 返回 `ForeignKeyInfo`，public 字段 `g`(labels)/`h`(unicodes int[])/`i`/`j`/`l`(int) 是长按相关数据。
5. **只改 `O2` 只改显示**；要改**长按输入**必须同时写 `f0` 的 `g/h/i/j/l`。
6. **不要**在 `setSymbol` 里设 `i2/M2/W1/X1/Y1`（UPPER/MIDDLE/SECOND 标签）——会把**字母显示**也顶成符号。
7. **上划为什么改不了**：实测改主题文件 `MINOR_LABEL`、写 `ForeignKeyInfo` 全部字段、写 UPPER/MIDDLE/SECOND 标签，**上划都仍出原符号** → 上划取值来自搜狗引擎内部（native）。改主题文件只影响"角标显示"。
   - 2026-10-05 复挖佐证：26 键字母键段在 `template.ini` 里只有 `LABEL`/`MINOR_LABEL`/`TEXT`，**没有任何上划属性**；dex 里却有独立的上划提交概念 `COMMIT_TYPE_SLIDE_UP`、`COMMIT_PUNCTUATION_BY_SLIDE_UP`、`FUNC_UP_SLIDE_*`，以及键模型的 `UPPER_LABEL` / `POPUP_LONGPRESS_UPPER_LABELS` / `POPUP_LONGPRESS_UPPER_UNICODES`（"UPPER"=上划方向）。说明上划是引擎侧的能力，值来自内置标点表。
   - 搜狗 release 版**关闭了自身日志**，所以无法靠它的 log 定位。要再往前推只能：给模块加探针 hook 候选方法（记录上划时被调用的方法与返回值），**由人工在键盘上真实上划一次**（合成手势触发不了搜狗滑动）来定位；定位后把该返回值改成自定义符号。
   - 兜底替代方案：在 `commitText` 上做文本替换（提交文本 == 某已配置键的原符号 → 换成自定义符号）。能覆盖上划；**副作用（从符号面板手输同一符号也被改写）已于 2026-10-05 修掉，见 §5.13**。
   - **2026-10-05 已实现（提交替换）**：探针抓到上划提交走独立路径 `android.*#commitText <- BaseInputLogic#C <- BaseInputLogic#z0`（普通拼音是 `BaseInputLogic#z <- PinyinInputLogic#z`）。于是 hook `commitText`，当调用栈含 `BaseInputLogic#z0` 且提交文本等于某键原符号时，改 `p.args[0]` 成自定义符号 → 上划出自定义符号（实测 `！`→`7`）。
   - **⚠️ 长按与上划共用 `z0`**：长按的提交也来自 `z0`，光看提交路径**分不开**。
   - **英文模式（Typany 引擎）是另一条路**（2026-10-05 补）：英文下提交栈为 `android.hardware.*#commitText <- com.typany.shell.helper.EditorChangeHelper#applyChangeInternal <- EditorChangeHelper#applyChange <- com.typany.shell.Interface#applyEditorChange <- Interface#handleSecondaryInput <- com.sohu.inputmethod.foreign.inputsession.*`。`handleSecondaryInput` 同样**长按与上划共用**（实测两者栈逐帧相同）。因此 `isSlideUp()` 同时认中文的 `BaseInputLogic#z0` 和英文的 `com.typany.shell.Interface#handleSecondaryInput`。**不加这条时英文上划会漏改**（用户实测：S 中 `+`/英 `-`，英文上划回落到原符号 `!`）。
   - **合成上划可以用 `input swipe` 触发**（`adb shell input swipe <x> <y> <x> <y-270> 80`，要快、位移要够）；慢速的 `motionevent DOWN/MOVE/UP` 会被当成**长按**。
   - **「仅上划」= 哨兵法**（保留）：把每个字母键的长按数据 `f0` 写成它自己的私用区哨兵（`U+E000+i`，表见 `buildSentinels()` → `SENT_BY_SECTION`/`SENT2SECTION`），提交时若是哨兵就换回该键原符号（长按表现如常）、若是原符号就换成自定义（上划生效）。实测：仅上划下快划 S 出 `+`（`replace ！ -> +`），长按出 `！`（`sentinel -> ！`）。
   - ⚠️ **哨兵会画进长按气泡**：`f0.g` 同时用于「长按提交的值」和「长按气泡里画的那个字」，哨兵是私用区字符 → 气泡显示 **NO GLYPH 方块**。（用户明确接受这个显示代价。）
   - ⚠️ **「按住再滑」被引擎当长按**：纯长按与「按住再滑」的**完整调用栈**（`PinyinInputLogic#x0 → BaseInputLogic#E0 → z0 → C → commitText`）与 **Message 序列**（`what=25/84/156/78/90`）完全一致，分不开 → 这种手势会提交哨兵、回填成原符号。只有**快速一划**（不先按住）走引擎自己的上划通道，那条才出自定义。
   - **两条"让哨兵不显示"的路都走死了**（2026-10-05 实测）：
     ① 把显示与提交拆开（`g`=原符号、`h/i/j/l`=哨兵）→ 提交跟着 `g` 变回原符号，拆不开；
     ② 给符号追加不可见/可见标记（`orig+U+200B` 或 `orig+"x"`）→ **搜狗把按键的值截断到第一个字符**，追加的标记根本不进提交。所以哨兵必须是**单个字符**，只能是无字形字符。
   - 因此现在**只有两种生效方式**：仅长按 / 都生效。字母键统一把 `f0` 写成自定义符号（`setSymbol` 不再有哨兵分支），长按与上划都出自定义；「仅长按」靠 `swipeEnabled()==false` 关掉提交替换，快速一划仍出原符号。实测（中/英各三种手势）：`flick`→替换成自定义、`hold`→自定义、`slide`→自定义，气泡显示自定义符号（不再有方块）。
   - 英文（Typany 引擎）能区分长按与上划，但为保持一致也走同一套（`f0`=自定义）。
   - 逗号/句号的**点按**同样走 `z0`（`COMMIT[，] slideUp=true`），所以同一个替换逻辑也让 `，`/`。` 键能改输出。
8. **改 `resources.apk` 的坑**：重打包后必须 `zipalign -p 4`，否则未压缩资源 mmap 失败、**上划闪退**；动手前先 `cp -a` 备份。
9. **跨进程传配置别用文件/`XSharedPreferences`**：Android 的 SELinux 按应用给 `app_data_file` 打了类别标签，搜狗进程（不同 uid）读不到模块的 prefs 文件（即使 0644）。而且本机 LSPosed 还会把模块的 SharedPreferences 重定向到 `/data/misc/apexdata/<uuid>/prefs/<pkg>/`（`lsposed_file` 类型），`XSharedPreferences` 因此读空。**改用导出的 `ContentProvider`**（`ConfigProvider`，authority `com.qoder.sogousym.config`），hook 侧经 `AndroidAppHelper.currentApplication()` 拿 Context 查询，稳定可用。
10. **绝不能在 `ClassLoader.loadClass` 的 hook 回调里做文件/类加载类工作**：会与目标类的初始化锁重入死锁 —— 实测搜狗 IME 服务启动 ANR、键盘永远弹不出来（ANR 堆栈卡在 `Prefs.loadForHook`）。所以 `refreshMapAsync()` 放在 `handleLoadPackage` 里、在**后台线程**完成，且按加载器只装一次 hook。
11. **键面标签是 `BaseKeyData#r()`**：字母键返回字母（`GET r[Key_Q]=q`），所以**字母键面可以改**——覆盖 `r()` 的返回值，或调 `i2/M2/W1/X1/Y1`（实测把 S 键面从 `S` 变成 `7`，代价是看不到字母）。
    - **但逗号/句号的键面改不了**：可见键 `Key_UDSymbol1_Qwerty` 上**没有任何 getter 返回该字形**（`r`/`F0`/`n2`/`s2`/… 全 null）；唯一返回 `，` 的是它 `L_KEY` 指向的 `Key_Comma` 的 `r()`（那是长按弹出用的）。实测覆盖 `Key_Comma` 的 `r()`（日志 `face Key_Comma -> 9`）或它的 `TEXT`（日志 `k1.B TEXT Key_Comma -> 9`）**键面都不变**；再结合"去掉 `CODES` → 键面变空白"，可判定字形是引擎按 `CODES` 在绘制时算出来的，不经过 Java 键模型。
    - 注意：`Key_Comma`/`Key_Period` 这些段**不是 `on#e` 解析的**（`on.e INJECT` 只见到 `Key_UDSymbol*`），要改它们得从 `k1.B` 下手。
12. **当前是中文还是英文，靠 `tn#P` 判定**（2026-10-05 新增 `CN_MODE`）：`tn.P(String)` 每次加载布局都会**重新注册该布局的全部键名**，而一个布局**只声明自己模式的键**（中文下只有 `Key_S_PY`/`Key_Apostrophe_Shift`/`Key_SwitchToEN_Qwerty`，英文下只有 `Key_S_EN`/`Key_Shift_Qwerty`/`Key_EnPreSwitchToCH_Qwerty`/`Key_CommaEn_T`…）。`noteMode()` 看到 `_PY`/中文专用名 → 中文，`_EN`/英文专用名 → 英文。
    - 用途：`Mapping.origOf(section, CN_MODE)`、`Mapping.configKeyOf(section, CN_MODE)`、`customOf(section)`。Z/X/B/N 等**共用段名**的键，配置键与角标原符号都要按当前模式取。
    - 不要用 `on#e`/`k1#B` 判模式：实测它们**看不到 `Key_*` 段**（`on.e` 只给 `Advance`/`Cands_Badge_*` 这类主题段）。
    - ⚠️ **`tn.P` 只在某个布局「首次解析」时注册键名**：切到英文再切回中文时，搜狗复用缓存的布局、**不再注册**，于是 `CN_MODE` 卡在 `false` → 中文下用了英文替换表 → 查不到 → **上划失效**（用户实测「刚开始好，切英文再切回来就失效」；日志 `COMMIT[！] slideUp=true cn=false` 即此症）。所以**提交时必须按栈重新判模式**：`modeFromStack()` 扫栈，含 `com.typany.shell` → 英文，含 `BaseInputLogic` → 中文，每个 commit 都更新 `CN_MODE`（注入端仍靠 `tn.P`，因每个布局首次解析时就注册好了）。
    - 备选（更稳的模式信号，未采用）：英文模式的长按提交栈经 `com.typany.shell.helper.EditorChangeHelper`，中文经 `BaseInputLogic`。
    - **配置键派生**（`Mapping.Key#pyKey/enKey`）：段名不同的键直接用段名；**段名共用**的键（`shared()`）派生成 `<段名>_PY` / `<段名>_EN`。`Mapping.configKeyOf(运行时段名, 中英)` 做这个映射。**老配置**若把值存在裸段名下，`MainHook.customOf()` 有兜底回落。
    - ⚠️ **提交替换也必须是两张表**：C/V/M 这类键**中英原符号相同**（都是 `-`/`_`/`/`），若只用一张 `ORIG2CUSTOM`，英文值会覆盖中文 → 现在拆成 `ORIG2CUSTOM_PY` / `ORIG2CUSTOM_EN`（`PUNCT_PY/EN` 同理），提交时按 `CN_MODE` 取。
13. **提交替换不再误伤「符号面板」手输的符号**（2026-10-05 修复）：把 S 的 `！` 换成 `+` 后，从搜狗符号面板点 `！` 也会被换成 `+`，于是再也输不出 `！`。三条来源最后都汇到 `BaseInputLogic#C`，但再往下的栈不同：
    - 手势（快划 / 长按字母键）：`PinyinInputLogic#x0 <- b54#G0 <- q0#handleMessage`
    - 主键盘逗号/句号键：`PinyinInputLogic#F0 <- b54#L0 <- w0#handleMessage`
    - **符号面板**：`PinyinInputLogic#p0 <- b54#y0 <- h3#handleMessage`
    - 于是新增 `isSymbolPanelPath()`（栈里出现 `PinyinInputLogic#p0` 或 `inputsession.h3#handleMessage` 即判为面板），替换前先排除 → 面板手输保持原样，手势与逗号/句号键的替换不受影响。实测：面板点 `!` 保持 `!`（S 英文自定义为 `哈` 时不再被换）、快划 S 仍 `！`→`+`、逗号键仍 `，`→`8`。
14. **⚠️ 长按与上划「同文本」撞车（未解决）**：长按提交的是**自定义符号本身**（`f0.g`），上划提交的是**该键原符号**。若 A 键的自定义符号恰好等于 B 键的原符号、且 B 也设了自定义，则 A 长按会被当成 B 的上划替换掉。
    - 复现（v0.66，都生效模式）：S 中 `+`/英 `-`，C 中 `q`/英 `w` → 英文长按 S 出 `-`，被替换成 C 的 `w`。
    - 试过的解法都不可行：①哨兵只解决「仅上划」里长按 vs 上划的区分，对本撞车没用（都生效模式下长按本来就出自定义本身）；②用调用栈区分——实测中英两侧长按与上划的入口**参数完全相同**（英文都是 `com.typany.shell.Interface#handleSecondaryInput(text, 1)`，中文同走 `BaseInputLogic#z0`），分不开；③「显示与提交分开」——`f0.g` 同时用于气泡渲染与长按提交，改一个另一个跟着变，无法分开；④给值追加标记——引擎把值**截断到第一个字符**，标记进不了提交。
    - 可能的后续方向（未验证）：hook 更上层的手势/触摸处理（`handleSecondaryInput` 之上）拿手势类型。
    - 用户可规避：别把某键的自定义填成另一个键的原符号（原符号见 `Mapping` 的 `pyOrig`/`enOrig`）。
15. **搜狗 v20.17.0 的混淆映射（2026-10-05 实测，用 dexdump 反汇编 base.apk 定出）**：升级后**旧类名整体失效**（`hp`/`on`/`k1`/`tn`/`ws`/`ma2`/`tf4`/`cw7`/`i40`/`gn` 全部 ClassNotFound），主题解析改到 `com.sogou.theme.parse.parseimpl.*`：
    - 键模型：`com.sogou.theme.data.key.b` → **`com.sogou.theme.data.key.c`**（`c extends BaseKeyData`）；`BaseKeyData` / `ForeignKeyInfo` 类名未变。
    - 角标（MINOR_LABEL）：`b.O2(String)` → **`c.R2(CharSequence)`**（在 `parseimpl.a#B` 的 `"MINOR_LABEL"` 分支里被调用）。
    - 属性 setter：`k1.B(b,keyName,attr,val,tn)` → **`com.sogou.theme.parse.parseimpl.a#B(c, String keyName, String attr, String val, com.sogou.theme.data.view.a)`**。
    - 长按弹窗属性在 **`com.sogou.theme.parse.parseimpl.q#B`**：`POPUP_LONGPRESS_LABELS` → `ForeignKeyInfo.g`，`_UPPER_LABELS` → `.i`，`_UNICODES` → `.h`。
    - 长按数据 getter：`b.f0()` → **`BaseKeyData.m0()`**（返回同一个 `ForeignKeyInfo`；public 字段仍是 `g/h/i/j/l`，类型 g/i=CharSequence、h/j=int[]、l=int）→ `writeForeignKey` 只改方法名即可。
    - **不再有 tinker 补丁**：设备上已无 `/data/data/.../tinker/`，真实业务类就在 `lp.classLoader`（`PathClassLoader`）上（旧版在 `DelegateLastClassLoader` 上）。
    - 提交链路名字都还在：`BaseInputLogic#z0`/`#C`、`com.typany.shell.Interface#handleSecondaryInput`、`com.sogou.imskit.core.input.inputconnection.CachedInputConnection` → **提交替换逻辑不用改**。
    - ⚠️ **但手势判定方法名变了**：v12 长按/上划共用入口 `BaseInputLogic#z0`，v20 改成了 **`BaseInputLogic#D0`**（实测提交栈 `commitText <- BaseInputLogic#D <- BaseInputLogic#D0`）。所以 `isSlideUp()` 要按版本取 `z0`(v12) / `D0`(v20)，否则**新版上划不替换**（上划提交原符号 `！` 但判 `slideUp=false`，直接漏出去）。
    - v20 的 `ForeignKeyInfo` **只需写 `g`**（弹窗标签+长按提交值）；`h/j/l` 在新版是引擎自己的后备数组，覆盖成外来值会让引擎在长按自定义键时做多余工作、表现为**卡顿**（2026-10-05 改为 v20 只写 `g`）。
    - 复现方法：`dexdump -d <dex> | grep 'const-string.*"MINOR_LABEL"'` 找属性 setter；`findAndHookMethod` 前先用 `findClassIfExists` 探测。
16. **模块改为运行时版本探测**：`installAll(cl)` 先用 `findClassIfExists` 探测 `com.sogou.theme.parse.parseimpl.a`（→ v20）/ `hp`（→ v12），只装对应那套 hook；探测**不抛异常**（旧版每次 findClass 失败都产生异常栈、刷 LSPosed 日志）。装 v20 时钩 `parseimpl.a#B` + `parseimpl.q#B` + `commitText`；装 v12 时钩 `hp.j`/`on.e`/`k1.B`/`tn.P` + `commitText`。
    - ⚠️ **探测必须按 loader 限次数**（`PROBED` / `MAX_PROBES=3`）：`installAll` 是从 `ClassLoader.loadClass` 的 after-hook 里调的，不承载搜狗类的动态 dex loader（kuikly / `InMemoryDexClassLoader`）若每次都重新探测，会因为探测自身触发 loadClass 而**递归放大**、拖垮 class 加载。
    - **v20 下不再挂 `ClassLoader.loadClass` 全局 hook**（2026-10-05 卡顿优化）：该 hook 只为发现 v12 的热修复 `DelegateLastClassLoader`；v20 的真类就在 `lp.classLoader` 上，装了它等于给**每次类加载**加一层 Xposed 跳板 → 打字卡顿。改法：`handleLoadPackage` 先 `installAll(lp.classLoader)`，只有 `VER != 2` 才 `hookLoaderDiscovery()`（callback 里也加 `if (VER==2) return;` 兜底）。实测打字掉帧尾部尖峰消失（去掉前 99 分位 350ms/90 分位 20ms → 去掉后 90 分位 17ms、95 分位 19ms，无 350ms 尖峰，`dumpsys gfxinfo com.sohu.inputmethod.sogou`）。
    - 已删除的"诊断 hook"（`hookF0`/`hookHpH`/`hookKeyCtor`/`sectionParser`）：它们在新版会**误绑到热门方法**——`com.sogou.theme.data.key.BaseKeyData#F0()` 在 v20 是返回 int 的热方法（不是角标 getter），旧代码在它 after-hook 里做反射 + 刷日志，属纯负担。
17. **⚠️「输入法卡死」的真正根因（2026-10-05 实测，与模块无关）**：搜狗 IME 进程被 **OPPO ColorOS 的 `OplusHansManager`（应用速冻）** 用 cgroup freezer 反复冻结（日志 `OplusHansManager: freeze uid: 10344 com.sohu.inputmethod.sogou pids: [...] scene: LcdOn`，状态机 `U→SM→R→M→F`，约每 15~30s 一轮）。冻结后输入事件无人响应 → `ANR in com.sohu.inputmethod.sogou`（`Input dispatching timed out ... Waited 5000ms for MotionEvent`）→ 系统 `Killing ... (adj 100): bg anr` → 重启 → 循环。ANR trace 里**全线程 `do_freezer_trap`**（Java 栈 dump 不到，`libdebuggerd_client: failed to read status response from tombstoned`）。
    - **对照实验（关键）**：`adb uninstall com.qoder.sogousym`（此时 LSPosed 里没有任何模块注入搜狗）后，键盘照样每 30~45s 被冻结、100 秒内 4 次 ANR/杀进程；装「零 hook」版模块同样冻结。→ **不是模块导致**，触发点是搜狗 v20.17.0 自身被 OPPO 省电策略判定为可冻结。
    - 可行规避：把「搜狗输入法」加入 ColorOS 电池/省电白名单（不允许速冻 / 后台冻结），或换用/降级输入法版本。
    - 排查命令：`adb logcat | grep -E "OplusHansManager|ANR in com.sohu.inputmethod.sogou|freeze uid: 10344"`。

---

## 6. 限制 / 已知问题

- 上划不可通过主题改（§5.7）；但**可选是否用提交替换让上划出自定义符号**（面板「生效方式」）。
- **逗号/句号的键面改不了**（2026-10-05 用日志把这条路走完，见 §5.11）：字形由引擎按 `CODES` 在绘制时算，可见键上没有任何 getter 返回它，覆盖被指向的 `Key_Comma` 的 `r()`/`TEXT` 都无效。**输出**已可自定义（走 `z0` 提交替换，点按即出你的符号）。
- **字母键面可以改**（§5.11，覆盖 `r()` 或 `i2/M2/W1/X1/Y1`），代价是键面上看不到字母；目前**未做成开关**（默认保持显示字母）。
- **三种生效方式**：仅长按 / 仅上划（哨兵法）/ 都生效。⚠️「仅上划」的两点代价见 §5.7 末（气泡无字形方块、「按住再滑」出原符号）。逗号/句号无长按，输出改动始终生效。
- 自定义符号**限 1 个字符**。**全部 28 个键**都能分别填「中」「英」（段名共用的键由 `_PY`/`_EN` 派生配置键 + `CN_MODE` 区分，见 §5.12）。
- **⚠️ 撞车**：若某个键的自定义符号恰好等于**另一个键的原符号**、且那一键也设了自定义，则前者长按会被误替换（长按提交的是自定义符号本身，引擎无法与上划区分）。**目前未解决**，见 §5.13 —— 给用户看的规避办法：别把自定义填成别的键的原符号。
- 保存后需**强杀搜狗再拉起键盘**才生效（面板「重启搜狗生效」按钮；该按钮需 Magisk 给本 App 授权 root，未授权时会失败——面板 root 自检区会说明，也可用「搜狗·强行停止」手动停）。
- 依赖搜狗 v12.0 的混淆类名（`on`/`hp`/`k1`/`b`/`on#e`…）；**搜狗升级后可能失效**，需按 §4/§5/§7 重新定位。
- 提交日志行含 `cn=<true|false>`（当前判定到的中/英模式），排查中英相关的替换问题时先看它。
- **v20.17.0 兼容范围**（2026-10-05 实机验证）：角标（自定义 + 原符号）、「都生效」模式下的**长按**与**上划**输出自定义符号均已生效（`S` 中文自定义 `+` → 角标 `＋ ！`、长按出 `+`、上划 `replace ！ -> +`）。**未在 v20 上实机验证**：仅长按 / 仅上划（哨兵法）两种模式、逗号/句号的输出替换、英文模式。v12.0 代码路径保持原样，但本次**无法在 v12 设备上回归**（设备已升级）。
- **触发自定义键时的卡顿排查**（2026-10-05）：加探针确认**长按/上划期间模块 hook 一次都没被调用**（解析期才 27 次 MINOR_LABEL，手势期 0 次；长按提交的已是自定义符号、`interesting=false` 故不做替换）→ 模块代码不在该手势路径上。已把模块侧可能的开销降到最低（v20 只写 `g`、去掉全局 loadClass hook）。该机根因仍是 §5.17 的 OPPO 冻结/杀进程循环（测量期间 IME 进程每几秒重启一次），建议先给搜狗加省电白名单再评估首键延迟。
- **模块不再有诊断探针**（`F0`/`hp.h`/getter 等已删除，避免误绑新版的同名热方法）；排查时临时把 `MainHook.DEBUG_COMMIT` 置 `true` 可打印每次提交及调用栈。
- **卸载重装的副作用**（本次踩过）：① LSPosed 里模块记录的 APK 路径会失效，需在 LSPosed 管理器里重新启用/重新安装一次模块；② Magisk SuList 授权按 uid，重装后 uid 变 → 面板显示「未授权」，需回 Magisk 重新勾选；③ 模块的 SharedPreferences（自定义符号）会随重装清空。
- **⚠️「输入法卡死」不是模块问题**（见 §5.17），是 OPPO 速冻搜狗进程所致，卸载模块后同样复现。排查先看 `OplusHansManager freeze uid: 10344`。
- **打字卡顿**：模块侧已把最大开销（全局 `ClassLoader.loadClass` hook）在 v20 去掉（见 §5.16）。剩余掉帧（实测约 10% janky frames、90 分位 ~17ms）主要来自搜狗自身渲染 + OPPO 冻结后的解冻延迟；给搜狗加省电白名单同样能改善首键延迟。

---

## 7. 排查 / 复盘命令

```bash
# 看模块日志
adb logcat | grep SogouSym

# 卡死排查（OPPO 速冻搜狗进程）：谁冻结了它 / 是否 ANR / 是否被杀
adb logcat | grep -E "OplusHansManager|ANR in com.sohu.inputmethod.sogou|freeze uid: 10344"
adb shell su -c 'ls -t /data/anr/ | head -1'   # 最新 ANR trace；全线程 do_freezer_trap = 被冻结

# 反汇编（本机 build-tools 自带 dexdump）
/d/App/AndroidSdk/build-tools/34.0.0/dexdump.exe -d <dex> > x.dis
# 找 v20 属性 setter：grep 'const-string.*"MINOR_LABEL"' x.dis

# 强杀搜狗（让新 hook 生效）
adb shell su -c 'am force-stop com.sohu.inputmethod.sogou'

# 读界面文本（判断输入了什么）
adb shell uiautomator dump /sdcard/u.xml && adb exec-out cat /sdcard/u.xml

# 模拟长按（上划模拟不了：合成手势不触发搜狗滑动）
adb shell input motionevent DOWN <x> <y>
adb shell input motionevent UP   <x> <y>

# 读 App 私有文件（注意：adb pull 会被 MSYS 路径转换搞坏，用 exec-out cat）
adb exec-out su -c "cat /data/data/com.sohu.inputmethod.sogou/xxx" > out.bin

# 反编译（jadx 已备好；工具放在逆向产物目录同级的 tools/bin 下）
<jadx 工具目录>/bin/jadx -d out -r <dex>
```

---

## 8. 回滚

- LSPosed 里停用/卸载 `SogouSym`，或 `adb uninstall com.qoder.sogousym`
- 主题资源如需还原：设备上 `tinker/patch-af771747/res/resources.apk.bak` 是原版备份
- Magisk 授权记录（`policies` 表对应 uid 行）可在 Magisk 应用里撤销

---

## 9. 相关路径

| 内容 | 路径 |
|---|---|
| **本工程** | 本仓库根目录 |
| 旧手编工程（可删） | 早期的无 Gradle 手编工程（含 `build.sh`），已不再使用 |
| 逆向产物 | 分析用的独立目录（base.apk、tinker 补丁、各 dex 的 jadx 反编译、jadx 工具、api-82.jar）；**不随本仓库分发** |
| Android SDK | 本机自备；在 `local.properties` 里指向其路径（该文件已被 gitignore） |
