package com.github.unidbg.trace;

import java.nio.ByteBuffer;

/**
 * NativeTracer ring buffer 的 drain 结果(阶段3 ④)。
 *
 * buffer 是 C 层 ring 的只读 direct 视图, 有效字节由调用方按
 * {@link #written} 限定 —— 视图容量可能大于 written(初始容量), 不可越界读。
 * 实视图仅在 freeNativeTrace() 前有效。
 */
public final class NativeTraceData {

    /** ring 原始字节(小端), 只读; 可能为 null(未开启/无数据) */
    public final ByteBuffer buffer;
    /** 已写 entry 字节数 */
    public final long written;
    /** ring 写满标志 —— true 时 trace 不完整(溢出停写不覆盖) */
    public final boolean overflow;

    public NativeTraceData(ByteBuffer buffer, long written, boolean overflow) {
        this.buffer = buffer;
        this.written = written;
        this.overflow = overflow;
    }
}
