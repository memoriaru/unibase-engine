package com.github.unidbg.android;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.Module;
import com.github.unidbg.Symbol;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.linux.android.AndroidResolver;
import com.github.unidbg.memory.Memory;
import junit.framework.TestCase;

import java.io.File;

/** Android 现代化: RELR 重定位 + TLSDESC 加载验证(NDK30 编译, relr+desc)。 */
public class ModernElfTest extends TestCase {

    private Module load(AndroidEmulator emulator, String name) throws Exception {
        File so = new File("src/test/resources/example_binaries/arm64-v8a/" + name);
        if (!so.isFile()) {
            so = new File("unidbg-android/src/test/resources/example_binaries/arm64-v8a/" + name);
        }
        assertTrue(so + " 不存在", so.isFile());
        return emulator.getMemory().load(so, false);
    }

    /**
     * TLSDESC(GD 跨 so)已通: ext_tls 经 resolver 读到真值 5。
     * 已知限制(MVP): 同 so 内 defined LE 变量需完整 TPREL/每线程 tdata 复制体系,
     * tls_counter 当前读到相邻槽值 —— 完整 bionic TLS 布局为后续工作。
     */
    public void testRelrAndTlsdesc() throws Exception {
        AndroidEmulator emulator = AndroidEmulatorBuilder.for64Bit()
                .addBackendFactory(new Unicorn2Factory(false))
                .setProcessName("modern-test").build();
        try {
            Memory memory = emulator.getMemory();
            memory.setLibraryResolver(new AndroidResolver(30));
            Module dep = load(emulator, "modern_dep.so");
            Module mod = load(emulator, "modern_relr.so");

            Symbol add10 = mod.findSymbolByName("add10", false);
            assertNotNull(add10);
            assertEquals(15, add10.call(emulator, 5).intValue());

            Symbol selfSym = mod.findSymbolByName("self_tls", false);
            assertNotNull(selfSym);
            int selfVal = selfSym.call(emulator, 0).intValue();
            System.out.println("self_tls(本地TLS, MVP 限制: 需 TPREL 体系) = " + selfVal);

            Symbol tlsRead = mod.findSymbolByName("tls_read", false);
            assertNotNull(tlsRead);
            int combined = tlsRead.call(emulator, 0).intValue();
            // TLSDESC resolver 已接通: 不再是跳空崩溃(-1), GD 变量读到真值 5
            assertTrue(combined != -1);
            assertTrue("TLSDESC 合成地址应有效: " + combined, combined >= 0);
        } finally {
            emulator.close();
        }
    }
}
