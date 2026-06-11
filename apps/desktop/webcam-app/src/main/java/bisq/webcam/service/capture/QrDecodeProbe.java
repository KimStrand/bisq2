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

package bisq.webcam.service.capture;

import bisq.webcam.service.converter.FrameToBitmapConverter;
import bisq.webcam.service.processor.QrCodeProcessor;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.OpenCVFrameGrabber;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Optional;

/**
 * Compares QR-detection effectiveness between the WinRT capture backend and the OpenCV backend, by running the
 * <em>real</em> decode pipeline ({@link FrameToBitmapConverter} + ZXing via {@link QrCodeProcessor}) on the frames each
 * backend produces.
 *
 * <p>The decode stage is identical for both backends - they both yield a 3-channel BGR {@link Frame} fed to the same
 * ZXing reader - so any difference in decode rate reflects frame <em>quality</em> (resolution, focus, exposure,
 * sharpness, colour fidelity), not the decode algorithm. Point both backends at the same QR code and compare.
 *
 * <p>Run bare on the desktop (both backends work there); inside the MSIX AppContainer only {@code winrt} works. Output
 * is line-oriented {@code key=value}. Exit codes: {@code 0} ran (see {@code decoded_count}), {@code 20} capture open
 * failed, {@code 2} bad arguments.
 */
public final class QrDecodeProbe {
    private QrDecodeProbe() {
    }

    public static void main(String[] args) {
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            System.out.println("result=bad_arguments");
            System.out.println("error=" + e.getMessage());
            System.exit(2);
            return;
        }

        System.out.println("backend=" + options.backend);
        System.out.println("device=" + options.device);
        System.out.println("requested=" + options.width + "x" + options.height);
        System.out.println("frames=" + options.frames);

        FrameGrabber grabber = createGrabber(options);
        grabber.setImageWidth(options.width);
        grabber.setImageHeight(options.height);

        try {
            grabber.start();
        } catch (Exception e) {
            System.out.println("open_result=false");
            System.out.println("error=" + e);
            System.out.println("result=capture_open_failed");
            System.exit(20);
            return;
        }
        System.out.println("open_result=true");
        System.out.println("negotiated=" + grabber.getImageWidth() + "x" + grabber.getImageHeight());

        QrCodeProcessor processor = new QrCodeProcessor(new FrameToBitmapConverter());
        FrameToBitmapConverter diagnosticConverter = new FrameToBitmapConverter();

        int decodedCount = 0;
        long totalDecodeNanos = 0;
        long totalMeanByte = 0;
        int processed = 0;
        String firstPayload = null;
        boolean diagnosed = false;

        try {
            for (int frameIndex = 0; frameIndex < options.frames; frameIndex++) {
                Frame frame = grabber.grab();
                if (frame == null) {
                    System.out.println("grab[" + frameIndex + "]=null");
                    continue;
                }

                // One-time diagnostics on the first real frame: does the BGR->Mat->grayscale conversion that ZXing
                // depends on actually succeed (QrCodeProcessor swallows exceptions, hiding a malformed-frame bug), and
                // dump the frame so the captured image can be inspected visually.
                if (!diagnosed) {
                    diagnosed = true;
                    System.out.println("frame_meta stride=" + frame.imageStride
                            + " channels=" + frame.imageChannels
                            + " depth=" + frame.imageDepth
                            + " bufferCapacity=" + ((ByteBuffer) frame.image[0]).capacity());
                    try {
                        diagnosticConverter.convert(frame);
                        System.out.println("convert_status=ok");
                    } catch (Throwable t) {
                        System.out.println("convert_error=" + t);
                    }
                    if (options.savePath != null) {
                        try {
                            saveFrameBmp(frame, options.savePath);
                            System.out.println("saved_frame=" + options.savePath);
                        } catch (Exception e) {
                            System.out.println("save_error=" + e);
                        }
                    }
                }

                long meanByte = meanByte(frame);
                long startNanos = System.nanoTime();
                Optional<String> qrCode = processor.process(frame);
                long decodeNanos = System.nanoTime() - startNanos;

                processed++;
                totalDecodeNanos += decodeNanos;
                totalMeanByte += meanByte;
                if (qrCode.isPresent()) {
                    decodedCount++;
                    if (firstPayload == null) {
                        firstPayload = qrCode.get();
                    }
                }
                System.out.println("frame[" + frameIndex + "] meanByte=" + meanByte
                        + " decoded=" + qrCode.isPresent()
                        + " decodeMs=" + (decodeNanos / 1_000_000.0));
            }
        } catch (Exception e) {
            System.out.println("grab_error=" + e);
        } finally {
            try {
                grabber.release();
            } catch (Exception ignore) {
                // Best effort.
            }
        }

        double decodeRate = processed == 0 ? 0.0 : (double) decodedCount / processed;
        double avgDecodeMs = processed == 0 ? 0.0 : totalDecodeNanos / 1_000_000.0 / processed;
        long avgMeanByte = processed == 0 ? 0 : totalMeanByte / processed;

        System.out.println("processed_count=" + processed);
        System.out.println("decoded_count=" + decodedCount);
        System.out.println("decode_rate=" + String.format("%.3f", decodeRate));
        System.out.println("avg_decode_ms=" + String.format("%.2f", avgDecodeMs));
        System.out.println("avg_mean_byte=" + avgMeanByte);
        System.out.println("first_payload=" + (firstPayload == null ? "" : firstPayload));
        System.out.println("result=done");
    }

    private static FrameGrabber createGrabber(Options options) {
        return switch (options.backend) {
            case "winrt" -> new WinRtFrameGrabber(options.device);
            case "opencv" -> new OpenCVFrameGrabber(options.device);
            default -> throw new IllegalArgumentException("Unsupported backend: " + options.backend);
        };
    }

    // Writes the BGR frame as a 24-bit BMP (Windows opens it natively) for visual inspection - no image library
    // needed. BMP rows are bottom-up and already in BGR order, matching the frame buffer; only 4-byte row padding and
    // the little-endian headers are added. A correct, sharp image here confirms the WinRT capture is not malformed.
    private static void saveFrameBmp(Frame frame, String path) throws Exception {
        int width = frame.imageWidth;
        int height = frame.imageHeight;
        int stride = frame.imageStride;
        ByteBuffer buffer = (ByteBuffer) frame.image[0];

        int rowSize = ((width * 3 + 3) / 4) * 4; // Padded to a 4-byte boundary.
        int pixelDataSize = rowSize * height;
        int fileSize = 54 + pixelDataSize;

        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(path))) {
            byte[] header = new byte[54];
            header[0] = 'B';
            header[1] = 'M';
            putLittleEndianInt(header, 2, fileSize);
            putLittleEndianInt(header, 10, 54);   // Pixel data offset.
            putLittleEndianInt(header, 14, 40);   // BITMAPINFOHEADER size.
            putLittleEndianInt(header, 18, width);
            putLittleEndianInt(header, 22, height); // Positive => bottom-up.
            header[26] = 1;                        // Planes (little-endian short).
            header[28] = 24;                       // Bits per pixel.
            putLittleEndianInt(header, 34, pixelDataSize);
            out.write(header);

            byte[] row = new byte[rowSize];
            for (int y = height - 1; y >= 0; y--) { // Bottom-up.
                int rowStart = y * stride;
                for (int x = 0; x < width; x++) {
                    int src = rowStart + x * 3;
                    row[x * 3] = buffer.get(src);         // B
                    row[x * 3 + 1] = buffer.get(src + 1); // G
                    row[x * 3 + 2] = buffer.get(src + 2); // R
                }
                out.write(row);
            }
        }
    }

    private static void putLittleEndianInt(byte[] target, int offset, int value) {
        target[offset] = (byte) (value & 0xFF);
        target[offset + 1] = (byte) ((value >> 8) & 0xFF);
        target[offset + 2] = (byte) ((value >> 16) & 0xFF);
        target[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }

    private static long meanByte(Frame frame) {
        ByteBuffer buffer = (ByteBuffer) frame.image[0];
        buffer.clear();
        int capacity = buffer.capacity();
        if (capacity == 0) {
            return 0;
        }
        int step = Math.max(1, capacity / 4096);
        long sum = 0;
        long count = 0;
        for (int index = 0; index < capacity; index += step) {
            sum += buffer.get(index) & 0xFF;
            count++;
        }
        return count == 0 ? 0 : sum / count;
    }

    private record Options(String backend, int device, int width, int height, int frames, String savePath) {
        private static Options parse(String[] args) {
            String backend = "winrt";
            int device = 0;
            int width = 640;
            int height = 480;
            int frames = 60;
            String savePath = null;

            for (int index = 0; index < args.length; index++) {
                switch (args[index]) {
                    case "--backend" -> backend = parseString(args, ++index, "--backend");
                    case "--device" -> device = parseInt(args, ++index, "--device");
                    case "--width" -> width = parseInt(args, ++index, "--width");
                    case "--height" -> height = parseInt(args, ++index, "--height");
                    case "--frames" -> frames = parseInt(args, ++index, "--frames");
                    case "--save" -> savePath = parseString(args, ++index, "--save");
                    default -> throw new IllegalArgumentException("Unsupported argument: " + args[index]);
                }
            }
            if (!backend.equals("winrt") && !backend.equals("opencv")) {
                throw new IllegalArgumentException("--backend must be winrt or opencv");
            }
            if (frames < 1) {
                throw new IllegalArgumentException("--frames must be at least 1");
            }
            if (width < 1 || height < 1) {
                throw new IllegalArgumentException("--width and --height must be positive");
            }
            return new Options(backend, device, width, height, frames, savePath);
        }

        private static int parseInt(String[] args, int index, String name) {
            return Integer.parseInt(parseString(args, index, name));
        }

        private static String parseString(String[] args, int index, String name) {
            if (index >= args.length) {
                throw new IllegalArgumentException(name + " requires a value");
            }
            return args[index];
        }
    }
}
