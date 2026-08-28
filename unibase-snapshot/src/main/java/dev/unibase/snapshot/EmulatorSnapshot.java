package dev.unibase.snapshot;

import com.github.unidbg.Emulator;
import com.github.unidbg.arm.backend.Backend;
import com.github.unidbg.memory.MemoryMap;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * P1 快照原语(MVP): CPU 上下文 + 已映射内存的全量快照。
 *
 * 语义: save 之后 emulator 上任何执行造成的变更, restore 后全部消失, 回到 save 时刻;
 * 同一 snapshot 可对同一 emulator 反复 restore。worker 模型 = 一个 snapshot 绑定
 * 一个 emulator(上下文句柄不可跨 backend)。
 *
 * COW 说明: 当前为全量保存/回写。已映射内存通常几十 MB 级, memcpy 回写毫秒级;
 * 页粒度 COW(写时复制)与脏页跟踪是后续优化(PLAN 阶段1)。
 */
public final class EmulatorSnapshot implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(EmulatorSnapshot.class);

    private final Backend backend;      // save 时的 backend, context 句柄与之绑定
    private final long context;
    private final List<Region> regions;
    private final long payloadBytes;
    private boolean closed;

    private EmulatorSnapshot(Backend backend, long context, List<Region> regions, long payloadBytes) {
        this.backend = backend;
        this.context = context;
        this.regions = regions;
        this.payloadBytes = payloadBytes;
    }

    /** 保存当前 CPU 上下文与全部已映射内存。要求 emulator 处于非执行状态(调用间隙)。 */
    public static EmulatorSnapshot save(Emulator<?> emulator) {
        Backend backend = emulator.getBackend();
        Collection<MemoryMap> maps = emulator.getMemory().getMemoryMap();
        List<Region> regions = new ArrayList<>(maps.size());
        long total = 0;
        for (MemoryMap map : maps) {
            byte[] data = backend.mem_read(map.base, map.size);
            regions.add(new Region(map.base, map.size, map.prot, data));
            total += map.size;
        }
        long context = backend.context_alloc();
        backend.context_save(context);
        log.debug("snapshot saved: {} regions, {} bytes", regions.size(), total);
        return new EmulatorSnapshot(backend, context, regions, total);
    }

    /** 恢复到 save 时刻。必须传创建本快照的同一 emulator(同 backend)。 */
    public void restore(Emulator<?> emulator) {
        if (closed) {
            throw new IllegalStateException("snapshot already closed");
        }
        if (emulator.getBackend() != backend) {
            throw new IllegalArgumentException("snapshot belongs to a different backend/emulator");
        }
        for (Region r : regions) {
            backend.mem_write(r.address, r.data);
        }
        backend.context_restore(context);
    }

    /** 快照携带的内存字节数(不含 JVM 对象头开销)。 */
    public long payloadBytes() {
        return payloadBytes;
    }

    public int regionCount() {
        return regions.size();
    }

    @Override
    public void close() {
        if (!closed) {
            backend.context_free(context);
            closed = true;
        }
    }

    private static final class Region {
        final long address;
        final long size;
        final int perms;
        final byte[] data;

        Region(long address, long size, int perms, byte[] data) {
            this.address = address;
            this.size = size;
            this.perms = perms;
            this.data = data;
        }
    }
}
