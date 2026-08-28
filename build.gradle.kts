// unibase-engine 根构建: 统一 Java 21 工具链
// 注意: legacy 模块保持 release=8(unidbg 上游源码级别, 维持 bugfix 回馈能力);
// fork 新增模块(如 unibase/ 平台原语)应设 release=21

plugins {
    `java-library`
}

allprojects {
    group = "com.github.unidbg"
    version = "0.9.10-SNAPSHOT"
}

subprojects {
    apply(plugin = "java-library")

    java {
        toolchain {
            // 运行/编译统一 JBR 21(Android Studio 自带); 无 foojay resolver,
            // 依赖本机已有 JDK: 本地用 JAVA_HOME 指向 JBR, CI 用 actions/setup-java
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        sourceCompatibility = "8"
        targetCompatibility = "8"
    }

    // release 21(2026-08-29 决策): unibase 全栈统一 Java 21。
    // - 上游可合并性保留: master 镜像分支不升; unibase/base 的功能性回馈 patch
    //   不用 9+ 语法, 上游(source/target 8)照常编译
    // - 通配 import + java.lang.Module 的二义性已修复(AbstractARMDebugger/
    //   AndroidElfLoader/MachOLoader 改显式 import)
    // - 消费方仅 platform(21 运行时), 字节码 65 无部署顾虑
    tasks.named<JavaCompile>("compileJava") {
        options.release.set(21)
    }
    tasks.named<JavaCompile>("compileTestJava") {
        options.release.set(21)
    }

    tasks.withType<Test>().configureEach {
        useJUnit()
        // 关键: -ea 会改变 unidbg 模拟执行流程(hongguo sign 返回空), 载荷回归必须关断言
        enableAssertions = false
        testLogging {
            events("failed", "skipped")
            showStandardStreams = false
        }
        // emulated 工作负载需要更大栈与堆; C1-only 规避 JBR21 C2 编译器崩溃
        jvmArgs("-Xmx2g", "-XX:TieredStopAtLevel=1")
    }
}
