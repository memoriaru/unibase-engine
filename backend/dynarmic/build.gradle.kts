// Dynarmic 后端: 预编译 natives 已入库(src/main/resources/natives/{linux,osx}_{64,arm64})
dependencies {
    api(project(":unidbg-api"))
    testImplementation("junit:junit:4.13.2")
}
