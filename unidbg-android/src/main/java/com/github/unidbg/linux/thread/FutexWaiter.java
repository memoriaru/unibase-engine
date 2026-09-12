package com.github.unidbg.linux.thread;

import com.github.unidbg.Emulator;
import com.sun.jna.Pointer;
import unicorn.Arm64Const;
import unicorn.ArmConst;

public abstract class FutexWaiter extends AndroidWaiter {

    private final Pointer uaddr;
    private final int val;

    public FutexWaiter(Pointer uaddr, int val) {
        this.uaddr = uaddr;
        this.val = val;
    }

    /**
     * unibase 实验(X-Argus 研究): 强制翻转等待谓词 —— 写 val+1 使 canDispatch
     * 为真, 唤醒等待者走一步; 它完成一步后会以新期望重新 futex_wait(握手链)。
     */
    public void kick() {
        uaddr.setInt(0, val + 1);
    }

    @Override
    public boolean canDispatch() {
        if (wokenUp) {
            return true;
        }
        int old = uaddr.getInt(0);
        return old != val;
    }

    @Override
    public final void onContinueRun(Emulator<?> emulator) {
        super.onContinueRun(emulator);

        if (wokenUp) {
            emulator.getBackend().reg_write(emulator.is32Bit() ? ArmConst.UC_ARM_REG_R0 : Arm64Const.UC_ARM64_REG_X0, 0);
        } else {
            onContinueRunInternal(emulator);
        }
    }

    protected void onContinueRunInternal(Emulator<?> emulator) {
    }

    protected boolean wokenUp;

    @Override
    public String toString() {
        return "futex@" + uaddr + " expect!=" + val + " now=" +
                (uaddr == null ? "?" : uaddr.getInt(0)) + (wokenUp ? " woken" : "");
    }

    public boolean wakeUp(Pointer uaddr) {
        if (this.uaddr.equals(uaddr)) {
            this.wokenUp = true;
            return true;
        } else {
            return false;
        }
    }

}
