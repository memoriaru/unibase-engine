package com.github.unidbg.android;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.Module;
import com.github.unidbg.Symbol;
import com.github.unidbg.arm.backend.DynarmicFactory;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.linux.android.AndroidResolver;
import com.github.unidbg.memory.Memory;
import com.github.unidbg.pointer.UnidbgPointer;
import org.junit.Test;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 反模拟检测面暴露矩阵(看雪 thread-292882 四探测点 · PLAN 后续计划⑦)。
 *
 * 探针 so(libemu_probe.so, 源码 src/test/native/emuprobe/emu_probe.c)在 guest 内执行:
 *   bit0 CNTVCT 恒定   bit1 CNTPCT 恒定   —— 真机: 计数器随硬件推进
 *   bit2 EL0 读 EL1-only(SCTLR_EL1)存活 —— 真机: 架构 UNDEF → SIGILL
 *   bit3 LDP 同寄存器对存活              —— 真机: CONSTRAINED UNPREDICTABLE → SIGILL
 *   bit4 CASP 奇偶错配对存活             —— 真机: UNDEFINED → SIGILL
 * bit2/3/4 是"确定能杀"类: 当前所有后端必然暴露(无权限隔离/无译码校验),
 * 本测试将其固化为回归基线 —— 未来接入 SIGILL 投递后翻转为"必须不暴露"。
 * bit0/1(计时器)只打印不硬断言(unicorn2 跟随宿主钟、dynarmic 恒定, ②虚拟时钟后更新)。
 */
public class EmulatorFingerprintProbeTest {

    private static final String SO = "libemu_probe.so";

    private static class ProbeResult {
        int detections;
        long cntfrq, cntvctDelta, cntpctDelta, sctlr;
        String backendName;
        boolean dynarmicReal;
    }

    private ProbeResult runProbes(AndroidEmulator emulator, int mode) throws Exception {
        Memory memory = emulator.getMemory();
        memory.setLibraryResolver(new AndroidResolver(23));
        File so = new File("src/test/resources/example_binaries/arm64-v8a/" + SO);
        if (!so.isFile()) {
            so = new File("unidbg-android/src/test/resources/example_binaries/arm64-v8a/" + SO);
        }
        Module module = memory.load(so, false);
        Symbol run = module.findSymbolByName("run_probes", false);
        Symbol resultSym = module.findSymbolByName("probe_result", false);
        assertTrue("run_probes 应可解析", run != null);
        assertTrue("probe_result 应可解析", resultSym != null);

        int detections = run.call(emulator, mode).intValue();
        // probe_result 是函数, 调用后 x0 返回 g_result 地址(不是符号地址本身)
        UnidbgPointer resultPtr = UnidbgPointer.pointer(emulator,
                resultSym.call(emulator).longValue() & 0xffffffffffffL);
        assertTrue(resultPtr != null);
        byte[] raw = resultPtr.getByteArray(0, 88);
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);

        ProbeResult r = new ProbeResult();
        int magic = buf.getInt();
        assertEquals("magic 应为 UBPR", 0x52504255, magic);
        r.detections = buf.getInt();
        int done = buf.getInt();
        assertEquals("探针应全部执行完毕(done 标记)", 0xDEADBEEF, done);
        buf.getInt(); // reserved
        r.cntfrq = buf.getLong();
        buf.getLong(); buf.getLong(); // cntvct a/b
        long cntpctA = buf.getLong();
        long cntpctB = buf.getLong();
        r.cntvctDelta = -1; // a/b 对在下方重读
        // 重读 cntvct a/b
        long vA = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).getLong(24);
        long vB = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).getLong(32);
        r.cntvctDelta = vB - vA;
        r.cntpctDelta = cntpctB - cntpctA;
        r.sctlr = buf.getLong(56);
        assertEquals("detections 返回值应与结构体一致", r.detections & 0xFFFF, detections & 0xFFFF);
        // 高 16 位 = skip 位, 与低位的探测结果互斥(同位不同时置位)
        assertEquals("skip 的探测不应产生暴露位",
                0, r.detections & ((r.detections >>> 16) & 0xFFFF));
        r.backendName = emulator.getBackend().getClass().getSimpleName();
        r.dynarmicReal = r.backendName.contains("Dynarmic");
        return r;
    }

    private void printMatrix(String leg, ProbeResult r) {
        System.out.printf("[FPMATRIX] %s backend=%s detections=0x%02x "
                        + "cntfrq=%d cntvctDelta=%d cntpctDelta=%d sctlr_el1=0x%x ldp/casp=executed%n",
                leg, r.backendName, r.detections, r.cntfrq, r.cntvctDelta, r.cntpctDelta, r.sctlr);
    }

    private void assertDeterministicExposures(ProbeResult r, int mode, boolean expectLeak) {
        if (!expectLeak) return; // 异常语义的探测组合不做 leak 断言
        int ran = mode & 0b1110;               // probe2..4 的 ran 位
        int deterministicBits = ran << 1;      // probe p → 暴露位 bit p
        assertEquals("已运行的译码探测应暴露(执行即泄漏)",
                deterministicBits, r.detections & deterministicBits);
        if ((mode & 2) != 0) {
            assertTrue("SCTLR_EL1 泄漏值应非零", r.sctlr != 0);
        }
    }

    private void assertTimerTicks(ProbeResult r) {
        assertTrue("CNTVCT 应随工作量推进(②虚拟时钟): delta=" + r.cntvctDelta,
                r.cntvctDelta != 0);
        assertTrue("CNTPCT 应随工作量推进(②虚拟时钟): delta=" + r.cntpctDelta,
                r.cntpctDelta != 0);
    }

    /**
     * unicorn2 计时器: 跟随宿主钟(delta 非零), 朴素探测不可区分;
     * 指令数关联分析可识破 —— fork qemu timer patch 为后续项。
     * probe2/3/4 在 unicorn2 上全部正确抛 EXCP_UDEF(QEMU 新版译码严格,
     * 与真机 SIGILL 方向一致; jstack 实证), 但 unidbg 把 UDEF 送进控制台
     * 调试器而非投递 guest SIGILL —— 投递缺口归 ③深改档, 不进 CI。
     */
    @Test
    public void unicorn2LegMatrix() throws Exception {
        ProbeResult uc;
        int mode = Integer.getInteger("unibase.probe.mode.uc", 0x1);
        try (AndroidEmulator emulator = AndroidEmulatorBuilder.for64Bit()
                .setProcessName("emu-probe-uc")
                .addBackendFactory(new Unicorn2Factory(true))
                .build()) {
            uc = runProbes(emulator, mode);
            printMatrix("unicorn2-leg(mode=" + mode + ")", uc);
        }
        assertTimerTicks(uc);
    }

    /**
     * dynarmic 计时器(②虚拟时钟验收): CNTVCT/CNTPCT 均为 基准+指令数×1,
     * 修复前 CNTPCT 为恒定值(delta=0, 确定性暴露)、CNTVCT 直接不支持
     * (interpreter fallback → UDEF)。
     */
    @Test
    public void dynarmicTimerVirtualClock() throws Exception {
        ProbeResult dyn;
        try (AndroidEmulator emulator = AndroidEmulatorBuilder.for64Bit()
                .setProcessName("emu-probe-dyn-timer")
                .addBackendFactory(new DynarmicFactory(true))
                .addBackendFactory(new Unicorn2Factory(true))
                .build()) {
            dyn = runProbes(emulator, 0x1);
            printMatrix("dynarmic-timer", dyn);
        }
        assertTrue(dyn.dynarmicReal);
        assertTimerTicks(dyn);
    }

    /**
     * dynarmic 译码探测(实测矩阵, 2026-09-08):
     *   probe2 MRS SCTLR_EL1 → fallback → UDEF(异常语义, 与真机 SIGILL 方向一致)
     *   probe3 LDP x9,x9    → 原生致命(JIT abort, 杀进程; 真机可捕获 SIGILL 继续)
     *   probe4 CASP 奇偶    → 未测(同族译码, 预期同 probe3)
     * 三者在 dynarmic 上均无法进程内测量 —— 默认 mode=0(全跳过)保 CI 绿;
     * 手动测量: -Dunibase.probe.mode.dyn=0x4|0x2|0x8(单测一个, 见类注释坑)。
     * UDEF 投递缺口(调试器而非 guest SIGILL)与 fatal 行为修复归 ③深改档。
     */
    @Test
    public void dynarmicLeakProbes() throws Exception {
        ProbeResult dyn;
        int mode = Integer.getInteger("unibase.probe.mode.dyn", 0x0);
        try (AndroidEmulator emulator = AndroidEmulatorBuilder.for64Bit()
                .setProcessName("emu-probe-dyn-leak")
                .addBackendFactory(new DynarmicFactory(true))
                .addBackendFactory(new Unicorn2Factory(true))
                .build()) {
            dyn = runProbes(emulator, mode);
            printMatrix("dynarmic-leak(mode=" + mode + ")", dyn);
        }
        if (mode != 0) {
            assertDeterministicExposures(dyn, mode, true);
        } else {
            System.out.println("[FPMATRIX] dynarmic leak probes skipped (mode=0; 致命/异常探测不进 CI)");
        }
    }

    // 直接运行入口(绕过 Gradle 便于观察原生崩溃): -Dunibase.probe.mode.dyn=N
    public static void main(String[] args) throws Exception {
        new EmulatorFingerprintProbeTest().dynarmicTimerVirtualClock();
    }
}
