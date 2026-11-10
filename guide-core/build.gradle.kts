import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The Plex Guide's engine, its Plex client and the Plex cache (Phase 8): shared by :app on
// the TVs and, from step 4, the guide station on the NAS (JVM). AGP 9 gives a KMP module its
// Android target through its own plugin; :app keeps AGP's built-in Kotlin.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    // Without it the module has no `lint` task, so CI's `lint` would skip it.
    alias(libs.plugins.android.lint)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
}

kotlin {
    android {
        namespace = "com.phonoodlesramen.actioncenter.guidecore"
        compileSdk = 37
        minSdk = 30
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    // Room's `expect object …Constructor` (@ConstructedBy) is an expect class (Beta).
    compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }

    sourceSets {
        commonMain.dependencies {
            // api: the databases extend RoomDatabase, so callers need Room on their classpath.
            api(libs.androidx.room.runtime)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.okhttp)
            implementation(libs.kotlinx.serialization.json)
        }
        // The bundled SQLite is the station's driver only: on the TVs Room keeps the
        // platform's SQLite, so the APK carries no extra native library.
        jvmMain.dependencies {
            implementation(libs.androidx.sqlite.bundled)
        }
        jvmTest.dependencies {
            implementation(libs.junit)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.okhttp.mockwebserver)
        }
    }
}

// Room's compiler runs once per target; there is no common-metadata pass.
dependencies {
    add("kspAndroid", libs.androidx.room.compiler)
    add("kspJvm", libs.androidx.room.compiler)
}

// GuideDatabase's and PlexDatabase's schemas (moved from app/schemas, identity hashes
// unchanged): a later version migrates instead of starting over.
room {
    schemaDirectory("$projectDir/schemas")
}

// The bundled SQLite loads a native library: newer JDKs warn unless native access is on
// (local builds run on Android Studio's JDK 25; CI on 17).
tasks.withType<Test>().configureEach {
    if (JavaVersion.current() >= JavaVersion.VERSION_22) jvmArgs("--enable-native-access=ALL-UNNAMED")
}
