import java.util.Properties
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    id("org.springframework.boot") version "3.5.4"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.spring") version "2.2.21"
    kotlin("plugin.serialization") version "2.2.21"
}

group = "org.qo"
version = "1.0-SNAPSHOT"

val productVersion = "4.0.0"
val ktorVersion = "3.4.2"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.maxmind.geoip2:geoip2:4.2.1")

    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlin:kotlin-stdlib")

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-quartz")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    implementation("redis.clients:jedis:3.6.3")
    implementation("org.xerial:sqlite-jdbc:3.34.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.commonmark:commonmark:0.20.0")
    implementation("com.mysql:mysql-connector-j:8.4.0")
    implementation("org.jetbrains:annotations:23.0.0")
    implementation("com.alibaba:druid:1.2.22")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json")

    implementation("io.asyncer:r2dbc-mysql:1.2.0")

    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.mockito:mockito-core")

    implementation("jakarta.mail:jakarta.mail-api:2.1.5")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

val generatedResDir = layout.buildDirectory.dir("generated-resources/main")

sourceSets {
    main {
        resources {
            srcDir(generatedResDir)
        }
    }
}

val generateProperties by tasks.registering {
    val outputDir = generatedResDir.get().asFile
    val propsFile = outputDir.resolve("version.properties")

    outputs.file(propsFile)

    doLast {
        outputDir.mkdirs()
        val props = Properties().apply {
            setProperty("build.version", productVersion)
            setProperty("build.timestamp", System.currentTimeMillis().toString())
        }
        propsFile.outputStream().use { props.store(it, null) }
    }
}

tasks.named("processResources") {
    dependsOn(generateProperties)
}

tasks.named<BootJar>("bootJar") {
    archiveFileName.set("QAPI3-1.0-SNAPSHOT.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.register("buildAndCopy") {
    dependsOn("bootJar")

    doLast {
        val jarFile = tasks.named<BootJar>("bootJar").get().archiveFile.get().asFile
        val outputDir = file("/opt/server/api")
        outputDir.mkdirs()

        val destination = outputDir.resolve(jarFile.name)
        if (destination.exists()) {
            destination.delete()
        }
        jarFile.copyTo(destination, overwrite = true)
        println("复制了文件: ${jarFile.absolutePath} 到 ${destination.absolutePath}")
    }
}