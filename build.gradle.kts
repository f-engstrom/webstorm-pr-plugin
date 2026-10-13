plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.intellij.platform")
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        // Build and run against the locally installed WebStorm instead of downloading one.
        local(providers.gradleProperty("platformLocalPath").get())
        bundledPlugin("Git4Idea")
        // Since 2026.x the DVCS base classes (GitRepository supertypes) are a separate platform module.
        bundledModule("intellij.platform.vcs.dvcs")
        bundledModule("intellij.platform.vcs.dvcs.impl")
    }
}

intellijPlatform {
    instrumentCode = false
    buildSearchableOptions = false

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"
            untilBuild = provider { null }
        }
    }
}

tasks.named<org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask>("runIde") {
    // Poll for PR activity every 30 s in the sandbox, instead of every 5 minutes, to test notifications.
    jvmArgumentProviders += CommandLineArgumentProvider { listOf("-DadoPrReview.pollIntervalSeconds=30") }
}
