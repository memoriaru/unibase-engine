dependencies {
    api(project(":unidbg-api"))
    api("net.dongliu:apk-parser:2.6.10")
    // 上游 pom 将四个后端置于 test scope(测试源码 import 全部后端工厂)
    testImplementation(project(":backend:unicorn2"))
    testImplementation(project(":backend:dynarmic"))
    testImplementation(project(":backend:hypervisor"))
    testImplementation(project(":backend:kvm"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.slf4j:slf4j-reload4j:2.0.16")
}

// P0 基线冒烟: unibase/ 包下的载荷回归测试(hongguo 样本, 仅本地验证不入库)。
// CI 无该目录时任务空跑不失败; 载荷存在时用 -Pbackend=unicorn2|dynarmic 切换后端。
val smokeTest by tasks.registering(Test::class) {
    group = "verification"
    description = "本地载荷基线(hongguo 签名复现), 需要样本文件, CI 上空跑"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    include("unibase/**")
    filter.setFailOnNoMatchingTests(false)
    systemProperty("unibase.backend", (project.findProperty("backend") as String?) ?: "unicorn2")
    // 基线依赖宿主侧种子文件, 由本机准备(见 HongguoSignBaselineTest 注释)
    systemProperty("unibase.hongguo.seeds", System.getenv("MSDATA_DIR") ?: "/tmp/msdata_files")
}

// 调试用: 打印测试运行时完整 classpath (./gradlew :unidbg-android:printTestClasspath -q)
tasks.register("printTestClasspath") {
    doLast { println(sourceSets["test"].runtimeClasspath.asPath) }
}
