# 本地基线验证（hongguo 样本）

阶段 0 的基线样本（hongguo libmetasec_ml.so 签名复现）**只做本地验证，不入库不进 CI**：
so 与 .msdata 种子是专有二进制，golden 断言值属于样本分析产物。

## 文件（均被 .gitignore 排除）

- `unidbg-android/src/test/java/universal/com/phoenix/read/SixGod.java` —— 手工补环境的签名驱动（8.5k 行，来自 hongguo 项目积累）
- `unidbg-android/src/test/java/unibase/baseline/HongguoSignBaselineTest.java` —— 断言包装：跑 SixGod，捕获 stdout，7 个头齐全 + 格式断言（X-Gorgon 52 位 hex、X-Khronos=签名时刻 epoch、Argus/Ladon 短值形态等）

## 本机准备

1. so：`-Dunibase.hongguo.so` 指向 libmetasec_ml.so（v713，arm64）
2. 种子：把 .msdata 种子树解到 `-Dunibase.hongguo.seeds`（默认 `/tmp/msdata_files`，结构 `<seeds>/.msdata/mssdk/ml/…`）

## 运行

```bash
# Unicorn2
JAVA_HOME="<JBR 21>" ./gradlew :unidbg-android:smokeTest -Pbackend=unicorn2

# Dynarmic（阶段3后端下沉前的兼容性观察哨）
JAVA_HOME="<JBR 21>" ./gradlew :unidbg-android:smokeTest -Pbackend=dynarmic
```

CI 上该任务因载荷缺失自动空跑（`filter.setFailOnNoMatchingTests(false)`），矩阵保持绿。

## 已知修复（SixGod 移植时打上的）

1. **ENGFIX 指针污染**：初始化期把 CFF 引擎指针预写进 `0x3E0950`，sign 路径把它当
   sign 引擎 wrapper 读取 `+0x18` 得到垃圾值（0x24）→ `UC_ERR_READ_UNMAPPED`。
   sign 调用前必须把 `0x3E0950` 清零，让它自行初始化（历史成功运行时该值本来就是 0）。
2. **PRE_SIGN 诊断块**：对 `wrapper+0x18` 读出的值不加校验就解引用 → 加 `> 0x10000`
   守卫 + try/catch，诊断失败不阻断签名主路径。
3. **头名残留 \r**：so 返回串按 `\r\n` 切分后头名带 `\r`（`X-Gorgon\r: ...`），
   终端/工具渲染会吞掉 \r 造成"签名丢失"假象 —— 断言前先 `replace("\r","")`。
4. **JVM `-ea` 观察**：Gradle Test 默认开断言，与 Maven surefire 默认不同；
   smokeTest 统一 `enableAssertions=false` 保持与上游测试环境一致。

## 对平台的意义

这份"手工环境 + 固化 golden 断言"就是平台层第一个载荷的雏形：
`platform/payloads/hongguo-metasec/manifest.toml` 把这里的 so 路径、入口偏移（0x27d288）、
桩声明、参数与预期输出逐项声明化。阶段 1 的 Worker Pool + 快照热恢复直接消费它。
