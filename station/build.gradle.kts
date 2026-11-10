import javax.inject.Inject
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The guide station (Phase 8): a plain JVM service on :guide-core, run in Docker on the NAS
// (Java 21 there; built for 17 like the rest, so CI's JDK 17 compiles and tests it).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // Against Java 17's API too, so a newer local JDK can't let in a call CI's 17 lacks
        // (Java 21's virtual threads are looked up at run time instead).
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    mainClass.set("com.phonoodlesramen.actioncenter.station.MainKt")
    // The bundled SQLite loads a native library: JDK 22+ warns unless native access is on.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

dependencies {
    implementation(project(":guide-core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.androidx.sqlite.bundled)
    implementation(libs.bouncycastle.bcpkix)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

tasks.withType<Test>().configureEach {
    if (JavaVersion.current() >= JavaVersion.VERSION_22) jvmArgs("--enable-native-access=ALL-UNNAMED")
}

/**
 * The bundled SQLite's Linux x64 library, out of its jar: the image puts it in `/usr/lib`,
 * where `System.loadLibrary` finds it, so the read-only container never unpacks it into /tmp.
 */
abstract class ExtractSqliteNative : DefaultTask() {
    @get:InputFiles
    abstract val classpath: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val archives: ArchiveOperations

    @get:Inject
    abstract val files: FileSystemOperations

    @TaskAction
    fun extract() {
        val jar = classpath.files.single { it.name.startsWith("sqlite-bundled-jvm") }
        files.sync {
            from(archives.zipTree(jar)) {
                include("natives/linux_x64/libsqliteJni.so")
                eachFile { path = name }
                includeEmptyDirs = false
            }
            into(outputDir)
        }
    }
}

val extractSqliteNative = tasks.register<ExtractSqliteNative>("extractSqliteNative") {
    classpath.from(configurations.runtimeClasspath)
    outputDir.set(layout.buildDirectory.dir("sqlite-native"))
}

// installDist: lib/ (the jars) and native/libsqliteJni.so, which station/Dockerfile copies.
distributions {
    main {
        contents {
            from(extractSqliteNative) { into("native") }
        }
    }
}
