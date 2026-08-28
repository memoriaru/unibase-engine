// KVM 后端(linux only): 仅参与编译(unidbg-android 测试源码引用)
dependencies {
    api(project(":unidbg-api"))
    testImplementation("junit:junit:4.13.2")
}
