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
        // openhongguo from our fork's GitHub Release, for upstream's unmerged
        // #16 (playback from video_tab when the playback endpoints come back
        // empty, since 2026-11-24). Only that one module comes from here.
        // Move back to io.github.beilunyang:openhongguo on Maven Central once
        // upstream ships the fix.
        exclusiveContent {
            forRepository {
                ivy {
                    name = "openhongguoFork"
                    url = uri("https://github.com/kadaliao/openhongguo/releases/download")
                    patternLayout { artifact("v[revision]/[module]-[revision].[ext]") }
                    metadataSources { artifact() }
                }
            }
            filter { includeModule("com.github.kadaliao", "openhongguo") }
        }
    }
}

rootProject.name = "Kaixinguo"
include(":app")
