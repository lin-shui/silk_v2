import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.DetektCreateBaselineTask
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
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

    // Lint
    id("io.gitlab.arturbosch.detekt") version "1.23.8" apply false
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

fun Project.lintBaselineName(): String =
    if (this == rootProject) "root" else path.removePrefix(":").replace(':', '-')

val lintExcludes =
    listOf(
        "**/build/**",
        "**/bin/**",
        "**/.gradle/**",
        "**/.silk-runtime/**",
        "**/kotlin-js-store/**",
        "**/node_modules/**",
        "**/generated/**",
        "**/frontend/harmonyApp/.hvigor/**",
        "**/frontend/harmonyApp/oh_modules/**",
    )

val detektSourceDirectories =
    listOf(
        "src/main/kotlin",
        "src/test/kotlin",
        "src/commonMain/kotlin",
        "src/commonTest/kotlin",
        "src/androidMain/kotlin",
        "src/androidUnitTest/kotlin",
        "src/androidTest/kotlin",
        "src/desktopMain/kotlin",
        "src/desktopTest/kotlin",
        "src/jsMain/kotlin",
        "src/jsTest/kotlin",
    )

subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")

    configure<DetektExtension> {
        toolVersion = "1.23.8"
        buildUponDefaultConfig = true
        allRules = false
        parallel = true
        config.setFrom(rootProject.files("config/lint/detekt.yml"))
        baseline = rootProject.file("config/lint/detekt/${lintBaselineName()}.xml")
        basePath = rootProject.projectDir.absolutePath
        source.setFrom(files(detektSourceDirectories.map { file(it) }))
    }

    tasks.withType<Detekt>().configureEach {
        jvmTarget = "17"
        lintExcludes.forEach { exclude(it) }
        reports {
            xml.required.set(true)
            html.required.set(true)
            sarif.required.set(true)
            md.required.set(false)
        }
    }

    tasks.withType<DetektCreateBaselineTask>().configureEach {
        jvmTarget = "17"
        lintExcludes.forEach { exclude(it) }
    }
}

val detektTasks = subprojects.map { it.tasks.named("detekt") }
val detektBaselineTasks = subprojects.map { it.tasks.named("detektBaseline") }

tasks.register<Exec>("silkScriptLint") {
    group = "verification"
    description = "Checks repository shell entrypoint syntax."
    commandLine("bash", "-n", "silk.sh")
}

tasks.register("silkLint") {
    group = "verification"
    description = "Runs Silk's fast Kotlin static analysis and script lint checks."
    dependsOn("silkScriptLint")
    dependsOn(detektTasks)
}

tasks.register("silkLintBaseline") {
    group = "verification"
    description = "Regenerates detekt baselines for existing findings."
    dependsOn(detektBaselineTasks)
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
