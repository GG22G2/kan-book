plugins {
    id("java")
//    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.7.1"
}

group = "hsb.learn"
version = "1.0"

repositories {
    // Offline: prefer the local Maven mirror
    maven { url = uri("file:///G:/kaifa_environment/maven-repository") }
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Configure IntelliJ Platform Gradle Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    intellijPlatform {
//        create("IC", "2025.1.4.1")
//        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        // Add necessary plugin dependencies for compilation here, example:
        // bundledPlugin("com.intellij.java")

        local("G:\\JetBrains\\Toolbox\\IntelliJ IDEA Ultimate")
    }
    implementation("com.google.code.gson:gson:2.10.1")
}

intellijPlatform {
    // Offline: instrumentation tools are served only from JetBrains repositories
    // Set back to true after fetching them once if instrumentation is needed
    instrumentCode = false

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "251"
            untilBuild = provider { null }
        }

        changeNotes = """
            Initial version
        """.trimIndent()
    }

    sandboxContainer = file("D:\\GradleRepo\\idea-sandbox")
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
    runIde {
        jvmArgs = listOf("-Dfile.encoding=UTF-8")
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}