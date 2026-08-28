package dev.unibase.snapshot;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.memory.Memory;
import com.github.unidbg.pointer.UnidbgPointer;
import unicorn.Arm64Const;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/** 快照原语语义测试: 内存与寄存器的 save → 破坏 → restore 往返。 */
public class EmulatorSnapshotTest {

    private AndroidEmulator emulator;
    private UnidbgPointer page;

    @Before
    public void setUp() throws Exception {
        emulator = AndroidEmulatorBuilder.for64Bit()
                .setProcessName("snapshot-test")
                .addBackendFactory(new Unicorn2Factory(true))
                .build();
        Memory memory = emulator.getMemory();
        page = memory.mmap(0x10000, 7); // PROT_READ|WRITE|EXEC
    }

    @After
    public void tearDown() throws Exception {
        if (emulator != null) {
            emulator.close();
        }
    }

    private byte[] readBack(int size) {
        return emulator.getBackend().mem_read(page.peer, size);
    }

    @Test
    public void saveRestoreRoundTrip() throws Exception {
        byte[] pattern = new byte[0x100];
        for (int i = 0; i < pattern.length; i++) {
            pattern[i] = (byte) (i * 7);
        }
        emulator.getBackend().mem_write(page.peer, pattern);
        emulator.getBackend().reg_write(Arm64Const.UC_ARM64_REG_X0, 0x1234L);

        try (EmulatorSnapshot snapshot = EmulatorSnapshot.save(emulator)) {
            // 破坏现场
            byte[] garbage = new byte[0x100];
            Arrays.fill(garbage, (byte) 0xAB);
            emulator.getBackend().mem_write(page.peer, garbage);
            emulator.getBackend().reg_write(Arm64Const.UC_ARM64_REG_X0, 0x0L);

            snapshot.restore(emulator);

            assertArrayEquals("内存未恢复", pattern, readBack(0x100));
            assertEquals("寄存器未恢复", 0x1234L,
                    emulator.getBackend().reg_read(Arm64Const.UC_ARM64_REG_X0).longValue());
        }
    }

    @Test
    public void repeatedRestoreIsDeterministic() throws Exception {
        byte[] pattern = new byte[]{1, 2, 3, 4};
        emulator.getBackend().mem_write(page.peer, pattern);

        try (EmulatorSnapshot snapshot = EmulatorSnapshot.save(emulator)) {
            for (int i = 0; i < 3; i++) {
                emulator.getBackend().mem_write(page.peer, new byte[]{9, 9, 9, 9});
                snapshot.restore(emulator);
                assertArrayEquals(pattern, readBack(4));
            }
        }
    }

    @Test
    public void snapshotReportsPayloadSize() throws Exception {
        try (EmulatorSnapshot snapshot = EmulatorSnapshot.save(emulator)) {
            assertEquals("区域数应与 MemoryMap 一致",
                    emulator.getMemory().getMemoryMap().size(), snapshot.regionCount());
            long mapped = emulator.getMemory().getMemoryMap().stream()
                    .mapToLong(m -> m.size).sum();
            assertEquals("负载字节数应等于已映射总量", mapped, snapshot.payloadBytes());
        }
    }
}
