# ELF32 Runner —— 32 位 ARM 命令行程序运行器

一个 Android 应用：在**纯 64 位（AArch64）设备**上，通过内置的 A32 解释器**模拟执行 32 位 ARM（ARMv7）命令行二进制**。

> 它**不是**"能跑图形化 32 位 APK 的模拟器"。那种东西无法以 APK 形式存在（原因见文末）。
> 它是"APK 形式"在技术上唯一能真实落地、真实运行的东西：一个体积很小、可安装、可运行、
> 能在 64 位设备上证明"32 位指令确实被翻译执行了"的最小实现。

---

## 它是什么

```
你选中一个 32 位 ARM ELF（静态链接的命令行程序）
        │
        ▼
  Elf32 解析（读入口点、PT_LOAD 段，加载到内存映像）
        │
        ▼
  A32CPU 解释器（逐条取指 → 译码 → 解释执行，含 NZCV 标志、32 位回绕）
        │
        ▼
  ArmSyscalls 系统调用桥（write / exit / getpid …）
        │
        ▼
  输出结果显示在界面上
```

核心是 `A32CPU`：把 32 位 ARM 指令流一条条解码并解释执行。在 8 Gen 3 这类 CPU 已删除
AArch32 执行状态的设备上，这个解释器**就是**"32 位模拟器"——它不依赖硬件的 32 位能力，
只依赖宿主能跑 64 位 Java 字节码。

---

## 支持的系统版本

| 维度 | 要求 |
|---|---|
| **Android** | **5.0（API 21）及以上**。minSdk 21，用 `ACTION_OPEN_DOCUMENT`（API 19+）与标准平台 API |
| **CPU** | 任意架构均可（arm64-v8a / x86_64 / armeabi-v7a）。解释器是纯 Java，不依赖具体指令集 |
| **无需** | root、BL 解锁、NDK、任何原生库 |

> 注意：`minSdk 21` 是工程里设的下限。由于解释器只用了 Java 8 语法和 `Activity`/`Intent`/
> `PrintStream` 这些最基础的 API，实际可降到更低，21 只是保守设置。

---

## 运行环境要求

- **存储**：应用安装包 < 100 KB（release + 混淆后更小），运行占用内存 < 20 MB
- **权限**：无（用 SAF 文件选择器读用户选中的文件，不申请任何权限）
- **依赖**：零第三方库，只用 Android 平台自带的 `android.app.*` / `java.io.*`

---

## 主要功能限制

### 能做什么
- 运行**静态链接**的 32 位 ARM（ARMv7 / A32 指令集，小端）可执行文件
- 支持指令子集：数据处理（mov/mvn/add/sub/and/orr/eor/bic/cmp/cmn/tst/teq，
  含立即数、寄存器、桶形移位、S 位）、mul、ldr/str（字/字节，偏移与前/后索引，
  含 PC 相对）、b/bl/bx、svc
- 系统调用：write / exit / exit_group / getpid（其余返回 ENOSYS）
- 完整的 NZCV 条件码语义、32 位算术回绕、PC=地址+8 约定

### 不能做什么（这是与"真模拟器"的边界，必须诚实说明）

1. **不能运行 32 位 APK。** 一个 APK 要跑起来，需要 32 位 ART（跑 dex）、32 位
   bionic、32 位图形栈、binder 通信、zygote 进程模型——这些都要装在 `/system`，
   或由内核/init 提供，APK 一个都拿不到。本应用只能跑**独立的命令行 ELF**。

2. **不能动态链接。** 只支持 `ET_EXEC`（静态链接）。`ET_DYN`（PIE / 共享库）
   需要动态加载器 + 重定位 + 依赖库，超出"体积尽量小"的范围。

3. **不支持浮点 / NEON / SIMD。** VFP/NEON 是 32 位 ARM 最重的一块（占真实
   `libunity.so` 指令的约 7.9%），本实现不含浮点，遇到 `vmov/vadd/vldr` 等会报
   "未识别指令"。

4. **不支持 Thumb-2。** 只支持 ARM 状态（A32），`bx` 到奇数地址（Thumb 状态）会报错。

5. **系统调用极简。** 只有 write/exit/getpid。真实命令行程序常用的 open/read/close/
   mmap/brk/futex 等都没有——这意味着它目前**能完整运行的程序基本只有"写死的 hello
   类"**，不能跑 `ls`、`cat` 这类依赖文件系统的程序（那些还依赖动态链接，见第 2 条）。

6. **性能**：解释执行，约为原生速度的 1/1000 量级（纯 Java 逐条解释，无 JIT）。
   但它验证的是**正确性**，不是性能。

> **一句话总结**：本应用证明了"在 64 位设备上模拟执行 32 位指令"这件事**成立且体积极小**，
> 但从这里到"运行任意 32 位 Android 应用"，中间隔着 32 位 ART、图形栈、binder、
> 内核模块——那些必须 ROM 级集成，见 `../aosp-integration/`。

---

## 体积与占用

| 项目 | 数值 |
|---|---|
| 源码 | 约 30 KB（4 个 Java 类 + 1 个 138 字节内置示例） |
| APK（debug） | < 100 KB |
| APK（release + minify） | 预计 < 50 KB |
| 运行内存 | < 20 MB（解释器 + 映像，无大对象） |
| 内置示例 | 138 字节（一个 hello world 的 32 位 ELF） |

对比：上一阶段交付的 ROM 集成方案，翻译层本体约 2.2 MB，且还需 163 MB 的 32 位
用户空间。本应用把"翻译引擎"压缩到 **< 50 KB 的纯 Java**，代价是放弃了 ART/图形栈/
动态链接，只能跑最小的命令行程序。

---

## 如何编译

### 方式一：GitHub Actions 云端构建（推荐，零本地环境）

本机（Windows，仅有 JRE 1.8）**没有** Android SDK / JDK 17，所以构建交给 GitHub Actions。
推送到 `main` 即自动编译，`v*` 标签还会自动发 Release：

```bash
git push origin main          # 触发构建
git tag -a v1.0.0 -m "首版"   # 可选：同时发 Release 并把 APK 挂上去
git push origin v1.0.0        # 注意：务必先推分支，再推标签
```

产物在 Actions 页面 → 最新一次运行 → **Artifacts** → `elf32-runner-apk`。
打标签的话，APK 会直接出现在 Releases 页面。

本地想看結果不用等邮件：

```bash
export GH_TOKEN=<你的 GitHub 令牌>
python3 tools/actions_watch.py 480     # 轮询到构建结束，失败自动存 build-logs.zip
```

工作流要点（踩过的坑，别改回去）：

| 项 | 为什么 |
|---|---|
| **JDK 17**（非 8/11） | AGP 8.5.2 的硬要求，低版本直接报不支持的类文件版本 |
| **`.gitattributes` 锁 `gradlew` 为 LF** | 一旦混进 CRLF，Linux 上跑 `./gradlew` 报 `env: 'bash\r': No such file or directory` |
| **根 `build.gradle` 不写 `allprojects { repositories }`** | `settings.gradle` 用了 `RepositoriesMode.FAIL_ON_PROJECT_REPOS`，两者冲突会让 Gradle 立刻中止，报 "Build was configured to prefer settings repositories over project repositories" |
| **先验 `verify_engine.py` 再编译** | APK 编译通过 ≠ 解释器语义正确。语义自检是构建门槛，不过直接 fail，省得编出一个跑错结果的包 |
| **判成败看 APK 文件是否存在** | 不只依赖 gradle 退出码 |

### 方式二：本地 Android Studio

任意装了 Android SDK + JDK 17 的机器上：

```bash
./gradlew assembleDebug     # 产物 app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease   # 产物 app/build/outputs/apk/release/app-release.apk
```

> `local.properties` 含本机 SDK 绝对路径，已在 `.gitignore` 里，不要提交。

安装到设备：

```bash
adb install app/build/outputs/apk/release/app-release.apk
```

---

## 使用方法

1. 打开应用，点「运行内置示例（验证引擎）」——它会执行那个 138 字节的 32 位 ARM
   程序，输出 `Hello from ARM32!`，证明引擎工作正常。
2. 点「选择 32 位 ELF 文件」，选一个**静态链接的 32 位 ARM 可执行文件**，点「运行」。

内置示例的机器码（`samples/hello_arm32`）：

```
0xE3A00001   mov r0, #1
0xE59F1014   ldr r1, [pc, #20]    ; 从字面量池取字符串地址（imm12=20 字节）
0xE3A02012   mov r2, #18
0xE3A07004   mov r7, #4           ; syscall write
0xEF000000   svc #0
0xE3A00000   mov r0, #0
0xE3A07001   mov r7, #1           ; syscall exit
0xEF000000   svc #0
```

---

## 工程结构

```
elf32-runner/
├── build.gradle / gradle.properties / local.properties / settings.gradle
├── app/
│   ├── build.gradle
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/example/elf32runner/
│       │   ├── MainActivity.java       UI：选文件 / 运行 / 显示输出
│       │   ├── SampleProgram.java      内置 138 字节的 hello ELF
│       │   └── engine/
│       │       ├── A32CPU.java         32 位 ARM 解释器（核心）
│       │       ├── Elf32.java          ELF32 解析器
│       │       ├── ArmSyscalls.java    系统调用桥
│       │       └── Runner.java         串联执行
│       └── res/                       布局 / 样式 / 图标
├── samples/
│   ├── hello_arm32                    内置示例的原始 ELF
│   └── sum_1_10                       累加 1..10 的验证样例
└── docs/
    └── (说明文档)
```

---

## 验证记录

### 最新一轮：在真实 JVM 上运行 APK 里那份 Java 源码

`engine/` 包刻意不引用任何 `android.*` 类（只用 `java.io.PrintStream`），因此可以在
没有 Android SDK 的机器上直接 `javac` 编译并运行 —— 验证的是**会被打进 APK 的那份本体**，
而不是它的某个等价改写。

```bash
javac -encoding UTF-8 -d build/host \
    app/src/main/java/com/example/elf32runner/engine/*.java \
    tools/host/HostMain.java
java -Dfile.encoding=UTF-8 -cp build/host HostMain
```

```
[通过] hello_arm32   输出="Hello from ARM32!\n"   r0=0
[通过] sum_1_10      输出=""                      r0=55
```

这同样挂成了 GitHub Actions 的构建门槛：语义不过就不许出包。

### 三种错误，按危险程度排序

| # | 错误 | 现象 | 谁抓到的 |
|---|---|---|---|
| 1 | 第二操作数的移位量形式取了 **bit7**，ARM 规范应是 **bit4**（bit4=0=移位量立即数，=1=来自 Rs） | 编译通过；`add r0,r0,r1` 的移位量变成上一次的 `r[0]`，`sum_1_10` 算成 **101 而非 55** | **只有真跑才发现** —— Python 等价实现因为结构不同完全没复现 |
| 2 | 移位量 `>= 32` 未处理 | 不报错，但 Java 移位只取低 5 位，与 ARM 规定（LSL/LSR 得 0、ASR 得符号位）不符 | 代码审查 + `shifted()` 加了保护分支 |
| 3 | `carryOut` 写成 `boolean`、`shiftImm` 写成 `int` | javac 直接报 9 处 `incompatible types` | GitHub Actions 第一次构建 |

第一条是本记录的重点：**类型性错误编译器会拦，手写译码器的位域错误不会**。

### 更早：本机 Python 等价验证

本机（无 Android SDK）曾通过 Python 做了逐指令集的等价验证：

- `hello_arm32`：正确输出 `Hello from ARM32!`（7 条指令）
- `sum_1_10`：累加 1..10 = 55，含 `add`（立即数/寄存器）、`cmp`+`bne`（条件分支）、`svc`，43 步完成

期间发现两个 bug：**访存指令 bit25 语义与数据处理指令相反**（数据处理 bit25=1 是立即数，
ldr/str 则是 bit25=**0** 才是立即数偏移），以及 **`ldr` 立即数偏移本身就是字节偏移、不要再乘 4**。

> 教训：这套 Python 验证抓不到上面的第 1 条错误。等价实现给的是"检查 another 实现是否正确"，
> 不是"检查本体"。能跑本体就一定要跑本体。

---

## 为什么"APK 形式的 32 位模拟器"无法运行图形化应用

（完整论证见 `../arm32-on-arm64/README.md` 第六节）

| 必需能力 | 谁能提供 | APK 能否 |
|---|---|---|
| 32 位 ELF 自动落到翻译器 | 内核 `binfmt_misc` | ❌ 需 root/init |
| 32 位系统调用语义 | 内核模块 | ❌ 需 root + GKI 签名 |
| 32 位 ART / 图形栈 / bionic | `/system` 分区 | ❌ 无权写 |
| 32 位 zygote 进程模型 | init | ❌ 无法拉起 |

本应用是"APK 形态"能给到的**最接近的东西**：一个真实可运行、体积 <50 KB 的解释器，
证明翻译层在 64 位设备上确实工作。要跑真正的 32 位 Android 应用，路线是
`../aosp-integration/`（ROM 集成）、换设备（小米 14 等已内置 Tango）、或云手机。
