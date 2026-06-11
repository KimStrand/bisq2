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

package bisq.webcam.service.processor;

import bisq.webcam.service.converter.FrameToBitmapConverter;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.NotFoundException;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import org.bytedeco.javacv.Frame;

import java.util.Map;
import java.util.Optional;

/**
 * Processes JavaCV {@link Frame}'s to detect and decode QR codes within the image.
 */
public class QrCodeProcessor implements FrameProcessor<String> {
    private static final Map<DecodeHintType, Object> HINTS = Map.of(DecodeHintType.TRY_HARDER, Boolean.TRUE);
    // Decoding with TRY_HARDER is the expensive part, and most frames during scanning contain no QR code at all.
    // Retrying every miss with an inverted source would roughly double that cost on the hot path, so the inverted
    // (light-on-dark) attempt is throttled to one in every INVERTED_RETRY_INTERVAL misses. At typical frame rates an
    // inverted QR is still picked up within a fraction of a second. Accessed only from the single capture thread.
    private static final int INVERTED_RETRY_INTERVAL = 4;

    private final FrameToBitmapConverter frameToBitmapConverter;
    private long missCount;

    public QrCodeProcessor(FrameToBitmapConverter frameToBitmapConverter) {
        this.frameToBitmapConverter = frameToBitmapConverter;
    }

    /**
     * Processes the given JavaCV {@link Frame} to detect and decode any QR codes present.
     *
     * @param frame The JavaCV {@link Frame} to be processed.
     * @return An {@link Optional<String>} containing the decoded QR code text if a
     * QR code is detected, or {@link Optional#empty()} if no QR code is found.
     */
    @Override
    public Optional<String> process(final Frame frame) {
        if (frame == null || frame.image == null || frame.imageWidth <= 0 || frame.imageHeight <= 0) {
            // Ignore the frame if null or has invalid dimensions
            return Optional.empty();
        }

        final LuminanceSource source;
        try {
            source = frameToBitmapConverter.toLuminanceSource(frame);
        } catch (Exception ignored) {
            // Frame could not be converted to a grayscale source.
            return Optional.empty();
        }

        final QRCodeReader reader = new QRCodeReader();
        try {
            return Optional.of(reader.decode(new BinaryBitmap(new HybridBinarizer(source)), HINTS).getText());
        } catch (NotFoundException notFound) {
            // No standard (dark-on-light) QR code found. Periodically retry with an inverted source to also support
            // light-on-dark QR codes, reusing the same grayscale (only the binarization is repeated).
            if (missCount++ % INVERTED_RETRY_INTERVAL != 0) {
                return Optional.empty();
            }
            try {
                return Optional.of(reader.decode(new BinaryBitmap(new HybridBinarizer(source.invert())), HINTS).getText());
            } catch (Exception ignored) {
                return Optional.empty();
            }
        } catch (Exception ignored) {
            // There is no QR code in the image
            return Optional.empty();
        }
    }
}
