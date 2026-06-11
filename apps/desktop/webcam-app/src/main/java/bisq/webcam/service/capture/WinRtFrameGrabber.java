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
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.bytedeco.opencv.opencv_core.Mat;

import java.nio.ByteBuffer;

import static org.bytedeco.opencv.global.opencv_core.CV_8UC3;

/**
 * A JavaCV {@link FrameGrabber} backed by the Windows WinRT camera shim ({@link WinRtCamera}) instead of OpenCV's
 * {@code VideoCapture}/MSMF, which cannot open the camera inside the Windows AppContainer sandbox.
 *
 * <p>The native shim writes each captured frame as tightly packed 24-bit BGR directly into the native data buffer of a
 * reused OpenCV {@link Mat}, which is then wrapped into a {@link Frame} via {@link OpenCVFrameConverter.ToMat} - the
 * same Mat-backed frame the OpenCV grabber produces on the other platforms. This matters: the resulting frame carries
 * the {@code Mat} as its {@code opaque} payload, so the downstream {@code FrameToMatConverter} returns it via the
 * opaque fast-path instead of trying to rebuild a Mat from a hand-constructed frame buffer (which fails). Preview
 * rendering and ZXing QR decoding are therefore unchanged.
 */
public class WinRtFrameGrabber extends FrameGrabber {
    private static final int DEFAULT_WIDTH = 640;
    private static final int DEFAULT_HEIGHT = 480;
    // Bounds a single grab against a stalled/disconnected camera so the capture loop fails instead of hanging.
    private static final int GRAB_TIMEOUT_MILLIS = 5_000;

    private final int deviceIndex;
    private WinRtCamera camera;
    private OpenCVFrameConverter.ToMat matConverter;
    private Mat bgrMat;
    private ByteBuffer matBuffer;

    public WinRtFrameGrabber(int deviceIndex) {
        this.deviceIndex = deviceIndex;
    }

    public static int countDevices() {
        return WinRtCamera.countDevices();
    }

    @Override
    public void start() throws Exception {
        if (camera != null) {
            return;
        }
        try {
            int requestedWidth = imageWidth > 0 ? imageWidth : DEFAULT_WIDTH;
            int requestedHeight = imageHeight > 0 ? imageHeight : DEFAULT_HEIGHT;
            camera = WinRtCamera.open(deviceIndex, requestedWidth, requestedHeight);
            // The camera may negotiate a different resolution than requested; adopt the actual one.
            imageWidth = camera.getWidth();
            imageHeight = camera.getHeight();

            // A contiguous BGR Mat; the shim writes straight into its native buffer, so there is no copy across JNI
            // and the converted Frame is Mat-backed (opaque), which the decode/preview pipeline expects.
            bgrMat = new Mat(imageHeight, imageWidth, CV_8UC3);
            matBuffer = bgrMat.createBuffer();
            matConverter = new OpenCVFrameConverter.ToMat();
        } catch (RuntimeException | LinkageError e) {
            // LinkageError covers UnsatisfiedLinkError when the native shim DLL is missing/unloadable.
            release();
            throw new Exception("Starting WinRT frame grabber for device index " + deviceIndex + " failed", e);
        }
    }

    @Override
    public Frame grab() throws Exception {
        if (camera == null || bgrMat == null || matConverter == null) {
            throw new Exception("WinRT frame grabber is not started");
        }
        matBuffer.clear();
        int result = camera.grab(matBuffer, GRAB_TIMEOUT_MILLIS);
        if (result != 0) {
            throw new Exception("WinRT frame grab failed with native error code " + result);
        }
        return matConverter.convert(bgrMat);
    }

    @Override
    public void trigger() {
    }

    @Override
    public void stop() throws Exception {
        release();
    }

    @Override
    public void release() {
        if (camera != null) {
            camera.close();
            camera = null;
        }
        if (matConverter != null) {
            matConverter.close();
            matConverter = null;
        }
        if (bgrMat != null) {
            bgrMat.close();
            bgrMat = null;
        }
        matBuffer = null;
    }
}
