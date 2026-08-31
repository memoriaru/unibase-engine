package com.github.unidbg.ios.service;

import com.github.unidbg.ios.OSVersion;
import org.junit.Test;

import java.io.StringReader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 桩库 B 期单测(CI 可跑, 零样本依赖): 表逻辑 + 版本门控语义。
 * guest 侧合成链路(CFString 结构/函数桩)由本地样本测试回归
 * (unibase.ios.GADSignalsCallTest, 文件缺失 skip)。
 */
public class HostStubStoreTest {

    @Test
    public void seedTableComplete() {
        HostStubStore store = new HostStubStore(null);
        // 实证种子(先例 UIKitNotificationStub/CoreGraphicsStub 全量并入)
        assertTrue(store.size() > 30);
        // 种子含: 通知名 + CG 数据常量 + CG 函数 + version gate(+1)
    }

    @Test
    public void externalTableOverridesSeed() throws Exception {
        HostStubStore store = new HostStubStore(null);
        int before = store.size();
        store.load(new StringReader(
                "_UIWindowDidBecomeKeyNotification\tcfstring\tCustomNameValue\tUIKit iOS2.0+\n" +
                "_FooNotification\tcfstring\t\t\t\n" +
                "_MyInsetsZero\tdata\t0,0,0,0\t\n" +
                "_MyRectFunc\tfunc\trect\t\n" +
                "_OBJC_CLASS_$_UIButton\tobjc-class\t\t# catjam count=25\n"));
        assertEquals(before + 4, store.size()); // 1 覆盖 + 4 新增
        assertTrue(store.size() > 0);
    }

    @Test
    public void tableParseErrors() throws Exception {
        HostStubStore store = new HostStubStore(null);
        try {
            store.load(new StringReader("_X\tbogus\t\n"));
            fail("unknown kind should fail");
        } catch (java.io.IOException expected) {
        }
        try {
            store.load(new StringReader("_X\tdata\t\n"));
            fail("data without payload should fail");
        } catch (java.io.IOException expected) {
        }
    }

    @Test
    public void versionGateSemantics() {
        OSVersion v7 = OSVersion.DEFAULT; // 7.1.0
        assertTrue(!v7.isAtLeast(8, 0, 0));
        assertTrue(!v7.isAtLeast(7, 2, 0));
        assertTrue(!v7.isAtLeast(7, 1, 1));
        assertTrue(v7.isAtLeast(7, 1, 0));
        assertTrue(v7.isAtLeast(7, 0, 0));
        assertTrue(v7.isAtLeast(6, 99, 99));

        OSVersion v16 = OSVersion.ofIOS(16, 5, 1, "20F75");
        assertTrue(v16.isAtLeast(15, 0, 0));
        assertTrue(v16.isAtLeast(16, 5, 1));
        assertTrue(!v16.isAtLeast(16, 6, 0));
        assertEquals("23.5.0", v16.kernelRelease); // Darwin = iOS + 7
        assertEquals("20F75", v16.buildVersion);
    }

    @Test
    public void defaultVersionIdentity() {
        assertSame(7, OSVersion.DEFAULT.major);
        assertSame(1, OSVersion.DEFAULT.minor);
        assertSame("14.0.0", OSVersion.DEFAULT.kernelRelease);
        assertSame("9A127", OSVersion.DEFAULT.buildVersion);
    }

    /** eFunc 直调 svc 桩(零样本): 版本门控 guest 侧真执行。 */
    @Test
    public void versionGateSvcCallable() throws java.io.IOException {
        com.github.unidbg.Emulator<?> emulator = null;
        try {
            emulator = com.github.unidbg.ios.DarwinEmulatorBuilder.for64Bit()
                    .setRootDir(new java.io.File("target/rootfs/hoststub-test"))
                    .addBackendFactory(new com.github.unidbg.arm.backend.Unicorn2Factory(true))
                    .build();
            emulator.getMemory().setLibraryResolver(new com.github.unidbg.ios.DarwinResolver());
            HostStubStore store = new HostStubStore(emulator);
            long gate = store.hook(emulator.getSvcMemory(), "test", "___isPlatformVersionAtLeast", 0);
            assertTrue(gate > 0);
            // 默认 7.1.0
            assertEquals(0L, emulator.eFunc(gate, 15, 0, 0).longValue());
            assertEquals(1L, emulator.eFunc(gate, 7, 1, 0).longValue());
            // 动态提档
            ((com.github.unidbg.ios.MachOLoader) emulator.getMemory())
                    .setOSVersion(OSVersion.ofIOS(16, 5, 0, "20F5046e"));
            assertEquals(1L, emulator.eFunc(gate, 15, 0, 0).longValue());
        } finally {
            if (emulator != null) {
                emulator.close();
            }
        }
    }
}
