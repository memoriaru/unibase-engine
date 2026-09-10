package com.github.unidbg.android.integration;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.linux.android.AndroidResolver;
import com.github.unidbg.linux.android.dvm.DalvikModule;
import com.github.unidbg.linux.android.dvm.DvmClass;
import com.github.unidbg.linux.android.dvm.BaseVM;
import com.github.unidbg.linux.android.dvm.DvmClass;
import com.github.unidbg.linux.android.dvm.VM;
import com.github.unidbg.linux.android.dvm.VaList;
import com.github.unidbg.memory.Memory;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Map;

/**
 * Runbook 实战(阶段2 验收): 真实新 so = libmmkv.so(腾讯开源 MMKV, 588KB, stripped,
 * RegisterNatives 动态注册)。设备无关链路: Step0 elf_deps 预检 ✅ → Step4 驱动组装
 * (JNI_OnLoad → 枚举 nativesMap 得精确 API → initialize/defaultMMKV → encode/decode
 * 往返)。Step1 真机录制环境受阻(无 arm64 设备), 桩生成/回放对齐环节待设备补做。
 */
public class MmkvRunbookTest {

    private static File soFile() {
        File so = new File("src/test/resources/example_binaries/arm64-v8a/libmmkv.so");
        if (!so.isFile()) {
            so = new File("unidbg-android/src/test/resources/example_binaries/arm64-v8a/libmmkv.so");
        }
        org.junit.Assert.assertTrue("libmmkv.so 应在测试资源中", so.isFile());
        return so;
    }

    /** JNI_OnLoad 后枚举 RegisterNatives 注册表 —— 精确 API 零猜测(反射包级私有字段)。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> dumpNatives(DvmClass clazz) throws Exception {
        Field f = DvmClass.class.getDeclaredField("nativesMap");
        f.setAccessible(true);
        return (Map<String, Object>) f.get(clazz);
    }

    @Test
    public void stepA_discoverRegisteredNatives() throws Exception {
        try (AndroidEmulator emulator = AndroidEmulatorBuilder.for64Bit()
                .setProcessName("mmkv-runbook")
                // 坑: 不显式加工厂会回退 legacy UnicornBackend(官方 binding),
                // Intel mac 上 TCG tb_set_jmp_target 直接 SIGSEGV —— 所有驱动必须显式指定
                .addBackendFactory(new com.github.unidbg.arm.backend.Unicorn2Factory(true))
                .build()) {
            Memory memory = emulator.getMemory();
            memory.setLibraryResolver(new AndroidResolver(23));
            VM vm = emulator.createDalvikVM();
            // Runbook Step4 口径: FallbackJni 先跑通(WARN 什么补什么), 零手工桩。
            // 实测唯一缺口 = mmkvLogImp(日志回调, SO 打 logcat 用) —— 显式 no-op 后零缺口。
            vm.setJni(new com.github.unidbg.linux.android.dvm.FallbackJni() {
                @Override
                public void callStaticVoidMethodV(BaseVM vm, DvmClass dvmClass,
                                                  String signature, VaList vaList) {
                    if (signature.contains("mmkvLogImp")) {
                        return; // 日志回调 no-op
                    }
                    super.callStaticVoidMethodV(vm, dvmClass, signature, vaList);
                }
            });
            DalvikModule dm = vm.loadLibrary(soFile(), false);
            dm.callJNI_OnLoad(emulator);

            DvmClass mmkv = vm.resolveClass("com/tencent/mmkv/MMKV");
            Map<String, Object> natives = dumpNatives(mmkv);
            System.out.println("[RUNBOOK] MMKV registered natives: " + natives.size());
            natives.keySet().stream().sorted().forEach(k ->
                    System.out.println("[RUNBOOK]   " + k));
            org.junit.Assert.assertTrue("MMKV 应注册 ≥10 个 native 方法",
                    natives.size() >= 10);
        }
    }

    /**
     * Step4+6: 驱动组装跑通 + 确定性 golden —— jniInitialize → getDefaultMMKV
     * → encodeInt/decodeInt 往返 → 快照恢复后重复读仍一致(快照保真度)。
     */
    @Test
    public void stepB_encodeDecodeRoundtripWithSnapshotRestore() throws Exception {
        try (AndroidEmulator emulator = AndroidEmulatorBuilder.for64Bit()
                .setProcessName("mmkv-runbook")
                .addBackendFactory(new com.github.unidbg.arm.backend.Unicorn2Factory(true))
                .build()) {
            Memory memory = emulator.getMemory();
            memory.setLibraryResolver(new AndroidResolver(23));
            VM vm = emulator.createDalvikVM();
            vm.setJni(new com.github.unidbg.linux.android.dvm.FallbackJni());
            DalvikModule dm = vm.loadLibrary(soFile(), false);
            dm.callJNI_OnLoad(emulator);
            DvmClass mmkv = vm.resolveClass("com/tencent/mmkv/MMKV");

            // version()(静态, 无状态)—— 顺带拿版本做 golden 的一部分
            com.github.unidbg.linux.android.dvm.StringObject ver =
                    mmkv.callStaticJniMethodObject(emulator, "version()Ljava/lang/String;");
            System.out.println("[RUNBOOK] mmkv version = " + ver.getValue());
            org.junit.Assert.assertNotNull(ver.getValue());

            // jniInitialize(rootDir, cacheDir, logLevel, ?)V —— 建根目录(guest 文件 IO)
            mmkv.callStaticJniMethod(emulator, "jniInitialize(Ljava/lang/String;Ljava/lang/String;IZ)V",
                    new com.github.unidbg.linux.android.dvm.StringObject(vm, "/data/data/com.ub.runbook/mmkv"),
                    new com.github.unidbg.linux.android.dvm.StringObject(vm, "/data/data/com.ub.runbook/cache"),
                    0, false);

            // getDefaultMMKV(mode=SINGLE_PROCESS, cryptKey=null) → 句柄
            long handle = mmkv.callStaticJniMethodLong(emulator,
                    "getDefaultMMKV(ILjava/lang/String;)J",
                    1, null);
            org.junit.Assert.assertTrue("defaultMMKV 句柄应非零", handle != 0);
            System.out.println("[RUNBOOK] defaultMMKV handle = 0x" + Long.toHexString(handle));

            com.github.unidbg.linux.android.dvm.DvmObject<?> instance = mmkv.newObject(handle);
            com.github.unidbg.linux.android.dvm.StringObject key =
                    new com.github.unidbg.linux.android.dvm.StringObject(vm, "ub_runbook_int");

            boolean ok = instance.callJniMethodBoolean(emulator,
                    "encodeInt(JLjava/lang/String;I)Z", handle, key, 42);
            org.junit.Assert.assertTrue("encodeInt 应成功", ok);
            int back = instance.callJniMethodInt(emulator,
                    "decodeInt(JLjava/lang/String;I)I", handle, key, 0);
            org.junit.Assert.assertEquals("encode/decode 往返", 42, back);

            // 快照恢复后重读 —— mmap 数据页内容属于 guest 内存, restore 后应原样
            dev.unibase.snapshot.EmulatorSnapshot snap = dev.unibase.snapshot.EmulatorSnapshot.save(emulator);
            try {
                instance.callJniMethodBoolean(emulator,
                        "encodeInt(JLjava/lang/String;I)Z", handle, key, 4242);
                org.junit.Assert.assertEquals(4242, instance.callJniMethodInt(emulator,
                        "decodeInt(JLjava/lang/String;I)I", handle, key, 0));
                snap.restore(emulator);
                org.junit.Assert.assertEquals("快照恢复后应回到 encode 42 时刻",
                        42, instance.callJniMethodInt(emulator,
                        "decodeInt(JLjava/lang/String;I)I", handle, key, 0));
            } finally {
                snap.close();
            }
            System.out.println("[RUNBOOK] roundtrip + snapshot-restore PASS");
        }
    }
}
