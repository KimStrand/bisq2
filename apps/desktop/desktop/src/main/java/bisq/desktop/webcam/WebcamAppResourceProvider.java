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
import bisq.common.platform.OS;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static bisq.desktop.webcam.WebcamAppResourceVerifier.fileMatchesPackagedZipIfPresent;
import static bisq.desktop.webcam.WebcamAppResourceVerifier.fileMatchesPackagedZip;

@Slf4j
class WebcamAppResourceProvider {
    private static final String SANDBOX_LAUNCHER_FILE_NAME = LinuxWebcamSandboxPolicy.SANDBOX_LAUNCHER_FILE_NAME;

    private final Path webcamDirPath;

    WebcamAppResourceProvider(Path webcamDirPath) {
        this.webcamDirPath = webcamDirPath;
    }

    Path prepareWebcamAppResources() throws IOException {
        String version = FileReaderUtils.readStringFromResource("webcam-app/version.txt").trim();
        return prepareWebcamAppResources(version);
    }

    Path prepareWebcamAppResources(String version) throws IOException {
        String jarFileName = "webcam-app-" + version + "-all.jar";
        Path jarFilePath = webcamDirPath.resolve(jarFileName);
        String resourcePath = "webcam-app/webcam-app-" + version + ".zip";
        Optional<Boolean> sandboxLauncherMatchesPackagedZip = sandboxLauncherMatchesPackagedZip(resourcePath);
        boolean packagedSandboxLauncherExists = sandboxLauncherMatchesPackagedZip.isPresent();

        if (DevMode.isDevMode()
                || !fileMatchesPackagedZip(jarFilePath, openWebcamZipResource(resourcePath), jarFileName)
                || sandboxLauncherNeedsExtraction(sandboxLauncherMatchesPackagedZip)) {
            extractWebcamZip(resourcePath);
            if (!fileMatchesPackagedZip(jarFilePath, openWebcamZipResource(resourcePath), jarFileName)) {
                throw new IOException("Extracted webcam jar verification failed");
            }

            if (packagedSandboxLauncherExists && !sandboxLauncherMatchesPackagedZip(resourcePath).orElse(false)) {
                throw new IOException("Extracted webcam sandbox launcher verification failed");
            }
        }
        prepareSandboxLauncher(packagedSandboxLauncherExists);
        return jarFilePath;
    }

    private Optional<Boolean> sandboxLauncherMatchesPackagedZip(String resourcePath) throws IOException {
        return fileMatchesPackagedZipIfPresent(sandboxLauncherPath(),
                openWebcamZipResource(resourcePath),
                SANDBOX_LAUNCHER_FILE_NAME);
    }

    private boolean sandboxLauncherNeedsExtraction(Optional<Boolean> sandboxLauncherMatchesPackagedZip) {
        return sandboxLauncherMatchesPackagedZip.map(matches -> !matches).orElse(false);
    }

    private void prepareSandboxLauncher(boolean packagedSandboxLauncherExists) throws IOException {
        Path sandboxLauncherPath = sandboxLauncherPath();
        if (!packagedSandboxLauncherExists) {
            Files.deleteIfExists(sandboxLauncherPath);
            return;
        }

        if (!Files.exists(sandboxLauncherPath)) {
            throw new IOException("Extracted webcam sandbox launcher is missing");
        }
        if (!OS.isLinux()) {
            return;
        }

        boolean isExecutable = sandboxLauncherPath.toFile().setExecutable(true, true);
        if (!isExecutable || !Files.isExecutable(sandboxLauncherPath)) {
            throw new IOException("Could not make webcam sandbox launcher executable");
        }
    }

    private Path sandboxLauncherPath() {
        return webcamDirPath.resolve(SANDBOX_LAUNCHER_FILE_NAME);
    }

    private void extractWebcamZip(String resourcePath) throws IOException {
        try (InputStream inputStream = openWebcamZipResource(resourcePath);
             ZipFileExtractor zipFileExtractor = new ZipFileExtractor(inputStream, webcamDirPath)) {
            zipFileExtractor.extractArchive();
            log.info("Extracted webcam app resources");
        }
    }

    InputStream openWebcamZipResource(String resourcePath) throws IOException {
        return FileReaderUtils.getResourceAsStream(resourcePath);
    }
}
