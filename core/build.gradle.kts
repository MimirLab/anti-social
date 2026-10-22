import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

plugins { kotlin("jvm") }
kotlin { jvmToolchain(21) }
sourceSets.test { resources.srcDir("../app/src/main/assets") }
tasks.processTestResources {
    from("../app/src/main/context-media-catalog/catalog.json") { into("bundled-catalogs/context-media") }
    from("../app/src/main/translation-catalog/catalog.json") { into("bundled-catalogs/translation") }
}
dependencies {
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("net.sf.biweekly:biweekly:0.6.8")
    implementation("net.dankito.readability4j:readability4j:1.0.8")
    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.26.5")
    implementation("org.mozilla:rhino:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("org.jsoup:jsoup:1.21.2")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
tasks.test { systemProperty("antisocial.native.path", "${providers.gradleProperty("antisocialBuildRoot").get()}/native-host/libantisocial_filter.so") }
tasks.register<JavaExec>("publicArticleAudit") {
    dependsOn(tasks.testClasses)
    classpath=sourceSets.test.get().runtimeClasspath
    mainClass.set("app.antisocial.core.PublicArticleAuditKt")
    systemProperty("antisocial.native.path","${providers.gradleProperty("antisocialBuildRoot").get()}/native-host/libantisocial_filter.so")
    systemProperty("antisocial.articleAuditOutput",providers.gradleProperty("antisocialArticleAuditOutput").getOrElse("${providers.gradleProperty("antisocialBuildRoot").get()}/inference-kv/darwin-article-routes/live.json"))
    providers.gradleProperty("antisocialArticleAuditUrls").orNull?.let {systemProperty("antisocial.articleAuditUrls",it)}
}
tasks.register("prepareContextPipelineAudit") {
    dependsOn(tasks.testClasses)
    doLast {
        val root=file(providers.gradleProperty("antisocialBuildRoot").get()).canonicalFile
        val out=file(providers.gradleProperty("antisocialPipelineOutput").get()).canonicalFile
        require(out.path.startsWith(root.path+File.separator)) { "Keep evidence under LiberBuild" }
        require(!out.exists()) { "Refusing to overwrite pipeline evidence" }
        out.mkdirs()
        val copies=sourceSets.test.get().runtimeClasspath.files.filter { it.exists() }.mapIndexed { index, source ->
            val target=File(out,"classpath/$index-${if(source.isDirectory) source.name+".jar" else source.name}")
            target.parentFile.mkdirs()
            if(source.isDirectory) JarOutputStream(target.outputStream()).use { jar ->
                source.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(source).invariantSeparatorsPath }.forEach { input ->
                    jar.putNextEntry(JarEntry(input.relativeTo(source).invariantSeparatorsPath).apply { time=0 })
                    input.inputStream().use { it.copyTo(jar) };jar.closeEntry()
                }
            } else source.copyTo(target)
            target
        }
        File(out,"classpath.txt").writeText(copies.joinToString(File.pathSeparator) { it.path })
        File(out,"java.txt").writeText(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }.get().executablePath.asFile.path)
    }
}
tasks.register<JavaExec>("contextRouterAudit") {
    dependsOn(tasks.testClasses)
    classpath=sourceSets.test.get().runtimeClasspath
    mainClass.set("app.antisocial.core.ContextRouterAuditKt")
    systemProperty("antisocialBuildRoot", providers.gradleProperty("antisocialBuildRoot").get())
    systemProperty("antisocial.routerAuditMode", providers.gradleProperty("antisocialRouterAuditMode").getOrElse("2B"))
    listOf("Output", "Binary", "Backend", "Cache", "Position", "Capacity", "Cases", "Fixture", "Summary", "Threads").forEach { option ->
        providers.gradleProperty("antisocialRouterAudit$option").orNull?.let {
            systemProperty("antisocial.routerAudit$option", it)
        }
    }
}
tasks.register<JavaExec>("youtubeAudit") {
    dependsOn(tasks.testClasses)
    classpath=sourceSets.test.get().runtimeClasspath
    mainClass.set("app.antisocial.core.YouTubeAuditKt")
    systemProperty("antisocial.native.path", "${providers.gradleProperty("antisocialBuildRoot").get()}/native-host/libantisocial_filter.so")
    systemProperty("antisocial.evidenceRoot", providers.gradleProperty("antisocialYouTubeEvidence").getOrElse("${providers.gradleProperty("antisocialBuildRoot").get()}/evidence/youtube-and-post-details-2026-10-18/gateway-live"))
    systemProperty("antisocial.youtubeAuditMode", providers.gradleProperty("antisocialYouTubeAuditMode").getOrElse("all"))
    systemProperty("antisocial.youtubeRecoveryId", providers.gradleProperty("antisocialYouTubeRecoveryId").getOrElse("jNQXAC9IVRw"))
}
tasks.register<JavaExec>("extractionAudit") {
    dependsOn(tasks.testClasses)
    classpath=sourceSets.test.get().runtimeClasspath
    mainClass.set("app.antisocial.core.ExtractionAuditKt")
    systemProperty("antisocial.extractionInput",providers.gradleProperty("antisocialExtractionInput").getOrElse("${providers.gradleProperty("antisocialBuildRoot").get()}/evidence/source-audit/twitter-nitter.tiekoetter.com.txt"))
    systemProperty("antisocial.extractionTarget",providers.gradleProperty("antisocialExtractionTarget").getOrElse("https://x.com/NASA"))
    systemProperty("antisocial.extractionFrontend",providers.gradleProperty("antisocialExtractionFrontend").getOrElse("nitter"))
    systemProperty("antisocial.extractionRoot",providers.gradleProperty("antisocialExtractionRoot").getOrElse("https://nitter.tiekoetter.com"))
    systemProperty("antisocial.extractionOutput",providers.gradleProperty("antisocialExtractionOutput").getOrElse("${providers.gradleProperty("antisocialBuildRoot").get()}/evidence/extraction-audit.json"))
}
tasks.register<JavaExec>("linkAudit") {
    dependsOn(tasks.testClasses)
    classpath=sourceSets.test.get().runtimeClasspath
    mainClass.set("app.antisocial.core.LinkAuditKt")
    systemProperty("antisocial.linkAuditOutput", providers.gradleProperty("antisocialLinkAuditOutput").getOrElse("${providers.gradleProperty("antisocialBuildRoot").get()}/evidence/link-audit.json"))
}
tasks.register<JavaExec>("filterAudit") {
    dependsOn(tasks.testClasses)
    classpath=sourceSets.test.get().runtimeClasspath
    mainClass.set("app.antisocial.core.FilterAuditKt")
    systemProperty("antisocial.native.path", "${providers.gradleProperty("antisocialBuildRoot").get()}/native-host/libantisocial_filter.so")
    systemProperty("antisocial.filterAuditOutput", "${providers.gradleProperty("antisocialBuildRoot").get()}/evidence/filter-audit.json")
}
tasks.register<JavaExec>("liveSmoke") {
    dependsOn(tasks.testClasses)
    classpath=sourceSets.test.get().runtimeClasspath
    mainClass.set("app.antisocial.core.LiveSmokeKt")
    systemProperty("antisocial.evidenceRoot", "${providers.gradleProperty("antisocialBuildRoot").get()}/evidence/live")
    systemProperty("antisocial.native.path", "${providers.gradleProperty("antisocialBuildRoot").get()}/native-host/libantisocial_filter.so")
}
tasks.register<JavaExec>("sourceAudit") {
    dependsOn(tasks.testClasses)
    classpath=sourceSets.test.get().runtimeClasspath
    mainClass.set("app.antisocial.core.SourceAuditKt")
    systemProperty("antisocial.auditPlatforms", providers.gradleProperty("antisocialAuditPlatforms").getOrElse("tiktok,instagram,pinterest,ifunny,threads,twitter"))
    systemProperty("antisocial.auditRoots", providers.gradleProperty("antisocialAuditRoots").getOrElse(""))
    systemProperty("antisocial.auditTarget", providers.gradleProperty("antisocialAuditTarget").getOrElse(""))
    systemProperty("antisocial.auditAdapter", providers.gradleProperty("antisocialAuditAdapter").getOrElse("false"))
    systemProperty("antisocial.auditOriginal", providers.gradleProperty("antisocialAuditOriginal").getOrElse("false"))
    systemProperty("antisocial.evidenceRoot", "${providers.gradleProperty("antisocialBuildRoot").get()}/evidence/source-audit")
    systemProperty("antisocial.native.path", "${providers.gradleProperty("antisocialBuildRoot").get()}/native-host/libantisocial_filter.so")
}
tasks.register<JavaExec>("networkAudit") {
    dependsOn(tasks.testClasses)
    classpath=sourceSets.test.get().runtimeClasspath
    mainClass.set("app.antisocial.core.NetworkAuditKt")
    systemProperty("antisocial.auditUrls",providers.gradleProperty("antisocialAuditUrls").getOrElse(""))
    systemProperty("antisocial.evidenceRoot",providers.gradleProperty("antisocialAuditEvidence").getOrElse("${providers.gradleProperty("antisocialBuildRoot").get()}/evidence/network-audit"))
    systemProperty("antisocial.native.path", "${providers.gradleProperty("antisocialBuildRoot").get()}/native-host/libantisocial_filter.so")
}
tasks.register<JavaExec>("sourceCartographyAudit") {
    dependsOn(tasks.testClasses)
    classpath=sourceSets.test.get().runtimeClasspath
    mainClass.set("app.antisocial.core.SourceCartographyAuditKt")
    systemProperty("antisocial.native.path","${providers.gradleProperty("antisocialBuildRoot").get()}/native-host/libantisocial_filter.so")
    systemProperty("antisocial.cartographyInput",providers.gradleProperty("antisocialCartographyInput").get())
    systemProperty("antisocial.cartographyOutput",providers.gradleProperty("antisocialCartographyOutput").get())
}
tasks.register<JavaExec>("publicSocialAudit") {
    dependsOn(tasks.testClasses)
    classpath=sourceSets.test.get().runtimeClasspath
    mainClass.set("app.antisocial.core.PublicSocialAuditKt")
    systemProperty("antisocial.evidenceRoot",providers.gradleProperty("antisocialPublicSocialEvidence").getOrElse("${providers.gradleProperty("antisocialBuildRoot").get()}/evidence/public-social-adapters"))
    systemProperty("antisocial.auditPlatforms",providers.gradleProperty("antisocialAuditPlatforms").getOrElse(""))
    systemProperty("antisocial.native.path","${providers.gradleProperty("antisocialBuildRoot").get()}/native-host/libantisocial_filter.so")
}

tasks.register<JavaExec>("archiveAudit") {
    dependsOn(tasks.testClasses)
    classpath=sourceSets.test.get().runtimeClasspath
    mainClass.set("app.antisocial.core.ArchiveAuditKt")
    systemProperty("antisocial.native.path", "${providers.gradleProperty("antisocialBuildRoot").get()}/native-host/libantisocial_filter.so")
    systemProperty("antisocial.archiveEvidence",providers.gradleProperty("antisocialArchiveEvidence").getOrElse("${providers.gradleProperty("antisocialBuildRoot").get()}/evidence/archive-history-2026-10-16/reader-live"))
    systemProperty("antisocial.archiveRecovery",providers.gradleProperty("antisocialArchiveRecovery").getOrElse("false"))
}
