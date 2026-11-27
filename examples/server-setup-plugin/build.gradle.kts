plugins { kotlin("jvm") }
repositories {
    mavenCentral()
    exclusiveContent {
        forRepository { maven { url = uri("https://jitpack.io") } }
        filter { includeModule("com.github.Manevolent", "ts3j") }
    }
}
kotlin { jvmToolchain(21) }
dependencies {
    compileOnly(project(":"))
    testImplementation(project(":"))
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.14.1")
}
tasks.test {
    useJUnitPlatform()
    dependsOn(tasks.jar)
    systemProperty("cerberus.setup.jar", tasks.jar.get().archiveFile.get().asFile.absolutePath)
}
