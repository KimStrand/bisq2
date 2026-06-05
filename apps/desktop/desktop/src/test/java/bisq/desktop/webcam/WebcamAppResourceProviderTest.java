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

import bisq.common.platform.OS;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WebcamAppResourceProviderTest {
    private static final String VERSION = "1.0.0";
    private static final String JAR_FILE_NAME = "webcam-app-" + VERSION + "-all.jar";
    private static final String RESOURCE_PATH = "webcam-app/webcam-app-" + VERSION + ".zip";
    private static final String SANDBOX_LAUNCHER_FILE_NAME = "test-webcam-sandbox-launcher";

    @TempDir
    Path tempDir;

    @Test
    void extractsPackagedJarWhenExtractedJarIsMissing() throws IOException {
        byte[] jarBytes = "packaged-jar".getBytes();
        WebcamAppResourceProvider provider = newProvider(jarBytes);

        Path jarPath = provider.prepareWebcamAppResources(VERSION);

        assertEquals(tempDir.resolve(JAR_FILE_NAME), jarPath);
        assertArrayEquals(jarBytes, Files.readAllBytes(jarPath));
    }

    @Test
    void keepsExtractedJarWhenItMatchesPackagedJar() throws IOException {
        byte[] jarBytes = "packaged-jar".getBytes();
        Path jarPath = tempDir.resolve(JAR_FILE_NAME);
        Files.write(jarPath, jarBytes);
        WebcamAppResourceProvider provider = newProvider(jarBytes);
        long lastModified = Files.getLastModifiedTime(jarPath).toMillis();

        Path preparedJarPath = provider.prepareWebcamAppResources(VERSION);

        assertEquals(jarPath, preparedJarPath);
        assertArrayEquals(jarBytes, Files.readAllBytes(jarPath));
        assertEquals(lastModified, Files.getLastModifiedTime(jarPath).toMillis());
    }

    @Test
    void replacesExtractedJarWhenItDiffersFromPackagedJar() throws IOException {
        byte[] jarBytes = "packaged-jar".getBytes();
        Path jarPath = tempDir.resolve(JAR_FILE_NAME);
        Files.write(jarPath, "tampered-jar".getBytes());
        WebcamAppResourceProvider provider = newProvider(jarBytes);

        provider.prepareWebcamAppResources(VERSION);

        assertArrayEquals(jarBytes, Files.readAllBytes(jarPath));
    }

    @Test
    void extractsPackagedSandboxLauncherWhenPresent() throws IOException {
        byte[] jarBytes = "packaged-jar".getBytes();
        byte[] sandboxLauncherBytes = "sandbox-launcher".getBytes();
        WebcamAppResourceProvider provider = newProviderForZipBytes(zipWithJarAndSandboxLauncherBytes(jarBytes, sandboxLauncherBytes));

        provider.prepareWebcamAppResources(VERSION);

        Path sandboxLauncherPath = tempDir.resolve(SANDBOX_LAUNCHER_FILE_NAME);
        assertArrayEquals(sandboxLauncherBytes, Files.readAllBytes(sandboxLauncherPath));
        if (OS.isLinux()) {
            assertTrue(Files.isExecutable(sandboxLauncherPath));
        }
    }

    @Test
    void replacesExtractedSandboxLauncherWhenItDiffersFromPackagedLauncher() throws IOException {
        byte[] jarBytes = "packaged-jar".getBytes();
        byte[] sandboxLauncherBytes = "sandbox-launcher".getBytes();
        Files.write(tempDir.resolve(JAR_FILE_NAME), jarBytes);
        Path sandboxLauncherPath = tempDir.resolve(SANDBOX_LAUNCHER_FILE_NAME);
        Files.write(sandboxLauncherPath, "stale-launcher".getBytes());
        WebcamAppResourceProvider provider = newProviderForZipBytes(zipWithJarAndSandboxLauncherBytes(jarBytes, sandboxLauncherBytes));

        provider.prepareWebcamAppResources(VERSION);

        assertArrayEquals(sandboxLauncherBytes, Files.readAllBytes(sandboxLauncherPath));
    }

    @Test
    void removesStaleSandboxLauncherWhenPackagedZipDoesNotContainLauncher() throws IOException {
        Path sandboxLauncherPath = tempDir.resolve(SANDBOX_LAUNCHER_FILE_NAME);
        Files.write(sandboxLauncherPath, "stale-launcher".getBytes());
        WebcamAppResourceProvider provider = newProvider("packaged-jar".getBytes());

        provider.prepareWebcamAppResources(VERSION);

        assertFalse(Files.exists(sandboxLauncherPath));
    }

    @Test
    void usesPackagedAppResourcesWhenExtractionIsDisabled() throws IOException {
        byte[] jarBytes = "packaged-jar".getBytes();
        byte[] sandboxLauncherBytes = "sandbox-launcher".getBytes();
        Path jarPath = tempDir.resolve(JAR_FILE_NAME);
        Files.write(jarPath, jarBytes);
        Path sandboxLauncherPath = tempDir.resolve(SANDBOX_LAUNCHER_FILE_NAME);
        Files.write(sandboxLauncherPath, sandboxLauncherBytes);
        WebcamAppResourceProvider provider = newProviderForZipBytes(zipWithJarAndSandboxLauncherBytes(jarBytes, sandboxLauncherBytes),
                false);
        long jarLastModified = Files.getLastModifiedTime(jarPath).toMillis();
        long sandboxLauncherLastModified = Files.getLastModifiedTime(sandboxLauncherPath).toMillis();

        Path preparedJarPath = provider.prepareWebcamAppResources(VERSION);

        assertEquals(jarPath, preparedJarPath);
        assertEquals(jarLastModified, Files.getLastModifiedTime(jarPath).toMillis());
        assertEquals(sandboxLauncherLastModified, Files.getLastModifiedTime(sandboxLauncherPath).toMillis());
    }

    @Test
    void usesPackagedAppResourcesWhenExtractionIsDisabledWithoutComparingBundledZip() throws IOException {
        Path jarPath = tempDir.resolve(JAR_FILE_NAME);
        Files.write(jarPath, "installed-jar".getBytes());
        Path sandboxLauncherPath = tempDir.resolve(SANDBOX_LAUNCHER_FILE_NAME);
        Files.write(sandboxLauncherPath, "installed-launcher".getBytes());
        WebcamAppResourceProvider provider = newProviderForZipBytes(zipWithJarAndSandboxLauncherBytes(
                "different-bundled-jar".getBytes(),
                "different-bundled-launcher".getBytes()), false);

        Path preparedJarPath = provider.prepareWebcamAppResources(VERSION);

        assertEquals(jarPath, preparedJarPath);
        assertArrayEquals("installed-jar".getBytes(), Files.readAllBytes(jarPath));
        assertArrayEquals("installed-launcher".getBytes(), Files.readAllBytes(sandboxLauncherPath));
    }

    @Test
    void throwsWhenPackagedAppJarIsMissingAndExtractionIsDisabled() throws IOException {
        byte[] jarBytes = "packaged-jar".getBytes();
        WebcamAppResourceProvider provider = newProviderForZipBytes(zipWithJarBytes(jarBytes), false);

        assertThrows(IOException.class, () -> provider.prepareWebcamAppResources(VERSION));
        assertFalse(Files.exists(tempDir.resolve(JAR_FILE_NAME)));
    }

    @Test
    void throwsWhenPackagedSandboxLauncherIsMissingAndExtractionIsDisabled() throws IOException {
        byte[] jarBytes = "packaged-jar".getBytes();
        byte[] sandboxLauncherBytes = "sandbox-launcher".getBytes();
        Files.write(tempDir.resolve(JAR_FILE_NAME), jarBytes);
        WebcamAppResourceProvider provider = newProviderForZipBytes(zipWithJarAndSandboxLauncherBytes(jarBytes, sandboxLauncherBytes),
                false);

        assertThrows(IOException.class, () -> provider.prepareWebcamAppResources(VERSION));
        assertFalse(Files.exists(tempDir.resolve(SANDBOX_LAUNCHER_FILE_NAME)));
    }

    @Test
    void throwsWhenPackagedZipDoesNotContainExpectedJar() throws IOException {
        WebcamAppResourceProvider provider = newProviderForZipBytes(zipWithEntryBytes("readme.txt", "content".getBytes()));

        assertThrows(IOException.class, () -> provider.prepareWebcamAppResources(VERSION));
    }

    private WebcamAppResourceProvider newProvider(byte[] jarBytes) throws IOException {
        return newProviderForZipBytes(zipWithJarBytes(jarBytes));
    }

    private WebcamAppResourceProvider newProviderForZipBytes(byte[] zipBytes) {
        return newProviderForZipBytes(zipBytes, true);
    }

    private WebcamAppResourceProvider newProviderForZipBytes(byte[] zipBytes, boolean extractionAllowed) {
        return new WebcamAppResourceProvider(tempDir, sandboxPolicy(), extractionAllowed) {
            @Override
            InputStream openWebcamZipResource(String resourcePath) {
                assertEquals(RESOURCE_PATH, resourcePath);
                return new ByteArrayInputStream(zipBytes);
            }
        };
    }

    private WebcamSandboxPolicy sandboxPolicy() {
        return new BaselineWebcamSandboxPolicy() {
            @Override
            public Optional<WebcamSandboxPolicy.SandboxLauncherResource> sandboxLauncherResource() {
                return Optional.of(new WebcamSandboxPolicy.SandboxLauncherResource(SANDBOX_LAUNCHER_FILE_NAME, OS.isLinux()));
            }
        };
    }

    private byte[] zipWithJarBytes(byte[] jarBytes) throws IOException {
        return zipWithEntryBytes(JAR_FILE_NAME, jarBytes);
    }

    private byte[] zipWithEntryBytes(String entryName, byte[] bytes) throws IOException {
        return zipWithEntries(List.of(new ZipEntryBytes(entryName, bytes)));
    }

    private byte[] zipWithJarAndSandboxLauncherBytes(byte[] jarBytes, byte[] sandboxLauncherBytes) throws IOException {
        return zipWithEntries(List.of(
                new ZipEntryBytes(JAR_FILE_NAME, jarBytes),
                new ZipEntryBytes(SANDBOX_LAUNCHER_FILE_NAME, sandboxLauncherBytes)));
    }

    private byte[] zipWithEntries(List<ZipEntryBytes> zipEntryBytesList) throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        try (ZipOutputStream zipOutputStream = new ZipOutputStream(byteArrayOutputStream)) {
            for (ZipEntryBytes zipEntryBytes : zipEntryBytesList) {
                zipOutputStream.putNextEntry(new ZipEntry(zipEntryBytes.entryName()));
                zipOutputStream.write(zipEntryBytes.bytes());
                zipOutputStream.closeEntry();
            }
        }
        return byteArrayOutputStream.toByteArray();
    }

    private record ZipEntryBytes(String entryName, byte[] bytes) {
    }
}
