package com.github.unidbg.ios.service;

import com.github.unidbg.Emulator;
import com.github.unidbg.Module;
import com.github.unidbg.Symbol;
import com.github.unidbg.arm.Arm64Svc;
import com.github.unidbg.hook.HookListener;
import com.github.unidbg.ios.MachOLoader;
import com.github.unidbg.ios.OSVersion;
import com.github.unidbg.memory.SvcMemory;
import com.github.unidbg.pointer.UnidbgPointer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 数据驱动 iOS 宿主符号桩库(PLAN 阶段4 桩库 B 期): 常量类符号声明表 + 通用合成机制,
 * 取代逐符号手写 Stub(先例 UIKitNotificationStub/CoreGraphicsStub 已并入种子表)。
 *
 * <p>条目语义(按"调用方语义"定桩, 而非按宿主实现复刻):
 * <ul>
 *   <li>cfstring —— CFStringRef 全局常量(通知名/键名), 合成 CFConstantString
 *       (isa=___CFConstantStringClassReference, flags=0x7c8, 经 WallCrawler 实证);
 *       默认值 = 符号名去下划线(UIKit/Foundation 通知名惯例, 真值抽查走 shared cache strings)</li>
 *   <li>data —— 数据常量(CGPointZero/CGRectNull 等), 逐 8 字节写入</li>
 *   <li>func-rect / func-scalar —— 函数桩: AAPCS64 sret(x8 出参) memcpy 32B / 标量返回 0</li>
 * </ul>
 *
 * <p>内置函数条目: {@code ___isPlatformVersionAtLeast}(现代 SDK @available 门控,
 * 读 {@link OSVersion} 版本指纹 —— 指纹参数化后与 {@code 虚拟指纹能低则低}策略同一机制)。
 *
 * <p>数据边界: 本类及种子表只含符号名与互操作事实(名字/几何语义), 不含 Apple 头文件内容;
 * 全量声明表由 platform 侧从 theos/sdks 头文件脚本化生成(互操作数据, 私有仓),
 * 经 {@link #load(Reader)} 叠加 —— 后加载覆盖同名条目, 样本/manifest 按需启用。
 *
 * <p>表格式(TSV, '#' 注释; 第 4 列起为 availability 标注, 仅供人工审阅):
 * <pre>
 * _UIWindowDidBecomeKeyNotification	cfstring			UIKit, iOS 2.0+
 * _CGRectNull	data	0x7ff0000000000000,0x7ff0000000000000,0,0
 * _CGRectStandardize	func	rect
 * </pre>
 */
public class HostStubStore implements HookListener {

    private static final String VERSION_GATE = "___isPlatformVersionAtLeast";

    private final Emulator<?> emulator;
    private final Map<String, Entry> table = new LinkedHashMap<>();

    private static final class Entry {
        final String symbol;
        final Kind kind;
        final String payload; // cfstring 自定义值 / data 的 long bits 逗号表
        final String note;    // availability 等标注, 仅注释

        Entry(String symbol, Kind kind, String payload, String note) {
            this.symbol = symbol;
            this.kind = kind;
            this.payload = payload;
            this.note = note;
        }
    }

    enum Kind { CFSTRING, DATA, FUNC_RECT, FUNC_SCALAR }

    public HostStubStore(Emulator<?> emulator) {
        this.emulator = emulator;
        installSeed();
    }

    /** 实证种子表(WallCrawler GADSignals 全链路跑通的清单) —— engine 独立可用的最小集。 */
    private void installSeed() {
        // UIKit/CoreTelephony/UIScene 通知名
        for (String n : new String[]{
                "UIApplicationWillEnterForegroundNotification",
                "UIApplicationDidBecomeActiveNotification",
                "UIApplicationDidEnterBackgroundNotification",
                "UIApplicationWillResignActiveNotification",
                "UIApplicationDidChangeStatusBarOrientationNotification",
                "UIApplicationDidChangeStatusBarFrameNotification",
                "UIDeviceOrientationDidChangeNotification",
                "UIWindowDidBecomeKeyNotification",
                "UIWindowDidResignKeyNotification",
                "CTRadioAccessTechnologyDidChangeNotification",
                "UISceneWillDeactivateNotification",
                "UISceneWillEnterForegroundNotification",
                "UISceneDidEnterBackgroundNotification",
                "UISceneDidActivateNotification",
                "UISceneDidDisconnectNotification",
                "UISceneInvalidationNotification"}) {
            addCfString("_" + n, null, null);
        }
        // CoreGraphics 数据常量
        double inf = Double.POSITIVE_INFINITY, ninf = Double.NEGATIVE_INFINITY;
        addData("_CGPointZero", new long[]{0, 0}, null);
        addData("_CGSizeZero", new long[]{0, 0}, null);
        addData("_CGRectZero", new long[]{0, 0, 0, 0}, null);
        addData("_CGRectNull", new long[]{
                Double.doubleToRawLongBits(inf), Double.doubleToRawLongBits(inf), 0, 0}, null);
        addData("_CGRectInfinite", new long[]{
                Double.doubleToRawLongBits(ninf), Double.doubleToRawLongBits(ninf),
                Double.doubleToRawLongBits(inf), Double.doubleToRawLongBits(inf)}, null);
        // 注意: __objc_empty_vtable 不桩(实证 2026-08-31): 基座 libobjc 导出值 0 是
        // absolute symbol 的正确语义, 桩成零页反而让 runtime 走进废弃 vtable 路径,
        // dispatch_once 卡死(GADSignals 回归翻车); 收集器报告它是误报, 人工审勿启用
        // CoreGeometry 函数(rect->rect 传值 / 标量返回)
        for (String f : new String[]{"_CGRectStandardize", "_CGRectIntersection", "_CGRectUnion",
                "_CGRectInset", "_CGRectOffset", "_CGRectIntegral"}) {
            addFunc(f, true, null);
        }
        for (String f : new String[]{"_CGRectIsNull", "_CGRectIsEmpty", "_CGRectEqualToRect",
                "_CGRectContainsPoint", "_CGRectGetMaxX", "_CGRectGetMaxY", "_CGRectGetMidX",
                "_CGRectGetMidY", "_CGRectGetMinX", "_CGRectGetMinY", "_CGRectGetWidth",
                "_CGRectGetHeight"}) {
            addFunc(f, false, null);
        }
    }

    /** 解析并叠加一张 TSV 声明表(外部全量表/样本级表), 同名条目覆盖。 */
    public HostStubStore load(Reader reader) throws IOException {
        try (BufferedReader br = reader instanceof BufferedReader ? (BufferedReader) reader
                : new BufferedReader(reader)) {
            String line;
            int lineno = 0;
            while ((line = br.readLine()) != null) {
                lineno++;
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                String[] cols = trimmed.split("\t");
                String symbol = cols[0];
                Kind kind;
                switch (cols.length > 1 ? cols[1].trim() : "") {
                    case "cfstring":
                        kind = Kind.CFSTRING;
                        break;
                    case "data":
                        kind = Kind.DATA;
                        break;
                    case "func":
                        kind = "rect".equals(cols.length > 2 ? cols[2].trim() : "")
                                ? Kind.FUNC_RECT : Kind.FUNC_SCALAR;
                        break;
                    default:
                        throw new IOException("host stub table line " + lineno + ": unknown kind: " + line);
                }
                String payload = null;
                if (kind == Kind.CFSTRING && cols.length > 2 && !cols[2].trim().isEmpty()) {
                    payload = cols[2].trim(); // func-rect/scalar 的子类型列不构成 payload
                }
                if (kind == Kind.DATA) {
                    if (cols.length <= 2 || cols[2].trim().isEmpty()) {
                        throw new IOException("host stub table line " + lineno + ": data needs payload: " + line);
                    }
                    payload = cols[2].trim();
                }
                String note = cols.length > 3 ? cols[3].trim() : null;
                table.put(symbol, new Entry(symbol, kind, payload, note));
            }
        }
        return this;
    }

    public void addCfString(String symbol, String value, String note) {
        table.put(symbol, new Entry(symbol, Kind.CFSTRING, value, note));
    }

    public void addData(String symbol, long[] words, String note) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("0x").append(Long.toHexString(words[i]));
        }
        table.put(symbol, new Entry(symbol, Kind.DATA, sb.toString(), note));
    }

    public void addFunc(String symbol, boolean rectToRect, String note) {
        table.put(symbol, new Entry(symbol, rectToRect ? Kind.FUNC_RECT : Kind.FUNC_SCALAR, null, note));
    }

    public int size() {
        return table.size() + 1; // + version gate
    }

    @Override
    public long hook(SvcMemory svcMemory, String libraryName, String symbolName, long old) {
        if (VERSION_GATE.equals(symbolName)) {
            return versionGate(svcMemory);
        }
        Entry entry = table.get(symbolName);
        if (entry == null) {
            return 0;
        }
        return cache.computeIfAbsent(symbolName, k -> synthesize(svcMemory, entry));
    }

    private final Map<String, Long> cache = new HashMap<>();

    private long synthesize(SvcMemory svcMemory, Entry entry) {
        switch (entry.kind) {
            case CFSTRING:
                return synthesizeCfString(svcMemory, entry);
            case DATA:
                return synthesizeData(svcMemory, entry);
            case FUNC_RECT:
            case FUNC_SCALAR:
                return synthesizeFunc(svcMemory, entry);
            default:
                return 0;
        }
    }

    private long synthesizeCfString(SvcMemory svcMemory, Entry entry) {
        String value = entry.payload != null ? entry.payload
                : entry.symbol.substring(1); // 通知名惯例: 值 = 符号名去下划线
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        UnidbgPointer block = svcMemory.allocate(32 + bytes.length + 1, "HostStub:" + entry.symbol);
        Module coreFoundation = emulator.getMemory().findModule("CoreFoundation");
        if (coreFoundation == null) {
            throw new IllegalStateException("CoreFoundation NOT loaded");
        }
        Symbol clsRef = coreFoundation.findSymbolByName("___CFConstantStringClassReference", false);
        if (clsRef == null) {
            throw new IllegalStateException("___CFConstantStringClassReference NOT found");
        }
        UnidbgPointer str = (UnidbgPointer) block.share(32);
        byte[] withNul = new byte[bytes.length + 1];
        System.arraycopy(bytes, 0, withNul, 0, bytes.length);
        str.write(0, withNul, 0, withNul.length);
        block.setLong(0, clsRef.getAddress()); // isa
        block.setLong(8, 0x7c8L);              // flags: constant ASCII(实证)
        block.setLong(16, str.peer);           // data
        block.setLong(24, bytes.length);       // length
        return block.peer;
    }

    private long synthesizeData(SvcMemory svcMemory, Entry entry) {
        String[] parts = entry.payload.split(",");
        long[] words = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            words[i] = Long.decode(parts[i].trim());
        }
        UnidbgPointer block = svcMemory.allocate(words.length * 8, "HostStub:" + entry.symbol);
        for (int i = 0; i < words.length; i++) {
            block.setLong((long) i * 8, words[i]);
        }
        return block.peer;
    }

    private long synthesizeFunc(SvcMemory svcMemory, Entry entry) {
        final boolean isRect = entry.kind == Kind.FUNC_RECT;
        return svcMemory.registerSvc(new Arm64Svc(entry.symbol.substring(1)) {
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
        }).peer;
    }

    /**
     * {@code bool ___isPlatformVersionAtLeast(int major, int minor, int patch)}
     * (clang @available 门控 builtin, 参数走 w0/w1/w2)。读 MachOLoader 上的版本指纹
     * —— 版本是 manifest 参数, 指纹标低即恒 false, guest 走旧路径桩面最小。
     */
    private long versionGate(SvcMemory svcMemory) {
        return cache.computeIfAbsent(VERSION_GATE, k -> svcMemory.registerSvc(new Arm64Svc(
                "___isPlatformVersionAtLeast") {
            @Override
            public long handle(Emulator<?> e) {
                OSVersion version = ((MachOLoader) e.getMemory()).getOSVersion();
                return version.isAtLeast(e.getContext().getIntArg(0),
                        e.getContext().getIntArg(1), e.getContext().getIntArg(2)) ? 1 : 0;
            }
        }).peer);
    }
}
