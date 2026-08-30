package unibase.ios;

import com.github.unidbg.Emulator;
import com.github.unidbg.Module;
import com.github.unidbg.arm.backend.CodeHook;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.file.ios.DarwinFileIO;
import com.github.unidbg.ios.MachOModule;
import com.github.unidbg.ios.ipa.BundleLoader;
import com.github.unidbg.ios.ipa.EmulatorConfigurator;
import com.github.unidbg.ios.ipa.LoadedBundle;
import com.github.unidbg.ios.objc.ObjC;
import com.github.unidbg.ios.struct.objc.ObjcClass;
import com.github.unidbg.pointer.UnidbgPointer;
import com.sun.jna.Pointer;
import io.kaitai.MachO;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * 阶段4 调试驱动: dirty selector(双注册表)根因定位。
 *
 * 已确认机制链:
 * - +[GADSignals sharedInstance] 真执行(类对象作 receiver), init 链健康推进,
 *   深处某探测调用用未注册 selector 指针(如 libswiftCore __TEXT 里的字符串副本)
 *   调 msgSend → 方法表指针比较 miss → unrecognized → NSException → terminate → abort
 * - code hook 必须在加载前挂(TB 缓存对后挂 hook 失明)
 * 专有样本不入库: 文件缺失时 skip, CI 空跑。
 */
public class GADSignalsDebugTest {

    private static final File FRAMEWORKS = new File(System.getProperty("unibase.ios.frameworks",
            "/Users/memo/Documents/admob_ios/WallCrawler/Payload/WallCrawler.app/Frameworks"));

    @Test
    public void traceSharedInstanceAbort() throws Exception {
        File binary = new File(FRAMEWORKS, "UnityFramework.framework/UnityFramework");
        assumeTrue("样本不存在, 跳过(CI 无专有样本)", binary.isFile());

        final long[] lookUpImpOrForward = new long[1]; // libobjc.base + 0xeae0, 惰性解析
        final long[] randHook = new long[1];
        final long[] probeThrow = new long[1];
        final long[] probeObjcThrow = new long[1];
        final long[] probeAbortMsg = new long[1];
        final String[] ring = new String[256];
        final long[] ringIdx = new long[1];
        final Emulator<?>[] emuRef = new Emulator<?>[1];

        BundleLoader loader = new BundleLoader(FRAMEWORKS, new File("target/rootfs/unityfw-dbg")) {
            @Override
            protected String getBundleIdentifier() {
                return "com.unity.framework";
            }
        };
        loader.addBackendFactory(new Unicorn2Factory(true));
        // rand 序列 hook(确定性对照): _rand 返回 0,1,2,...,255 循环, 与 Python 侧 monkeypatch 一致
        final long[] randSeq = new long[1];

        LoadedBundle bundle = loader.load("UnityFramework", new EmulatorConfigurator() {
            @Override
            public void configure(Emulator<DarwinFileIO> emulator, String executableBundlePath,
                                  File rootDir, String bundleIdentifier) {
                emuRef[0] = emulator;
                emulator.getMemory().addHookListener(new com.github.unidbg.hook.HookListener() {
                    @Override
                    public long hook(com.github.unidbg.memory.SvcMemory svcMemory, String libraryName, String symbolName, long old) {
                        if (!"_rand".equals(symbolName)) {
                            return 0;
                        }
                        if (randHook[0] == 0) {
                            randHook[0] = svcMemory.registerSvc(new com.github.unidbg.arm.Arm64Svc("rand_seq") {
                                @Override
                                public long handle(Emulator<?> e) {
                                    return (randSeq[0]++) % 256;
                                }
                            }).peer;
                        }
                        return randHook[0];
                    }
                });
                emulator.getBackend().hook_add_new(new CodeHook() {
                    @Override
                    public void hook(com.github.unidbg.arm.backend.Backend backend, long address, int size, Object user) {
                        // 异常/终止链观察点(全部纯内存读, 禁止 guest 重入)
                        if (probeThrow[0] == 0) {
                            Module abi = emulator.getMemory().findModule("libc++abi.dylib");
                            Module objcM = emulator.getMemory().findModule("libobjc.A.dylib");
                            if (abi != null) {
                                probeThrow[0] = abi.base + 0x1aa70L; // ___cxa_throw (nm 实证)
                                probeAbortMsg[0] = abi.base + 0x93c;  // _abort_message (nm 实证)
                            }
                            if (objcM != null) {
                                com.github.unidbg.Symbol s = objcM.findSymbolByName("_objc_exception_throw", false);
                                if (s != null) {
                                    probeObjcThrow[0] = s.getAddress();
                                }
                            }
                        }
                        boolean isThrow = probeThrow[0] != 0 && address == probeThrow[0]
                                || probeObjcThrow[0] != 0 && address == probeObjcThrow[0]
                                || probeAbortMsg[0] != 0 && address == probeAbortMsg[0]
                                || address == 0xfffe23c4L; /* abort svc 跳板(实测) */
                        if (isThrow) {
                            com.github.unidbg.arm.context.RegisterContext ctx = emulator.getContext();
                            long lr = ctx.getLR();
                            Module m = emulator.getMemory().findModuleByAddress(lr);
                            String what = probeThrow[0] != 0 && address == probeThrow[0] ? "__cxa_throw"
                                    : probeObjcThrow[0] != 0 && address == probeObjcThrow[0] ? "objc_exception_throw"
                                    : probeAbortMsg[0] != 0 && address == probeAbortMsg[0] ? "abort_message"
                                    : "ABORT-svc";
                            String extra = "";
                            if (probeAbortMsg[0] != 0 && address == probeAbortMsg[0]) {
                                UnidbgPointer fmt = UnidbgPointer.pointer(emulator, ctx.getLongArg(0));
                                if (fmt != null) {
                                    try {
                                        extra = " fmt=" + fmt.getString(0);
                                    } catch (Exception ignore) {
                                    }
                                }
                            }
                            System.out.printf("[%s] LR=%s%s%n", what,
                                    m == null ? "0x" + Long.toHexString(lr)
                                            : m.name + "+0x" + Long.toHexString(lr - m.base),
                                    extra);
                        }
                        if (lookUpImpOrForward[0] == 0) {
                            Module libobjc = emulator.getMemory().findModule("libobjc.A.dylib");
                            if (libobjc == null) {
                                return;
                            }
                            lookUpImpOrForward[0] = libobjc.base + 0xeae0L;
                        }
                        if (address != lookUpImpOrForward[0]) {
                            return;
                        }
                        // 纯内存观察(禁止 guest 重入): ring buffer 记最近查询
                        com.github.unidbg.arm.context.RegisterContext ctx = emulator.getContext();
                        long sel = ctx.getLongArg(1);
                        long lr = ctx.getLR();
                        String sname = null;
                        UnidbgPointer sp = UnidbgPointer.pointer(emulator, sel);
                        if (sp != null) {
                            try {
                                sname = sp.getString(0);
                            } catch (Exception ignore) {
                            }
                        }
                        ring[(int) (ringIdx[0] % ring.length)] = String.format("sel=0x%x('%s') cls=0x%x LR=0x%x",
                                sel, sname, ctx.getLongArg(0), lr);
                        ringIdx[0]++;
                    }

                    @Override
                    public void onAttach(com.github.unidbg.arm.backend.UnHook unHook) {
                    }

                    @Override
                    public void detach() {
                    }
                }, 1L, 0x120000000L, null);
                // 未映射读崩溃现场(事件驱动, 不受 TB 缓存影响)
                emulator.getBackend().hook_add_new(new com.github.unidbg.arm.backend.EventMemHook() {
                    @Override
                    public boolean hook(com.github.unidbg.arm.backend.Backend backend, long address, int size,
                                        long value, Object user, com.github.unidbg.arm.backend.EventMemHook.UnmappedType unmappedType) {
                        long pc = emulator.getContext().getLongByReg(unicorn.Arm64Const.UC_ARM64_REG_PC);
                        Module m = emulator.getMemory().findModuleByAddress(pc);
                        System.out.printf("[FAULT] %s addr=0x%x size=%d PC=%s LR=0x%x%n",
                                unmappedType, address, size,
                                m == null ? "0x" + Long.toHexString(pc)
                                        : m.name + "+0x" + Long.toHexString(pc - m.base),
                                emulator.getContext().getLR());
                        return false; // 不处理, 保持原语义(结束任务)
                    }

                    @Override
                    public void onAttach(com.github.unidbg.arm.backend.UnHook unHook) {
                    }

                    @Override
                    public void detach() {
                    }
                }, unicorn.UnicornConst.UC_HOOK_MEM_READ_UNMAPPED
                        | unicorn.UnicornConst.UC_HOOK_MEM_FETCH_UNMAPPED, null);
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

            // selref fixup(原型): 模拟 objc4 read_images 的 selref 注册回写
            int modulesFixed = 0, refsFixed = 0;
            java.util.List<Module> mods = new java.util.ArrayList<>(emulator.getMemory().getLoadedModules());
            for (Module mod : mods) {
                if (!(mod instanceof MachOModule)) {
                    continue;
                }
                MachO.SegmentCommand64.Section64 sec =
                        ((MachOModule) mod).objcSections.get("__objc_selrefs");
                if (sec == null) {
                    continue;
                }
                UnidbgPointer base = UnidbgPointer.pointer(emulator, mod.base + sec.addr());
                if (base == null) {
                    continue;
                }
                // 快照读全部项(guest 调用可能 lazy 加载新模块, 不在遍历中改列表)
                int count = (int) (sec.size() / 8);
                String[] names = new String[count];
                for (int i = 0; i < count; i++) {
                    UnidbgPointer cur = base.getPointer((long) i * 8);
                    if (cur == null) {
                        continue;
                    }
                    try {
                        names[i] = cur.getString(0);
                    } catch (Exception e) {
                        names[i] = null;
                    }
                }
                modulesFixed++;
                for (int i = 0; i < count; i++) {
                    String name = names[i];
                    if (name == null || name.isEmpty()) {
                        continue;
                    }
                    Pointer canonical = objc.registerName(name);
                    UnidbgPointer cur = base.getPointer((long) i * 8);
                    if (cur != null && UnidbgPointer.nativeValue(canonical) != cur.peer) {
                        base.setPointer((long) i * 8, canonical);
                        refsFixed++;
                    }
                }
            }
            System.out.printf("[DBG] selref fixup: %d modules, %d refs rewritten%n",
                    modulesFixed, refsFixed);


            // CF 常量字符串结构实证: 从 Foundation 的已绑定通知常量抄 isa/flags
            Module cf = emulator.getMemory().findModule("CoreFoundation");
            com.github.unidbg.Symbol clsRef = cf.findSymbolByName("___CFConstantStringClassReference", false);
            System.out.println("[CF] ___CFConstantStringClassReference = " + clsRef);
            // Unity 自己的 cfstring 条目(0xac88bc 引用 Unity+0x480dad8): rebase 后结构完整
            UnidbgPointer cfs = UnidbgPointer.pointer(emulator, unity.base + 0x480dad8L);
            System.out.printf("[CF] Unity cfstring @0x480dad8: isa=0x%x flags=0x%x data=0x%x len=%d str=%s%n",
                    cfs.getLong(0), cfs.getLong(8), cfs.getLong(16), cfs.getInt(24),
                    UnidbgPointer.pointer(emulator, cfs.getLong(16)).getString(0));
            UnidbgPointer clsRefAddr = UnidbgPointer.pointer(emulator, clsRef.getAddress());
            System.out.printf("[CF] ___CFConstantStringClassReference addr=0x%x value=0x%x%n",
                    clsRef.getAddress(), clsRefAddr.getLong(0));


            // 补 NSProcessInfo isiOSAppOnMac(iOS 14 API, Foundation 7.1 无, 样本 minos 12.5 会调)
            {
                ObjcClass nsProcessInfo2 = objc.lookUpClass("NSProcessInfo");
                com.github.unidbg.Symbol classAddMethod = libobjc2(emulator).findSymbolByName("_class_addMethod", false);
                final UnidbgPointer imp = emulator.getSvcMemory().registerSvc(new com.github.unidbg.arm.Arm64Svc("isiOSAppOnMac") {
                    @Override
                    public long handle(Emulator<?> e) {
                        return 0; // NO
                    }
                });
                Number r = classAddMethod.call(emulator, nsProcessInfo2,
                        objc.registerName("isiOSAppOnMac"), imp.peer, "B16@0:8");
                System.out.println("[PATCH] class_addMethod isiOSAppOnMac -> " + r);
                // -[UIApplication keyWindow]: iOS 13 废弃路径, 基座 Foundation 无
                ObjcClass uiApp = objc.lookUpClass("UIApplication");
                if (uiApp != null && !objc.respondsToSelector(uiApp, "keyWindow")) {
                    UnidbgPointer imp2 = emulator.getSvcMemory().registerSvc(new com.github.unidbg.arm.Arm64Svc("keyWindow") {
                        @Override
                        public long handle(Emulator<?> e) {
                            return 0; // nil
                        }
                    });
                    Number r2 = classAddMethod.call(emulator, uiApp,
                            objc.registerName("keyWindow"), imp2.peer, "@16@0:8");
                    System.out.println("[PATCH] class_addMethod keyWindow -> " + r2);
                }
            }

            System.out.println("[DBG] >>> calling +sharedInstance with CLASS as receiver ...");
            dumpSingletonStatics(emulator, unity, "before");
            UnidbgPointer singletonPtr = gadSignals.call("sharedInstance");
            dumpSingletonStatics(emulator, unity, "after ");
            System.out.println("[DBG] sharedInstance raw = 0x"
                    + (singletonPtr == null ? 0 : singletonPtr.peer));
            if (singletonPtr != null && singletonPtr.peer != 0) {
                // dictionaryWithSignals: 内部两个关键调用的运行时 GOT 值
            for (long[] gotOff : new long[][]{{0x4d024c8}, {0x4cf8c98}}) {
                UnidbgPointer g = UnidbgPointer.pointer(emulator, unity.base + gotOff[0]);
                long v = g.getLong(0);
                Module m = emulator.getMemory().findModuleByAddress(v);
                System.out.printf("[GOT] 0x%x -> 0x%x (%s)%n", gotOff[0], v,
                        m == null ? (v == 0 ? "NULL" : "svc/unmapped") : m.name + "+0x" + Long.toHexString(v - m.base));
                UnidbgPointer tgt = UnidbgPointer.pointer(emulator, v);
                if (tgt != null && v != 0) {
                    System.out.printf("[GOT] target bytes: %08x %08x %08x%n",
                            tgt.getInt(0), tgt.getInt(4), tgt.getInt(8));
                }
            }
            // Foundation+0x1e33d8 (0x3af8ec0 stub 的运行时目标) 的字节 + stub 自身字节
            Module fnd = emulator.getMemory().findModule("Foundation");
            UnidbgPointer f33 = UnidbgPointer.pointer(emulator, fnd.base + 0x1e33d8L);
            System.out.printf("[GOT] Foundation+0x1e33d8 bytes: %08x %08x %08x%n",
                    f33.getInt(0), f33.getInt(4), f33.getInt(8));
            // selrefs 区间与两个 GOT 的归属
            MachO.SegmentCommand64.Section64 selrefs =
                    ((MachOModule) unity).objcSections.get("__objc_selrefs");
            if (selrefs != null) {
                System.out.printf("[SEC] Unity __objc_selrefs: 0x%x ~ 0x%x (size 0x%x)%n",
                        selrefs.addr(), selrefs.addr() + selrefs.size(), selrefs.size());
                for (long got : new long[]{0x4d024c8L, 0x4cf8c98L}) {
                    boolean in = got >= selrefs.addr() && got < selrefs.addr() + selrefs.size();
                    UnidbgPointer gp = UnidbgPointer.pointer(emulator, unity.base + got);
                    long val = gp.getLong(0);
                    String str = null;
                    UnidbgPointer vp = UnidbgPointer.pointer(emulator, val);
                    if (vp != null) {
                        try { str = vp.getString(0); } catch (Exception ignore) {}
                    }
                    Pointer canon = str == null ? null : objc.registerName(str);
                    System.out.printf("[SEC] GOT 0x%x inSelrefs=%b value=0x%x str='%s' canonical=0x%x%n",
                            got, in, val, str, canon == null ? -1 : UnidbgPointer.nativeValue(canon));
                }
            }
            for (long stubOff : new long[]{0x3af8ec0L, 0x3ae0b80L}) {
                UnidbgPointer stubP = UnidbgPointer.pointer(emulator, unity.base + stubOff);
                System.out.printf("[STUB] Unity+0x%x: %08x %08x %08x%n", stubOff,
                        stubP.getInt(0), stubP.getInt(4), stubP.getInt(8));
            }
            // dump 实例状态: _signalSources / _mainQueueSignalUpdateBlocks
            final com.github.unidbg.ios.struct.objc.ObjcObject inst;
            if (singletonPtr != null && singletonPtr.peer != 0) {
                inst = com.github.unidbg.ios.struct.objc.ObjcObject.create(emulator, singletonPtr);
                UnidbgPointer sources = inst.getInstanceVariable("_signalSources");
                System.out.println("[IVAR] _signalSources = " + sources);
                if (sources != null && sources.peer != 0) {
                    com.github.unidbg.ios.struct.objc.ObjcObject src =
                            com.github.unidbg.ios.struct.objc.ObjcObject.create(emulator, sources);
                    System.out.println("[IVAR] _signalSources count = " + src.callObjcLong("count"));
                    System.out.println("[IVAR] _signalSources keys:");
                    com.github.unidbg.ios.objc.NSArray ks = src.callObjc("allKeys").toNSArray();
                    for (com.github.unidbg.ios.struct.objc.ObjcObject k : ks) {
                        System.out.println("  [SRC] " + k.getDescription());
                    }
                }
                UnidbgPointer blocks = inst.getInstanceVariable("_mainQueueSignalUpdateBlocks");
                System.out.println("[IVAR] _mainQueueSignalUpdateBlocks = " + blocks);
            } else {
                inst = null;
            }
            System.out.println("[DBG] >>> calling -dictionaryWithSignals: ...");
                // 实例方法 receiver = sharedInstance 实例
                // dictionaryWithSignals: 参数 = 要收集的信号键数组(fast enumeration 遍历它)
                UnidbgPointer sourcesPtr = inst.getInstanceVariable("_signalSources");
                com.github.unidbg.ios.struct.objc.ObjcObject srcObj =
                        com.github.unidbg.ios.struct.objc.ObjcObject.create(emulator, sourcesPtr);
                UnidbgPointer allKeys = srcObj.call("allKeys");
                // retain: call 返回的是 autoreleased 对象, 跨调用传递必须持有
                allKeys = (UnidbgPointer) objc.retain(com.github.unidbg.ios.struct.objc.ObjcObject.create(emulator, allKeys)).getPointer();
                System.out.println("[DBG] passing allKeys(retained) = 0x" + allKeys.peer);
            // 实证: NSArray fast enumeration 是否工作(与 dictionaryWithSignals: 内部同路径)
            {
                com.github.unidbg.memory.MemoryBlock stateBlock = emulator.getMemory().malloc(64, true);
                com.github.unidbg.memory.MemoryBlock bufBlock = emulator.getMemory().malloc(16 * 8, true);
                UnidbgPointer fe = com.github.unidbg.ios.struct.objc.ObjcObject
                        .create(emulator, allKeys)
                        .call("countByEnumeratingWithState:objects:count:",
                                stateBlock.getPointer(), bufBlock.getPointer(), 16);
                System.out.printf("[FE] countByEnumerating... first batch = %s%n", fe);
                stateBlock.free(); bufBlock.free();
            }
                // 方案A: dictionaryWithSignals: 外壳(返回 autoreleased 字典)
                UnidbgPointer dict = inst.call("dictionaryWithSignals:", allKeys.peer);
                System.out.println("[DBG] dictionaryWithSignals: = 0x"
                        + (dict == null ? 0 : dict.peer));
                // 方案B: 直接驱动 signal sources(核心语义, 输出字典自持有)
                {
                    ObjcClass mutDictCls = objc.getClass("NSMutableDictionary");
                    UnidbgPointer out = mutDictCls.call("new");
                    objc.retain(com.github.unidbg.ios.struct.objc.ObjcObject.create(emulator, out));
                    com.github.unidbg.ios.objc.NSArray vals = srcObj.callObjc("allValues").toNSArray();
                    int i = 0;
                    for (com.github.unidbg.ios.struct.objc.ObjcObject source : vals) {
                        com.github.unidbg.ios.struct.objc.ObjcObject held =
                                objc.retain(source);
                        try {
                            held.call("addSignalEntriesToMutableDictionary:", out);
                            System.out.println("[SRC-DUMP] source[" + (i++) + "] " + held.getDescription());
                        } catch (Throwable t) {
                            System.out.println("[SRC-DUMP] source[" + (i++) + "] failed: "
                                    + String.valueOf(t.getMessage()).split("\n")[0]);
                        }
                    }
                    com.github.unidbg.ios.struct.objc.ObjcObject outObj =
                            com.github.unidbg.ios.struct.objc.ObjcObject.create(emulator, out);
                    System.out.println("[OUT] merged dict count = " + outObj.callObjcLong("count"));
                    System.out.println("[OUT] merged dict = " + outObj.getDescription());
                }
                if (dict != null && dict.peer != 0) {
                    // NSDictionary description -> NSString(用 unidbg 桥读)
                    com.github.unidbg.ios.struct.objc.ObjcObject dictObj =
                            com.github.unidbg.ios.struct.objc.ObjcObject.create(emulator, dict);
                    System.out.println("[DBG] dict description:");
                    System.out.println(dictObj.getDescription());
                    // count 与 allKeys 结构化读取
                    long cnt = dictObj.callObjcLong("count");
                    System.out.println("[DBG] dict count = " + cnt);
                    com.github.unidbg.ios.objc.NSArray keys = dictObj.callObjc("allKeys").toNSArray();
                    int shown = 0;
                    for (com.github.unidbg.ios.struct.objc.ObjcObject k : keys) {
                        if (shown++ >= 20) break;
                        com.github.unidbg.ios.struct.objc.ObjcObject v =
                                dictObj.callObjc("objectForKey:", k.getPointer());
                        String vs;
                        try {
                            vs = v == null ? "nil" : v.getDescription();
                        } catch (Exception e) {
                            vs = "(desc failed)";
                        }
                        System.out.printf("[SIG] %s = %s%n",
                                k == null ? "?" : k.getDescription(), vs);
                    }
                }
            }
            // ============ 确定性差分对照: ggi_ged(Unity+0x9c9c98) vs Python 基线 ============
            {
                byte[] input = "unibase-gad-diff-test-input".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                com.github.unidbg.memory.MemoryBlock inBlock = emulator.getMemory().malloc(input.length, true);
                inBlock.getPointer().write(0, input, 0, input.length);
                randSeq[0] = 0; // 序列重置, 与 Python 侧起点一致
                Number ged = ((MachOModule) unity).callFunction(emulator, 0x9c9c98L,
                        inBlock.getPointer(), input.length, 0);
                UnidbgPointer outP = UnidbgPointer.pointer(emulator, ged.longValue());
                byte[] outBytes = outP == null ? null : outP.getByteArray(0, 48);
                StringBuilder hex = new StringBuilder();
                if (outBytes != null) {
                    for (byte b : outBytes) hex.append(String.format("%02x", b));
                }
                System.out.println("[DIFF] unidbg ggi_ged out head hex: " + hex);
                System.out.println("[DIFF] rand consumed: " + randSeq[0]);
                // Python 基线(gad_bs_impl ret_buf 前 48B): 固定随机序列 0..255
                final String PY_HEAD = "7fa610367faf1a3f4064f1387fa61036626173651b756e692d6469669c9d9e9f606060607fa61036000102037fa61036";
                boolean match = hex.length() == PY_HEAD.length() && hex.toString().equals(PY_HEAD);
                System.out.println("[DIFF] byte-level match with Python: " + match);
                if (!match && hex.length() == PY_HEAD.length()) {
                    for (int i = 0; i < PY_HEAD.length(); i += 2) {
                        if (!hex.substring(i, i + 2).equals(PY_HEAD.substring(i, i + 2))) {
                            System.out.printf("[DIFF] first mismatch at byte %d: unidbg=%s python=%s%n",
                                    i / 2, hex.substring(i, i + 2), PY_HEAD.substring(i, i + 2));
                            break;
                        }
                    }
                }
                inBlock.free();
            }

            // dump lookUpImpOrForward ring buffer(含地址解析)
            long start = Math.max(0, ringIdx[0] - ring.length);
            System.out.printf("[DBG] lookUpImpOrForward total=%d, dumping last %d:%n",
                    ringIdx[0], ringIdx[0] - start);
            for (long i = start; i < ringIdx[0]; i++) {
                String e = ring[(int) (i % ring.length)];
                if (e == null) {
                    continue;
                }
                // 解析 LR 模块归属
                try {
                    long lr = Long.parseLong(e.substring(e.lastIndexOf("LR=0x") + 5), 16);
                    Module m = emulator.getMemory().findModuleByAddress(lr);
                    e += m == null ? "(?)" : "(" + m.name + "+0x" + Long.toHexString(lr - m.base) + ")";
                } catch (Exception ignore) {
                }
                System.out.printf("[LOOKUP %d] %s%n", i, e);
            }
            assertTrue("debug driver: placeholder", true);
        } finally {
            emulator.close();
        }
    }

    private static void dumpSingletonStatics(Emulator<?> emulator, Module unity, String when) {
        long token = UnidbgPointer.pointer(emulator, unity.base + 0x50bd658L).getLong(0);
        long inst = UnidbgPointer.pointer(emulator, unity.base + 0x50bd660L).getLong(0);
        System.out.printf("[DBG] statics %s call: onceToken=0x%x instance=0x%x%n", when, token, inst);
    }

    private static Module libobjc2(Emulator<?> emulator) {
        return emulator.getMemory().findModule("libobjc.A.dylib");
    }
}
