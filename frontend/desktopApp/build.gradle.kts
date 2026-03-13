import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.io.File

// ==================== 读取 .env 文件 ====================
fun readEnvFile(): Map<String, String> {
    val env = mutableMapOf<String, String>()
    val cwd = File(System.getProperty("user.dir"))
    val candidates = listOf(
        File(cwd, ".env"),
        cwd.parentFile?.let { File(it, ".env") },
        cwd.parentFile?.parentFile?.let { File(it, ".env") }
    ).filterNotNull()

    for (file in candidates) {
        if (file.isFile) {
            file.forEachLine { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEachLine
                val eq = trimmed.indexOf('=')
                if (eq <= 0) return@forEachLine
                var key = trimmed.substring(0, eq).trim()
                var value = trimmed.substring(eq + 1).trim()
                if (key.startsWith("export ")) key = key.removePrefix("export ").trim()
                if (value.startsWith("\"") && value.endsWith("\"")) value = value.drop(1).dropLast(1)
                if (value.startsWith("'") && value.endsWith("'")) value = value.drop(1).dropLast(1)
                env[key] = value
            }
            break
        }
    }
    return env
}
val envFile = readEnvFile()

val backendHost = envFile["BACKEND_HOST"] ?: System.getenv("BACKEND_HOST") ?: "localhost"
val backendPort = envFile["BACKEND_HTTP_PORT"] ?: System.getenv("BACKEND_HTTP_PORT") ?: "8003"
println("🖥️ [desktopApp] BACKEND_HOST = $backendHost, BACKEND_HTTP_PORT = $backendPort")

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.jetbrains.compose")
}

// ==================== 生成 BuildConfig.kt ====================
val generateBuildConfig by tasks.registering {
    val outputDir = file("$buildDir/generated/buildconfig")
    outputs.dir(outputDir)
    doLast {
        val dir = File(outputDir, "com/silk/desktop")
        dir.mkdirs()
        File(dir, "BuildConfig.kt").writeText("""
            package com.silk.desktop

            object BuildConfig {
                const val BACKEND_HOST = "$backendHost"
                const val BACKEND_HTTP_PORT = "$backendPort"
                const val BACKEND_BASE_URL = "http://$backendHost:$backendPort"
                const val BACKEND_WS_URL = "ws://$backendHost:$backendPort"
            }
        """.trimIndent())
    }
}

sourceSets["main"].java.srcDir("$buildDir/generated/buildconfig")

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(generateBuildConfig)
}

dependencies {
    implementation(project(":frontend:shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
}

compose.desktop {
    application {
        mainClass = "com.silk.desktop.MainKt"
        
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Silk"
            packageVersion = "1.0.0"
            
            macOS {
                bundleID = "com.silk.desktop"
            }
        }
    }
}
