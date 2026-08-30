package com.github.unidbg.ios.service;

import com.github.unidbg.Emulator;
import com.github.unidbg.Module;
import com.github.unidbg.Symbol;
import com.github.unidbg.hook.HookListener;
import com.github.unidbg.memory.SvcMemory;
import com.github.unidbg.pointer.UnidbgPointer;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * UIKit/CoreTelephony 通知名常量桩: 基座只带 Foundation/CoreFoundation,
 * 第三方 framework 引用的 UIKit 通知名(CFStringRef 全局常量)解析失败被绑 0,
 * guest 解引用即崩(实证: GADSignals init 深处构建通知观察表 ldr x8,[x8] 崩在
 * _UIWindowDidBecomeKeyNotification)。
 *
 * 机制: HookListener 在 MachOLoader doBindAt 符号解析失败时被询问,
 * 命中白名单则在 svcMemory 合成合法 CFConstantString(结构经实证:
 * isa=___CFConstantStringClassReference 地址, flags=0x7c8, data=UTF-8, len=字节数)。
 */
public class UIKitNotificationStub implements HookListener {

    private static final Map<String, String> NOTIFICATIONS = new HashMap<>();

    static {
        // 实证清单(UnityFramework GADSignals init 路径) + UIKit 常用通知名
        put("UIApplicationWillEnterForegroundNotification");
        put("UIApplicationDidBecomeActiveNotification");
        put("UIApplicationDidEnterBackgroundNotification");
        put("UIApplicationWillResignActiveNotification");
        put("UIApplicationDidChangeStatusBarOrientationNotification");
        put("UIApplicationDidChangeStatusBarFrameNotification");
        put("UIDeviceOrientationDidChangeNotification");
        put("UIWindowDidBecomeKeyNotification");
        put("UIWindowDidResignKeyNotification");
        put("CTRadioAccessTechnologyDidChangeNotification");
        // UIScene 系(iOS 13+ 场景生命周期, 同一注册表路径)
        put("UISceneWillDeactivateNotification");
        put("UISceneWillEnterForegroundNotification");
        put("UISceneDidEnterBackgroundNotification");
        put("UISceneDidActivateNotification");
        put("UISceneDidDisconnectNotification");
        put("UISceneInvalidationNotification");
    }

    private static void put(String name) {
        NOTIFICATIONS.put("_" + name, name);
    }

    private final Emulator<?> emulator;
    private final Map<String, Long> cache = new HashMap<>();

    public UIKitNotificationStub(Emulator<?> emulator) {
        this.emulator = emulator;
    }

    @Override
    public long hook(SvcMemory svcMemory, String libraryName, String symbolName, long old) {
        String name = NOTIFICATIONS.get(symbolName);
        if (name == null) {
            return 0;
        }
        return cache.computeIfAbsent(symbolName, k -> allocate(svcMemory, name));
    }

    private long allocate(SvcMemory svcMemory, String name) {
        byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        UnidbgPointer block = svcMemory.allocate(32 + bytes.length + 1, "UIKitNotificationStub:" + name);
        Module coreFoundation = emulator.getMemory().findModule("CoreFoundation");
        if (coreFoundation == null) {
            throw new IllegalStateException("CoreFoundation NOT loaded");
        }
        Symbol clsRef = coreFoundation.findSymbolByName("___CFConstantStringClassReference", false);
        if (clsRef == null) {
            throw new IllegalStateException("___CFConstantStringClassReference NOT found");
        }
        UnidbgPointer str = (UnidbgPointer) block.share(32);
        byte[] withNul = new byte[bytes.length + 1];
        System.arraycopy(bytes, 0, withNul, 0, bytes.length);
        str.write(0, withNul, 0, withNul.length);
        block.setLong(0, clsRef.getAddress()); // isa
        block.setLong(8, 0x7c8L);              // flags: constant ASCII
        block.setLong(16, str.peer);           // data
        block.setLong(24, bytes.length);       // length
        return block.peer;
    }
}
