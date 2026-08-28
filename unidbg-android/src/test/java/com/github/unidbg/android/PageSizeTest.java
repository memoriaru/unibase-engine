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

/** 16KB page 实验矩阵: 4K 模拟器加载 16K/4K 对齐 so。 */
public class PageSizeTest extends TestCase {

    private int run(String soName) throws Exception {
        AndroidEmulator emulator = AndroidEmulatorBuilder.for64Bit()
                .addBackendFactory(new Unicorn2Factory(false))
                .setProcessName("pagesize-test").build();
        try {
            Memory memory = emulator.getMemory();
            memory.setLibraryResolver(new AndroidResolver(23));
            File so = new File("src/test/resources/example_binaries/arm64-v8a/" + soName);
            if (!so.isFile()) {
                so = new File("unidbg-android/src/test/resources/example_binaries/arm64-v8a/" + soName);
            }
            Module module = memory.load(so, false);
            Symbol add3 = module.findSymbolByName("add3", false);
            assertNotNull("add3 符号缺失: " + soName, add3);
            return add3.call(emulator, 1, 2, 3).intValue();
        } finally {
            emulator.close();
        }
    }

    public void testLoad4KAligned() throws Exception {
        assertEquals(6, run("align4.so"));
    }

    public void testLoad16KAligned() throws Exception {
        assertEquals(6, run("align16.so"));
    }
}
