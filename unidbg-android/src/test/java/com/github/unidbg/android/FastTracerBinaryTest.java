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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 阶段3 ③ 端到端: FastTracer BINARY(UBTR)与 TEXT 双跑同一函数,
 * pc 序列逐条一致 + 模块字典正确 + 二进制体积显著小于文本。
 */
public class FastTracerBinaryTest extends TestCase {

    private static final String SO = "align4.so";

    private interface TracerBody {
        long apply(Symbol add3, Emulator<?> emulator) throws Exception;
    }

    private long runWith(TracerBody body) throws Exception {
        AndroidEmulator emulator = AndroidEmulatorBuilder.for64Bit()
                .addBackendFactory(new Unicorn2Factory(false))
                .setProcessName("ubtr-test").build();
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

    /** TEXT 行 "0xADDR  [..]  ..." → pc。 */
    private static List<Long> parseTextPcs(String path) throws IOException {
        List<Long> pcs = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                int sp = line.indexOf("  ");
                pcs.add(Long.parseLong(line.substring(2, sp), 16));
            }
        }
        return pcs;
    }

    public void testBinaryMatchesText() throws Exception {
        // TEXT 基线
        final long[] textResult = {0};
        List<Long> textPcs = new ArrayList<>();
        textResult[0] = runWith((add3, emulator) -> {
            FastTracer t = new FastTracer(emulator, "/tmp/ubtr_e2e_text.log", 1, 0);
            emulator.getBackend().hook_add_new(t, 1, 0, null);
            long r = add3.call(emulator, 1, 2, 3).intValue();
            r += add3.call(emulator, 10, 20, 30).intValue(); // 两次调用, 序列非平凡
            t.stopTrace();
            return r;
        });
        textPcs.addAll(parseTextPcs("/tmp/ubtr_e2e_text.log"));
        assertEquals(66, textResult[0]);
        assertTrue("两次调用应远超单次 3 条(lazy 解析路径): " + textPcs.size(),
                textPcs.size() >= 6);

        // BINARY 对照
        final long[] binResult = {0};
        final long[] binCount = {0};
        binResult[0] = runWith((add3, emulator) -> {
            FastTracer t = new FastTracer(emulator, "/tmp/ubtr_e2e.ubtr", 1, 0, FastTracer.Mode.BINARY);
            emulator.getBackend().hook_add_new(t, 1, 0, null);
            long r = add3.call(emulator, 1, 2, 3).intValue();
            r += add3.call(emulator, 10, 20, 30).intValue();
            t.stopTrace();
            binCount[0] = t.getCount();
            return r;
        });
        assertEquals(66, binResult[0]);

        // 解析: pc 序列逐条一致, 计数一致, 模块字典含目标 so
        final List<Long> binPcs = new ArrayList<>();
        try (BinaryTraceReader reader = new BinaryTraceReader(new java.io.FileInputStream("/tmp/ubtr_e2e.ubtr"))) {
            reader.forEach((tid, pc, size) -> binPcs.add(pc));
            assertTrue("模块字典应含 " + SO,
                    reader.getModules().stream().anyMatch(m -> m.name.equals(SO)));
        }
        assertEquals(binCount[0], binPcs.size());
        assertEquals("TEXT/BINARY pc 序列长度一致", textPcs.size(), binPcs.size());
        for (int i = 0; i < textPcs.size(); i++) {
            assertEquals("pc[" + i + "]", (long) textPcs.get(i), (long) binPcs.get(i));
        }

        // 体积: 二进制应显著小于文本
        long textSize = new File("/tmp/ubtr_e2e_text.log").length();
        long binSize = new File("/tmp/ubtr_e2e.ubtr").length();
        assertTrue("UBTR(" + binSize + "B) 应小于文本(" + textSize + "B)", binSize < textSize);
    }
}
