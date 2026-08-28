dependencies {
    api("com.github.zhkl0228:unicorn:1.0.14")
    api("com.github.zhkl0228:capstone:3.1.8")
    api("com.github.zhkl0228:keystone:0.9.7")
    api("com.github.zhkl0228:demumble:1.0.4")
    api("commons-codec:commons-codec:1.15")
    api("org.apache.commons:commons-collections4:4.4")
    api("commons-io:commons-io:2.14.0")
    api("com.alibaba:fastjson:1.2.83")
    // slf4j-api 2.0.16(compile) / junit 4.13.2(test) / slf4j-reload4j 2.0.16(test) 继承自上游根 pom
    api("org.slf4j:slf4j-api:2.0.16")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.slf4j:slf4j-reload4j:2.0.16")
}

// HexTest.testStream 写死 Maven 布局相对路径 target/, Gradle 下无效(上游无 CI 的遗留)
tasks.withType<Test>().configureEach {
    filter { excludeTestsMatching("com.github.unidbg.HexTest.testStream") }
}
