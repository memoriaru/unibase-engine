package com.github.unidbg.linux;

import com.github.unidbg.Emulator;
import com.github.unidbg.memory.SvcMemory;
import com.github.unidbg.pointer.UnidbgPointer;

import java.util.HashMap;
import java.util.Map;

/**
 * ELF TLS 支持(阶段3 · Android 现代化)。
 *
 * 职责:
 * 1. 为含 PT_TLS 的 so 分配 TLS 槽并复制 .tdata 初始数据
 * 2. 提供通用 TLSDESC resolver: 子线程/主线程访问 __thread 变量时,
 *    SO 代码 blr desc.func → resolver 返回变量地址(相对 TP 的恒等计算)
 *
 * MVP 限制: 所有线程共享同一 TLS 区(无 per-thread 副本) —— 单写者场景
 * (线程读初始值/单线程写)行为正确, 多写者互不隔离。
 */
public final class TLSManager {

    private static final TLSManager INSTANCE = new TLSManager();

    public static TLSManager getInstance() {
        return INSTANCE;
    }

    private TLSManager() {
    }

    private UnidbgPointer region;
    private long cursor;
    private final Map<String, Long> slots = new HashMap<>();
    private UnidbgPointer resolver;

    /** 为 so 分配 TLS 槽(16 字节对齐)并写入 .tdata 初始数据。 */
    public synchronized long allocateSlot(Emulator<?> emulator, String soName, long align, byte[] initData) {
        Long existing = slots.get(soName);
        if (existing != null) {
            return existing;
        }
        if (region == null) {
            region = emulator.getMemory().mmap(0x40000, 7); // 256K RW
            cursor = region.peer;
        }
        cursor = (cursor + align - 1) & ~(align - 1);
        long slot = cursor;
        if (initData != null && initData.length > 0) {
            emulator.getBackend().mem_write(slot, initData);
        }
        cursor += Math.max(initData == null ? 16 : initData.length, 16);
        slots.put(soName, slot);
        System.out.println("[TLSALLOC] " + soName + " slot=0x" + Long.toHexString(slot)
                + " init[0..4]=" + (initData != null && initData.length >= 4
                        ? String.valueOf(initData[0]) : "null"));
        return slot;
    }

    /** 查 so 的 TLS 槽基址; 未分配返回 0。 */
    public long getSlot(String soName) {
        Long v = slots.get(soName);
        return v == null ? 0L : v;
    }

    /**
     * 通用 TLSDESC resolver(合成代码, 一次分配全部 desc 共用):
     *   mrs x16, tpidr_el0   ; 当前线程 TP
     *   ldr x0, [x0, #8]     ; x0 = desc.arg = 变量绝对地址
     *   sub x0, x0, x16      ; x0 = 变量地址 - TP (调用方约定: mrs TP 后相加还原)
     *   ret
     */
    public synchronized UnidbgPointer getResolver(Emulator<?> emulator) {
        if (resolver != null) {
            return resolver;
        }
        SvcMemory svc = emulator.getSvcMemory();
        UnidbgPointer p = svc.allocate(32, "TLSDESC_resolver");
        p.setInt(0, 0xd53bd050);  // mrs x16, tpidr_el0
        p.setInt(4, 0xf9400400);  // ldr x0, [x0, #8]
        p.setInt(8, 0xcb100000);  // sub x0, x0, x16
        p.setInt(12, 0xd65f03c0); // ret
        resolver = p;
        return p;
    }
}
