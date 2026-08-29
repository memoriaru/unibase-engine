package com.github.unidbg.android;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.Emulator;
import com.github.unidbg.Module;
import com.github.unidbg.Symbol;
import com.github.unidbg.arm.FastTracer;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.linux.android.AndroidResolver;
import com.github.unidbg.memory.Memory;
import com.github.unidbg.trace.BinaryTraceReader;
import junit.framework.TestCase;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 阶段3 ④ 端到端: FastTracer NATIVE(C 层 ring)与 BINARY(Java 回调)双跑同一
 * 函数, pc 序列逐条一致 —— NativeTracer 的正确性验收(hongguo 大样本验收
 * 之外的自包含回归, 不依赖私有载荷)。
 *
 * natives 不含 uc_trace 符号的平台(旧 natives): NATIVE 自动降级 BINARY,
 * 本测试对降级路径 skip(仅验证不崩), 完整断言只在 native 实际生效时执行。
 */
public class FastTracerNativeTest extends TestCase {

    private static final String SO = "align4.so";

    private interface TracerBody {
        long apply(Symbol add3, Emulator<?> emulator) throws Exception;
    }

    private long runWith(TracerBody body) throws Exception {
        AndroidEmulator emulator = AndroidEmulatorBuilder.for64Bit()
                .addBackendFactory(new Unicorn2Factory(false))
                .setProcessName("ntrace-test").build();
        try {
            Memory memory = emulator.getMemory();
            memory.setLibraryResolver(new AndroidResolver(23));
            File so = new File("src/test/resources/example_binaries/arm64-v8a/" + SO);
            if (!so.isFile()) {
                so = new File("unidbg-android/src/test/resources/example_binaries/arm64-v8a/" + SO);
            }
            Module module = memory.load(so, false);
            Symbol add3 = module.findSymbolByName("add3", false);
            assertNotNull(add3);
            return body.apply(add3, emulator);
        } finally {
            emulator.close();
        }
    }

    private List<Long> runMode(String path, FastTracer.Mode mode, long[] countOut) throws Exception {
        final boolean[] active = {false};
        runWith((add3, emulator) -> {
            FastTracer t = new FastTracer(emulator, path, 1, 0, mode);
            // NATIVE 模式下 hook 仍可挂(回调被忽略), 与既有驱动用法兼容
            emulator.getBackend().hook_add_new(t, 1, 0, null);
            long r = add3.call(emulator, 1, 2, 3).intValue();
            r += add3.call(emulator, 10, 20, 30).intValue();
            t.stopTrace();
            countOut[0] = t.getCount();
            active[0] = t.isNativeTraceActive();
            return r;
        });
        List<Long> pcs = new ArrayList<>();
        try (BinaryTraceReader reader = new BinaryTraceReader(new FileInputStream(path))) {
            reader.forEach((tid, pc, size) -> pcs.add(pc));
        }
        if (mode == FastTracer.Mode.NATIVE && !active[0]) {
            return null; // 降级路径: 调用方 skip
        }
        return pcs;
    }

    public void testNativeMatchesBinary() throws Exception {
        long[] binCount = {0}, natCount = {0};
        List<Long> binPcs = runMode("/tmp/ntrace_bin.ubtr", FastTracer.Mode.BINARY, binCount);
        List<Long> natPcs = runMode("/tmp/ntrace_nat.ubtr", FastTracer.Mode.NATIVE, natCount);

        // 基本完整性: BINARY 路径不因 NATIVE 引入回归
        assertTrue(binPcs.size() >= 6);
        assertEquals(binCount[0], binPcs.size());

        if (natPcs == null) {
            // natives 无 uc_trace(旧 dylib/so): 降级 BINARY 已在 runMode 内验证不崩
            return;
        }

        assertEquals("NATIVE/BINARY 计数一致", binCount[0], natCount[0]);
        assertEquals("NATIVE/BINARY pc 序列长度一致", binPcs.size(), natPcs.size());
        for (int i = 0; i < binPcs.size(); i++) {
            assertEquals("pc[" + i + "]", (long) binPcs.get(i), (long) natPcs.get(i));
        }
    }
}
