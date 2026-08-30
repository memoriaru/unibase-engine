package com.github.unidbg.arm.backend;

import java.util.Set;

/**
 * 后端能力协商(P3 · PLAN 阶段3 首项)。
 *
 * 背景: 各后端 API 支持面差异大且此前只能靠运行时异常发现(Dynarmic 的
 * CodeHook/ReadHook/WriteHook/BlockHook 抛 UnsupportedOperationException,
 * debugger_add 静默空实现 —— hongguo 与 unidbg 与 IDA 调试陷阱清单均实证)。
 * 能力协商让调用方在装 hook 前查询, 优雅降级或显式失败。
 *
 * 兼容性: Backend.capabilities() 默认返回全能力(保持既有语义 —— 未声明的
 * 后端假定可用), Unicorn2/Dynarmic 精确声明。新能力需求先加枚举再在
 * 后端实现侧声明。
 */
public enum Capability {
    /** 指令级代码 hook(hook_add_new(CodeHook,...)) */
    CODE_HOOK,
    /** 内存读 hook */
    READ_HOOK,
    /** 内存写 hook */
    WRITE_HOOK,
    /** 块级 hook */
    BLOCK_HOOK,
    /** 内存事件 hook(UNMAPPED 等) */
    EVENT_MEM_HOOK,
    /** 中断 hook(SVC) */
    INTERRUPT_HOOK,
    /** 调试器 hook(debugger_add) */
    DEBUG_HOOK,
    /** CPU 上下文快照(context_alloc/save/restore) —— 快照/COW 原语的基础 */
    CONTEXT_SNAPSHOT,
    /** 硬件断点 */
    HW_BREAKPOINT,
    /**
     * C 层指令 trace(uc_trace ring buffer, 阶段3 NativeTracer)。
     * 运行时可用性还取决于 natives 是否含 uc_trace 符号 —— 旧版 natives 下
     * startNativeTrace 抛 UnsatisfiedLinkError, 调用方应探测降级(见 FastTracer)。
     */
    NATIVE_TRACE,
    /**
     * 指令计数时间片(registerEmuCountHook —— 协作式线程调度器的抢占机制)。
     * 不支持的后端(如 dynarmic 无指令级 hook)线程调度退化为纯协作式
     * (仅 clone/futex/nanosleep 等让出点切换)。
     */
    EMU_COUNT,
    ;

    public static java.util.Set<Capability> all() {
        return Set.of(Capability.values());
    }
}
