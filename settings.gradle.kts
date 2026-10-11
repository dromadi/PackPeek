pluginManagement {
    repositories {
        google()
        // Same content as mavenCentral(); used first because repo.maven.apache.org often answers
        // 429 (rate limit) on shared build machines.
        maven("https://repo1.maven.org/maven2")
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven("https://repo1.maven.org/maven2")
        mavenCentral()
    }
}

rootProject.name = "PackPeek"

include(":app")
