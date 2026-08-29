package com.github.unidbg.trace;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * UBTR v1 二进制 trace 写入器(阶段3 ③)。
 *
 * 文件布局(小端):
 * <pre>
 * Header(12B): "UBTR" | u16 version=1 | u16 flags(bit0=含模块字典) | u32 reserved
 * ModuleDict(可选): u32 count, { u16 nameLen, name[UTF-8], u64 base, u64 size }*
 * Records: 变长记录流, tag 高 2 位选类型:
 *   00 SEQ_RUN  low6=n-1(1..64): n 条顺序执行, pc 逐条 += size, size 不变
 *   01 SIZE_RUN low6=n-1, 后随 u8 newSize: 先换 size, 再 n 条顺序执行
 *   10 JUMP     后随 zigzag varint delta, 再 u8 sizeCode:
 *                 pc += delta; sizeCode 0=size 不变, 其他=新 size, 255=后随 uvarint size
 *   11 CTRL     low6: 0=END, 1=SWITCH, 后随 uvarint tid(后续记录归属该 tid)
 * </pre>
 *
 * 记录流基点: 初始 pc=-1, size=0, 首条必为 JUMP(自带 size), 天然自定位。
 * 写入侧只做整数比较与字节追加, 无反汇编/无字符串(与 FastTracer 文本模式的
 * 核心差异), 顺序流 run-length 后典型 <1B/条(文本模式约 117B/条)。
 */
public class BinaryTraceWriter implements Closeable {

    static final byte[] MAGIC = {'U', 'B', 'T', 'R'};
    static final int VERSION = 1;
    static final int FLAG_MODULE_DICT = 1;

    private static final int KIND_SEQ = 0;
    private static final int KIND_SIZE = 1;
    private static final int KIND_JUMP = 2;
    private static final int KIND_CTRL = 3;
    private static final int CTRL_END = 0;
    private static final int CTRL_SWITCH = 1;

    private static final int RUN_MAX = 64; // low6 位 n-1 的上限

    private final OutputStream out;
    private final byte[] buf = new byte[4096];
    private int bufPos;

    private long lastPc = -1;   // 首条必然 != lastPc+lastSize → JUMP 自带 size
    private int lastSize = 0;
    private int pendingRun;     // 顺序 run 中尚未落盘的条数
    private boolean forceJump;  // SWITCH 后强制显式编码(防误并入前线程 run)
    private long count;
    private boolean finished;

    public BinaryTraceWriter(File file, List<TraceModule> modules) throws IOException {
        this(new BufferedOutputStream(new FileOutputStream(file), 1 << 16), modules);
    }

    public BinaryTraceWriter(OutputStream out, List<TraceModule> modules) {
        this.out = out;
        try {
            DataOutputStream header = new DataOutputStream(out); // header 立即透传, 不占 buf
            header.write(MAGIC);
            header.writeShort(VERSION);
            header.writeShort(modules == null || modules.isEmpty() ? 0 : FLAG_MODULE_DICT);
            header.writeInt(0);
            if (modules != null && !modules.isEmpty()) {
                header.writeInt(modules.size());
                for (TraceModule m : modules) {
                    byte[] name = m.name.getBytes("UTF-8");
                    if (name.length > 0xFFFF) {
                        throw new IOException("module name too long: " + m.name);
                    }
                    header.writeShort(name.length);
                    header.write(name);
                    header.writeLong(m.base);
                    header.writeLong(m.size);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("UBTR header/module dict write failed", e);
        }
    }

    /** 追加一条指令记录。 */
    public void append(long pc, int size) {
        if (!forceJump && pc == lastPc + lastSize) { // 顺序执行
            if (size == lastSize) {
                pendingRun++;
                if (pendingRun >= RUN_MAX) {
                    writeSeqRun();
                }
            } else {
                // thumb 等变长指令流: 顺序但 size 变化, SIZE_RUN(n=1) 落盘
                flushRun();
                writeTag(KIND_SIZE, 0);
                writeU8(size);
            }
        } else { // 跳转 / 首条 / SWITCH 后首条
            flushRun();
            writeJump(pc, size);
        }
        lastPc = pc;
        lastSize = size;
        forceJump = false;
        count++;
    }

    /** 线程切换标记(协作式调度下多线程指令交错时使用)。 */
    public void switchThread(int tid) {
        flushRun();
        writeTag(KIND_CTRL, CTRL_SWITCH);
        writeUvarint(tid);
        forceJump = true;
    }

    private void flushRun() {
        while (pendingRun > 0) {
            int n = Math.min(pendingRun, RUN_MAX);
            writeTag(KIND_SEQ, n - 1);
            pendingRun -= n;
        }
    }

    private void writeSeqRun() {
        writeTag(KIND_SEQ, pendingRun - 1);
        pendingRun = 0;
    }

    private void writeJump(long pc, int size) {
        writeTag(KIND_JUMP, 0);
        writeZigzag(pc - lastPc);
        if (size == lastSize) {
            writeU8(0);
        } else if (size >= 0 && size < 255) {
            writeU8(size);
        } else {
            writeU8(255);
            writeUvarint(size);
        }
    }

    private void writeTag(int kind, int low6) {
        ensure(1);
        buf[bufPos++] = (byte) ((kind << 6) | low6);
    }

    private void writeU8(int v) {
        ensure(1);
        buf[bufPos++] = (byte) v;
    }

    private void writeUvarint(long v) {
        ensure(10);
        while ((v & ~0x7FL) != 0) {
            buf[bufPos++] = (byte) ((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        buf[bufPos++] = (byte) v;
    }

    private void writeZigzag(long v) {
        writeUvarint((v << 1) ^ (v >> 63));
    }

    private void ensure(int bytes) {
        if (bufPos + bytes > buf.length) {
            flushBuf();
        }
    }

    private void flushBuf() {
        try {
            if (bufPos > 0) {
                out.write(buf, 0, bufPos);
                bufPos = 0;
            }
        } catch (IOException e) {
            throw new IllegalStateException("UBTR write failed", e);
        }
    }

    /** 写 END 标记并落盘。 */
    public void finish() throws IOException {
        if (finished) {
            return;
        }
        finished = true;
        flushRun();
        writeTag(KIND_CTRL, CTRL_END);
        flushBuf();
        out.flush();
    }

    @Override
    public void close() throws IOException {
        try {
            finish();
        } finally {
            out.close();
        }
    }

    public long getCount() {
        return count;
    }
}
