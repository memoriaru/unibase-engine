package unibase.ios;

import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.ios.ipa.BundleLoader;
import com.github.unidbg.ios.ipa.LoadedBundle;
import com.github.unidbg.ios.service.HostStubCollector;
import org.junit.Test;

import java.io.File;
import java.io.FileWriter;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * 阶段4 桩库 C 期验证: CatJam(第二个 iOS 样本)惰性收集闭环实测。
 *
 * 样本: 重度广告 SDK App(20+ framework: FBSDK/Firebase/InMobi/...+
 * UnityFramework 111MB), 加载链三关与 WallCrawler 相同(cryptid=0/
 * LC_DYLD_INFO_ONLY/纯 arm64, minos 12.0 sdk 17.5)。
 *
 * 闭环(C 期): 裸跑(种子表) → 收集未绑定符号 → 桩声明草案落盘 →
 * 人工审语义 → 声明表启用重放。触发条件判定: 缺口 >10 处启用全量闭环。
 * 专有样本不入库: 文件缺失时 skip, CI 空跑。
 */
public class CatJamCollectTest {

    private static final File FRAMEWORKS = new File(System.getProperty("unibase.ios.catjam.frameworks",
            "/Users/memo/Documents/admob_ios/CatJam/Payload/CatJam.app/Frameworks"));

    @Test
    public void collectUnboundStubs() throws Exception {
        File binary = new File(FRAMEWORKS, "UnityFramework.framework/UnityFramework");
        assumeTrue("样本不存在, 跳过(CI 无专有样本)", binary.isFile());

        int bare = collect(null);
        System.out.println("[CATJAM] bare(仅种子表): unbound = " + bare);

        // 触发条件判定(PLAN 决策: >10 处启用全量闭环)
        System.out.println("[CATJAM] trigger(>10): " + (bare > 10 ? "ON 全量闭环" : "OFF 维持 ad-hoc"));
        assertTrue("收集器应正常工作", bare >= 0);

        // 闭环重放: theos/sdks 全量声明表(platform 私有仓数据) → cfstring 族应一次清零
        File fullTable = new File(System.getProperty("unibase.ios.stubs",
                "/Users/memo/Projects/unibase/platform/stubs/ios/host_stub_full.tsv"));
        int full = bare;
        if (fullTable.isFile()) {
            full = collect(fullTable);
            System.out.println("[CATJAM] full(全量表重放): unbound = " + full
                    + " (清零 " + (bare - full) + " = " + (100 * (bare - full) / bare) + "%)");
            assertTrue("全量表应显著清零缺口", full < bare);
        } else {
            System.out.println("[CATJAM] full table 不存在(" + fullTable + "), 跳过重放对比");
        }

        // 闭环第三步: 样本级表(收集草案 → 人工审语义 → 启用) —— C 期完整闭环实证
        File sampleTable = new File(fullTable.getParentFile(), "catjam.tsv");
        if (sampleTable.isFile()) {
            int sample = collectWith(fullTable, sampleTable);
            System.out.println("[CATJAM] sample(全量+审核后样本表): unbound = " + sample
                    + " (较 bare 清零 " + (bare - sample) + " = " + (100 * (bare - sample) / bare) + "%)");
            assertTrue("样本级表应进一步清零", sample < full);
        }
    }

    private int collect(File stubTable) throws Exception {
        return collectWith(stubTable, null);
    }

    private int collectWith(File stubTable, File sampleTable) throws Exception {
        HostStubCollector collector = new HostStubCollector();
        BundleLoader loader = new BundleLoader(FRAMEWORKS, new File("target/rootfs/catjam-collect")) {
            @Override
            protected String getBundleIdentifier() {
                return "com.unity.framework";
            }
        };
        loader.addBackendFactory(new Unicorn2Factory(true));
        loader.setStubCollector(collector);
        if (stubTable != null) {
            loader.addStubTable(stubTable);
        }
        if (sampleTable != null) {
            loader.addStubTable(sampleTable);
        }

        long start = System.currentTimeMillis();
        LoadedBundle bundle = loader.load("UnityFramework", null);
        long elapsed = System.currentTimeMillis() - start;
        bundle.getEmulator().close();

        int unbound = collector.getUnboundCount();
        System.out.println("[CATJAM] load " + elapsed + "ms, unbound symbols = " + unbound
                + ", selector miss = " + collector.getSelectorMiss().size());

        // 桩声明草案落盘(人工审语义后并入 platform/stubs/ios/<sample>.tsv 重放)
        File report = new File(stubTable == null ? "build/catjam_stub_draft_bare.tsv"
                : sampleTable == null ? "build/catjam_stub_draft_full.tsv"
                : "build/catjam_stub_draft_sample.tsv");
        report.getParentFile().mkdirs();
        try (FileWriter out = new FileWriter(report)) {
            collector.writeReport(stubTable == null ? "CatJam(bare)"
                    : sampleTable == null ? "CatJam(full)" : "CatJam(full+sample)", out);
        }
        System.out.println("[CATJAM] stub draft -> " + report.getAbsolutePath());
        return unbound;
    }
}
