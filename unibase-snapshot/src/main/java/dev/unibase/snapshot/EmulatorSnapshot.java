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
 * P1 快照原语: CPU 上下文 + 已映射内存快照。
 *
 * 语义: save 之后 emulator 上任何执行造成的变更, restore 后全部消失, 回到 save 时刻;
 * 同一 snapshot 可对同一 emulator 反复 restore。worker 模型 = 一个 snapshot 绑定
 * 一个 emulator(上下文句柄不可跨 backend)。
 *
 * 恢复路径(A2): backend 支持脏页跟踪(dynarmic C 层位图)时, restore 只回写
 * 脏页(4KB 粒度)—— guest/JNI 写路径在执行期置位, 典型请求脏集远小于全量内存,
 * restore 从全量 memcpy 降到脏页数 × 4KB 写。不支持时退回全量回写(原语义)。
 *
 * 已知限制: save 之后新映射的内存不在快照内(全量路径同样不覆盖), 脏页清单中
 * 属于这类页的条目跳过回写; 请求期 unmap/map 会混淆页索引的场景 sign 路径不出现。
 */
public final class EmulatorSnapshot implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(EmulatorSnapshot.class);

    /** 脏页粒度(与 dynarmic DYN_PAGE_BITS=12 对齐; 跟踪能力目前仅 dynarmic 提供) */
    private static final int DIRTY_PAGE_SIZE = 0x1000;

    private final Backend backend;      // save 时的 backend, context 句柄与之绑定
    private final long context;
    private final List<Region> regions;
    private final long payloadBytes;
    private final boolean dirtyTracking;
    private boolean closed;

    private EmulatorSnapshot(Backend backend, long context, List<Region> regions, long payloadBytes,
                             boolean dirtyTracking) {
        this.backend = backend;
        this.context = context;
        this.regions = regions;
        this.payloadBytes = payloadBytes;
        this.dirtyTracking = dirtyTracking;
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
        // 脏页跟踪在 save 时刻开启: 位图清零, 此后执行期的写入全部被标记
        boolean dirty = false;
        try {
            dirty = backend.startDirtyTracking();
        } catch (LinkageError | UnsupportedOperationException e) {
            log.debug("dirty tracking unavailable ({}), fallback to full restore", e.toString());
        }
        if (dirty) {
            log.debug("snapshot save with dirty tracking enabled");
        }
        log.debug("snapshot saved: {} regions, {} bytes", regions.size(), total);
        return new EmulatorSnapshot(backend, context, regions, total, dirty);
    }

    /** 恢复到 save 时刻。必须传创建本快照的同一 emulator(同 backend)。 */
    public void restore(Emulator<?> emulator) {
        if (closed) {
            throw new IllegalStateException("snapshot already closed");
        }
        if (emulator.getBackend() != backend) {
            throw new IllegalArgumentException("snapshot belongs to a different backend/emulator");
        }
        if (dirtyTracking && backend.isDirtyTrackingActive()) {
            restoreDirtyPages();
        } else {
            for (Region r : regions) {
                backend.mem_write(r.address, r.data);
            }
        }
        backend.context_restore(context);
    }

    /** 增量恢复: 只回写 save 以来被写过的页(collect 已挂起标记, 回写不会误标)。 */
    private void restoreDirtyPages() {
        long[] dirtyPages = backend.collectAndResetDirtyPages();
        try {
            if (dirtyPages.length > 0) {
                byte[] buf = new byte[DIRTY_PAGE_SIZE];
                for (long pageAddr : dirtyPages) {
                    Region r = regionContaining(pageAddr);
                    if (r == null) {
                        continue; // save 之后新映射的页, 快照无数据(与全量路径语义一致)
                    }
                    int offset = (int) (pageAddr - r.address);
                    int len = (int) Math.min(DIRTY_PAGE_SIZE, r.size - offset);
                    System.arraycopy(r.data, offset, buf, 0, len);
                    backend.mem_write(pageAddr, len == DIRTY_PAGE_SIZE ? buf : java.util.Arrays.copyOf(buf, len));
                }
                if (log.isDebugEnabled()) {
                    log.debug("snapshot restore wrote {} dirty page(s)", dirtyPages.length);
                }
            }
        } finally {
            backend.resumeDirtyMarking();
        }
    }

    private Region regionContaining(long address) {
        for (Region r : regions) {
            if (address >= r.address && address < r.address + r.size) {
                return r;
            }
        }
        return null;
    }

    /** 本快照是否启用了脏页跟踪(决定 restore 走增量还是全量)。 */
    public boolean usesDirtyTracking() {
        return dirtyTracking;
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
            if (dirtyTracking) {
                try {
                    backend.stopDirtyTracking();
                } catch (LinkageError ignored) {
                    // 旧 natives: start 已成功则不会走到这里, 防御性忽略
                }
            }
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
