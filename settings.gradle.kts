pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral(); maven("https://jitpack.io"); maven("https://raw.githubusercontent.com/guardianproject/gpmaven/master") } }
rootProject.name = "AntiSocial"
include(":core", ":app")
