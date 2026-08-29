package com.github.unidbg.trace;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;

/**
 * UBTR v1 二进制 trace 解析器(纯 Java, 见 BinaryTraceWriter 的格式说明)。
 *
 * 用法:
 *   BinaryTraceReader r = new BinaryTraceReader(file);
 *   r.forEach((tid, pc, size) -> ...)   // 流式回调, 千万级记录不装箱
 *   r.moduleAt(pc)                       // "libc.so+0x1234" / null
 */
public class BinaryTraceReader implements Closeable {

    public static final String MAGIC = "UBTR";

    /** 每条记录的消费回调: (tid, pc, size)。 */
    public interface RecordConsumer {
        void accept(int tid, long pc, int size);
    }

    private final DataInput in;
    private final int version;
    private final boolean moduleDict;
    private final List<TraceModule> modules;

    public BinaryTraceReader(InputStream in) throws IOException {
        this.in = new DataInputStream(in instanceof BufferedInputStream
                ? in : new BufferedInputStream(in, 1 << 16));
        byte[] magic = new byte[4];
        try {
            ((DataInputStream) this.in).readFully(magic);
        } catch (EOFException e) {
            throw new IOException("UBTR 文件过短(无 header)");
        }
        if (!new String(magic, "UTF-8").equals(MAGIC)) {
            throw new IOException("非 UBTR 文件: magic=" + new String(magic));
        }
        this.version = this.in.readUnsignedShort();
        int flags = this.in.readUnsignedShort();
        this.in.readInt(); // reserved
        this.moduleDict = (flags & BinaryTraceWriter.FLAG_MODULE_DICT) != 0;
        if (version != 1) {
            throw new IOException("不支持的 UBTR 版本: " + version);
        }
        if (moduleDict) {
            int count = this.in.readInt();
            List<TraceModule> list = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int nameLen = this.in.readUnsignedShort();
                byte[] name = new byte[nameLen];
                ((DataInputStream) this.in).readFully(name);
                list.add(new TraceModule(new String(name, "UTF-8"),
                        this.in.readLong(), this.in.readLong()));
            }
            this.modules = Collections.unmodifiableList(list);
        } else {
            this.modules = Collections.emptyList();
        }
    }

    public String getMagic() { return MAGIC; }
    public int getVersion() { return version; }
    public boolean hasModuleDict() { return moduleDict; }
    public List<TraceModule> getModules() { return modules; }

    /** 流式消费全部记录直到 END。 */
    public void forEach(RecordConsumer consumer) throws IOException {
        long pc = -1;
        int size = 0;
        int tid = 0;
        boolean end = false;
        while (!end) {
            int tag = readU8();
            int kind = (tag >>> 6) & 3;
            int aux = tag & 0x3F;
            switch (kind) {
                case 0: { // SEQ_RUN
                    int n = aux + 1;
                    for (int i = 0; i < n; i++) {
                        pc += size;
                        consumer.accept(tid, pc, size);
                    }
                    break;
                }
                case 1: { // SIZE_RUN: 第一条用旧 size 推进, size 即刻换新值
                    int newSize = readU8();
                    int n = aux + 1;
                    for (int i = 0; i < n; i++) {
                        pc += (i == 0) ? size : newSize;
                        size = newSize;
                        consumer.accept(tid, pc, size);
                    }
                    break;
                }
                case 2: { // JUMP
                    pc += readZigzag();
                    int sizeCode = readU8();
                    if (sizeCode == 255) {
                        size = (int) readUvarint();
                    } else if (sizeCode != 0) {
                        size = sizeCode;
                    }
                    consumer.accept(tid, pc, size);
                    break;
                }
                case 3: // CTRL
                    if (aux == 0) {
                        end = true;
                    } else if (aux == 1) {
                        tid = (int) readUvarint();
                    } else {
                        throw new IOException("未知 CTRL 记录: aux=" + aux);
                    }
                    break;
                default:
                    throw new IOException("不可达记录类型: " + kind);
            }
        }
    }

    /** 模块归属标注 "name+0xoff", 不在任何模块内返回 null。 */
    public String moduleAt(long pc) {
        for (TraceModule m : modules) {
            if (pc >= m.base && pc < m.base + m.size) {
                return m.name + "+0x" + Long.toHexString(pc - m.base);
            }
        }
        return null;
    }

    /** 还原人读文本: "tid  pc  [module+off]  size"(无反汇编, 离线可用)。 */
    public void dumpText(PrintStream out) throws IOException {
        forEach((tid, pc, size) -> {
            String mod = moduleAt(pc);
            out.println("tid=" + tid + "  0x" + Long.toHexString(pc)
                    + "  [" + (mod == null ? "?" : mod) + "]  size=" + size);
        });
        out.flush();
    }

    /** 单遍统计: 总条数/跳转数(含线程切换后首条)/tid 与模块分布。 */
    public Stats stats() throws IOException {
        Stats s = new Stats(modules);
        forEach(s);
        return s;
    }

    /** 流式消费的统计收集器(也可独立用于任意 pc 流)。 */
    public static class Stats implements RecordConsumer {
        public long total;
        public long jumps; // pc 不连续(或 tid 切换)的条数 ≈ 控制流转移数
        public final Map<Integer, Long> perTid = new HashMap<>();
        public final Map<String, Long> perModule = new HashMap<>();
        private long prevPc = -2;
        private int prevSize = -1;
        private int prevTid = 0;

        @Override
        public void accept(int tid, long pc, int size) {
            total++;
            if (tid != prevTid || pc != prevPc + prevSize) {
                jumps++;
            }
            perTid.merge(tid, 1L, Long::sum);
            TraceModule m = findModule(pc);
            perModule.merge(m == null ? "?" : m.name, 1L, Long::sum);
            prevPc = pc;
            prevSize = size;
            prevTid = tid;
        }

        private TraceModule findModule(long pc) {
            for (TraceModule m : moduleLookup) {
                if (pc >= m.base && pc < m.base + m.size) return m;
            }
            return null;
        }

        private final List<TraceModule> moduleLookup;
        public Stats(List<TraceModule> modules) { this.moduleLookup = modules; }
        public Stats() { this.moduleLookup = Collections.emptyList(); }
    }

    /** 命令行: java ... BinaryTraceReader trace.ubt [--text|--stats]。 */
    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("用法: BinaryTraceReader <trace.ubt> [--text|--stats]");
            System.exit(2);
        }
        try (BinaryTraceReader reader = new BinaryTraceReader(new java.io.FileInputStream(args[0]))) {
            boolean text = false, stats = false;
            for (int i = 1; i < args.length; i++) {
                if ("--text".equals(args[i])) text = true;
                else if ("--stats".equals(args[i])) stats = true;
            }
            System.err.println("UBTR v" + reader.getVersion()
                    + ", modules=" + reader.getModules().size());
            if (text) {
                reader.dumpText(System.out);
            }
            if (stats || !text) {
                Stats s = new Stats(reader.getModules());
                reader.forEach(s);
                System.out.println("total=" + s.total + " jumps=" + s.jumps);
                System.out.println("per-module:");
                s.perModule.entrySet().stream()
                        .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                        .forEach(e -> System.out.println("  " + e.getKey() + ": " + e.getValue()));
                if (s.perTid.size() > 1) {
                    System.out.println("per-tid: " + s.perTid);
                }
            }
        }
    }

    private int readU8() throws IOException {
        int v = in.readByte() & 0xFF;
        return v;
    }

    private long readUvarint() throws IOException {
        long result = 0;
        int shift = 0;
        while (true) {
            int b = in.readByte() & 0xFF;
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
            shift += 7;
            if (shift >= 64) {
                throw new IOException("uvarint 过长(>64bit)");
            }
        }
    }

    private long readZigzag() throws IOException {
        long v = readUvarint();
        return (v >>> 1) ^ -(v & 1);
    }

    @Override
    public void close() throws IOException {
        if (in instanceof Closeable) {
            ((Closeable) in).close();
        }
    }
}
