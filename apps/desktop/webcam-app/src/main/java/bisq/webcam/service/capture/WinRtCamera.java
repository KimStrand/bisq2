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

import java.nio.ByteBuffer;

/**
 * Thin JNI binding over the Windows {@code bisq_webcam_winrt} native shim, which captures camera frames through
 * {@code Windows.Media.Capture} (WinRT {@code MediaCapture} + {@code MediaFrameReader}).
 *
 * <p>This is the AppContainer-compatible replacement for OpenCV's {@code VideoCapture}/MSMF capture path: WinRT honours
 * the AppContainer {@code webcam} capability and the system consent broker, whereas OpenCV/MSMF cannot open the camera
 * from inside the sandbox. Capture stays fully in-process inside the sandbox; only the capture <i>backend</i> differs
 * from the other platforms. Decode (ZXing) and preview keep consuming a JavaCV {@code Frame} unchanged.
 *
 * <p>Frames are delivered as tightly packed 24-bit BGR (channel order matching OpenCV/JavaCV's default) written
 * directly into a caller-supplied direct {@link ByteBuffer}, so no copy crosses the JNI boundary on the hot path.
 *
 * <p>Not thread-safe: a single instance is owned by one {@link WinRtFrameGrabber} capture thread.
 */
final class WinRtCamera implements AutoCloseable {
    private static final Object NATIVE_LIBRARY_LOCK = new Object();
    private static volatile boolean nativeLibraryLoaded;

    private final int width;
    private final int height;
    private long handle; // Native pointer to the capture context; 0 once closed.

    private WinRtCamera(long handle, int width, int height) {
        this.handle = handle;
        this.width = width;
        this.height = height;
    }

    static void loadNativeLibrary() {
        if (nativeLibraryLoaded) {
            return;
        }
        synchronized (NATIVE_LIBRARY_LOCK) {
            if (!nativeLibraryLoaded) {
                // Resolved from java.library.path, which the AppContainer sandbox policy points at the read-only
                // packaged webcam dir where the DLL is co-located with the OpenCV/JavaCPP natives.
                System.loadLibrary("bisq_webcam_winrt");
                nativeLibraryLoaded = true;
            }
        }
    }

    static int countDevices() {
        loadNativeLibrary();
        return nativeCountDevices();
    }

    static WinRtCamera open(int deviceIndex, int requestedWidth, int requestedHeight) {
        loadNativeLibrary();
        long handle = nativeOpen(deviceIndex, requestedWidth, requestedHeight);
        if (handle == 0L) {
            throw new IllegalStateException("WinRT camera open failed for device index " + deviceIndex);
        }
        int width = nativeWidth(handle);
        int height = nativeHeight(handle);
        if (width <= 0 || height <= 0) {
            nativeClose(handle);
            throw new IllegalStateException("WinRT camera reported invalid frame dimensions "
                    + width + "x" + height + " for device index " + deviceIndex);
        }
        return new WinRtCamera(handle, width, height);
    }

    int getWidth() {
        return width;
    }

    int getHeight() {
        return height;
    }

    /**
     * Blocks until the next camera frame arrives (or {@code timeoutMillis} elapses) and writes it as tightly packed
     * 24-bit BGR into {@code directDestination}.
     *
     * @param directDestination a direct {@link ByteBuffer} with capacity >= width * height * 3
     * @return 0 on success, a negative native error code otherwise
     */
    int grab(ByteBuffer directDestination, int timeoutMillis) {
        if (handle == 0L) {
            throw new IllegalStateException("WinRT camera is closed");
        }
        if (!directDestination.isDirect()) {
            throw new IllegalArgumentException("Destination buffer must be direct");
        }
        int required = width * height * 3;
        if (directDestination.capacity() < required) {
            throw new IllegalArgumentException("Destination buffer too small: capacity="
                    + directDestination.capacity() + " required=" + required);
        }
        return nativeGrab(handle, directDestination, timeoutMillis);
    }

    @Override
    public void close() {
        if (handle != 0L) {
            nativeClose(handle);
            handle = 0L;
        }
    }

    private static native int nativeCountDevices();

    private static native long nativeOpen(int deviceIndex, int requestedWidth, int requestedHeight);

    private static native int nativeWidth(long handle);

    private static native int nativeHeight(long handle);

    private static native int nativeGrab(long handle, ByteBuffer directDestination, int timeoutMillis);

    private static native void nativeClose(long handle);
}
