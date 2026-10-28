plugins {
    id("com.android.application")
    id("kotlin-parcelize")
    alias(libs.plugins.protobuf)
    alias(libs.plugins.ksp)
    alias(libs.plugins.aboutlibraries)
}

setupApp()

android {
    namespace = "io.nekohasekai.sagernet"

    // The core AAR (built by gomobile on CI) carries libgojni.so under
    // jni/<abi>/. AGP does not reliably merge natives of file-dependency AARs
    // (merged_native_libs blame only ever lists maven deps), so extract the .so
    // from the AAR into a build-dir jniLibs folder and let the regular project
    // jniLibs pipeline pick it up. Keeps the core single-sourced in the AAR.
    // "libs/**" is kept as a fallback for hand-repacked AARs.
    packaging {
        jniLibs.pickFirsts.add("**/libgojni.so")
    }
    sourceSets.getByName("main") {
        jniLibs.srcDir(layout.buildDirectory.dir("extracted-core-jniLibs").get().asFile)
    }
}

val extractCoreJniLibs = tasks.register<Copy>("extractCoreJniLibs") {
    from(zipTree(file("libs/libexclavecore.aar"))) {
        include("jni/**/libgojni.so")
        include("libs/**/libgojni.so")
        // flatten <dir>/<abi>/libgojni.so -> <abi>/libgojni.so
        eachFile { relativePath = RelativePath(true, *relativePath.segments.drop(1).toTypedArray()) }
        includeEmptyDirs = false
    }
    into(layout.buildDirectory.dir("extracted-core-jniLibs"))
}

tasks.whenTaskAdded {
    if (name.startsWith("merge") && name.endsWith("JniLibFolders")) {
        dependsOn(extractCoreJniLibs)
    }
}

ksp {
    arg("room.incremental", "true")
    arg("room.schemaLocation", "$projectDir/schemas")
}

aboutLibraries {
    offlineMode = true
    collect {
        configPath = file("src/main/aboutlibraries/config")
        includePlatform = true
    }
    export {
        excludeFields.addAll("name", "description", "developers", "funding", "licenses", "organization", "scm", "website", "License")
        prettyPrint = true
    }
    exports {
        create("ossRelease") {
            outputFile = file("src/main/aboutlibraries/aboutlibraries.json")
        }
        create("legacyRelease") {
            outputFile = file("src/main/aboutlibraries/aboutlibraries_legacy.json")
        }
    }
}

dependencies {
    implementation(fileTree("libs"))
    implementation(project(":plugin:api"))
    implementation(project(":library:proto-stub"))
    implementation(libs.kotlinx.coroutines.android)
    "ossImplementation"(libs.core.ktx)
    "ossImplementation"(libs.activity.ktx)
    "ossImplementation"(libs.fragment.ktx)
    "ossImplementation"(libs.camera.view)
    "ossImplementation"(libs.camera.lifecycle)
    "ossImplementation"(libs.camera.camera2)
    implementation(libs.swiperefreshlayout)
    "ossImplementation"(libs.appcompat)
    implementation(libs.preference)
    implementation(libs.flexbox)
    "ossImplementation"(libs.work.runtime.ktx)
    "ossImplementation"(libs.work.multiprocess)
    "ossImplementation"(libs.room.runtime)
    "kspOss"(libs.room.compiler)
    "ossImplementation"(libs.room.ktx)
    "ossImplementation"(libs.material)
    implementation(libs.gson)
    implementation(libs.bouncycastle)
    implementation(libs.zxing.core)
    implementation(libs.snakeyaml)
    implementation(libs.material.about.library)
    implementation(libs.process.phoenix)
    implementation(libs.kryo)
    implementation(libs.jini.lib)
    implementation(libs.markwon.core)
    implementation(libs.recyclerview.fastscroll) {
        exclude(group = "androidx.recyclerview")
        exclude(group = "androidx.appcompat")
    }
    implementation(libs.editorkit)
    implementation(libs.editorkit.language.json)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    "legacyImplementation"(libs.core.ktx.minSdk21)
    "legacyImplementation"(libs.activity.ktx.minSdk21)
    "legacyImplementation"(libs.fragment.ktx.minSdk21)
    "legacyImplementation"(libs.camera.view.minSdk21)
    "legacyImplementation"(libs.camera.lifecycle.minSdk21)
    "legacyImplementation"(libs.camera.camera2.minSdk21)
    "legacyImplementation"(libs.appcompat.minSdk21)
    "legacyImplementation"(libs.work.runtime.ktx.minSdk21)
    "legacyImplementation"(libs.work.multiprocess.minSdk21)
    "legacyImplementation"(libs.room.runtime.minSdk21)
    "kspLegacy"(libs.room.compiler.minSdk21)
    "legacyImplementation"(libs.room.ktx.minSdk21)
    "legacyImplementation"(libs.material.minSdk21)
}
