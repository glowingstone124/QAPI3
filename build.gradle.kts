import java.util.Properties
plugins {
    id("org.springframework.boot") version "3.1.5"
    id("io.spring.dependency-management") version "1.1.3"
    kotlin("jvm") version "2.0.20"
    kotlin("plugin.spring") version "2.0.20"
}

group = "org.qo"
version = "1.0-SNAPSHOT"
val ProductVersion = "4.0.0"

java.sourceCompatibility = JavaVersion.VERSION_21
kotlin {
    jvmToolchain(21)
}
repositories {
    mavenCentral()
}

dependencies {
    //GEOIP
    implementation("com.maxmind.geoip2:geoip2:4.2.1")
    //Kotlin Stdlibs
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    // Springboot
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-quartz")
    implementation("org.springframework.boot:spring-boot-starter-reactor-netty")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-web")
    //Redis
    implementation("redis.clients:jedis:3.6.3")
    implementation("org.mockito:mockito-core:5.13.0")
    implementation("org.json:json:20231013")
    implementation("javax.servlet:javax.servlet-api:4.0.1")
    implementation("org.xerial:sqlite-jdbc:3.34.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.commonmark:commonmark:0.20.0")
    implementation("com.mysql:mysql-connector-j:8.4.0")
    implementation("org.jetbrains:annotations:23.0.0")
    implementation("com.alibaba:druid:1.2.22")
    implementation("com.sun.mail:javax.mail:1.6.2")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.9.1")
    implementation("io.asyncer:r2dbc-mysql:1.2.0")
    testImplementation("org.jetbrains.kotlin:kotlin-test:${kotlin.coreLibrariesVersion}")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

val generateProperties by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated-properties").get().asFile
    val propsFile = File(outputDir, "version.properties")
    val resourceDir = layout.buildDirectory.dir("resources/main").get().asFile

    outputs.file(propsFile)

    doLast {
        outputDir.mkdirs()
        val props = Properties().apply {
            setProperty("build.version", ProductVersion)
            setProperty("build.timestamp", System.currentTimeMillis().toString())
        }
        propsFile.outputStream().use {
            props.store(it, null)
        }
        println("Successfully generated properties file")

        resourceDir.mkdirs()
        propsFile.copyTo(File(resourceDir, "version.properties"), overwrite = true)
        println("Copied version.properties to resources")
    }
}


tasks.named("processResources") {
    dependsOn(generateProperties)
}

tasks.register("buildAndCopy") {
    dependsOn("build")

    doLast {
        val buildDir =  layout.buildDirectory.dir("libs").get().asFile
        val outputDir = File("/opt/server/api")
        val targetJarName = "QAPI3-1.0-SNAPSHOT.jar"

        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }
        val targetJarFile = buildDir.resolve(targetJarName)

        if (targetJarFile.exists()) {
            val destinationFile = outputDir.resolve(targetJarFile.name)

            if (destinationFile.exists()) {
                destinationFile.delete()
                println("删除了文件: ${destinationFile.absolutePath}")
            }

            targetJarFile.copyTo(destinationFile)
            println("复制了文件: ${targetJarFile.absolutePath} 到 ${destinationFile.absolutePath}")
        } else {
            println("文件 $targetJarName 未找到在目录: ${buildDir.absolutePath}")
        }
    }
}
