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

import bisq.security.DigestUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

class WebcamAppResourceVerifier {
    static boolean fileMatchesPackagedZip(Path filePath, InputStream packagedZipInputStream, String fileName) throws IOException {
        return fileMatchesPackagedZipIfPresent(filePath, packagedZipInputStream, fileName)
                .orElseThrow(() -> new IOException("Packaged webcam entry not found in zip: " + fileName));
    }

    static Optional<Boolean> fileMatchesPackagedZipIfPresent(Path filePath,
                                                            InputStream packagedZipInputStream,
                                                            String fileName) throws IOException {
        try (packagedZipInputStream) {
            Optional<byte[]> packagedFileHash = sha256ZipEntryIfPresent(packagedZipInputStream, fileName);
            if (packagedFileHash.isEmpty()) {
                return Optional.empty();
            }
            if (!Files.exists(filePath)) {
                return Optional.of(false);
            }
            return Optional.of(MessageDigest.isEqual(sha256(filePath), packagedFileHash.get()));
        }
    }

    private static byte[] sha256(Path path) throws IOException {
        try (InputStream inputStream = Files.newInputStream(path)) {
            return DigestUtil.sha256(inputStream);
        }
    }

    private static Optional<byte[]> sha256ZipEntryIfPresent(InputStream zipInputStream, String fileName) throws IOException {
        try (ZipInputStream inputStream = new ZipInputStream(zipInputStream)) {
            ZipEntry zipEntry;
            while ((zipEntry = inputStream.getNextEntry()) != null) {
                if (!zipEntry.isDirectory() && isZipEntry(zipEntry, fileName)) {
                    return Optional.of(DigestUtil.sha256(inputStream));
                }
            }
        }
        return Optional.empty();
    }

    private static boolean isZipEntry(ZipEntry zipEntry, String fileName) {
        String entryName = zipEntry.getName();
        return entryName.equals(fileName) || entryName.endsWith("/" + fileName);
    }

}
