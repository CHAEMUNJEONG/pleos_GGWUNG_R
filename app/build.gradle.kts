import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val isWindows = System.getProperty("os.name").lowercase().contains("win")
val cargoExeName = if (isWindows) "cargo.exe" else "cargo"
val serverBinName = if (isWindows) "kkwongal-server.exe" else "kkwongal-server"
val rustDir = File(rootProject.projectDir, "rust")
val generatedJniDir = layout.buildDirectory.dir("generated/rustJniLibs")

fun resolveCargoExecutable(): String {
    val candidates = buildList {
        System.getenv("CARGO")?.takeIf { it.isNotBlank() }?.let { add(File(it)) }
        System.getenv("CARGO_HOME")?.takeIf { it.isNotBlank() }?.let { add(File(it, "bin/$cargoExeName")) }
        System.getProperty("user.home")?.takeIf { it.isNotBlank() }?.let { add(File(it, ".cargo/bin/$cargoExeName")) }
        (System.getenv("PATH") ?: "").split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .forEach { add(File(it, cargoExeName)) }
    }
    return candidates.firstOrNull { it.isFile }?.absolutePath ?: cargoExeName
}

fun resolveSdkDirFromLocalProperties(): File? {
    val propsFile = File(rootProject.projectDir, "local.properties")
    if (!propsFile.isFile) return null
    val props = Properties()
    propsFile.inputStream().use { props.load(it) }
    return props.getProperty("sdk.dir")?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isDirectory }
}

fun resolveAndroidNdkDir(): File {
    val direct = listOfNotNull(
        System.getenv("ANDROID_NDK_HOME"),
        System.getenv("ANDROID_NDK_ROOT"),
        System.getenv("NDK_HOME"),
    ).map(::File).firstOrNull { it.isDirectory }
    if (direct != null) return direct

    val sdkRoots = listOfNotNull(
        System.getenv("ANDROID_HOME")?.let(::File),
        System.getenv("ANDROID_SDK_ROOT")?.let(::File),
        resolveSdkDirFromLocalProperties(),
    ).filter { it.isDirectory }

    for (sdk in sdkRoots) {
        val ndkRoot = File(sdk, "ndk")
        val latest = ndkRoot.listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name }
        if (latest != null) return latest
        val bundle = File(sdk, "ndk-bundle")
        if (bundle.isDirectory) return bundle
    }
    error(
        "Android NDK not found. Set ANDROID_NDK_HOME or install NDK under ANDROID_HOME/ndk " +
            "(or sdk.dir in local.properties).",
    )
}

fun resolveNdkToolchainBin(ndkDir: File): File {
    val prebuiltRoot = File(ndkDir, "toolchains/llvm/prebuilt")
    val hostDir = prebuiltRoot.listFiles()?.firstOrNull { it.isDirectory }
        ?: error("NDK LLVM prebuilt toolchain not found under $prebuiltRoot")
    return File(hostDir, "bin")
}

val cargoBuildHost by tasks.registering(Exec::class) {
    group = "rust"
    description = "Builds host Rust JNI library and weekly league server for unit/contract tests."
    workingDir = rustDir
    inputs.file(File(rustDir, "Cargo.toml"))
    inputs.file(File(rustDir, "Cargo.lock"))
    inputs.dir(File(rustDir, "crates"))
    outputs.file(File(rustDir, "target/release/$serverBinName"))
    commandLine(
        resolveCargoExecutable(),
        "build",
        "-p",
        "kkwongal-jni",
        "-p",
        "kkwongal-server",
        "--release",
    )
}

val cargoBuildAndroid by tasks.registering {
    group = "rust"
    description = "Cross-compiles Rust JNI library (libkkwongal_jni.so) for x86_64 and arm64-v8a via Android NDK."
    inputs.file(File(rustDir, "Cargo.toml"))
    inputs.file(File(rustDir, "Cargo.lock"))
    inputs.dir(File(rustDir, "crates"))
    outputs.dir(generatedJniDir)

    doLast {
        val cargo = resolveCargoExecutable()
        val ndkDir = resolveAndroidNdkDir()
        val binDir = resolveNdkToolchainBin(ndkDir)
        val clangSuffix = if (isWindows) ".cmd" else ""
        val arSuffix = if (isWindows) ".exe" else ""
        val x86Linker = File(binDir, "x86_64-linux-android28-clang$clangSuffix").absolutePath
        val armLinker = File(binDir, "aarch64-linux-android28-clang$clangSuffix").absolutePath
        val llvmAr = File(binDir, "llvm-ar$arSuffix").absolutePath
        val pageSizeFlag = "-C link-arg=-Wl,-z,max-page-size=16384"

        val targets = listOf(
            "x86_64-linux-android" to "x86_64",
            "aarch64-linux-android" to "arm64-v8a",
        )
        for ((rustTarget, androidAbi) in targets) {
            exec {
                workingDir = rustDir
                environment("ANDROID_NDK_HOME", ndkDir.absolutePath)
                environment("CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER", x86Linker)
                environment("CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER", armLinker)
                environment("CARGO_TARGET_X86_64_LINUX_ANDROID_RUSTFLAGS", pageSizeFlag)
                environment("CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS", pageSizeFlag)
                environment("CC_x86_64_linux_android", x86Linker)
                environment("CC_aarch64_linux_android", armLinker)
                environment("AR_x86_64_linux_android", llvmAr)
                environment("AR_aarch64_linux_android", llvmAr)
                commandLine(cargo, "build", "-p", "kkwongal-jni", "--release", "--target", rustTarget)
            }
            val builtSo = File(rustDir, "target/$rustTarget/release/libkkwongal_jni.so")
            val abiOutDir = File(generatedJniDir.get().asFile, androidAbi)
            abiOutDir.mkdirs()
            builtSo.copyTo(File(abiOutDir, "libkkwongal_jni.so"), overwrite = true)
        }
    }
}

android {
    namespace = "com.kkwongal.drive"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kkwongal.drive"
        minSdk = 28 // 공모전 요구사항: API 28 이상
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        val leagueUrl = providers.gradleProperty("LEAGUE_API_BASE_URL").orElse("").get()
        require(leagueUrl.none { it == '\"' || it == '\\' || it.isISOControl() }) { "Invalid API URL" }
        buildConfigField("String", "LEAGUE_API_BASE_URL", "\"$leagueUrl\"")
        buildConfigField("boolean", "ALLOW_LOCAL_LEAGUE_HTTP", "false")
    }

    // 안드로이드 차량용 표준 API (속도, 기어, 주행 중 화면 제한)
    useLibrary("android.car")

    buildTypes {
        debug {
            buildConfigField(
                "boolean",
                "ALLOW_LOCAL_LEAGUE_HTTP",
                providers.gradleProperty("LEAGUE_LOCAL_HTTP").orElse("false").get().toBooleanStrict().toString(),
            )
        }
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets {
        getByName("main") {
            jniLibs.srcDir(generatedJniDir)
        }
    }
    testOptions {
        unitTests.all {
            it.dependsOn(cargoBuildHost)
            val rustReleaseDir = File(rustDir, "target/release").canonicalFile
            val existingPath = System.getProperty("java.library.path") ?: ""
            it.systemProperty(
                "java.library.path",
                rustReleaseDir.absolutePath + File.pathSeparator + existingPath,
            )
            it.environment("PLEOS_BACKEND_RUST", File(rustReleaseDir, serverBinName).absolutePath)
        }
    }
}

tasks.matching { it.name == "mergeDebugJniLibFolders" || it.name == "mergeReleaseJniLibFolders" }.configureEach {
    dependsOn(cargoBuildAndroid)
}

dependencies {
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(libs.pleos.navihelper)
    // 핸들 각도 (급조향 감지)
    implementation(libs.pleos.vehicle)
    // 감점 음성 안내 (Pleos Gleo AI TextToSpeech SDK)
    implementation(libs.pleos.tts)
    // 여행 지도: 오픈스트리트맵 지도 표시
    implementation(libs.osmdroid)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material3)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
}
