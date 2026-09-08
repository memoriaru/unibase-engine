package com.github.unidbg.arm.backend;

import com.github.unidbg.Emulator;
import com.github.unidbg.arm.backend.dynarmic.Dynarmic;
import com.github.unidbg.arm.backend.dynarmic.DynarmicBackend32;
import com.github.unidbg.arm.backend.dynarmic.DynarmicBackend64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DynarmicFactory extends BackendFactory {

    private static final Logger log = LoggerFactory.getLogger(DynarmicFactory.class);

    // native 装载失败必须记旗标而非吞掉: dlopen 失败抛 UnsatisfiedLinkError(Error,
    // 非 IOException), 穿透 static 块会把整个类毒死(后续全 NoClassDefFoundError),
    // fallbackUnicorn 回退链失去执行机会 —— 实证 macos-latest(arm64) 装载上游
    // Xcode26 重建的 osx_arm64 libdynarmic 失败, CI 双后端矩阵全红(2026-09-08)。
    private static volatile boolean nativeLoadFailed;

    static {
        try {
            org.scijava.nativelib.NativeLoader.loadLibrary("dynarmic");
        } catch (Throwable t) {
            nativeLoadFailed = true;
            log.warn("dynarmic native library unavailable on this platform ({}), "
                    + "DynarmicFactory defers to fallback/next factory", t.toString());
        }
    }

    public DynarmicFactory(boolean fallbackUnicorn) {
        super(fallbackUnicorn);
    }

    @Override
    protected Backend newBackendInternal(Emulator<?> emulator, boolean is64Bit) {
        if (nativeLoadFailed) {
            // 交父类 newBackend 的 catch(Throwable): fallbackUnicorn 时返回 null,
            // 由 createBackend 的下一工厂(通常 Unicorn2Factory)接管
            throw new UnsupportedOperationException("dynarmic native library not loaded");
        }
        Dynarmic dynarmic = new Dynarmic(is64Bit);
        return is64Bit ? new DynarmicBackend64(emulator, dynarmic) : new DynarmicBackend32(emulator, dynarmic);
    }

}
