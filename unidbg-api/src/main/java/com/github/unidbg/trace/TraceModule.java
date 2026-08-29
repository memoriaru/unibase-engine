package com.github.unidbg.trace;

/**
 * trace 模块字典条目: 模块名 + 加载基址 + 大小。
 * 二进制 trace(UBTR)头部记录, 供离线解析时还原 [module+offset] 标注。
 */
public final class TraceModule {

    public final String name;
    public final long base;
    public final long size;

    public TraceModule(String name, long base, long size) {
        this.name = name;
        this.base = base;
        this.size = size;
    }

    @Override
    public String toString() {
        return name + "@0x" + Long.toHexString(base) + "+0x" + Long.toHexString(size);
    }
}
