package com.github.unidbg.ios.ipa;

import com.dd.plist.NSDictionary;
import com.dd.plist.PropertyListFormatException;
import com.dd.plist.PropertyListParser;
import com.github.unidbg.Emulator;
import com.github.unidbg.Module;
import com.github.unidbg.file.ios.DarwinFileIO;
import com.github.unidbg.ios.BaseLoader;
import com.github.unidbg.ios.DarwinARM64Emulator;
import com.github.unidbg.ios.DarwinResolver;
import com.github.unidbg.ios.DarwinSyscallHandler;
import com.github.unidbg.ios.MachOLoader;
import com.github.unidbg.ios.MachOModule;
import com.github.unidbg.ios.OSVersion;
import com.github.unidbg.spi.SyscallHandler;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class BundleLoader extends BaseLoader {

    private static final Logger log = LoggerFactory.getLogger(BundleLoader.class);

    public static final String APP_NAME = "UniDbg";

    private final File frameworkDir;
    protected final File rootDir;

    public BundleLoader(File frameworkDir, File rootDir) {
        this.frameworkDir = frameworkDir;
        this.rootDir = rootDir;

        if (!frameworkDir.exists() || !frameworkDir.isDirectory()) {
            throw new IllegalArgumentException("Invalid frameworkDir: " + frameworkDir);
        }
    }

    private String generateRandomSeed(String bundleIdentifier, String bundleVersion) {
        return bundleIdentifier + "_" + bundleVersion;
    }

    private String generateExecutableBundlePath(String bundleIdentifier, String bundleVersion) {
        String seed = generateRandomSeed(bundleIdentifier, bundleVersion);
        UUID uuid = UUID.nameUUIDFromBytes((seed + "_Application").getBytes(StandardCharsets.UTF_8));
        return APP_DIR + uuid.toString().toUpperCase() + "/" + APP_NAME + ".app";
    }

    public LoadedBundle load(String name, EmulatorConfigurator configurator) {
        final File bundleDir = new File(this.frameworkDir, name + ".framework");
        String executable;
        String bundleVersion;
        String bundleIdentifier;
        try {
            File infoFile = new File(bundleDir, "Info.plist");
            if (!infoFile.canRead()) {
                throw new IllegalStateException("load " + name + " failed");
            }
            byte[] data = FileUtils.readFileToByteArray(infoFile);
            NSDictionary info = (NSDictionary) PropertyListParser.parse(data);
            executable = parseExecutable(info);
            bundleVersion = parseVersion(info);
            bundleIdentifier = parseCFBundleIdentifier(info);
        } catch (IOException | PropertyListFormatException | ParseException | ParserConfigurationException |
                 SAXException e) {
            throw new IllegalStateException("load " + name + " failed", e);
        }

        String executableBundleDir = generateExecutableBundlePath(bundleIdentifier, bundleVersion);
        String executableBundlePath = executableBundleDir + "/" + APP_NAME;
//        String bundleAppDir = new File(executableBundlePath).getParentFile().getParentFile().getPath();
        File rootDir = new File(this.rootDir, bundleVersion);
        try {
            Emulator<DarwinFileIO> emulator = new DarwinARM64Emulator(executableBundlePath, rootDir, backendFactories, getEnvs(rootDir, executableBundlePath)) {
            };
            emulator.getSyscallHandler().setVerbose(log.isDebugEnabled());
            if (configurator != null) {
                configurator.configure(emulator, executableBundlePath, rootDir, bundleIdentifier);
            }
            config(emulator, executableBundlePath, rootDir);
            MachOLoader memory = (MachOLoader) emulator.getMemory();
            memory.setObjcRuntime(true);
            DarwinResolver resolver = createLibraryResolver();
            if (isUseOverrideResolver()) {
                resolver.setOverride();
            }
            memory.setLibraryResolver(resolver);
            Module module = load(emulator, configurator, new File(bundleDir, executable), executableBundleDir);
            return new LoadedBundle(emulator, module, bundleIdentifier, bundleVersion);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    protected void config(final Emulator<DarwinFileIO> emulator, String executableBundlePath, File rootDir) throws IOException {
        File executable = new File(executableBundlePath);
        SyscallHandler<DarwinFileIO> syscallHandler = emulator.getSyscallHandler();
        File appDir = executable.getParentFile();
        syscallHandler.addIOResolver(new BundleResolver(appDir.getPath(), getBundleIdentifier()));
        FileUtils.forceMkdir(new File(rootDir, appDir.getParentFile().getPath()));
        emulator.getMemory().addHookListener(new SymbolResolver(emulator));
        // 数据驱动桩库: 实证种子 + 外部声明表叠加(theos/sdks 生成, 互操作数据放私有仓)
        com.github.unidbg.ios.service.HostStubStore stubStore = new com.github.unidbg.ios.service.HostStubStore(emulator);
        if (stubTables != null) {
            for (java.io.File table : stubTables) {
                stubStore.load(new java.io.FileReader(table));
            }
        }
        emulator.getMemory().addHookListener(stubStore);
        if (osVersion != null) { // 版本指纹参数化(桩库 B 期): 三处消费单值注入
            ((MachOLoader) emulator.getMemory()).setOSVersion(osVersion);
            ((DarwinSyscallHandler) syscallHandler).setOSVersion(osVersion);
        }
        if (stubCollector != null) { // C 期惰性收集: 链尾观察 + objc runtime 打印拦截
            emulator.getMemory().addHookListener(stubCollector);
        }

//        ((DarwinSyscallHandler) syscallHandler).setExecutableBundlePath(executableBundlePath);
    }

    /** iOS 虚拟环境版本指纹(manifest 参数; null = 默认 7.1.0 世代, "能低则低")。 */
    private OSVersion osVersion;

    public BundleLoader setOSVersion(OSVersion osVersion) {
        this.osVersion = osVersion;
        return this;
    }

    /** 外部桩声明表(TSV, 数据由 theos/sdks 头文件生成, 互操作数据放私有仓)。 */
    private List<java.io.File> stubTables;

    public BundleLoader addStubTable(java.io.File table) {
        if (stubTables == null) {
            stubTables = new ArrayList<>();
        }
        stubTables.add(table);
        return this;
    }

    /** C 期惰性收集器(链尾观察, 不改变行为; 运行后 writeReport 出桩声明草案)。 */
    private com.github.unidbg.ios.service.HostStubCollector stubCollector;

    public BundleLoader setStubCollector(com.github.unidbg.ios.service.HostStubCollector collector) {
        this.stubCollector = collector;
        return this;
    }

    protected String getBundleIdentifier() {
        return getClass().getPackage().getName();
    }

    protected String[] getEnvs(File rootDir, String seed) throws IOException {
        List<String> list = new ArrayList<>();
        list.add("OBJC_PRINT_EXCEPTION_THROW=YES"); // log backtrace of every objc_exception_throw()
        addEnv(list);
        UUID uuid = UUID.nameUUIDFromBytes((seed + "_Documents").getBytes(StandardCharsets.UTF_8));
        String homeDir = "/var/mobile/Containers/Data/Application/" + uuid.toString().toUpperCase();
        list.add("CFFIXED_USER_HOME=" + homeDir);
        FileUtils.forceMkdir(new File(rootDir, homeDir + "/Documents"));
        return list.toArray(new String[0]);
    }

    private Module load(Emulator<DarwinFileIO> emulator, EmulatorConfigurator configurator, File executable, String executableBundleDir) throws IOException {
        MachOLoader loader = (MachOLoader) emulator.getMemory();
        loader.setLoader(this);
        Module module = loader.load(new BundleLibraryFile(executable, executableBundleDir), forceCallInit);
        if (configurator != null) {
            configurator.onExecutableLoaded(emulator, (MachOModule) module);
        }
        loader.onExecutableLoaded(executable.getName());
        return module;
    }

    @Override
    public boolean isPayloadModule(String path) {
        return false;
    }

}
