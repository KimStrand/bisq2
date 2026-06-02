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

val macOsBuildHost = System.getProperty("os.name").lowercase().contains("mac") ||
        System.getProperty("os.name").lowercase().contains("darwin")
val macOsWebcamHelperAppName = "BisqWebcam"
val macOsWebcamHelperOutputDir = layout.buildDirectory.dir("generated/macos-helper")
val macOsWebcamHelperAppDir = macOsWebcamHelperOutputDir.map { it.dir("$macOsWebcamHelperAppName.app") }
val macOsWebcamHelperEntitlements = layout.projectDirectory.file("package/macosx/BisqWebcam.entitlements")
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

val generateMacOsWebcamHelperApp by tasks.registering(org.gradle.api.tasks.Exec::class) {
    onlyIf { macOsBuildHost }

    val shadowJarTask = tasks.named<ShadowJar>("shadowJar")
    if (macOsBuildHost) {
        dependsOn(shadowJarTask)
        inputs.file(shadowJarTask.flatMap { it.archiveFile })
    }
    inputs.file(macOsWebcamHelperEntitlements)
    outputs.dir(macOsWebcamHelperAppDir)

    doFirst {
        delete(macOsWebcamHelperOutputDir)
        macOsWebcamHelperOutputDir.get().asFile.mkdirs()
        commandLine(
                jpackageExecutable.get().absolutePath,
                "--type", "app-image",
                "--dest", macOsWebcamHelperOutputDir.get().asFile.absolutePath,
                "--name", macOsWebcamHelperAppName,
                "--input", layout.buildDirectory.dir("libs").get().asFile.absolutePath,
                "--main-jar", shadowJarTask.get().archiveFileName.get(),
                "--main-class", "bisq.webcam.WebcamAppLauncher",
                "--app-version", VersionUtil.getVersionFromFile(project),
                "--mac-package-identifier", "bisq.webcam"
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
        }
    }

    distZip {
        enabled = false
    }

    distTar {
        enabled = false
    }
}
