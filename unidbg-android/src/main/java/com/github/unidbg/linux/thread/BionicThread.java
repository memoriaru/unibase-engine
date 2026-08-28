package com.github.unidbg.linux.thread;

import com.github.unidbg.AbstractEmulator;
import com.github.unidbg.Emulator;
import com.github.unidbg.arm.backend.Backend;
import com.github.unidbg.pointer.UnidbgPointer;
import com.github.unidbg.thread.ThreadTask;
import com.sun.jna.Pointer;
import unicorn.Arm64Const;
import unicorn.ArmConst;

/**
 * 通用 bionic 线程(阶段3): 与 MarshmallowThread(按 Android M 的
 * pthread_internal_t 固定偏移猜 TLS)不同, 直接使用 clone 系统调用
 * CLONE_SETTLS 传入的真实 TLS 指针 —— 布局由 SO 的 libc 调用方排好,
 * 不依赖 bionic 版本。
 *
 * 背景: 新版 bionic 的 __thread_entry 从 TPIDR_EL0 指向的
 * pthread_internal_t 按 API 版本相关偏移读 start_routine; MarshmallowThread
 * 的 arg.share(0xb0) 是 Android 6.0 布局, 在新 bionic 下读到 0 →
 * 线程跑飞 PC=0(hongguo 实证)。
 */
public class BionicThread extends ThreadTask {

    private final UnidbgPointer fn;
    private final UnidbgPointer arg;
    private final Pointer tls;
    private Pointer tidptr;

    public BionicThread(Emulator<?> emulator, UnidbgPointer fn, UnidbgPointer arg,
                        Pointer tls, Pointer tidptr, int tid) {
        super(tid, emulator.getReturnAddress());
        this.fn = fn;
        this.arg = arg;
        this.tls = tls;
        this.tidptr = tidptr;
    }

    @Override
    public void setExitStatus(int status) {
        super.setExitStatus(status);
        if (tidptr != null) {
            tidptr.setInt(0, 0); // CLONE_CHILD_CLEARTID 语义: 线程退出清 tid
        }
    }

    @Override
    public String toThreadString() {
        return String.format("BionicThread tid=%d, fn=%s, arg=%s, tls=%s", id, fn, arg, tls);
    }

    @Override
    protected Number runThread(AbstractEmulator<?> emulator) {
        Backend backend = emulator.getBackend();
        UnidbgPointer stack = allocateStack(emulator);
        if (emulator.is32Bit()) {
            backend.reg_write(ArmConst.UC_ARM_REG_R0, UnidbgPointer.nativeValue(arg));
            backend.reg_write(ArmConst.UC_ARM_REG_SP, stack.peer);
            if (tls != null) {
                backend.reg_write(ArmConst.UC_ARM_REG_C13_C0_3, UnidbgPointer.nativeValue(tls));
            }
            backend.reg_write(ArmConst.UC_ARM_REG_LR, until);
        } else {
            backend.reg_write(Arm64Const.UC_ARM64_REG_X0, UnidbgPointer.nativeValue(arg));
            backend.reg_write(Arm64Const.UC_ARM64_REG_SP, stack.peer);
            if (tls != null) {
                backend.reg_write(Arm64Const.UC_ARM64_REG_TPIDR_EL0, UnidbgPointer.nativeValue(tls));
            }
            backend.reg_write(Arm64Const.UC_ARM64_REG_LR, until);
        }
        return emulator.emulate(this.fn.peer, until);
    }

    public void set_tid_address(Pointer tidptr) {
        this.tidptr = tidptr;
    }
}
