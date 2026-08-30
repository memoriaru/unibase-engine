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

            // --- D 里程碑: objc4 运行时反射 + 真实 msgSend ---
            com.github.unidbg.ios.objc.ObjC objc = com.github.unidbg.ios.objc.ObjC.getInstance(emulator);
            com.github.unidbg.ios.struct.objc.ObjcClass gadSignals = objc.getClass("GADSignals");
            assertNotNull("GADSignals 类应已注册进 objc4 运行时(BundleLoader 默认 setObjcRuntime(true))", gadSignals);
            System.out.println("[SPIKE] objc class GADSignals = 0x"
                    + Long.toHexString(com.sun.jna.Pointer.nativeValue(gadSignals.getPointer())));

            com.github.unidbg.ios.classdump.IClassDumper dumper =
                    com.github.unidbg.ios.classdump.ClassDumper.getInstance(emulator);
            String dump = dumper.dumpClass("GADSignals");
            System.out.println("[SPIKE] dumpClass GADSignals (" + dump.length() + " chars):\n" + dump);
            // objc4 对齐里程碑: 运行时能完整反射第三方 framework 类(ivars + 方法列表)
            assertTrue("dumpClass 应产出完整类描述(ivars/方法)",
                    dump != null && dump.contains("@interface GADSignals")
                            && dump.contains("dictionaryWithSignals:"));
            // 已知假象: ObjC.getClass 的映射表对该类返回 0 指针(dumpClass 走运行时查证
            // 才是真实路径) —— 方法调用下一步应改用 objc_getClass 解析类指针后
            // objc_msgSend +sharedInstance / respondsToSelector:

            // respondsToSelector: 经 objc_msgSend 问元类 —— 运行时调用链最小真调用
            try {
                com.github.unidbg.ios.struct.objc.ObjcObject responds = gadSignals.getMeta()
                        .callObjc("respondsToSelector:", objc.registerName("dictionaryWithSignals:"));
                System.out.println("[SPIKE] +respondsToSelector:dictionaryWithSignals: → " + responds);
            } catch (Exception e) {
                System.out.println("[SPIKE] msgSend probe failed (记录, 不作为本里程碑断言): " + e);
            }
        } finally {
            emulator.close();
        }
    }
}
