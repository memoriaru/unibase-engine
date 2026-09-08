package dev.unibase.snapshot;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.arm.backend.Backend;
import com.github.unidbg.arm.backend.DynarmicFactory;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.linux.android.AndroidResolver;
import com.github.unidbg.memory.Memory;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertFalse;

/**
 * A2 契约守护: dynarmic 的脏页跟踪门控必须保持关闭。
 *
 * 实测机制: dynarmic 开启 config.page_table 后 JIT 对已映射内存快速直访,
 * guest 常规 store 不经过 MemoryWrite* 回调 —— C 层位图对 guest 写不完整
 * (真实 so 函数调用实测脏页数 = 0), 增量恢复漏页会让后续执行走飞
 * (hongguo 实测: restore 回收 28 页后第二轮 sign 线性扫描 unmapped 800 万次)。
 *
 * 因此 DynarmicBackend.startDirtyTracking 必须 return false(全量恢复兜底)。
 * fork 级 mprotect COW(host 页 RO + fault 路径标记)落地后: 翻回 true 并把
 * 本测试改为"开启 + 真实 so 调用后断言脏页 >= 1"。
 */
public class DynarmicDirtyCoverageTest {

    @Test
    public void dirtyTrackingGateMustStayOffUntilFastmemCoverageIsFixed() throws Exception {
        AndroidEmulator emulator = AndroidEmulatorBuilder.for64Bit()
                .setProcessName("dirty-coverage")
                .addBackendFactory(new DynarmicFactory(true))
                .addBackendFactory(new Unicorn2Factory(true)) // dynarmic 不可用平台的回退
                .build();
        try {
            Memory memory = emulator.getMemory();
            memory.setLibraryResolver(new AndroidResolver(23));
            File so = new File("../unidbg-android/src/test/resources/example_binaries/arm64-v8a/align4.so");
            if (!so.isFile()) {
                so = new File("unidbg-android/src/test/resources/example_binaries/arm64-v8a/align4.so");
            }
            memory.load(so, false);
            Backend backend = emulator.getBackend();
            assertFalse("fastmem 直访绕过 MemoryWrite* 回调(见类注释), "
                            + "位图不完整期间 startDirtyTracking 必须返回 false",
                    backend.startDirtyTracking());
            assertFalse(backend.isDirtyTrackingActive());
        } finally {
            emulator.close();
        }
    }
}
