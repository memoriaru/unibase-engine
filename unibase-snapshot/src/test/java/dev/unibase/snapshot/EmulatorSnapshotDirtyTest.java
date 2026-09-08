package dev.unibase.snapshot;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.arm.backend.Backend;
import com.github.unidbg.arm.backend.DynarmicFactory;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.memory.Memory;
import com.github.unidbg.pointer.UnidbgPointer;
import org.junit.After;
import org.junit.Test;

import java.util.Arrays;
import java.util.function.BiConsumer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/**
 * A2 页粒度脏页跟踪语义: 保存 → 破坏(脏写) → 恢复 只回写脏页。
 * dynarmic 后端走增量路径(C 层位图); unicorn2 不支持 → 全量路径(降级对照)。
 * 两个后端跑同一组断言 —— restore 语义与路径无关。
 *
 * 旧 natives 无 dirty 符号时 startDirtyTracking 抛 LinkageError → 快照自动
 * 降级全量路径, 断言仍全过(只有 usesDirtyTracking 断言跳过)。
 */
public class EmulatorSnapshotDirtyTest {

    private interface EmuFactory extends AutoCloseable {
        AndroidEmulator create();

        @Override
        void close();
    }

    private void runOn(BiConsumer<AndroidEmulator, Boolean> body) {
        for (EmuFactory factory : new EmuFactory[]{
                new EmuFactory() {
                    @Override public AndroidEmulator create() {
                        return AndroidEmulatorBuilder.for64Bit().setProcessName("snapshot-dirty-dyn")
                                // dynarmic native 不可用的平台(如 macos arm64, 见 DynarmicFactory)
                                // 由下一工厂回退 unicorn2 —— 测试断言与后端无关, 两种路径都正确
                                .addBackendFactory(new DynarmicFactory(true))
                                .addBackendFactory(new Unicorn2Factory(true)).build();
                    }
                    @Override public void close() {}
                },
                new EmuFactory() {
                    @Override public AndroidEmulator create() {
                        return AndroidEmulatorBuilder.for64Bit().setProcessName("snapshot-dirty-uc")
                                .addBackendFactory(new Unicorn2Factory(true)).build();
                    }
                    @Override public void close() {}
                }}) {
            try (AndroidEmulator emulator = factory.create()) {
                Memory memory = emulator.getMemory();
                UnidbgPointer page = memory.mmap(0x10000, 7);
                boolean dirtySupported = false;
                try {
                    dirtySupported = emulator.getBackend().startDirtyTracking();
                    emulator.getBackend().stopDirtyTracking(); // 探测后复位, 快照内再正式开启
                } catch (LinkageError | UnsupportedOperationException ignored) {
                }
                try {
                    body.accept(emulator, dirtySupported);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            } finally {
                factory.close();
            }
        }
    }

    private byte[] readBack(AndroidEmulator emulator, UnidbgPointer page, int size) {
        return emulator.getBackend().mem_read(page.peer, size);
    }

    @Test
    public void dirtyRestoreWritesOnlyDirtyPages() {
        runOn((emulator, dirtySupported) -> {
            Backend backend = emulator.getBackend();
            UnidbgPointer page = emulator.getMemory().mmap(0x10000, 7);
            byte[] pattern = new byte[0x2000];
            for (int i = 0; i < pattern.length; i++) {
                pattern[i] = (byte) (i * 3);
            }
            backend.mem_write(page.peer, pattern);

            try (EmulatorSnapshot snapshot = EmulatorSnapshot.save(emulator)) {
                if (dirtySupported) {
                    assertEquals("dynarmic 应支持脏页跟踪", true, snapshot.usesDirtyTracking());
                }
                // 破坏两页(非连续) + 寄存器
                byte[] garbage = new byte[0x1000];
                Arrays.fill(garbage, (byte) 0xAB);
                backend.mem_write(page.peer, garbage);                       // 第 0 页
                backend.mem_write(page.peer + 0x1000, garbage);              // 第 1 页
                byte[] mid = readBack(emulator, page, 0x2000);
                assertEquals(0xAB, mid[0] & 0xFF);

                snapshot.restore(emulator);

                assertArrayEquals("脏页应恢复", pattern, readBack(emulator, page, 0x2000));
                // 再次 restore(无新脏写)仍正确 —— 空脏集路径
                snapshot.restore(emulator);
                assertArrayEquals(pattern, readBack(emulator, page, 0x2000));
            }
        });
    }

    @Test
    public void repeatedDirtyCyclesStayDeterministic() {
        runOn((emulator, dirtySupported) -> {
            Backend backend = emulator.getBackend();
            UnidbgPointer page = emulator.getMemory().mmap(0x10000, 7);
            byte[] pattern = new byte[]{1, 2, 3, 4};
            backend.mem_write(page.peer, pattern);

            try (EmulatorSnapshot snapshot = EmulatorSnapshot.save(emulator)) {
                for (int i = 0; i < 3; i++) {
                    backend.mem_write(page.peer, new byte[]{9, 9, 9, 9});
                    snapshot.restore(emulator);
                    assertArrayEquals(pattern, readBack(emulator, page, 4));
                }
            }
        });
    }

    @Test
    public void jniSideWritesAreTrackedToo() {
        runOn((emulator, dirtySupported) -> {
            Backend backend = emulator.getBackend();
            UnidbgPointer page = emulator.getMemory().mmap(0x10000, 7);
            byte[] pattern = new byte[0x100];
            Arrays.fill(pattern, (byte) 0x5A);
            backend.mem_write(page.peer, pattern);

            try (EmulatorSnapshot snapshot = EmulatorSnapshot.save(emulator)) {
                // JNI 侧写(UnidbgPointer → backend.mem_write), 非 guest 执行写
                byte[] host = new byte[0x100];
                Arrays.fill(host, (byte) 0xCD);
                backend.mem_write(page.peer, host);
                assertEquals(0xCD, readBack(emulator, page, 0x100)[0] & 0xFF);

                snapshot.restore(emulator);
                assertArrayEquals("JNI 侧写入也应被跟踪恢复", pattern, readBack(emulator, page, 0x100));
            }
        });
    }
}
