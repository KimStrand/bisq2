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

package bisq.desktop.common.utils;

import javafx.scene.image.Image;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CatHashImageUtilTest {

    @Test
    void testComposeImageReturnsImage() {
        String[] paths = {"cat1.png", "cat2.png"};
        Image image = CatHashImageUtil.composeImage(paths, 32);
        assertNotNull(image);
        assertEquals(32, image.getWidth());
        assertEquals(32, image.getHeight());
    }

    @Test
    void testByteArrayToImageAndImageToByteArray() {
        String[] paths = {"cat1.png"};
        Image image = CatHashImageUtil.composeImage(paths, 16);
        byte[] bytes = CatHashImageUtil.imageToByteArray(image);
        Image result = CatHashImageUtil.byteArrayToImage(bytes);
        assertEquals(image.getWidth(), result.getWidth());
        assertEquals(image.getHeight(), result.getHeight());
    }

    @Test
    void testWriteAndReadRawImage(@TempDir Path tempDir) throws IOException {
        String[] paths = {"cat1.png"};
        Image image = CatHashImageUtil.composeImage(paths, 8);
        File tempFile = tempDir.resolve("test.raw").toFile();

        CatHashImageUtil.writeRawImage(image, tempFile.toPath());
        Image readImage = CatHashImageUtil.readRawImage(tempFile.toPath());

        assertEquals(image.getWidth(), readImage.getWidth());
        assertEquals(image.getHeight(), readImage.getHeight());
    }
}
