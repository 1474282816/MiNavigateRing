pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MiNavigateRingProbe"

include(":app")

// Android SDK location. local.properties stays out of version control.
run {
    val localProperties = file("local.properties")
    if (!localProperties.exists()) {
        val candidate = System.getenv("ANDROID_HOME")?.takeIf { it.isNotBlank() }
            ?: System.getenv("ANDROID_SDK_ROOT")?.takeIf { it.isNotBlank() }
            ?: throw org.gradle.api.GradleException("Android SDK 未找到：请设置 ANDROID_HOME，或在仓库根目录创建 local.properties 并写入 sdk.dir=…")
        val sdkDir = candidate.replace('\\', '/')
        localProperties.writeText("sdk.dir=$sdkDir\n")
    }
}
