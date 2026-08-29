package com.github.unidbg.arm.backend.dynarmic;

public interface DynarmicCallback {

    void callSVC(long pc, int swi);

    /**
     * 返回<code>false</code>表示未处理的指令
     */
    boolean handleInterpreterFallback(long pc, int num_instructions);

    void handleExceptionRaised(long pc, int exception);

    /**
     * 未映射读: native 侧通知后重查页表。返回<code>true</code>表示 hook 已处理
     * (通常已 lazy 映射); 返回<code>false</code>时 native 侧 WARN+假值继续
     * (对齐 unicorn2 语义, 替代上游 abort(), 见 dynarmic.cpp notify_memory_*)。
     */
    boolean handleMemoryReadFailed(long vaddr, int size);

    boolean handleMemoryWriteFailed(long vaddr, int size);

}
