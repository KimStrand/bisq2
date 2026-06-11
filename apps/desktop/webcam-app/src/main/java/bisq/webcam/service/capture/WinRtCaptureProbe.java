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

import org.bytedeco.javacv.Frame;

import java.nio.ByteBuffer;

/**
 * Headless validation harness for the Windows WinRT capture backend ({@link WinRtFrameGrabber} / {@link WinRtCamera}).
 *
 * <p>It opens the camera, grabs a few frames, and reports the negotiated resolution plus a per-frame brightness
 * summary so a human (or a script) can tell that real, non-black frames are flowing — without the JavaFX window or the
 * stdio IPC bootstrap of the full {@code WebcamApp}. Run it bare first, then wrapped in the AppContainer launcher, to
 * separate "does WinRT capture work at all" from "does it work inside the sandbox".
 *
 * <p>Output is line-oriented {@code key=value} (mirroring {@code tools/.../CamProbe.java}). Exit codes:
 * {@code 0} frames flowing, {@code 20} camera open failed, {@code 21} no frames grabbed, {@code 2} bad arguments.
 */
public final class WinRtCaptureProbe {
    private WinRtCaptureProbe() {
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

        System.out.println("java.version=" + System.getProperty("java.version"));
        System.out.println("os.name=" + System.getProperty("os.name"));
        System.out.println("java.library.path=" + System.getProperty("java.library.path"));
        System.out.println("device=" + options.device);
        System.out.println("requested=" + options.width + "x" + options.height);
        System.out.println("frames=" + options.frames);

        try {
            System.out.println("device_count=" + WinRtFrameGrabber.countDevices());
        } catch (Throwable t) {
            System.out.println("device_count_error=" + t);
        }

        WinRtFrameGrabber grabber = new WinRtFrameGrabber(options.device);
        grabber.setImageWidth(options.width);
        grabber.setImageHeight(options.height);

        try {
            grabber.start();
        } catch (Exception e) {
            System.out.println("open_result=false");
            System.out.println("error=" + e);
            System.out.println("result=camera_open_failed");
            System.exit(20);
            return;
        }

        System.out.println("open_result=true");
        System.out.println("negotiated=" + grabber.getImageWidth() + "x" + grabber.getImageHeight());

        int grabbedCount = 0;
        try {
            for (int frameIndex = 0; frameIndex < options.frames; frameIndex++) {
                Frame frame = grabber.grab();
                grabbedCount++;
                System.out.println("grab[" + frameIndex + "]=ok"
                        + " size=" + frame.imageWidth + "x" + frame.imageHeight
                        + " meanByte=" + meanByte(frame));
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

        System.out.println("grabbed_count=" + grabbedCount);
        if (grabbedCount == 0) {
            System.out.println("result=no_frames");
            System.exit(21);
            return;
        }
        System.out.println("result=frames_flowing");
    }

    // Mean of a sampled subset of bytes; a value well above 0 (and varying across frames) indicates a live image
    // rather than an all-black buffer.
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

    private record Options(int device, int width, int height, int frames) {
        private static Options parse(String[] args) {
            int device = 0;
            int width = 640;
            int height = 480;
            int frames = 10;

            for (int index = 0; index < args.length; index++) {
                switch (args[index]) {
                    case "--device" -> device = parseInt(args, ++index, "--device");
                    case "--width" -> width = parseInt(args, ++index, "--width");
                    case "--height" -> height = parseInt(args, ++index, "--height");
                    case "--frames" -> frames = parseInt(args, ++index, "--frames");
                    default -> throw new IllegalArgumentException("Unsupported argument: " + args[index]);
                }
            }
            if (frames < 1) {
                throw new IllegalArgumentException("--frames must be at least 1");
            }
            if (width < 1 || height < 1) {
                throw new IllegalArgumentException("--width and --height must be positive");
            }
            return new Options(device, width, height, frames);
        }

        private static int parseInt(String[] args, int index, String name) {
            if (index >= args.length) {
                throw new IllegalArgumentException(name + " requires a value");
            }
            return Integer.parseInt(args[index]);
        }
    }
}
