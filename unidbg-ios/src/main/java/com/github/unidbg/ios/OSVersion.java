package com.github.unidbg.ios;

import java.util.Objects;

/**
 * iOS 虚拟环境版本指纹(单值来源, 三处消费):
 * <ul>
 *   <li>Dyld64._os_system_version_get_current_version(guest 的 os_system_version 查询)</li>
 *   <li>DarwinSyscallHandler kern.osrelease / kern.osversion(build) sysctl</li>
 *   <li>HostStubStore 的 ___isPlatformVersionAtLeast 桩(@available 门控)</li>
 * </ul>
 *
 * 设计决策(PLAN 阶段4 桩库 B 期): 环境版本是 manifest 参数不是基座属性 ——
 * 策略"虚拟指纹能低则低"(标低走旧路径, 桩面最小, 跑不通再按需提档),
 * 默认维持基座历史值 7.1.0 / kernel 14.0.0 / build 9A127。
 */
public final class OSVersion {

    /** 基座历史默认(与旧硬编码一致, 保持既有样本行为不变)。 */
    public static final OSVersion DEFAULT = new OSVersion(7, 1, 0, "14.0.0", "9A127");

    public final int major, minor, patch;
    /** sysctl kern.osrelease, 如 "14.0.0"(Darwin 版本号, 非 iOS 版本号)。 */
    public final String kernelRelease;
    /** sysctl kern.osversion, 如 "9A127"(build 号)。 */
    public final String buildVersion;

    public OSVersion(int major, int minor, int patch, String kernelRelease, String buildVersion) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.kernelRelease = Objects.requireNonNull(kernelRelease);
        this.buildVersion = Objects.requireNonNull(buildVersion);
    }

    /** 常用构造: 由 iOS 版本号推 kernel release(Darwin = iOS + 7)。 */
    public static OSVersion ofIOS(int major, int minor, int patch, String build) {
        return new OSVersion(major, minor, patch, (major + 7) + "." + minor + ".0", build);
    }

    /** ___isPlatformVersionAtLeast 语义: 当前平台版本 &gt;= (major, minor, patch)。 */
    public boolean isAtLeast(int major, int minor, int patch) {
        if (this.major != major) {
            return this.major > major;
        }
        if (this.minor != minor) {
            return this.minor > minor;
        }
        return this.patch >= patch;
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch + " kernel=" + kernelRelease + " build=" + buildVersion;
    }
}
