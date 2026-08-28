// Hypervisor 后端(macOS arm64 only): 仅参与编译(unidbg-android 测试源码引用);
// natives 由 macOS 系统框架(Hypervisor.framework)直接提供
dependencies {
    api(project(":unidbg-api"))
    testImplementation("junit:junit:4.13.2")
}
