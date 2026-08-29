package com.github.unidbg.arm;

import capstone.api.Instruction;
import com.github.unidbg.Emulator;
import com.github.unidbg.Module;
import com.github.unidbg.TraceHook;
import com.github.unidbg.arm.backend.Backend;
import com.github.unidbg.arm.backend.CodeHook;
import com.github.unidbg.arm.backend.UnHook;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * 快速指令追踪(阶段3 trace 优化的 Java 层落地, 看雪 290570 思路)。
 *
 * 相比 AssemblyCodeDumper 每条指令的三项开销, 本类:
 *  1. 零寄存器回读(RegAccessPrinter 每条 2+ 次 reg_read JNI 穿越 —— 大头)
 *  2. L1 指令文本缓存(地址→行, capstone 反汇编与模块查找零重复; 自修改代码
 *     由 disassemble 的机器码比对兜底 —— 缓存条目超限时整体失效防膨胀)
 *  3. 批量落盘(64KB 缓冲 + 每 8192 条 flush, 替代 System.err 逐条同步输出)
 *
 * 输出: "0xADDR [module+off] mnemonic operands"
 * 用法: FastTracer t = new FastTracer(emulator, "/tmp/trace.log");
 *       backend.hook_add_new(t, begin, end, null); ... t.stopTrace();
 */
public class FastTracer implements CodeHook, TraceHook {

    private static final int L1_LIMIT = 1 << 19;         // 52 万条上限(防自修改膨胀)
    private static final int L1_EVICT_LIMIT = L1_LIMIT * 2;
    private static final int FLUSH_EVERY = 8192;

    private final Emulator<?> emulator;
    private final long traceBegin;
    private final long traceEnd;
    private final PrintStream out;
    private final OutputStream outStream;

    private final Map<Long, String> l1 = new HashMap<>();
    private Module lastModule;
    private UnHook unHook;
    private long count;
    private boolean closed;

    public FastTracer(Emulator<?> emulator, String filePath, long begin, long end) {
        this.emulator = emulator;
        this.traceBegin = begin;
        this.traceEnd = end;
        try {
            OutputStream os = new BufferedOutputStream(new FileOutputStream(filePath), 1 << 16);
            this.outStream = os;
            this.out = new PrintStream(os, false);
        } catch (IOException e) {
            throw new IllegalStateException("FastTracer 输出文件打开失败: " + filePath, e);
        }
    }

    private boolean canTrace(long address) {
        return traceBegin > traceEnd || (address >= traceBegin && address <= traceEnd);
    }

    @Override
    public void hook(Backend backend, long address, int size, Object user) {
        if (!canTrace(address)) {
            return;
        }
        String line = l1.get(address);
        if (line == null) {
            line = format(address, size);
            if (l1.size() < L1_EVICT_LIMIT) {
                l1.put(address, line);
            } else if (l1.size() >= L1_EVICT_LIMIT) {
                l1.clear(); // 整体失效(优于逐条淘汰的成本)
                l1.put(address, line);
            }
        }
        out.println(line);
        if (++count % FLUSH_EVERY == 0) {
            out.flush();
        }
    }

    private String format(long address, int size) {
        Instruction[] insns = emulator.disassemble(address, size, 1);
        String text;
        if (insns == null || insns.length == 0) {
            text = "(invalid)";
        } else {
            String ops = insns[0].getOpStr();
            text = insns[0].getMnemonic() + (ops == null || ops.isEmpty() ? "" : " " + ops);
        }
        return "0x" + Long.toHexString(address) + "  [" + moduleAt(address) + "]  " + text;
    }

    private String moduleAt(long address) {
        if (lastModule != null && address >= lastModule.base
                && address < lastModule.base + lastModule.size) {
            return lastModule.name + "+0x" + Long.toHexString(address - lastModule.base);
        }
        Collection<Module> modules = emulator.getMemory().getLoadedModules();
        for (Module m : modules) {
            if (address >= m.base && address < m.base + m.size) {
                lastModule = m;
                return m.name + "+0x" + Long.toHexString(address - m.base);
            }
        }
        return "?";
    }

    @Override
    public void onAttach(UnHook unHook) {
        if (this.unHook != null) {
            throw new IllegalStateException();
        }
        this.unHook = unHook;
    }

    @Override
    public void detach() {
        if (unHook != null) {
            unHook.unhook();
            unHook = null;
        }
    }

    @Override
    public void stopTrace() {
        detach();
        flushAndClose();
    }

    @Override
    public void setRedirect(PrintStream redirect) {
        // FastTracer 输出构造时定向到文件, 不支持运行时重定向
    }

    public long getCount() {
        return count;
    }

    public void flushAndClose() {
        if (closed) return;
        closed = true;
        out.flush();
        if (outStream != null) {
            try { outStream.close(); } catch (IOException ignored) {}
        }
    }
}
