package com.github.unidbg.ios.file;

import com.github.unidbg.Emulator;
import com.github.unidbg.arm.backend.Backend;
import com.github.unidbg.file.ios.IOConstants;
import com.sun.jna.Pointer;

import java.util.concurrent.ThreadLocalRandom;

public class RandomFileIO extends DriverFileIO {

    private final Emulator<?> emulator;

    public RandomFileIO(Emulator<?> emulator, String path) {
        super(emulator, IOConstants.O_RDONLY, path);
        this.emulator = emulator;
    }

    @Override
    public int read(Backend backend, Pointer buffer, int count) {
        int total = 0;
        byte[] buf = new byte[Math.min(0x1000, count)];
        randBytes(buf);
        Pointer pointer = buffer;
        while (total < count) {
            int read = Math.min(buf.length, count - total);
            pointer.write(0, buf, 0, read);
            total += read;
            pointer = pointer.share(read);
        }
        return total;
    }

    protected void randBytes(byte[] bytes) {
        // 时间冻结时走确定性熵源(unibase A3), 否则真随机
        if (emulator.getSyscallHandler() instanceof com.github.unidbg.unix.UnixSyscallHandler) {
            ((com.github.unidbg.unix.UnixSyscallHandler<?>) emulator.getSyscallHandler()).nextGuestRandomBytes(bytes);
        } else {
            ThreadLocalRandom.current().nextBytes(bytes);
        }
    }

}
