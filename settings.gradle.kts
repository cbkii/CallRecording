pluginManagement {
    repositories {
        google {
            content {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        val agpVersion = "9.1.1"
        id("com.android.application") version agpVersion
        id("com.android.library") version agpVersion
        id("com.android.settings") version agpVersion
    }
}

plugins {
    id("com.android.settings")
}

android {
    minSdk = 28
    targetSdk = 37
    compileSdk = 37
    ndkVersion = "29.0.14206865"
    buildToolsVersion = "37.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        maven("https://api.xposed.info")
    }
}
include(":app")
