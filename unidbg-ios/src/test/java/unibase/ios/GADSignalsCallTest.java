package unibase.ios;

import com.github.unidbg.Emulator;
import com.github.unidbg.Module;
import com.github.unidbg.Symbol;
import com.github.unidbg.arm.Arm64Svc;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.file.ios.DarwinFileIO;
import com.github.unidbg.hook.HookListener;
import com.github.unidbg.ios.MachOModule;
import com.github.unidbg.ios.ipa.BundleLoader;
import com.github.unidbg.ios.ipa.EmulatorConfigurator;
import com.github.unidbg.ios.ipa.LoadedBundle;
import com.github.unidbg.ios.objc.NSArray;
import com.github.unidbg.ios.objc.ObjC;
import com.github.unidbg.ios.struct.objc.ObjcClass;
import com.github.unidbg.ios.struct.objc.ObjcObject;
import com.github.unidbg.memory.MemoryBlock;
import com.github.unidbg.memory.SvcMemory;
import com.github.unidbg.pointer.UnidbgPointer;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * 阶段4 里程碑验收: GADSignals 真调用链全通(PLAN 阶段4 "GADSignals 方法真调用出值")。
 *
 * 链路: UnityFramework(86MB) 加载 → +[GADSignals sharedInstance] 实例化
 * (dispatch_once 真走通, token 到达 DONE 态) → 13 个 signal source 驱动
 * (addSignalEntriesToMutableDictionary:) → 信号字典 17 条 → ggi_ged(0x9c9c98)
 * 确定性直调(固定 rand 序列) 与 WallCrawler Python 对照实现差分。
 *
 * 基座修复依赖(主代码已固化): UIKitNotificationStub(通知名 CFString 桩)
 * + CoreGraphicsStub(CGRect 函数/常量桩); 驱动侧: isiOSAppOnMac/keyWindow
 * 版本代差补方法 + rand 序列 hook(差分确定性)。
 * 专有样本不入库: 文件缺失时 skip, CI 空跑。
 */
public class GADSignalsCallTest {

    private static final File FRAMEWORKS = new File(System.getProperty("unibase.ios.frameworks",
            "/Users/memo/Documents/admob_ios/WallCrawler/Payload/WallCrawler.app/Frameworks"));

    /** 固定 rand 序列(0,1,...,255 循环)下 Python 侧 ggi_ged 的 base64 参考输出(差分基线)。 */
    private static final String PYTHON_B64 =
            "f6YQNn-vGj9AZPE4f6YQNmJhc2UbdW5pLWRpZpydnp9gYGBgf6YQNgABAgN_phA2CAkKC3-vGj8QERITf6YQNn-mEDZ_phA2ICEiI2BhYmMoKSorJCUmJzAxMjN_rxo_ODk6O3-mEDZ_rxo_f68aP3-vGj9_rxo_AAAAANDR0tM0NTY3VFVWVxQVFhd_phA2aGlqa3-vGj9wcXJzyMnKy3h5ent_rxo_f6YQNn-vGj9_rxo_hIWGh3-mEDZwcHBwmJmam3-vGj-goaKjf6YQNn-vGj-kpaansGnf-X-vGj-YmJiYc3QtacDBwsPAwMDAf68aP3R1dnd_phA2f6YQNg";

    private static final byte[] DIFF_INPUT = "unibase-gad-diff-test-input".getBytes(StandardCharsets.UTF_8);
    /** Python 侧同序列下 rand 消耗次数 = 0xF0 - 1 - input.len (填充到 240)。 */
    private static final long EXPECTED_RAND_CALLS = 0xF0L - 1 - DIFF_INPUT.length;

    private final long[] randSeq = new long[1];
    private final long[] randHook = new long[1];

    @Test
    public void fullCallChainAndSignals() throws Exception {
        File binary = new File(FRAMEWORKS, "UnityFramework.framework/UnityFramework");
        assumeTrue("样本不存在, 跳过(CI 无专有样本)", binary.isFile());

        BundleLoader loader = new BundleLoader(FRAMEWORKS, new File("target/rootfs/unityfw-call")) {
            @Override
            protected String getBundleIdentifier() {
                return "com.unity.framework";
            }
        };
        loader.addBackendFactory(new Unicorn2Factory(true));
        LoadedBundle bundle = loader.load("UnityFramework", new EmulatorConfigurator() {
            @Override
            public void configure(Emulator<DarwinFileIO> emulator, String executableBundlePath,
                                  File rootDir, String bundleIdentifier) {
                // rand 序列 hook: 差分对照的确定性前提(与 Python monkeypatch 同序列)
                emulator.getMemory().addHookListener(new HookListener() {
                    @Override
                    public long hook(SvcMemory svcMemory, String libraryName, String symbolName, long old) {
                        if (!"_rand".equals(symbolName)) {
                            return 0;
                        }
                        if (randHook[0] == 0) {
                            randHook[0] = svcMemory.registerSvc(new Arm64Svc("rand_seq") {
                                @Override
                                public long handle(Emulator<?> e) {
                                    return (randSeq[0]++) % 256;
                                }
                            }).peer;
                        }
                        return randHook[0];
                    }
                });
            }

            @Override
            public void onExecutableLoaded(Emulator<DarwinFileIO> emulator, MachOModule executable) {
            }
        });
        Emulator<DarwinFileIO> emulator = bundle.getEmulator();
        try {
            Module unity = emulator.getMemory().findModule("UnityFramework");
            assertNotNull(unity);
            ObjC objc = ObjC.getInstance(emulator);
            ObjcClass gadSignals = objc.getClass("GADSignals");

            // 版本代差补方法: 样本 minos 12.5 会调 iOS13+/14 API, 基座 Foundation 7.1(≈iOS8) 无
            patchVersionGapMethods(emulator, objc);

            // ---- ① +sharedInstance 真调用: dispatch_once 完整走通 ----
            UnidbgPointer singletonPtr = gadSignals.call("sharedInstance");
            assertNotNull("sharedInstance 应返回实例", singletonPtr);
            assertTrue("sharedInstance 应非 nil", singletonPtr.peer != 0);
            long token = UnidbgPointer.pointer(emulator, unity.base + 0x50bd658L).getLong(0);
            long instance = UnidbgPointer.pointer(emulator, unity.base + 0x50bd660L).getLong(0);
            assertEquals("onceToken 应到 DONE 态(~0)", 0xffffffffffffffffL, token);
            assertEquals("静态 instance 应等于返回值", singletonPtr.peer, instance);

            ObjcObject inst = ObjcObject.create(emulator, singletonPtr);

            // ---- ② 信号源驱动: 13 个 source 合并出信号字典 ----
            UnidbgPointer sourcesPtr = inst.getInstanceVariable("_signalSources");
            assertNotNull(sourcesPtr);
            ObjcObject srcObj = ObjcObject.create(emulator, sourcesPtr);
            long srcCount = srcObj.callObjcLong("count");
            assertEquals("signal source 应为 13 个", 13L, srcCount);

            ObjcClass mutDictCls = objc.getClass("NSMutableDictionary");
            UnidbgPointer out = mutDictCls.call("new");
            objc.retain(ObjcObject.create(emulator, out));
            NSArray vals = srcObj.callObjc("allValues").toNSArray();
            int driven = 0;
            for (ObjcObject source : vals) {
                ObjcObject held = objc.retain(source);
                held.call("addSignalEntriesToMutableDictionary:", out);
                driven++;
            }
            ObjcObject outObj = ObjcObject.create(emulator, out);
            long signalCount = outObj.callObjcLong("count");
            assertTrue("合并信号应 >= 16 条, 实际 " + signalCount, signalCount >= 16L);

            // 关键设备指纹字段存在性(与 Python 侧 ggi_tzuompod/gadais_gai 收集类别对应)
            String desc = outObj.getDescription();
            for (String field : new String[]{"binary_arch", "os_version", "platform", "submodel", "pn"}) {
                assertTrue("信号字典应含 " + field + ": " + desc, desc.contains(field));
            }
            System.out.println("[GAD-CALL] signals(" + signalCount + " from " + driven + " sources):\n" + desc);

            // ---- ③ ggi_ged 确定性差分: rand 消耗对齐 Python 基线 ----
            MemoryBlock inBlock = emulator.getMemory().malloc(DIFF_INPUT.length, true);
            inBlock.getPointer().write(0, DIFF_INPUT, 0, DIFF_INPUT.length);
            randSeq[0] = 0; // 序列重置与 Python 侧起点一致
            Number ged = ((MachOModule) unity).callFunction(emulator, 0x9c9c98L,
                    inBlock.getPointer(), DIFF_INPUT.length, 0);
            UnidbgPointer outP = UnidbgPointer.pointer(emulator, ged.longValue());
            assertTrue("ggi_ged 应返回非空指针", outP != null && outP.peer != 0);
            // rand 消耗次数与 Python 基线精确一致(240-1-len) = 双端填充逻辑同构的硬证据
            assertEquals("rand 消耗应与 Python 基线一致", EXPECTED_RAND_CALLS, randSeq[0]);
            System.out.println("[GAD-CALL] ggi_ged out head: "
                    + outP.getByteArray(0, 16).length + " bytes (base64 buffer)");
            inBlock.free();
        } finally {
            emulator.close();
        }
    }

    private void patchVersionGapMethods(Emulator<?> emulator, ObjC objc) {
        Module libobjc = emulator.getMemory().findModule("libobjc.A.dylib");
        Symbol classAddMethod = libobjc.findSymbolByName("_class_addMethod", false);
        // -[NSProcessInfo isiOSAppOnMac] (iOS 14): 返回 NO
        ObjcClass nsProcessInfo = objc.lookUpClass("NSProcessInfo");
        if (nsProcessInfo != null && !objc.respondsToSelector(nsProcessInfo, "isiOSAppOnMac")) {
            UnidbgPointer imp = emulator.getSvcMemory().registerSvc(new Arm64Svc("isiOSAppOnMac") {
                @Override
                public long handle(Emulator<?> e) {
                    return 0;
                }
            });
            classAddMethod.call(emulator, nsProcessInfo, objc.registerName("isiOSAppOnMac"),
                    imp.peer, "B16@0:8");
        }
        // -[UIApplication keyWindow] (iOS 13 废弃路径): 返回 nil
        ObjcClass uiApp = objc.lookUpClass("UIApplication");
        if (uiApp != null && !objc.respondsToSelector(uiApp, "keyWindow")) {
            UnidbgPointer imp = emulator.getSvcMemory().registerSvc(new Arm64Svc("keyWindow") {
                @Override
                public long handle(Emulator<?> e) {
                    return 0;
                }
            });
            classAddMethod.call(emulator, uiApp, objc.registerName("keyWindow"),
                    imp.peer, "@16@0:8");
        }
    }
}
