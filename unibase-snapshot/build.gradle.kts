plugins {
    `java-library`
}

dependencies {
    api(project(":unidbg-api"))
    // 测试需要真实后端 + 便捷的 emulator 构建器(构建器在 unidbg-android)
    testImplementation(project(":backend:unicorn2"))
    testImplementation(project(":unidbg-android"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.slf4j:slf4j-reload4j:2.0.16")
}
