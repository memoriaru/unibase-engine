dependencies {
    api(project(":unidbg-api"))
    api("io.kaitai:kaitai-struct-runtime:0.8")
    api("com.googlecode.plist:dd-plist:1.23")
    testImplementation(project(":backend:unicorn2"))
    testImplementation(project(":backend:dynarmic"))
    testImplementation(project(":backend:hypervisor"))
    testImplementation(project(":backend:kvm"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.slf4j:slf4j-reload4j:2.0.16")
}
