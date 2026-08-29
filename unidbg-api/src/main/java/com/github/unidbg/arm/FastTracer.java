package com.github.unidbg.arm;

import capstone.api.Instruction;
import com.github.unidbg.Emulator;
import com.github.unidbg.Module;
import com.github.unidbg.TraceHook;
import com.github.unidbg.arm.backend.Backend;
import com.github.unidbg.arm.backend.CodeHook;
import com.github.unidbg.arm.backend.UnHook;
import com.github.unidbg.thread.RunnableTask;
import com.github.unidbg.thread.Task;
import com.github.unidbg.trace.BinaryTraceWriter;
import com.github.unidbg.trace.NativeTraceData;
import com.github.unidbg.trace.TraceModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
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
 * 输出模式:
 *  TEXT   "0xADDR  [module+off]  mnemonic operands"(人读/IDA 对照)
 *  BINARY UBTR v1 二进制格式(BinaryTraceWriter): 写入侧零反汇编零字符串,
 *         顺序流 run-length 压缩典型 <1B/条(vs 文本 ~117B/条); 多线程指令流
 *         自动记录 SWITCH(tid); 离线用 BinaryTraceReader 解析/统计/还原文本。
 *         已知限制: 模块字典在构造时快照, trace 期间新加载的模块不进字典。
 *  NATIVE C 层 ring buffer(uc_trace, 阶段3 ④): 每指令纯 C 回调(零 JNI 穿越),
 *         stopTrace 时 drain 解码转 UBTR —— 与 BINARY 共享全部下游。
 *         线程标记来源 = UniThreadDispatcher 切换点的 traceMarker(而非 Java
 *         回调内懒检测)。native 不可用(后端不支持/natives 缺 uc_trace 符号)
 *         时自动降级 BINARY + WARN。ring 容量默认 128MB(顺序流 ~5B/条),
 *         可用系统属性 unibase.trace.nativeRingMb 调整; 溢出停写不覆盖,
 *         drain 时 WARN 并保留已写部分。
 *
 * 用法: FastTracer t = new FastTracer(emulator, "/tmp/trace.ubt", 1, 0, Mode.BINARY);
 *       backend.hook_add_new(t, begin, end, null); ... t.stopTrace();
 *       NATIVE 模式同样可挂 hook(回调被忽略), trace 由 C 层内建 hook 记录。
 */
public class FastTracer implements CodeHook, TraceHook {

    public enum Mode { TEXT, BINARY, NATIVE }

    private static final Logger log = LoggerFactory.getLogger(FastTracer.class);

    private static final int L1_LIMIT = 1 << 19;         // 52 万条上限(防自修改膨胀)
    private static final int L1_EVICT_LIMIT = L1_LIMIT * 2;
    private static final int FLUSH_EVERY = 8192;

    // ring entry 常量(与 uc_trace.h 对齐)
    private static final int TRACE_DELTA_ESC = 0xFFFFFFFF;
    private static final int TRACE_DELTA_MARKER = 0xFFFFFFFE;

    private final Emulator<?> emulator;
    private final long traceBegin;
    private final long traceEnd;
    private final Mode mode;
    private final boolean nativeTraceActive; // NATIVE 请求且 native 侧成功开启

    // TEXT 模式
    private final PrintStream out;
    private final OutputStream outStream;
    private final Map<Long, String> l1 = new HashMap<>();
    private Module lastModule;

    // BINARY/NATIVE 模式
    private final BinaryTraceWriter writer;
    private final boolean tidTracking; // dispatcher 缺失时禁用线程标记
    private RunnableTask lastTask; // 线程切换检测(getRunningTask 为字段读)

    private UnHook unHook;
    private long count;
    private boolean closed;

    public FastTracer(Emulator<?> emulator, String filePath, long begin, long end) {
        this(emulator, filePath, begin, end, Mode.TEXT);
    }

    public FastTracer(Emulator<?> emulator, String filePath, long begin, long end, Mode mode) {
        this.emulator = emulator;
        this.traceBegin = begin;
        this.traceEnd = end;

        boolean nativeActive = false;
        if (mode == Mode.NATIVE) {
            long ringBytes = Integer.getInteger("unibase.trace.nativeRingMb", 128) * 0x100000L;
            try {
                emulator.getBackend().startNativeTrace(begin, end, ringBytes);
                nativeActive = true;
            } catch (UnsupportedOperationException | LinkageError e) {
                log.warn("NATIVE trace unavailable ({}), fallback to BINARY", e.toString());
            }
        }
        this.nativeTraceActive = nativeActive;
        this.mode = nativeActive ? mode : (mode == Mode.NATIVE ? Mode.BINARY : mode);

        if (this.mode != Mode.TEXT) {
            try {
                List<TraceModule> modules = new ArrayList<>();
                for (Module m : emulator.getMemory().getLoadedModules()) {
                    modules.add(new TraceModule(m.name, m.base, m.size));
                }
                this.writer = new BinaryTraceWriter(
                        new BufferedOutputStream(new FileOutputStream(filePath), 1 << 16), modules);
            } catch (IOException e) {
                throw new IllegalStateException("FastTracer 输出文件打开失败: " + filePath, e);
            }
            this.out = null;
            this.outStream = null;
            this.tidTracking = !nativeActive && emulator.getThreadDispatcher() != null;
        } else {
            try {
                OutputStream os = new BufferedOutputStream(new FileOutputStream(filePath), 1 << 16);
                this.outStream = os;
                this.out = new PrintStream(os, false);
            } catch (IOException e) {
                throw new IllegalStateException("FastTracer 输出文件打开失败: " + filePath, e);
            }
            this.writer = null;
            this.tidTracking = false;
        }
    }

    /** NATIVE 模式是否实际生效(未降级)。 */
    public boolean isNativeTraceActive() {
        return nativeTraceActive;
    }

    private boolean canTrace(long address) {
        return traceBegin > traceEnd || (address >= traceBegin && address <= traceEnd);
    }

    @Override
    public void hook(Backend backend, long address, int size, Object user) {
        if (nativeTraceActive || !canTrace(address)) {
            return; // NATIVE: trace 由 C 层内建 hook 记录, Java 回调忽略
        }
        if (mode == Mode.BINARY) {
            if (writer == null) { // defensive: mode 不变式被破坏
                return;
            }
            if (tidTracking) {
                RunnableTask task = emulator.getThreadDispatcher().getRunningTask();
                if (task != lastTask) {
                    writer.switchThread(task instanceof Task ? ((Task) task).getId() : 0);
                    lastTask = task;
                }
            }
            writer.append(address, size);
            count++;
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
        if (nativeTraceActive) {
            Backend backend = emulator.getBackend();
            backend.stopNativeTrace();
            try {
                decodeNativeTrace(backend.drainNativeTrace());
            } finally {
                backend.freeNativeTrace();
            }
        }
        detach();
        flushAndClose();
    }

    /**
     * C 层 ring buffer 解码 → BinaryTraceWriter(与 BINARY 模式同一 UBTR 下游)。
     * entry 格式见 uc_trace.c: delta(5B) / 逃逸(13B) / marker(12B)。
     */
    private void decodeNativeTrace(NativeTraceData data) {
        if (data == null || data.buffer == null || writer == null) {
            return;
        }
        ByteBuffer buf = data.buffer;
        buf.limit((int) Math.min(data.written, buf.capacity()));
        long lastPc = 0; // 逃逸/delta 维护的解码基点(独立于 writer 的 lastPc)
        int lastSize = 0;
        boolean haveLast = false;
        while (buf.remaining() >= 4) {
            int tag = buf.getInt();
            if (tag == TRACE_DELTA_MARKER) {
                if (buf.remaining() < 8) break;
                writer.switchThread((int) buf.getLong());
                haveLast = false; // C 侧 marker 后 have_last=false, 下一条必逃逸
            } else if (tag == TRACE_DELTA_ESC) {
                if (buf.remaining() < 9) break;
                long pc = buf.getLong();
                int size = buf.get() & 0xFF;
                writer.append(pc, size);
                lastPc = pc;
                lastSize = size;
                haveLast = true;
            } else {
                if (buf.remaining() < 1) break;
                long pc = lastPc + (tag & 0xFFFFFFFFL);
                int sizeCode = buf.get() & 0xFF;
                int size = sizeCode == 0 ? lastSize : sizeCode;
                if (!haveLast) { // defensive: C 侧保证 delta 条目前必有基点
                    break;
                }
                writer.append(pc, size);
                lastPc = pc;
                lastSize = size;
            }
        }
        count = writer.getCount();
        if (data.overflow) {
            log.warn("native trace ring overflow (capacity reached), trace truncated at {} entries", count);
        }
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
        if (mode != Mode.TEXT) { // BINARY 与 NATIVE(drain 后)共用 UBTR 收尾
            try {
                writer.finish();
                writer.close();
            } catch (IOException e) {
                throw new IllegalStateException("UBTR finish failed", e);
            }
            return;
        }
        out.flush();
        if (outStream != null) {
            try { outStream.close(); } catch (IOException ignored) {}
        }
    }
}
