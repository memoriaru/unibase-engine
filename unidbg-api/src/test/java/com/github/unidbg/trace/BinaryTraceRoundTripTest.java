package com.github.unidbg.trace;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * UBTR v1 二进制 trace 格式 roundtrip 测试(阶段3 ③)。
 *
 * 写入侧(BinaryTraceWriter)与解析侧(BinaryTraceReader)共用一份语义:
 * 每条记录 = (tid, pc, size), 顺序流 run-length 压缩, 跳转 zigzag delta。
 */
public class BinaryTraceRoundTripTest {

    private static final class Rec {
        final int tid;
        final long pc;
        final int size;
        Rec(int tid, long pc, int size) { this.tid = tid; this.pc = pc; this.size = size; }
    }

    private ByteArrayOutputStream out;
    private BinaryTraceWriter writer;

    @Before
    public void setUp() {
        out = new ByteArrayOutputStream();
    }

    @After
    public void tearDown() {
        if (writer != null) {
            try { writer.close(); } catch (IOException ignored) {}
        }
    }

    private BinaryTraceWriter writer(TraceModule... modules) {
        writer = new BinaryTraceWriter(out, Arrays.asList(modules));
        return writer;
    }

    private List<Rec> roundtrip(BinaryTraceWriter w) throws IOException {
        w.finish();
        BinaryTraceReader reader = new BinaryTraceReader(
                new ByteArrayInputStream(out.toByteArray()));
        final List<Rec> recs = new ArrayList<>();
        reader.forEach((tid, pc, size) -> recs.add(new Rec(tid, pc, size)));
        assertEquals("记录总数与 writer 计数一致", w.getCount(), recs.size());
        return recs;
    }

    private static void assertRecs(List<Rec> actual, Rec... expected) {
        assertEquals(expected.length, actual.size());
        for (int i = 0; i < expected.length; i++) {
            Rec e = expected[i], a = actual.get(i);
            assertEquals("record[" + i + "].pc", e.pc, a.pc);
            assertEquals("record[" + i + "].size", e.size, a.size);
            assertEquals("record[" + i + "].tid", e.tid, a.tid);
        }
    }

    private static Rec r(long pc) { return new Rec(0, pc, 4); }
    private static Rec r(long pc, int size) { return new Rec(0, pc, size); }

    @Test
    public void testSequentialArm64() throws IOException {
        BinaryTraceWriter w = writer();
        for (long pc = 0x10000L; pc < 0x10000L + 4L * 10; pc += 4) {
            w.append(pc, 4);
        }
        List<Rec> recs = roundtrip(w);
        Rec[] expected = new Rec[10];
        for (int i = 0; i < 10; i++) expected[i] = r(0x10000L + 4L * i);
        assertRecs(recs, expected);
    }

    @Test
    public void testSequentialOverRunBoundary() throws IOException {
        // 130 条连续顺序指令, 跨越 SEQ_RUN 的 64 条 run 边界
        BinaryTraceWriter w = writer();
        List<Rec> expected = new ArrayList<>();
        long pc = 0x400000L;
        for (int i = 0; i < 130; i++) {
            w.append(pc, 4);
            expected.add(r(pc));
            pc += 4;
        }
        assertRecs(roundtrip(w), expected.toArray(new Rec[0]));
    }

    @Test
    public void testJumpBackwardAndForward() throws IOException {
        BinaryTraceWriter w = writer();
        w.append(0x1000L, 4);          // 首条, JUMP + size
        w.append(0x1004L, 4);          // 顺序
        w.append(0x2000L, 4);          // 前跳 +0xFC8
        w.append(0x2004L, 4);          // 顺序
        w.append(0x0F00L, 4);          // 回跳 -0x1108
        assertRecs(roundtrip(w), r(0x1000), r(0x1004), r(0x2000), r(0x2004), r(0x0F00));
    }

    @Test
    public void testThumbSizeInterleave() throws IOException {
        // thumb 流: 2/4 字节指令交错, 顺序执行但 size 变化
        BinaryTraceWriter w = writer();
        w.append(0x8000L, 2);
        w.append(0x8002L, 4);
        w.append(0x8006L, 2);
        w.append(0x8008L, 2);
        w.append(0x800aL, 4);
        assertRecs(roundtrip(w), r(0x8000, 2), r(0x8002, 4), r(0x8006, 2), r(0x8008, 2), r(0x800a, 4));
    }

    @Test
    public void testThreadSwitch() throws IOException {
        BinaryTraceWriter w = writer();
        w.append(0x1000L, 4);
        w.append(0x1004L, 4);
        w.switchThread(7);
        w.append(0x9000L, 4);          // 切线程后首条, 必显式编码
        w.append(0x9004L, 4);
        w.switchThread(0);
        w.append(0x1008L, 4);
        assertRecs(roundtrip(w),
                new Rec(0, 0x1000, 4), new Rec(0, 0x1004, 4),
                new Rec(7, 0x9000, 4), new Rec(7, 0x9004, 4),
                new Rec(0, 0x1008, 4));
    }

    @Test
    public void testSwitchThenSequentialSamePc() throws IOException {
        // 切线程后即使 pc 恰好连续, 也不得并入前一线程的顺序 run
        BinaryTraceWriter w = writer();
        w.append(0x1000L, 4);
        w.switchThread(3);
        w.append(0x1004L, 4);
        assertRecs(roundtrip(w), new Rec(0, 0x1000, 4), new Rec(3, 0x1004, 4));
    }

    @Test
    public void testModuleDictRoundTrip() throws IOException {
        TraceModule m1 = new TraceModule("libc.so", 0x7f000000000L, 0x100000);
        TraceModule m2 = new TraceModule("libtest.dylib", 0, 0x200000);
        BinaryTraceWriter w = writer(m1, m2);
        w.append(0x1000L, 4);
        w.finish();
        BinaryTraceReader reader = new BinaryTraceReader(new ByteArrayInputStream(out.toByteArray()));
        List<TraceModule> modules = reader.getModules();
        assertEquals(2, modules.size());
        assertEquals("libc.so", modules.get(0).name);
        assertEquals(0x7f000000000L, modules.get(0).base);
        assertEquals(0x100000, modules.get(0).size);
        assertEquals("libtest.dylib", modules.get(1).name);
        assertEquals(0, modules.get(1).base);
        assertEquals(0x200000, modules.get(1).size);
    }

    @Test
    public void testReaderModuleAt() throws IOException {
        TraceModule m = new TraceModule("libc.so", 0x1000L, 0x100);
        BinaryTraceWriter w = writer(m);
        w.append(0x1040L, 4);
        w.finish();
        BinaryTraceReader reader = new BinaryTraceReader(new ByteArrayInputStream(out.toByteArray()));
        assertEquals("libc.so+0x40", reader.moduleAt(0x1040L));
        assertEquals("libc.so+0xff", reader.moduleAt(0x10ffL));
        assertNull(reader.moduleAt(0x1100L));
        assertNull(reader.moduleAt(0xfffL));
    }

    @Test
    public void testHeaderMagicAndVersion() throws IOException {
        BinaryTraceWriter w = writer();
        w.append(0L, 4);
        w.finish();
        BinaryTraceReader reader = new BinaryTraceReader(new ByteArrayInputStream(out.toByteArray()));
        assertEquals(BinaryTraceReader.MAGIC, reader.getMagic());
        assertEquals(1, reader.getVersion());
        assertFalse(reader.hasModuleDict());
    }

    @Test
    public void testEmptyStream() throws IOException {
        BinaryTraceWriter w = writer();
        assertRecs(roundtrip(w));
    }

    @Test(expected = IOException.class)
    public void testRejectBadMagic() throws IOException {
        byte[] bad = new byte[]{0x4E, 0x4F, 0x50, 0x45, 1, 0, 0, 0, 0, 0, 0, 0};
        new BinaryTraceReader(new ByteArrayInputStream(bad)).forEach((t, p, s) -> {});
    }

    @Test
    public void testLargeJumpDelta() throws IOException {
        // 64 位地址空间两端的跳转, zigzag delta 不得溢出丢精度
        BinaryTraceWriter w = writer();
        w.append(0x10L, 4);
        w.append(0x7fff_ffff_f000L, 4);
        w.append(0x8L, 4);
        assertRecs(roundtrip(w), r(0x10), r(0x7fff_ffff_f000L), r(0x8));
    }
}
