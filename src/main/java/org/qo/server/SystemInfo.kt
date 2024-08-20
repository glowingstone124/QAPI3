package org.qo.server;
import java.io.BufferedReader
import java.lang.management.ManagementFactory
import java.lang.management.OperatingSystemMXBean
import java.io.File
import java.io.InputStreamReader

class SystemInfo {
    fun printSystemInfo() {
        val process = Runtime.getRuntime().exec("uname -a")
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val unameOutput = reader.readLine()
        val osBean: OperatingSystemMXBean = ManagementFactory.getOperatingSystemMXBean()
        println("Running on $unameOutput")

        println("CPU: ${osBean.availableProcessors}")

        val runtime = Runtime.getRuntime()
        println("内存: ${runtime.totalMemory() / (1024 * 1024)} MB / ${(runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)} MB")

        println("Java版本: ${System.getProperty("java.version")}")

        process.waitFor()

    }

}