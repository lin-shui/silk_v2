import org.jetbrains.kotlin.gradle.targets.js.npm.tasks.KotlinNpmInstallTask

plugins {
    // Kotlin 和 KMP 版本
    kotlin("jvm") version "1.9.20" apply false
    kotlin("multiplatform") version "1.9.20" apply false
    kotlin("android") version "1.9.20" apply false
    kotlin("plugin.serialization") version "1.9.20" apply false
    
    // Android
    id("com.android.application") version "8.1.4" apply false
    id("com.android.library") version "8.1.4" apply false
    
    // Compose
    id("org.jetbrains.compose") version "1.5.11" apply false
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

tasks.register("cleanAll", Delete::class) {
    delete(rootProject.buildDir)
}

// Yarn 默认会读 ~/.npmrc；若该文件属主为 root（例如误用 sudo），会导致 :kotlinNpmInstall EACCES。
// --no-default-rc 跳过全局/用户级 rc，仅凭仓库内 package.json 解析依赖（与私有 npm 镜像无关时可安全使用）。
gradle.afterProject {
    tasks.withType<KotlinNpmInstallTask>().configureEach {
        if (!args.contains("--no-default-rc")) {
            args.add("--no-default-rc")
        }
    }
}

