import bisq.gradle.common.VersionUtil
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.GradleException
import org.gradle.jvm.tasks.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion
import java.io.File

plugins {
    id("bisq.java-library")
    id("bisq.gradle.webcam_app.WebcamAppPlugin")
    application
    alias(libs.plugins.shadow)
    alias(libs.plugins.openjfx)
    alias(libs.plugins.gradle.javacpp.platform)
}

application {
    mainClass.set("bisq.webcam.WebcamAppLauncher")
}

val linuxSandboxLauncherFileName = "bisq-webcam-sandbox-launcher"
val linuxBuildHost = System.getProperty("os.name").lowercase().contains("linux")
val linuxSandboxLauncherSource = layout.projectDirectory.file("src/main/c/$linuxSandboxLauncherFileName.c")
val linuxSandboxLauncherOutput = layout.buildDirectory.file("native/linux/$linuxSandboxLauncherFileName")

val windowsAppContainerLauncherFileName = "bisq-webcam-appcontainer-launcher.exe"
val windowsBuildHost = System.getProperty("os.name").lowercase().contains("win")
val windowsAppContainerLauncherSource = layout.projectDirectory.file("src/main/c/bisq-webcam-appcontainer-launcher.c")
val windowsAppContainerLauncherOutput = layout.buildDirectory.file("native/windows/$windowsAppContainerLauncherFileName")
val windowsWebcamAppContentDir = layout.buildDirectory.dir("packaging/windows-app-content/webcam")

val windowsWinRtCaptureFileName = "bisq_webcam_winrt.dll"
val windowsWinRtCaptureSource = layout.projectDirectory.file("src/main/c/bisq_webcam_winrt.cpp")
val windowsWinRtCaptureOutput = layout.buildDirectory.file("native/windows/$windowsWinRtCaptureFileName")

val macOsBuildHost = System.getProperty("os.name").lowercase().contains("mac") ||
        System.getProperty("os.name").lowercase().contains("darwin")
val macOsWebcamHelperAppName = "BisqWebcam"
val macOsWebcamHelperOutputDir = layout.buildDirectory.dir("generated/macos-helper")
val macOsWebcamHelperAppDir = macOsWebcamHelperOutputDir.map { it.dir("$macOsWebcamHelperAppName.app") }
val macOsWebcamHelperEntitlements = layout.projectDirectory.file("package/macosx/BisqWebcam.entitlements")
val macOsWebcamHelperIconSource = layout.projectDirectory.file("src/main/resources/images/webcam-app-icon.png")
val macOsWebcamHelperIconFile = layout.buildDirectory.file("generated/macos-helper-icon/BisqWebcam.icns")
val jpackageExecutable = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(21))
}.map { it.metadata.installationPath.file("bin/jpackage").asFile }

val compileLinuxSandboxLauncher by tasks.registering(org.gradle.api.tasks.Exec::class) {
    onlyIf { linuxBuildHost }
    inputs.file(linuxSandboxLauncherSource)
    outputs.file(linuxSandboxLauncherOutput)

    doFirst {
        linuxSandboxLauncherOutput.get().asFile.parentFile.mkdirs()
    }

    commandLine(
            "cc",
            "-std=c11",
            "-O2",
            "-Wall",
            "-Wextra",
            "-Werror",
            "-D_FORTIFY_SOURCE=2",
            "-fstack-protector-strong",
            "-fPIE",
            "-pie",
            "-Wl,-z,relro,-z,now",
            "-o",
            linuxSandboxLauncherOutput.get().asFile.absolutePath,
            linuxSandboxLauncherSource.asFile.absolutePath
    )

    doLast {
        linuxSandboxLauncherOutput.get().asFile.setExecutable(true, true)
    }
}

val compileWindowsAppContainerLauncher by tasks.registering(org.gradle.api.tasks.Exec::class) {
    onlyIf { windowsBuildHost }
    inputs.file(windowsAppContainerLauncherSource)
    outputs.file(windowsAppContainerLauncherOutput)

    doFirst {
        windowsAppContainerLauncherOutput.get().asFile.parentFile.mkdirs()
    }

    commandLine(
            "cl.exe",
            "/nologo",
            "/W4",
            "/WX",
            "/O2",
            "/DUNICODE",
            "/D_UNICODE",
            "/D_WIN32_WINNT=0x0602",
            "/Fe${windowsAppContainerLauncherOutput.get().asFile.absolutePath}",
            windowsAppContainerLauncherSource.asFile.absolutePath,
            "userenv.lib",
            "advapi32.lib",
            "ole32.lib"
    )
}

// JDK home used only for its JNI headers (jni.h, jni_md.h). Pinned to the same toolchain version that packages the
// helper, so the JNI ABI matches the bundled runtime.
val jniHeaderJdkHome = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(21))
}.map { it.metadata.installationPath }

// Compiles the WinRT camera capture JNI shim used by the sandboxed Windows webcam helper. WinRT (windowsapp.lib) is
// header-only C++/WinRT; the only link dependency is windowsapp.lib, and the static CRT (/MT) avoids a VC runtime DLL
// dependency that the AppContainer would otherwise have to resolve.
val compileWindowsWinRtCapture by tasks.registering(org.gradle.api.tasks.Exec::class) {
    onlyIf { windowsBuildHost }
    inputs.file(windowsWinRtCaptureSource)
    outputs.file(windowsWinRtCaptureOutput)

    val outputFile = windowsWinRtCaptureOutput.get().asFile
    workingDir(outputFile.parentFile)

    doFirst {
        outputFile.parentFile.mkdirs()
        val jdkHome = jniHeaderJdkHome.get().asFile
        commandLine(
                "cl.exe",
                "/nologo",
                "/LD",
                "/EHsc",
                "/MT",
                "/O2",
                "/W3",
                "/std:c++17",
                "/DUNICODE",
                "/D_UNICODE",
                "/D_WIN32_WINNT=0x0A00",
                "/I", jdkHome.resolve("include").absolutePath,
                "/I", jdkHome.resolve("include").resolve("win32").absolutePath,
                "/Fe:${outputFile.absolutePath}",
                windowsWinRtCaptureSource.asFile.absolutePath,
                "/link",
                "windowsapp.lib"
        )
    }
}

fun createMacOsWebcamHelperIcon(iconSourceFile: File, iconSetDir: File, iconOutputFile: File) {
    val sipsFile = File("/usr/bin/sips")
    val iconutilFile = File("/usr/bin/iconutil")
    if (!sipsFile.canExecute()) {
        throw GradleException("Cannot generate macOS webcam helper icon: /usr/bin/sips is not executable.")
    }
    if (!iconutilFile.canExecute()) {
        throw GradleException("Cannot generate macOS webcam helper icon: /usr/bin/iconutil is not executable.")
    }

    val iconFiles = listOf(
            16 to "icon_16x16.png",
            32 to "icon_16x16@2x.png",
            32 to "icon_32x32.png",
            64 to "icon_32x32@2x.png",
            128 to "icon_128x128.png",
            256 to "icon_128x128@2x.png",
            256 to "icon_256x256.png",
            512 to "icon_256x256@2x.png",
            512 to "icon_512x512.png",
            1024 to "icon_512x512@2x.png"
    )
    iconFiles.forEach { (size, fileName) ->
        project.exec {
            commandLine(
                    sipsFile.absolutePath,
                    "-z", size.toString(), size.toString(),
                    iconSourceFile.absolutePath,
                    "--out", iconSetDir.resolve(fileName).absolutePath
            )
        }
    }

    project.exec {
        commandLine(
                iconutilFile.absolutePath,
                "-c", "icns",
                iconSetDir.absolutePath,
                "-o", iconOutputFile.absolutePath
        )
    }
}

val generateMacOsWebcamHelperIcon by tasks.registering {
    onlyIf { macOsBuildHost }
    inputs.file(macOsWebcamHelperIconSource)
    outputs.file(macOsWebcamHelperIconFile)

    doLast {
        val iconOutputFile = macOsWebcamHelperIconFile.get().asFile
        delete(iconOutputFile.parentFile)
        val iconSetDir = iconOutputFile.parentFile.resolve("BisqWebcam.iconset")
        iconSetDir.mkdirs()
        createMacOsWebcamHelperIcon(macOsWebcamHelperIconSource.asFile, iconSetDir, iconOutputFile)
    }
}

val generateMacOsWebcamHelperApp by tasks.registering(org.gradle.api.tasks.Exec::class) {
    onlyIf { macOsBuildHost }

    val shadowJarTask = tasks.named<ShadowJar>("shadowJar")
    if (macOsBuildHost) {
        dependsOn(shadowJarTask)
        dependsOn(generateMacOsWebcamHelperIcon)
        inputs.file(shadowJarTask.flatMap { it.archiveFile })
        inputs.file(macOsWebcamHelperIconFile)
    }
    inputs.file(macOsWebcamHelperEntitlements)
    outputs.dir(macOsWebcamHelperAppDir)

    doFirst {
        delete(macOsWebcamHelperOutputDir)
        macOsWebcamHelperOutputDir.get().asFile.mkdirs()

        val iconOutputFile = macOsWebcamHelperIconFile.get().asFile
        commandLine(
                jpackageExecutable.get().absolutePath,
                "--type", "app-image",
                "--dest", macOsWebcamHelperOutputDir.get().asFile.absolutePath,
                "--name", macOsWebcamHelperAppName,
                "--input", layout.buildDirectory.dir("libs").get().asFile.absolutePath,
                "--main-jar", shadowJarTask.get().archiveFileName.get(),
                "--main-class", "bisq.webcam.WebcamAppLauncher",
                "--app-version", VersionUtil.getVersionFromFile(project),
                "--mac-package-identifier", "bisq.webcam",
                "--icon", iconOutputFile.absolutePath
        )
    }

    doLast {
        val codesignFile = File("/usr/bin/codesign")
        if (!codesignFile.canExecute()) {
            throw GradleException("Cannot sign macOS webcam helper app: /usr/bin/codesign is not executable.")
        }
        exec {
            commandLine(
                    codesignFile.absolutePath,
                    "--force",
                    "--deep",
                    "--sign", "-",
                    "--entitlements", macOsWebcamHelperEntitlements.asFile.absolutePath,
                    macOsWebcamHelperAppDir.get().asFile.absolutePath
            )
        }
    }
}

val prepareWindowsWebcamAppContent by tasks.registering(org.gradle.api.tasks.Sync::class) {
    onlyIf { windowsBuildHost }

    val shadowJarTask = tasks.named<ShadowJar>("shadowJar")
    dependsOn(shadowJarTask)
    dependsOn(compileWindowsAppContainerLauncher)
    dependsOn(compileWindowsWinRtCapture)
    from(shadowJarTask.flatMap { it.archiveFile })
    from(windowsAppContainerLauncherOutput)
    // Co-locate the WinRT capture JNI shim with the OpenCV/JavaCPP natives so System.loadLibrary() resolves it from
    // the same read-only java.library.path dir inside the AppContainer.
    from(windowsWinRtCaptureOutput)

    // Unpack the JavaCPP/OpenCV Windows native libraries as loose, co-located files so the sandboxed helper can
    // System.load() them directly from this read-only dir (see WindowsWebcamSandboxPolicy.jvmArguments). They are
    // otherwise only bundled inside the shadow jar, from where JavaCPP would extract them to a writable cache that the
    // AppContainer cannot canonicalize. Flattened into the content root so java.library.path is a single dir and the
    // Windows loader resolves dependent DLLs from the same directory.
    from(provider {
        configurations.getByName("runtimeClasspath").files
                .filter { it.name.contains("windows-x86_64") && it.name.endsWith(".jar") }
                .map { zipTree(it) }
    }) {
        include("**/*.dll")
        includeEmptyDirs = false
        eachFile { path = name }
    }

    // JavaCPP's openblas Windows artifact ships only libopenblas.dll, but the JNI binding
    // jniopenblas_nolapack.dll (loaded transitively by opencv_core) imports a sibling named
    // libopenblas_nolapack.dll. In JavaCPP's normal cache-extraction flow that file is materialized as a
    // byte-identical copy of libopenblas.dll; since we bypass the cache (cacheLibraries=false) we recreate the copy
    // here so the Windows loader can resolve the dependency. It is the only synthetic DLL the cache produces that is
    // absent from the jars, and is SHA-256 identical to libopenblas.dll.
    from(provider {
        configurations.getByName("runtimeClasspath").files
                .filter { it.name.startsWith("openblas-") && it.name.contains("windows-x86_64") && it.name.endsWith(".jar") }
                .map { zipTree(it) }
    }) {
        include("**/libopenblas.dll")
        includeEmptyDirs = false
        eachFile { path = "libopenblas_nolapack.dll" }
    }

    into(windowsWebcamAppContentDir)
}

javafx {
    version = "21.0.11"
    modules = listOf("javafx.controls")
}

sourceSets {
    main {
        resources {
            srcDir(layout.buildDirectory.file("generated/src/main/resources"))
        }
    }
}

dependencies {
    implementation("bisq:i18n")
    implementation(libs.zxing.core)
    implementation(libs.javacv) {
        exclude(module = "ffmpeg")
        exclude(module = "flycapture")
        exclude(module = "libdc1394")
        exclude(module = "libfreenect")
        exclude(module = "libfreenect2")
        exclude(module = "librealsense")
        exclude(module = "librealsense2")
        exclude(module = "videoinput")
        exclude(module = "artoolkitplus")
        exclude(module = "leptonica")
        exclude(module = "tesseract")
        exclude(module = "ffmpeg-platform")
        exclude(module = "flycapture-platform")
        exclude(module = "spinnaker-platform")
        exclude(module = "libdc1394-platform")
        exclude(module = "libfreenect-platform")
        exclude(module = "libfreenect2-platform")
        exclude(module = "librealsense-platform")
        exclude(module = "librealsense2-platform")
        exclude(module = "videoinput-platform")
        exclude(module = "artoolkitplus-platform")
        exclude(module = "chilitags-platform")
        exclude(module = "flandmark-platform")
        exclude(module = "arrow-platform")
        exclude(module = "hdf5-platform")
        exclude(module = "hyperscan-platform")
        exclude(module = "lz4-platform")
        exclude(module = "mkl-platform")
        exclude(module = "mkl-dnn-platform")
        exclude(module = "dnnl-platform")
        exclude(module = "arpack-ng-platform")
        exclude(module = "cminpack-platform")
        exclude(module = "fftw-platform")
        exclude(module = "gsl-platform")
        exclude(module = "cpython-platform")
        exclude(module = "numpy-platform")
        exclude(module = "scipy-platform")
        exclude(module = "gym-platform")
        exclude(module = "llvm-platform")
        exclude(module = "libffi-platform")
        exclude(module = "libpostal-platform")
        exclude(module = "libraw-platform")
        exclude(module = "leptonica-platform")
        exclude(module = "tesseract-platform")
        exclude(module = "caffe-platform")
        exclude(module = "openpose-platform")
        exclude(module = "cuda-platform")
        exclude(module = "nvcodec-platform")
        exclude(module = "opencl-platform")
        exclude(module = "mxnet-platform")
        exclude(module = "pytorch-platform")
        exclude(module = "sentencepiece-platform")
        exclude(module = "tensorflow-platform")
        exclude(module = "tensorflow-lite-platform")
        exclude(module = "tensorrt-platform")
        exclude(module = "tritonserver-platform")
        exclude(module = "ale-platform")
        exclude(module = "depthai-platform")
        exclude(module = "onnx-platform")
        exclude(module = "ngraph-platform")
        exclude(module = "onnxruntime-platform")
        exclude(module = "tvm-platform")
        exclude(module = "bullet-platform")
        exclude(module = "liquidfun-platform")
        exclude(module = "qt-platform")
        exclude(module = "skia-platform")
        exclude(module = "cpu_features-platform")
        exclude(module = "modsecurity-platform")
        exclude(module = "systems-platform")
    }
}

tasks {
    named<Jar>("jar") {
        manifest {
            attributes(
                    mapOf(
                            Pair("Implementation-Title", project.name),
                            Pair("Implementation-Version", project.version),
                            Pair("Main-Class", "bisq.webcam.WebcamAppLauncher")
                    )
            )
        }
    }

    named<ShadowJar>("shadowJar") {
        val version = VersionUtil.getVersionFromFile(project)
        archiveClassifier.set("$version-all")
        mergeServiceFiles()
    }

    named<org.gradle.api.tasks.bundling.Zip>("zipWebcamAppShadowJar") {
        if (linuxBuildHost) {
            dependsOn(compileLinuxSandboxLauncher)
            include(linuxSandboxLauncherFileName)
            from(linuxSandboxLauncherOutput)
        } else if (windowsBuildHost) {
            dependsOn(compileWindowsAppContainerLauncher)
            include(windowsAppContainerLauncherFileName)
            from(windowsAppContainerLauncherOutput)
        }
    }

    distZip {
        enabled = false
    }

    distTar {
        enabled = false
    }
}
