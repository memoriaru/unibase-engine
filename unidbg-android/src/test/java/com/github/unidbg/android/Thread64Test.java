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

/**
 * 协作式线程调度验收(阶段3): 双线程 mutex 竞争(futex 让出) + nanosleep 让出
 * + join 等待。Unicorn2Backend.registerEmuCountHook = 指令计数时间片(半抢占)。
 * 期望 run_threads() == 200(每线程 100 次 mutex 自增, 无丢失/死锁)。
 */
public class Thread64Test extends TestCase {

    public void testThreadDispatcher() throws Exception {
        AndroidEmulator emulator = AndroidEmulatorBuilder.for64Bit()
                .addBackendFactory(new Unicorn2Factory(false))
                .setProcessName("thread64-test").build();
        try {
            emulator.getBackend().registerEmuCountHook(10000); // 指令计数时间片
            Memory memory = emulator.getMemory();
            memory.setLibraryResolver(new AndroidResolver(23));
            emulator.getSyscallHandler().setEnableThreadDispatcher(true);

            // Gradle test 工作目录 = 模块目录(unidbg-android/); 兼容 engine 根目录运行
            File so = new File("src/test/resources/example_binaries/arm64-v8a/libthread64.so");
            if (!so.isFile()) {
                so = new File("unidbg-android/src/test/resources/example_binaries/arm64-v8a/libthread64.so");
            }
            assertTrue("libthread64.so 不存在", so.isFile());
            Module module = memory.load(so, false);

            Symbol symbol = module.findSymbolByName("run_threads", false);
            assertNotNull("找不到 run_threads 符号", symbol);
            Number ret = symbol.call(emulator, 0);
            System.out.println("run_threads ret=" + ret);
            assertEquals(200, ret.intValue());
        } finally {
            emulator.close();
        }
    }
}
