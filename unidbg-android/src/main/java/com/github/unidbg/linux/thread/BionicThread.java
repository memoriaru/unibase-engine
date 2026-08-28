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
 * 通用 bionic 线程(阶段3): ARM64 clone 语义的正确实现。
 *
 * ARM64 kernel clone 与 arm32 不同: 子线程**从 syscall 返回点继续执行**
 * (x5/x6 不是 fn/arg —— 那是 arm32 __bionic_clone.S 的约定)。两种模式:
 *  1. fnMode(经 libc __pthread_start): libc pthread_create 路径, X0=arg,
 *     TLS=CLONE_SETTLS 值
 *  2. continueMode(SO 手搓 syscall clone): 子线程上下文 = 主线程 clone
 *     时的上下文副本(PC=svc 之后), x0=0(kernel 语义: 子线程 clone 返回 0)
 *
 * hongguo 实证: 加固 SO 手搓 clone, fnMode 跳进 __pthread_start 读到
 * start_routine=0 → PC=0 跑飞; continueMode 才是正确语义。
 */
public class BionicThread extends ThreadTask {

    private final UnidbgPointer fn;
    private final UnidbgPointer arg;
    private final Pointer tls;
    private final boolean continueMode;
    private Long rawEntryPC;     // rawContext 模式: 子线程初始 PC(clone 返回点)
    private UnidbgPointer rawStack; // rawContext 模式: 子线程初始 SP(child_stack)
    private Pointer tidptr;

    public BionicThread(Emulator<?> emulator, UnidbgPointer fn, UnidbgPointer arg,
                        Pointer tls, Pointer tidptr, int tid) {
        this(emulator, fn, arg, tls, tidptr, tid, false);
    }

    /** continueMode=true: 以当前 emulator 上下文为子线程起点(kernel clone 语义)。 */
    public BionicThread(Emulator<?> emulator, UnidbgPointer fn, UnidbgPointer arg,
                        Pointer tls, Pointer tidptr, int tid, boolean continueMode) {
        super(tid, emulator.getReturnAddress());
        this.fn = fn;
        this.arg = arg;
        this.tls = tls;
        this.tidptr = tidptr;
        this.continueMode = continueMode;
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

    /**
     * rawContext 模式(推荐): 手工构造子线程独立初始上下文, 完整复现
     * ARM64 kernel clone 语义 ——
     *   PC  = clone wrapper 的 syscall 返回点(svc+4)
     *   SP  = child_stack(libc wrapper 已压 fn/arg, 子线程 ldp 弹出)
     *   X0  = 0(子线程 clone 返回值), TLS = CLONE_SETTLS
     */
    public static BionicThread rawContext(Emulator<?> emulator, long entryPC,
                                          UnidbgPointer childStack, Pointer tls,
                                          Pointer tidptr, int tid) {
        BionicThread t = new BionicThread(emulator, null, null, tls, tidptr, tid, false);
        t.rawEntryPC = entryPC;
        t.rawStack = childStack;
        return t;
    }

    @Override
    protected Number runThread(AbstractEmulator<?> emulator) {
        if (rawEntryPC != null) {
            Backend backend = emulator.getBackend();
            if (rawStack == null) {
                throw new IllegalStateException("rawContext 缺少 child_stack");
            }
            backend.reg_write(unicorn.Arm64Const.UC_ARM64_REG_PC, rawEntryPC);
            backend.reg_write(unicorn.Arm64Const.UC_ARM64_REG_SP, rawStack.peer);
            backend.reg_write(unicorn.Arm64Const.UC_ARM64_REG_X0, 0);
            if (tls instanceof UnidbgPointer) {
                backend.reg_write(unicorn.Arm64Const.UC_ARM64_REG_TPIDR_EL0,
                        ((UnidbgPointer) tls).peer);
            }
            backend.reg_write(unicorn.Arm64Const.UC_ARM64_REG_LR, until);
            return emulator.emulate(rawEntryPC, until);
        }
        if (continueMode) {
            // kernel clone 语义: 子线程上下文已在 handler 内 saveContext(PC=svc 后)。
            // 恢复后覆盖 X0=0(子线程 clone 返回值) —— 必须在 restore 之后,
            // 否则被保存的 tid 值覆盖(父/子身份颠倒)
            restoreContext(emulator);
            Backend backend0 = emulator.getBackend();
            backend0.reg_write(unicorn.Arm64Const.UC_ARM64_REG_X0, 0);
            return emulator.emulate(backend0.reg_read(unicorn.Arm64Const.UC_ARM64_REG_PC).longValue(), until);
        }
        Backend backend = emulator.getBackend();
        UnidbgPointer stack = allocateStack(emulator);
        // 诊断(P3): dump pthread_internal_t —— __pthread_start 读 start_routine@+0x60
        if (Boolean.getBoolean("unibase.threaddump") && arg != null) {
            byte[] head = arg.getByteArray(0, 0x80);
            StringBuilder sb = new StringBuilder("[THREADDUMP] tid=" + id + " arg=" + arg + " tls=" + tls + "\n");
            for (int off = 0; off < 0x80; off += 8) {
                long v = 0;
                for (int b = 7; b >= 0; b--) v = (v << 8) | (head[off + b] & 0xFFL);
                if (v != 0) sb.append(String.format("  +0x%02x = 0x%x%n", off, v));
            }
            System.out.print(sb);
        }
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
