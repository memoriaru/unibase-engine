package com.github.unidbg.ios.service;

import com.github.unidbg.Emulator;
import com.github.unidbg.arm.Arm64Svc;
import com.github.unidbg.hook.HookListener;
import com.github.unidbg.memory.SvcMemory;
import com.github.unidbg.pointer.UnidbgPointer;

import java.util.HashMap;
import java.util.Map;

/**
 * CoreGraphics 函数桩: 基座无 CoreGraphics/UIKit 宿主, 第三方 framework 引用的
 * CGRect 系几何函数解析失败被绑 0, guest bl 0 即 fetch 崩溃(实证: GADSignals init
 * 深处 bl _CGRectStandardize)。
 *
 * 语义: CGRect 系按 AAPCS64 走 sret(x8=返回槽, x0=入参) —— 桩做 memcpy(out,in,32)
 * 忠实于"规范化非负矩形=原样"的常见情形; 读失败退化为 CGRectZero。
 */
public class CoreGraphicsStub implements HookListener {

    private static final Map<String, Boolean> FUNCTIONS = new HashMap<>();

    static {
        // value: true = rect->rect(memcpy), false = 标量返回(直接回 0)
        FUNCTIONS.put("_CGRectStandardize", true);
        FUNCTIONS.put("_CGRectIntersection", true);
        FUNCTIONS.put("_CGRectUnion", true);
        FUNCTIONS.put("_CGRectInset", true);
        FUNCTIONS.put("_CGRectOffset", true);
        FUNCTIONS.put("_CGRectIntegral", true);
        FUNCTIONS.put("_CGRectIsNull", false);
        FUNCTIONS.put("_CGRectIsEmpty", false);
        FUNCTIONS.put("_CGRectEqualToRect", false);
        FUNCTIONS.put("_CGRectContainsPoint", false);
        FUNCTIONS.put("_CGRectGetMaxX", false);
        FUNCTIONS.put("_CGRectGetMaxY", false);
        FUNCTIONS.put("_CGRectGetMidX", false);
        FUNCTIONS.put("_CGRectGetMidY", false);
        FUNCTIONS.put("_CGRectGetMinX", false);
        FUNCTIONS.put("_CGRectGetMinY", false);
        FUNCTIONS.put("_CGRectGetWidth", false);
        FUNCTIONS.put("_CGRectGetHeight", false);
    }

    private final Emulator<?> emulator;
    private final Map<String, Long> cache = new HashMap<>();
    private final Map<String, long[]> dataConstants = new HashMap<>();

    public CoreGraphicsStub(Emulator<?> emulator) {
        this.emulator = emulator;
        double inf = Double.POSITIVE_INFINITY;
        double ninf = Double.NEGATIVE_INFINITY;
        // CGPoint/CGSize = 2 double; CGRect = 4 double
        dataConstants.put("_CGPointZero", new long[]{0, 0});
        dataConstants.put("_CGSizeZero", new long[]{0, 0});
        dataConstants.put("_CGRectZero", new long[]{0, 0, 0, 0});
        dataConstants.put("_CGRectNull", new long[]{
                Double.doubleToRawLongBits(inf), Double.doubleToRawLongBits(inf), 0, 0});
        dataConstants.put("_CGRectInfinite", new long[]{
                Double.doubleToRawLongBits(ninf), Double.doubleToRawLongBits(ninf),
                Double.doubleToRawLongBits(inf), Double.doubleToRawLongBits(inf)});
    }

    @Override
    public long hook(SvcMemory svcMemory, String libraryName, String symbolName, long old) {
        long[] data = dataConstants.get(symbolName);
        if (data != null) {
            return cache.computeIfAbsent(symbolName, k -> {
                UnidbgPointer block = svcMemory.allocate(data.length * 8, "CGData:" + symbolName);
                for (int i = 0; i < data.length; i++) {
                    block.setLong((long) i * 8, data[i]);
                }
                return block.peer;
            });
        }
        Boolean isRect = FUNCTIONS.get(symbolName);
        if (isRect == null) {
            return 0;
        }
        return cache.computeIfAbsent(symbolName, k -> svcMemory.registerSvc(
                new Arm64Svc(symbolName.substring(1)) {
                    @Override
                    public long handle(Emulator<?> e) {
                        if (isRect) {
                            long out = e.getContext().getLongByReg(unicorn.Arm64Const.UC_ARM64_REG_X8);
                            long in = e.getContext().getLongArg(0);
                            UnidbgPointer outP = UnidbgPointer.pointer(e, out);
                            if (outP != null) {
                                UnidbgPointer inP = UnidbgPointer.pointer(e, in);
                                if (inP != null) {
                                    try {
                                        outP.write(0, inP.getByteArray(0, 32), 0, 32);
                                        return out;
                                    } catch (Exception ignore) {
                                    }
                                }
                                outP.write(0, new byte[32], 0, 32); // CGRectZero
                                return out;
                            }
                        }
                        return 0;
                    }
                }).peer);
    }
}
