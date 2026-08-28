dependencies {
    api(project(":unidbg-api"))
    api("io.kaitai:kaitai-struct-runtime:0.8")
    api("com.googlecode.plist:dd-plist:1.29")
    testImplementation(project(":backend:unicorn2"))
    testImplementation(project(":backend:dynarmic"))
    testImplementation(project(":backend:hypervisor"))
    testImplementation(project(":backend:kvm"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.slf4j:slf4j-reload4j:2.0.16")
}

// iOS 基座为阶段4范围: 套件依赖重量级 IPA 资源与 dynarmic(上游 native 破损),
// P0 仅编译验证(compileTestJava), 不进入常规 test 运行
tasks.withType<Test>().configureEach {
    include("unibase/__none__")
    filter { setFailOnNoMatchingTests(false) }
}
