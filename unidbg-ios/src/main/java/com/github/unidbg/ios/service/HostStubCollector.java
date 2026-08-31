package com.github.unidbg.ios.service;

import com.github.unidbg.Emulator;
import com.github.unidbg.arm.Arm64Svc;
import com.github.unidbg.hook.HookListener;
import com.github.unidbg.memory.SvcMemory;
import com.github.unidbg.pointer.UnidbgPointer;

import java.io.Writer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * iOS 宿主桩惰性收集器(PLAN 阶段4 桩库 C 期): 把 Android 侧
 * "FallbackJni WARN→采集→填桩"的半天接入闭环复制到 iOS。
 *
 * <p>闭环: 运行 → 收集(本类) → 生成桩声明草案(writeReport) → 人工审语义
 * → 声明表启用(HostStubStore.load) → 重放。
 *
 * <p>收集源:
 * <ol>
 *   <li><b>未绑定符号</b> —— 挂 HookListener 链尾: MachOLoader 的两条绑定路径
 *       (doBindAt 找不到符号时逐 listener 询问 / resolveSymbol 无条件询问),
 *       collector 被问到且前面无人接住(old==0 或 EACH_BIND)即记录, 返回 0 不改变行为</li>
 *   <li><b>unrecognized selector</b> —— 拦截 objc runtime 的 __objc_inform/_objc_fatal
 *       打印, 解析 "unrecognized selector sent to ..." 消息提取 class/selector</li>
 * </ol>
 * (EventMemHook fault PC 反汇编属驱动侧观测手段, 已有先例, 不在本类重复)
 *
 * <p>触发条件(PLAN 决策): 下一 iOS 样本接入缺口 &gt;10 处才启用全量闭环,
 * ≤10 处维持 ad-hoc —— {@link #getUnboundCount()} 即判定输入。
 */
public class HostStubCollector implements HookListener {

    /** 符号 → 绑定失败次数 + 首次出现所在库。 */
    private final Map<String, SymbolHit> unbound = new LinkedHashMap<>();

    /** class+selector → 命中次数(unrecognized selector)。 */
    private final Map<String, Integer> selectorMiss = new LinkedHashMap<>();

    /** objc runtime 打印原文(含 unrecognized selector 之外的运行时诊断, 供人工审)。 */
    private final List<String> objcInforms = new ArrayList<>();

    private long informSvc;

    public static final class SymbolHit {
        public final String symbol;
        public final String library;
        public int count;

        SymbolHit(String symbol, String library) {
            this.symbol = symbol;
            this.library = library;
        }
    }

    @Override
    public long hook(SvcMemory svcMemory, String libraryName, String symbolName, long old) {
        switch (symbolName) {
            case "__objc_inform":
            case "___objc_inform":
            case "_objc_fatal":
                return informSvc(svcMemory);
        }
        // 链尾观察: 被问到 = resolveSymbol 路径无条件询问(old 可为真实地址);
        // 真实地址已解析的不记录, old==0(解析失败)或负数标志(EACH_BIND 等绑定失败路径)记录
        if (old > 0) {
            return 0;
        }
        unbound.computeIfAbsent(symbolName, k -> new SymbolHit(k, libraryName)).count++;
        return 0; // 不改变行为(仍走原绑定结果)
    }

    private long informSvc(SvcMemory svcMemory) {
        if (informSvc == 0) {
            informSvc = svcMemory.registerSvc(new Arm64Svc("__objc_inform") {
                @Override
                public long handle(Emulator<?> emulator) {
                    String message = formatMessage(emulator);
                    if (message != null) {
                        synchronized (objcInforms) {
                            objcInforms.add(message);
                        }
                        Matcher m = UNRECOGNIZED_SELECTOR.matcher(message);
                        if (m.find()) {
                            String key = m.group(1) + " " + m.group(2);
                            synchronized (selectorMiss) {
                                selectorMiss.merge(key, 1, Integer::sum);
                            }
                        }
                    }
                    return 0;
                }
            }).peer;
        }
        return informSvc;
    }

    /** unrecognized selector 消息: "xxx[0x...]: -[Class selector:] unrecognized selector ..." */
    static final Pattern UNRECOGNIZED_SELECTOR =
            Pattern.compile("-\\[(\\S+) ([^\\]:]+)(?::[^\\]]*)?\\]\\s*:?.*unrecognized selector");

    /** 简易 printf(%s/%@/%p/%d/%ld): 参数自 x1 起, %@ 不解引用 objc 对象只显地址。 */
    private static String formatMessage(Emulator<?> emulator) {
        try {
            long fmtPtr = emulator.getContext().getLongArg(0);
            UnidbgPointer fmt = UnidbgPointer.pointer(emulator, fmtPtr);
            if (fmt == null) {
                return null;
            }
            String format = fmt.getString(0);
            if (format == null || format.isEmpty()) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            int argIndex = 1;
            for (int i = 0; i < format.length(); i++) {
                char c = format.charAt(i);
                if (c != '%' || i + 1 >= format.length()) {
                    sb.append(c);
                    continue;
                }
                char spec = format.charAt(++i);
                if (spec == '%') {
                    sb.append('%');
                    continue;
                }
                if (spec == 'l' && i + 1 < format.length()) { // %ld/%lu
                    spec = format.charAt(++i);
                }
                long arg = emulator.getContext().getLongArg(argIndex++);
                switch (spec) {
                    case 's': {
                        UnidbgPointer p = UnidbgPointer.pointer(emulator, arg);
                        if (p != null) {
                            try {
                                byte[] bytes = p.getByteArray(0, 256);
                                int len = 0;
                                while (len < bytes.length && bytes[len] != 0) {
                                    len++;
                                }
                                sb.append(new String(bytes, 0, len, StandardCharsets.UTF_8));
                                break;
                            } catch (Exception ignore) {
                            }
                        }
                        sb.append("(null)");
                        break;
                    }
                    case '@':
                        sb.append("@0x").append(Long.toHexString(arg));
                        break;
                    case 'p':
                        sb.append("0x").append(Long.toHexString(arg));
                        break;
                    case 'd':
                    case 'u':
                    case 'x':
                    case 'f':
                        sb.append(arg);
                        break;
                    default:
                        sb.append('%').append(spec);
                        argIndex--; // 未消费
                        break;
                }
            }
            return sb.toString();
        } catch (Exception ignore) {
            return null;
        }
    }

    public int getUnboundCount() {
        return unbound.size();
    }

    public Map<String, SymbolHit> getUnbound() {
        return unbound;
    }

    public Map<String, Integer> getSelectorMiss() {
        return selectorMiss;
    }

    public List<String> getObjcInforms() {
        return objcInforms;
    }

    /**
     * 生成桩声明草案(HostStubStore TSV 兼容): 按命名惯例给出建议 kind,
     * 把握不足的输出为注释行待人工审语义 —— 审完直接并入声明表重放。
     */
    public void writeReport(String sample, Writer out) throws IOException {
        out.write("# iOS 宿主桩声明草案(收集器生成, 人工审语义后启用)\n");
        out.write("# 样本: " + sample + "\n");
        out.write("# 未绑定符号: " + unbound.size() + " (触发阈值: >10 处启用全量闭环)\n");
        out.write("# unrecognized selector: " + selectorMiss.size() + "\n\n");
        List<SymbolHit> hits = new ArrayList<>(unbound.values());
        hits.sort(Comparator.comparingInt((SymbolHit h) -> h.count).reversed());
        for (SymbolHit hit : hits) {
            Guess guess = guessKind(hit.symbol);
            out.write(String.format("%s\t%s\t\t# count=%d lib=%s%s%n",
                    hit.symbol, guess.kind, hit.count, hit.library, guess.note));
        }
        if (!selectorMiss.isEmpty()) {
            out.write("\n# unrecognized selector(class selector count) —— 补 ObjC 方法桩或版本代差补方法:\n");
            for (Map.Entry<String, Integer> e : selectorMiss.entrySet()) {
                out.write("# objc-method\t" + e.getKey() + "\tcount=" + e.getValue() + "\n");
            }
        }
        if (!objcInforms.isEmpty()) {
            out.write("\n# objc runtime 打印(人工审):\n");
            for (String msg : objcInforms) {
                out.write("# inform: " + msg.replace('\n', ' ') + "\n");
            }
        }
    }

    private static final class Guess {
        final String kind;
        final String note;

        Guess(String kind, String note) {
            this.kind = kind;
            this.note = note.isEmpty() ? "" : " " + note;
        }
    }

    /** 命名惯例启发: 审语义的第一手建议(通知名→cfstring 等), 无把握则注释待审。 */
    private static Guess guessKind(String symbol) {
        String name = symbol.startsWith("_") ? symbol.substring(1) : symbol;
        if ("__objc_empty_vtable".equals(symbol)) {
            // 实证误报: 基座 libobjc 的 absolute symbol(值 0 正确), 桩成零页会卡死 dispatch_once
            return new Guess("# 勿桩", "(libobjc absolute symbol, 基座值 0 即正确语义)");
        }
        if (name.startsWith("OBJC_METACLASS_$_")) {
            return new Guess("# objc-class", "(元类与 _OBJC_CLASS_$_ 同名条目配套, 表里只写 CLASS 行)");
        }
        if (name.startsWith("OBJC_CLASS_$_")) {
            return new Guess("objc-class", "(降级桩: runtime 分配 NSObject 子类)");
        }
        if (name.startsWith("swift_") || name.startsWith("$s")) {
            return new Guess("# swift", "(Swift runtime 原语, 语义待审: 基座补 libswiftCore vs 原语桩)");
        }
        if (name.endsWith("Notification") || name.endsWith("Key") || name.startsWith("kCF")
                || name.startsWith("NS") || name.startsWith("UI") || name.startsWith("AV")
                || name.startsWith("CT") || name.startsWith("CM")) {
            return new Guess("cfstring", "(惯例: 字符串常量, 值默认=符号名, 真值抽查 shared cache)");
        }
        if (name.startsWith("CG")) {
            return new Guess("# func/data", "(几何函数或常量, 语义待审)");
        }
        return new Guess("# unknown", "(语义待审)");
    }
}
