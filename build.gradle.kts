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

    // main 源码用 --release 8: 消除 java.lang.Module(9+) 与 com.github.unidbg.Module 的
    // 二义性, 且保证上游源码真的只用 JDK8 API(可回馈)。
    // 测试源码保持 source/target 模式: 样本/上游测试用了 Java 9+ API(readAllBytes 等),
    // 运行时统一 JBR 21, 无 JDK8 部署需求。
    tasks.named<JavaCompile>("compileJava") {
        options.release.set(8)
    }

    tasks.withType<Test>().configureEach {
        useJUnit()
        // 关键: -ea 会改变 unidbg 模拟执行流程(hongguo sign 返回空), 载荷回归必须关断言
        enableAssertions = false
        testLogging {
            events("failed", "skipped")
            showStandardStreams = false
        }
        // emulated 工作负载需要更大栈与堆
        jvmArgs("-Xmx2g")
    }
}
