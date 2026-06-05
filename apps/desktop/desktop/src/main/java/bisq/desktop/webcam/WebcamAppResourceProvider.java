/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.desktop.webcam;

import bisq.common.application.DevMode;
import bisq.common.archive.ZipFileExtractor;
import bisq.common.file.FileReaderUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static bisq.desktop.webcam.WebcamAppResourceVerifier.fileMatchesPackagedZip;
import static bisq.desktop.webcam.WebcamAppResourceVerifier.fileMatchesPackagedZipIfPresent;

@Slf4j
class WebcamAppResourceProvider {
    private final Path webcamAppDirPath;
    private final WebcamSandboxPolicy sandboxPolicy;
    private final boolean extractionAllowed;

    WebcamAppResourceProvider(Path webcamAppDirPath) {
        this(webcamAppDirPath, WebcamSandboxPolicy.create(), true);
    }

    WebcamAppResourceProvider(Path webcamAppDirPath, WebcamSandboxPolicy sandboxPolicy) {
        this(webcamAppDirPath, sandboxPolicy, true);
    }

    WebcamAppResourceProvider(Path webcamAppDirPath, WebcamSandboxPolicy sandboxPolicy, boolean extractionAllowed) {
        this.webcamAppDirPath = webcamAppDirPath;
        this.sandboxPolicy = sandboxPolicy;
        this.extractionAllowed = extractionAllowed;
    }

    Path prepareWebcamAppResources() throws IOException {
        return prepareWebcamAppResources(webcamAppVersion());
    }

    String webcamAppVersion() throws IOException {
        return FileReaderUtils.readStringFromResource("webcam-app/version.txt").trim();
    }

    Path prepareWebcamAppResources(String version) throws IOException {
        String jarFileName = "webcam-app-" + version + "-all.jar";
        Path jarFilePath = webcamAppDirPath.resolve(jarFileName);
        String resourcePath = "webcam-app/webcam-app-" + version + ".zip";
        Optional<WebcamSandboxPolicy.SandboxLauncherResource> sandboxLauncherResource = sandboxPolicy.sandboxLauncherResource();

        if (!extractionAllowed) {
            verifyPackagedAppResources(jarFilePath, sandboxLauncherResource);
            return jarFilePath;
        }

        Optional<Boolean> sandboxLauncherMatchesPackagedZip = Optional.empty();
        if (sandboxLauncherResource.isPresent()) {
            sandboxLauncherMatchesPackagedZip = sandboxLauncherMatchesPackagedZip(resourcePath, sandboxLauncherResource.get().fileName());
        }
        boolean packagedSandboxLauncherExists = sandboxLauncherMatchesPackagedZip.isPresent();
        boolean jarMatchesPackagedZip = fileMatchesPackagedZip(jarFilePath, openWebcamZipResource(resourcePath), jarFileName);

        if (DevMode.isDevMode()
                || !jarMatchesPackagedZip
                || sandboxLauncherNeedsExtraction(sandboxLauncherMatchesPackagedZip)) {
            extractWebcamZip(resourcePath);
            if (!fileMatchesPackagedZip(jarFilePath, openWebcamZipResource(resourcePath), jarFileName)) {
                throw new IOException("Extracted webcam jar verification failed");
            }

            if (packagedSandboxLauncherExists
                    && !sandboxLauncherMatchesPackagedZip(resourcePath, sandboxLauncherResource.orElseThrow().fileName()).orElse(false)) {
                throw new IOException("Extracted webcam sandbox launcher verification failed");
            }
        }
        prepareSandboxLauncher(sandboxLauncherResource, packagedSandboxLauncherExists);
        return jarFilePath;
    }

    private void verifyPackagedAppResources(Path jarFilePath,
                                            Optional<WebcamSandboxPolicy.SandboxLauncherResource> sandboxLauncherResource) throws IOException {
        if (!Files.isRegularFile(jarFilePath)) {
            throw new IOException("Packaged webcam jar is missing: " + jarFilePath.toAbsolutePath());
        }

        if (sandboxLauncherResource.isEmpty()) {
            return;
        }

        WebcamSandboxPolicy.SandboxLauncherResource resource = sandboxLauncherResource.get();
        Path sandboxLauncherPath = sandboxLauncherPath(resource.fileName());
        if (!Files.isRegularFile(sandboxLauncherPath)) {
            throw new IOException("Packaged webcam sandbox launcher is missing: " + sandboxLauncherPath.toAbsolutePath());
        }
    }

    private Optional<Boolean> sandboxLauncherMatchesPackagedZip(String resourcePath, String sandboxLauncherFileName) throws IOException {
        return fileMatchesPackagedZipIfPresent(sandboxLauncherPath(sandboxLauncherFileName),
                openWebcamZipResource(resourcePath),
                sandboxLauncherFileName);
    }

    private boolean sandboxLauncherNeedsExtraction(Optional<Boolean> sandboxLauncherMatchesPackagedZip) {
        return sandboxLauncherMatchesPackagedZip.map(matches -> !matches).orElse(false);
    }

    private void prepareSandboxLauncher(Optional<WebcamSandboxPolicy.SandboxLauncherResource> sandboxLauncherResource,
                                        boolean packagedSandboxLauncherExists) throws IOException {
        if (sandboxLauncherResource.isEmpty()) {
            return;
        }

        WebcamSandboxPolicy.SandboxLauncherResource resource = sandboxLauncherResource.get();
        Path sandboxLauncherPath = sandboxLauncherPath(resource.fileName());
        if (!packagedSandboxLauncherExists) {
            Files.deleteIfExists(sandboxLauncherPath);
            return;
        }

        if (!Files.exists(sandboxLauncherPath)) {
            throw new IOException("Extracted webcam sandbox launcher is missing");
        }
        if (!resource.requiresExecutableBit()) {
            return;
        }

        boolean isExecutable = sandboxLauncherPath.toFile().setExecutable(true, true);
        if (!isExecutable || !Files.isExecutable(sandboxLauncherPath)) {
            throw new IOException("Could not make webcam sandbox launcher executable");
        }
    }

    private Path sandboxLauncherPath(String sandboxLauncherFileName) {
        return webcamAppDirPath.resolve(sandboxLauncherFileName);
    }

    private void extractWebcamZip(String resourcePath) throws IOException {
        try (InputStream inputStream = openWebcamZipResource(resourcePath);
             ZipFileExtractor zipFileExtractor = new ZipFileExtractor(inputStream, webcamAppDirPath)) {
            zipFileExtractor.extractArchive();
            log.info("Extracted webcam app resources");
        }
    }

    InputStream openWebcamZipResource(String resourcePath) throws IOException {
        return FileReaderUtils.getResourceAsStream(resourcePath);
    }
}
