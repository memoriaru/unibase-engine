package com.github.unidbg.ios.service;

import org.junit.Test;

import java.io.StringWriter;
import java.util.regex.Matcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** C 期收集器单测(CI 可跑): 链尾观察语义 + 报告生成。 */
public class HostStubCollectorTest {

    @Test
    public void tailObserverSemantics() {
        HostStubCollector collector = new HostStubCollector();
        // 绑定失败路径(EACH_BIND)与解析失败(old=0): 记录且不改变行为
        assertEquals(0, collector.hook(null, "UnityFramework", "_MissingNotification", HostStubCollector.EACH_BIND));
        assertEquals(0, collector.hook(null, "UnityFramework", "_MissingNotification", 0));
        assertEquals(1, collector.getUnboundCount());
        assertEquals(2, collector.getUnbound().get("_MissingNotification").count);
        // 真实符号已解析(old>0): 不记录
        assertEquals(0, collector.hook(null, "UnityFramework", "_RealSymbol", 0x1000L));
        assertEquals(1, collector.getUnboundCount());
    }

    @Test
    public void unrecognizedSelectorPattern() {
        Matcher m = HostStubCollector.UNRECOGNIZED_SELECTOR.matcher(
                "objc[1234]: -[GADSignals someMethod:]: unrecognized selector sent to instance 0x1234");
        assertTrue(m.find());
        assertEquals("GADSignals", m.group(1));
        assertEquals("someMethod", m.group(2));

        m = HostStubCollector.UNRECOGNIZED_SELECTOR.matcher(
                "*** -[NSProcessInfo isiOSAppOnMac]: unrecognized selector sent to instance 0x7f8");
        assertTrue(m.find());
        assertEquals("NSProcessInfo", m.group(1));
        assertEquals("isiOSAppOnMac", m.group(2));

        // 非目标消息不匹配
        assertTrue(!HostStubCollector.UNRECOGNIZED_SELECTOR.matcher(
                "objc[1]: some other message").find());
    }

    @Test
    public void reportDraftFormat() throws Exception {
        HostStubCollector collector = new HostStubCollector();
        collector.hook(null, "UnityFramework", "_UILoudNotification", HostStubCollector.EACH_BIND);
        collector.hook(null, "UnityFramework", "_UILoudNotification", HostStubCollector.EACH_BIND);
        collector.hook(null, "UnityFramework", "_CGMysteryFunc", HostStubCollector.EACH_BIND);
        StringWriter out = new StringWriter();
        collector.writeReport("test-sample", out);
        String report = out.toString();
        assertTrue(report.contains("# iOS 宿主桩声明草案"));
        assertTrue(report.contains("_UILoudNotification\tcfstring"));  // 惯例命中 → 激活建议
        assertTrue(report.contains("count=2"));
        assertTrue(report.contains("_CGMysteryFunc\t# func/data"));   // 无把握 → 注释待审
        assertTrue(report.contains("触发阈值"));
    }
}
