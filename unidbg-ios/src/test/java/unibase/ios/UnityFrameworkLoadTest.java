package unibase.ios;

import com.github.unidbg.Emulator;
import com.github.unidbg.Module;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.file.ios.DarwinFileIO;
import com.github.unidbg.ios.MachOModule;
import com.github.unidbg.ios.ipa.BundleLoader;
import com.github.unidbg.ios.ipa.EmulatorConfigurator;
import com.github.unidbg.ios.ipa.LoadedBundle;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * 阶段4 ① 加载链 spike(PLAN 后续开发事项3)。
 *
 * 样本 = WallCrawler 的 UnityFramework(86MB, 加载链三关全无:
 * cryptid=0 / LC_DYLD_INFO_ONLY(无 chained fixups) / 纯 arm64(无 PAC), minos 12.5)。
 *
 * 验收口径 = BundleLoader/MachOLoader 完成加载(rebase 应用) + 导出符号可解析。
 * 不执行任何 guest 函数(GADGestures 调用属阶段4 正式范围)。
 * 专有样本不入库: 文件缺失时 skip, CI 空跑。
 */
public class UnityFrameworkLoadTest {

    private static final File FRAMEWORKS = new File(System.getProperty("unibase.ios.frameworks",
            "/Users/memo/Documents/admob_ios/WallCrawler/Payload/WallCrawler.app/Frameworks"));

    @Test
    public void loadAndResolveSymbols() throws Exception {
        File binary = new File(FRAMEWORKS, "UnityFramework.framework/UnityFramework");
        assumeTrue("样本不存在, 跳过(CI 无专有样本)", binary.isFile());

        BundleLoader loader = new BundleLoader(FRAMEWORKS, new File("target/rootfs/unityfw")) {
            @Override
            protected String getBundleIdentifier() {
                return "com.unity.framework";
            }
        };
        loader.addBackendFactory(new Unicorn2Factory(true));
        long begin = System.currentTimeMillis();
        LoadedBundle bundle = loader.load("UnityFramework", new EmulatorConfigurator() {
            @Override
            public void configure(Emulator<DarwinFileIO> emulator, String executableBundlePath,
                                  File rootDir, String bundleIdentifier) {
            }

            @Override
            public void onExecutableLoaded(Emulator<DarwinFileIO> emulator, MachOModule executable) {
            }
        });
        long cost = System.currentTimeMillis() - begin;
        Emulator<DarwinFileIO> emulator = bundle.getEmulator();
        try {
            Module module = emulator.getMemory().findModule("UnityFramework");
            assertNotNull("UnityFramework 模块未加载", module);
            System.out.printf("[SPIKE] loaded in %dms: base=0x%x size=0x%x%n",
                    cost, module.base, module.size);

            // 符号解析: ObjC 类符号(exports/objc_classlist 走通, 不执行 guest 代码)
            String[] probes = {
                    "_OBJC_CLASS_$_GADSignals", // AdMob 信号生成类(strings 实证存在)
                    "_OBJC_METACLASS_$_GADSignals",
            };
            int resolved = 0;
            for (String probe : probes) {
                try {
                    if (module.findSymbolByName(probe, false) != null) {
                        resolved++;
                        System.out.println("[SPIKE] resolved " + probe);
                    }
                } catch (Exception e) {
                    System.out.println("[SPIKE] probe " + probe + " failed: " + e.getMessage());
                }
            }
            System.out.println("[SPIKE] symbol probes resolved: " + resolved + "/" + probes.length);
            assertTrue("ObjC 类符号应可解析(样本含 GADSignals, 见 strings 勘察)", resolved >= 1);
        } finally {
            emulator.close();
        }
    }
}
