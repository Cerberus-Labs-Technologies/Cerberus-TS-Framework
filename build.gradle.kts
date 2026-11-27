plugins {
    kotlin("jvm") version "2.4.21"
    `java-library`
    `maven-publish`
    application
}

group = "labs.cerberus"
version = "2.0.0-SNAPSHOT"
description = "Cerberus TS — TeamSpeak plugin framework by Cerberus Labs"

repositories {
    mavenCentral()
    exclusiveContent {
        forRepository { maven { url = uri("https://jitpack.io") } }
        filter { includeModule("com.github.Manevolent", "ts3j") }
    }
}

kotlin { jvmToolchain(21) }
java { withSourcesJar() }
application { mainClass.set("labs.cerberus.ts.MainKt") }

val webDirectory = layout.projectDirectory.dir("web")
val webInstall = tasks.register<Exec>("webInstall") {
    group = "build"
    description = "Install the locked web interface dependencies"
    workingDir(webDirectory)
    commandLine(providers.environmentVariable("CERBERUS_NPM").getOrElse("npm"), "ci", "--no-fund", "--no-audit")
    inputs.files(webDirectory.file("package.json"), webDirectory.file("package-lock.json"))
    outputs.dir(webDirectory.dir("node_modules"))
}
val webBuild = tasks.register<Exec>("webBuild") {
    group = "build"
    description = "Build the React/shadcn interface and its bundled asset manifest"
    dependsOn(webInstall)
    workingDir(webDirectory)
    commandLine(providers.environmentVariable("CERBERUS_NPM").getOrElse("npm"), "run", "build")
    inputs.files(fileTree(webDirectory) {
        exclude("node_modules/**", "dist/**", "shots/**", ".taste-skill/**", "*.tsbuildinfo")
    })
    outputs.dir(webDirectory.dir("dist"))
}
tasks.processResources {
    dependsOn(webBuild)
    from(webDirectory.dir("dist")) { into("web") }
}
tasks.named<Sync>("installDist") {
    providers.gradleProperty("cerberus.install.dir").orNull?.let { destinationDir = file(it) }
}

distributions {
    main {
        contents {
            from("README.md", "LICENSE", "NOTICE", "CHANGELOG.md", "SECURITY.md")
            from("config") { include("cerberus.example.properties"); into("config") }
            from("docs") { into("docs") }
        }
    }
}

dependencies {
    implementation("com.github.Manevolent:ts3j:db57d60c989e399626aa16d921390f5033e6cdeb") {
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15on")
    }
    implementation("org.bouncycastle:bcprov-jdk18on:1.87")
    implementation("io.github.jaredmdobson:concentus:1.0.2")
    implementation("com.github.mwiede:jsch:2.28.8")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.23.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.23.0")
    implementation("org.eclipse.angus:angus-mail:2.0.4")
    implementation("org.yaml:snakeyaml:2.7")
    api("jakarta.persistence:jakarta.persistence-api:3.2.0")
    implementation("org.hibernate.orm:hibernate-core:7.4.17.Final")
    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("io.github.classgraph:classgraph:5.0.0")
    runtimeOnly("org.mariadb.jdbc:mariadb-java-client:4.0.0")
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testImplementation("org.apache.sshd:sshd-core:2.16.0")
    testImplementation("com.h2database:h2:2.4.240")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.14.1")
}

tasks.test {
    useJUnitPlatform()
    dependsOn(":examples:welcome-plugin:jar", ":examples:music-plugin:jar")
    systemProperty("cerberus.example.jar", layout.projectDirectory.file("examples/welcome-plugin/build/libs/welcome-plugin.jar").asFile.absolutePath)
    systemProperty("cerberus.music.jar", layout.projectDirectory.file("examples/music-plugin/build/libs/music-plugin.jar").asFile.absolutePath)
}
tasks.jar {
    manifest {
        attributes("Implementation-Title" to "Cerberus TS", "Implementation-Version" to project.version,
            "Implementation-Vendor" to "Cerberus Labs")
    }
}
publishing {
    publications {
        create<MavenPublication>("library") {
            from(components["java"])
            pom {
                name.set("Cerberus TS")
                description.set("TeamSpeak plugin framework in Kotlin by Cerberus Labs")
                developers { developer { name.set("Cerberus Labs") } }
                licenses { license { name.set("MIT License"); url.set("https://opensource.org/license/mit/") } }
            }
        }
    }
}
