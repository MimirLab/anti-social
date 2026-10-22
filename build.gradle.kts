plugins {
    id("com.android.application") version "9.1.1" apply false
    kotlin("android") version "2.2.21" apply false
    kotlin("jvm") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
    id("com.google.devtools.ksp") version "2.2.21-2.0.4" apply false
}
val externalBuild = providers.gradleProperty("antisocialBuildRoot").orElse("/run/media/red/RedtopStorage/DarwinBuild/LiberBuild")
allprojects { layout.buildDirectory.set(file("${externalBuild.get()}/outputs/${project.name}")) }
