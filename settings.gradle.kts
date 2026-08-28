// unibase-engine —— unidbg fork 的 Gradle(Kotlin DSL) 构建骨架
// Maven 构建保留不动(upstream 可合并); Gradle 是 unibase 的正式构建入口(PLAN.md 技术栈决策)

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://jitpack.io") // com.github.zhkl0228:{unicorn,capstone,keystone,demumble}
    }
}

rootProject.name = "unibase-engine"

// P0 范围: 核心五模块 + hypervisor/kvm(unidbg-android 测试源码引用, 需要参与编译;
// 运行仅限对应平台: hypervisor=macOS arm64, kvm=linux)
include(":unidbg-api")
include(":backend:unicorn2")
include(":backend:dynarmic")
include(":backend:hypervisor")
include(":backend:kvm")
include(":unidbg-android")
include(":unidbg-ios")
