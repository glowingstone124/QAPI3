plugins {
    id("org.springframework.boot") version "3.1.5"
    id("io.spring.dependency-management") version "1.1.3"
    kotlin("jvm") version "2.0.20"
    kotlin("plugin.spring") version "2.0.20"
}

group = "org.qo"
version = "1.0-SNAPSHOT"
java.sourceCompatibility = JavaVersion.VERSION_21

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-tomcat")
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-quartz")
    implementation("redis.clients:jedis:3.6.3")
    implementation("org.mockito:mockito-core:5.13.0")
    implementation("org.json:json:20231013")
    implementation("javax.servlet:javax.servlet-api:4.0.1")
    implementation("org.xerial:sqlite-jdbc:3.34.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.commonmark:commonmark:0.20.0")
    implementation("com.mysql:mysql-connector-j:8.4.0")
    implementation("org.jetbrains:annotations:23.0.0")
    implementation("org.postgresql:postgresql:42.7.2")
    implementation("com.alibaba:druid:1.2.22")
    implementation("com.sun.mail:javax.mail:1.6.2")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.9.1")
    testImplementation("org.jetbrains.kotlin:kotlin-test:${kotlin.coreLibrariesVersion}")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.register("buildAndCopy") {
    // 指定该任务的依赖为 build 任务
    dependsOn("build")

    doLast {
        val buildDir = buildDir.resolve("libs")
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
