import java.util.zip.*
import java.io.ByteArrayOutputStream

plugins { id("com.android.application"); kotlin("android"); id("org.jetbrains.kotlin.plugin.compose"); id("com.google.devtools.ksp") }
val targetAbi=providers.gradleProperty("antisocialTargetAbi").orNull?.also {require(it in setOf("arm64-v8a","armeabi-v7a","x86_64")){"Unsupported antisocialTargetAbi"}}
val publicReadCandidate=providers.gradleProperty("antisocialPublicRead").map {it.toBooleanStrict()}.orElse(false).get()
val publicReadJni=layout.buildDirectory.dir("generated/public-read-jni")
val publicReadAssets=layout.buildDirectory.dir("generated/public-read-assets")
if(publicReadCandidate) {
    val prepare=tasks.register<Exec>("preparePublicReadCandidate") {
        inputs.files(rootProject.file("scripts/prepare_public_read_bridge.py"),rootProject.file("scripts/build_public_read.py"))
        inputs.dir(rootProject.file("native/public_read"))
        inputs.file(file(providers.gradleProperty("antisocialBuildRoot").get()+"/public-read-source-native/build.json"))
        outputs.dirs(publicReadJni,publicReadAssets)
        commandLine(listOf("python3",rootProject.file("scripts/prepare_public_read_bridge.py").path,"--output",publicReadJni.get().asFile.path,"--assets",publicReadAssets.get().asFile.path)+
            if(targetAbi!=null)listOf("--abi",targetAbi)else emptyList())
    }
    tasks.named("preBuild"){dependsOn(prepare)}
}
val torArchive by configurations.creating { isTransitive=false }
val c2paArchive=layout.buildDirectory.file("dependencies/c2pa-0.0.14.aar")
val fetchC2pa=tasks.register<Exec>("fetchC2pa") {
    inputs.file(rootProject.file("scripts/fetch_c2pa.py"))
    outputs.file(c2paArchive)
    commandLine("python3",rootProject.file("scripts/fetch_c2pa.py"),c2paArchive.get().asFile)
}
val c2paBridge=layout.buildDirectory.dir("generated/c2pa-jni")
val prepareC2paBridge=tasks.register<Exec>("prepareC2paBridge") {
    inputs.files(rootProject.file("scripts/prepare_c2pa_bridge.py"),file("src/main/cpp/c2pa_detached.c"))
    inputs.property("abi",targetAbi ?: "all")
    outputs.dir(c2paBridge)
    commandLine(listOf("python3",rootProject.file("scripts/prepare_c2pa_bridge.py").path,"--output",c2paBridge.get().asFile.path)+
        if(targetAbi!=null)listOf("--abi",targetAbi)else emptyList())
}
tasks.named("preBuild"){dependsOn(prepareC2paBridge)}
val verifyBundledFilters=tasks.register<Exec>("verifyBundledFilters") {
    workingDir(rootProject.projectDir)
    commandLine("python3","scripts/compile_filters.py","--check")
}
val verifyBranding=tasks.register<Exec>("verifyBranding") {
    workingDir(rootProject.projectDir)
    commandLine("python3","scripts/check_branding.py")
}
val aiAssets=layout.buildDirectory.dir("generated/ai-assets")
val runtimeAssets=layout.buildDirectory.dir("generated/runtime-assets")
val discoveryAssets=layout.buildDirectory.dir("generated/discovery-assets")
val prepareBundledOcr=tasks.register<Exec>("prepareBundledOcr") {
    inputs.files(rootProject.file("scripts/prepare_bundled_ocr.py"),file("src/main/context-media-catalog/catalog.json"))
    outputs.dir(discoveryAssets)
    commandLine("python3",rootProject.file("scripts/prepare_bundled_ocr.py"),"--output",discoveryAssets.get().asFile)
}
val prepareLocalRuntime=tasks.register<Exec>("prepareLocalRuntime") {
    inputs.file(rootProject.file("scripts/prepare_local_runtime.py"))
    val abis=if(targetAbi in setOf("arm64-v8a","x86_64"))listOf(targetAbi!!)else listOf("arm64-v8a","x86_64")
    inputs.files(abis.map {file("${providers.gradleProperty("antisocialBuildRoot").get()}/native-android/$it/libantisocial_local_ai.so")})
    inputs.property("abis",abis)
    outputs.dir(runtimeAssets)
    commandLine(listOf("python3",rootProject.file("scripts/prepare_local_runtime.py").path,"--output",runtimeAssets.get().dir("native-runtime").asFile.path)+abis.flatMap {listOf("--abi",it)})
}
val signAiCatalog=tasks.register<Exec>("signAiCatalog") {
    inputs.files(rootProject.file("scripts/sign_ai_catalog.py"),file("src/main/ai-catalog/catalog.json"),file("src/main/context-media-catalog/catalog.json"),file("src/main/translation-catalog/catalog.json"))
    inputs.file(System.getenv("ANTISOCIAL_SIGNING_STORE") ?: "${providers.gradleProperty("antisocialBuildRoot").get()}/android-user/debug.keystore")
    inputs.property("signingAlias",System.getenv("ANTISOCIAL_SIGNING_ALIAS") ?: "androiddebugkey")
    outputs.dir(aiAssets)
    // Sign with the same certificate as the APK; verification uses that installed certificate.
    commandLine("python3",rootProject.file("scripts/sign_ai_catalog.py"),"--output",aiAssets.get().asFile)
}
tasks.named("preBuild"){dependsOn(verifyBundledFilters)}
tasks.named("preBuild"){dependsOn(verifyBranding)}
tasks.named("preBuild"){dependsOn(signAiCatalog)}
tasks.named("preBuild"){dependsOn(prepareLocalRuntime)}
tasks.named("preBuild"){dependsOn(prepareBundledOcr)}
val torWrapperArchive=tasks.register("torWrapperArchive") {
    val output=layout.buildDirectory.file("tor/tor-android-native.aar")
    inputs.files(torArchive);outputs.file(output)
    doLast {
        val target=output.get().asFile;target.parentFile.mkdirs()
        ZipFile(torArchive.singleFile).use { source->
            ZipOutputStream(target.outputStream()).use {dest->
                source.entries().asSequence().forEach {entry->
                    dest.putNextEntry(ZipEntry(entry.name))
                    if(entry.name=="classes.jar") {
                        val buffer=ByteArrayOutputStream()
                        ZipInputStream(source.getInputStream(entry)).use {classes->ZipOutputStream(buffer).use {jar->
                            var member=classes.nextEntry
                            while(member!=null){if(!member.name.startsWith("org/torproject/jni/TorService")){jar.putNextEntry(ZipEntry(member.name));classes.copyTo(jar);jar.closeEntry()};member=classes.nextEntry}
                        }}
                        dest.write(buffer.toByteArray())
                    }else source.getInputStream(entry).use {it.copyTo(dest)}
                    dest.closeEntry()
                }
            }
        }
    }
}
android {
    namespace = "app.antisocial.android"
    compileSdk = 37
    buildToolsVersion = "36.0.0"
    defaultConfig { applicationId = "app.antisocial.android"; minSdk = 26; targetSdk = 36; versionCode = 198; versionName = "0.38.89"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    defaultConfig { if(targetAbi!=null)ndk {abiFilters+=targetAbi} }
    defaultConfig {buildConfigField("boolean","EXPERIMENTAL_PUBLIC_READ",publicReadCandidate.toString())}
    if(publicReadCandidate) {
        sourceSets.getByName("main").jniLibs.srcDir(publicReadJni.get().asFile)
        sourceSets.getByName("main").assets.srcDir(publicReadAssets.get().asFile)
    }
    sourceSets.getByName("main").assets.srcDir(aiAssets.get().asFile)
    sourceSets.getByName("main").assets.srcDir(runtimeAssets.get().asFile)
    sourceSets.getByName("main").assets.srcDir(discoveryAssets.get().asFile)
    sourceSets.getByName("main").jniLibs.srcDir(c2paBridge.get().asFile)
    // Kotlin internal symbols are variant-mangled. Compile instrumentation
    // against the actual release ABI when qualifying the shipping APK.
    testBuildType=providers.gradleProperty("antisocialTestBuildType").orElse("debug").get().also {require(it in setOf("debug","release"))}
    buildFeatures { compose = true; buildConfig = true }
    androidResources { noCompress += setOf("compiled","packed","bsdiff") }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21; isCoreLibraryDesugaringEnabled = true }
    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/LICENSE*", "META-INF/NOTICE*")
        jniLibs { useLegacyPackaging=true; excludes += "**/libantisocial_local_ai.so" }
    }
    val configuredSigner=System.getenv("ANTISOCIAL_SIGNING_STORE")?.let {path->signingConfigs.create("configured") {
        storeFile=file(path);storePassword=System.getenv("ANTISOCIAL_SIGNING_PASSWORD") ?: error("Set ANTISOCIAL_SIGNING_PASSWORD")
        keyAlias=System.getenv("ANTISOCIAL_SIGNING_ALIAS") ?: error("Set ANTISOCIAL_SIGNING_ALIAS");keyPassword=System.getenv("ANTISOCIAL_SIGNING_KEY_PASSWORD") ?: storePassword
    }}
    buildTypes {
        debug { if(configuredSigner!=null)signingConfig=configuredSigner }
        release { isMinifyEnabled = false; signingConfig = configuredSigner ?: signingConfigs.getByName("debug") }
    }
}
kotlin { jvmToolchain(21) }
dependencies {
    implementation(project(":core"))
    // Passive menu text only. Encryption/signing/crypto modules are not used.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0") { isTransitive=false }
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.room:room-runtime:2.8.3")
    implementation("androidx.room:room-ktx:2.8.3")
    ksp("androidx.room:room-compiler:2.8.3")
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.8.0")
    implementation("androidx.media3:media3-exoplayer-dash:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
    implementation("androidx.media3:media3-datasource-okhttp:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.13.2")
    // Upstream native validation is strictly offline and isolated to Android 9+.
    implementation(files(c2paArchive).builtBy(fetchC2pa))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    add("torArchive","info.guardianproject:tor-android:0.4.9.14@aar")
    implementation(files(torWrapperArchive))
    implementation("info.guardianproject:jtorctl:0.4.5.7")
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-intents:3.5.0")
    androidTestImplementation("org.jsoup:jsoup:1.21.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
