import java.io.ByteArrayOutputStream
import javax.inject.Inject
import org.gradle.process.ExecOperations

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
    alias(libs.plugins.roborazzi)
}

// Release signing on your own computer comes from the environment (D4). Without
// RELEASE_KEYSTORE_PATH, assembleRelease is unsigned. CI doesn't sign here: its build never
// sees the key, and a separate job signs the APK (.github/workflows/ci.yml).
val releaseKeystore: String? = providers.environmentVariable("RELEASE_KEYSTORE_PATH").orNull

/** Runs git in the repository: null without git, or outside a checkout (a source archive). */
abstract class Git : ValueSource<String, Git.Parameters> {
    interface Parameters : ValueSourceParameters {
        val directory: DirectoryProperty
        val arguments: ListProperty<String>
    }

    @get:Inject abstract val exec: ExecOperations

    override fun obtain(): String? = runCatching {
        val out = ByteArrayOutputStream()
        val result = exec.exec {
            workingDir = parameters.directory.get().asFile
            commandLine(listOf("git") + parameters.arguments.get())
            standardOutput = out
            errorOutput = ByteArrayOutputStream()
            isIgnoreExitValue = true
        }
        out.toString().trim().takeIf { result.exitValue == 0 && it.isNotEmpty() }
    }.getOrNull()
}

fun git(vararg arguments: String): Provider<String> = providers.of(Git::class.java) {
    parameters.directory.set(rootProject.layout.projectDirectory)
    parameters.arguments.set(arguments.toList())
}

// Release builds' version. A tag's build is named after the tag: CI passes v1.2.0 as
// ACTION_CENTER_VERSION, and the app says 1.2.0. Other builds are named by `git describe`:
// 1.2.0-3-gabc1234, three commits after v1.2.0, or dev-abc1234 before the first tag.
val releaseVersionName: Provider<String> = providers.environmentVariable("ACTION_CENTER_VERSION")
    .filter { it.isNotBlank() }
    .map { it.removePrefix("v") }
    .orElse(git("describe", "--tags", "--match", "v[0-9]*", "--always").map { if (it.startsWith("v")) it.removePrefix("v") else "dev-$it" })
    .orElse("dev")

// The version code is the minutes from 2026-01-01 UTC to the commit, so a later commit always
// installs over an earlier one, whichever repository built it. It runs out in the year 6000.
val releaseVersionCode: Provider<Int> = git("log", "-1", "--format=%ct")
    .map { ((it.toLong() - 1_767_225_600L) / 60).toInt().coerceAtLeast(1) }
    .orElse(1)

// Where a release build looks for its own updates: a repository's GitHub releases
// (docs/releasing.md, update.json). CI's build has GITHUB_REPOSITORY, so a fork's build checks
// the fork; ACTION_CENTER_UPDATE_REPO overrides it. A release build without either, and every
// debug build, never checks. ACTION_CENTER_UPDATE_BASE_URL replaces https://github.com, for a
// test on an emulator only: an http:// one lets that build use plain HTTP. CI never sets it.
val updateRepo: String = (
    providers.environmentVariable("ACTION_CENTER_UPDATE_REPO").orNull?.takeIf { it.isNotBlank() }
        ?: providers.environmentVariable("GITHUB_REPOSITORY").orNull.orEmpty()
    ).takeIf { Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(it) }.orEmpty()
val updateBaseUrl: String = providers.environmentVariable("ACTION_CENTER_UPDATE_BASE_URL").orNull
    ?.takeIf { Regex("https?://[A-Za-z0-9.:-]+").matches(it) } ?: "https://github.com"

android {
    namespace = "com.phonoodlesramen.actioncenter"
    // Compose 1.12 (BOM 2026.09.00) requires compileSdk 37. The app only ever runs on
    // API 30 (SHIELD Experience 9.2.x), so newer platform behaviour (targetSdk 37)
    // never applies.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.phonoodlesramen.actioncenter"
        minSdk = 30
        targetSdk = 37
        // Release builds get theirs from git (above). Debug builds keep these, so a new commit
        // doesn't change their tests' inputs.
        versionCode = 1
        versionName = "dev"
        // Debug builds never update themselves; the release type sets these (above).
        buildConfigField("String", "UPDATE_REPO", "\"\"")
        buildConfigField("String", "UPDATE_BASE_URL", "\"https://github.com\"")
        manifestPlaceholders["usesCleartextTraffic"] = "false"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.environmentVariable("RELEASE_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("RELEASE_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("RELEASE_KEY_PASSWORD").get()
            }
        }
    }

    // NextLib's FFmpeg (G15) is about 8 MB of native code per ABI. The Shield is
    // arm64-v8a; many Google TV sets are 32-bit only (armeabi-v7a), so release
    // builds carry both. Debug builds keep x86 for the Android TV emulator.
    buildTypes {
        debug {
            ndk { abiFilters += listOf("arm64-v8a", "x86") }
        }
        release {
            isMinifyEnabled = false
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
            buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
            buildConfigField("String", "UPDATE_BASE_URL", "\"$updateBaseUrl\"")
            manifestPlaceholders["usesCleartextTraffic"] = updateBaseUrl.startsWith("http://").toString()
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        // Robolectric needs the merged manifest and resources.
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        // The ABI filters above leave out x86_64: this app only ever runs on the Shield.
        disable += "ChromeOsAbiSupport"
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.outputs.forEach { output ->
            output.versionName.set(releaseVersionName)
            output.versionCode.set(releaseVersionCode)
        }
    }
}

// The app's own databases' schemas (usage, profiles, suggestions), versioned in the repo so
// migrations can be tested. The guide's and the Plex cache's are in :guide-core.
room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    // The guide engine, its Plex client and the Plex cache (Phase 8).
    implementation(project(":guide-core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.nextlib.media3ext)
    implementation(libs.qrcodegen)
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.tv.material)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
}
