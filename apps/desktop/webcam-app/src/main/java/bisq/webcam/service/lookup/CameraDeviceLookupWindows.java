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

package bisq.webcam.service.lookup;

import bisq.common.observable.Observable;
import bisq.common.threading.ExecutorFactory;
import bisq.webcam.service.ErrorCode;
import bisq.webcam.service.WebcamException;
import bisq.webcam.service.capture.WinRtFrameGrabber;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacv.FrameGrabber;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Windows camera lookup backed by the WinRT capture shim ({@link WinRtFrameGrabber}) instead of OpenCV's
 * {@code VideoCapture}, which cannot open the camera inside the AppContainer sandbox.
 * <p>
 * WinRT enumerates video capture devices directly, so device count comes from the native shim rather than probing
 * indices blindly. Each candidate is verified by starting a short-lived grabber, which surfaces permission/consent
 * failures early, mirroring the other platforms' lookups.
 */
@Slf4j
public class CameraDeviceLookupWindows implements CameraDeviceLookup {
    @Getter
    private final Observable<Integer> deviceNumber = new Observable<>(0);
    @Getter
    private final Observable<Integer> numDevices = new Observable<>(0);

    public CameraDeviceLookupWindows() {
    }

    public CompletableFuture<FrameGrabber> find() {
        return CompletableFuture.supplyAsync(() -> {
            int deviceCount;
            try {
                deviceCount = WinRtFrameGrabber.countDevices();
            } catch (Throwable t) {
                log.error("Counting WinRT camera devices failed", t);
                throw new WebcamException(ErrorCode.NUM_DEVICE_COUNT_FAILED, t);
            }
            numDevices.set(deviceCount);
            if (deviceCount <= 0) {
                throw new WebcamException(ErrorCode.NO_DEVICE_FOUND);
            }

            Throwable ignoredException = null;
            do {
                try {
                    return find(deviceNumber.get()).get();
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof WebcamException || cause instanceof TimeoutException) {
                        ignoredException = cause;
                        log.warn("Camera with deviceNumber {} not found. We try the next one. Error message={}", deviceNumber, e.getMessage());
                        deviceNumber.set(deviceNumber.get() + 1);
                    } else {
                        throw new WebcamException(ErrorCode.EXECUTION_EXCEPTION, e);
                    }
                } catch (InterruptedException e) {
                    log.warn("Thread got interrupted at find method", e);
                    Thread.currentThread().interrupt(); // Restore interrupted state
                    throw new WebcamException(ErrorCode.INTERRUPTED_EXCEPTION, e);
                }
            } while (deviceNumber.get() < deviceCount && !Thread.currentThread().isInterrupted());

            ErrorCode errorCode = ignoredException instanceof WebcamException
                    ? ((WebcamException) ignoredException).getErrorCode()
                    : ErrorCode.NO_DEVICE_FOUND;
            throw new WebcamException(errorCode, ignoredException);
        }, ExecutorFactory.newSingleThreadScheduledExecutor("look-up-camera"));
    }

    public CompletableFuture<FrameGrabber> find(int deviceNumber) {
        log.info("Try to find camera with device number {}", deviceNumber);
        return CompletableFuture.<FrameGrabber>supplyAsync(() -> {
                    try (FrameGrabber probeFrameGrabber = new WinRtFrameGrabber(deviceNumber)) {
                        probeFrameGrabber.start();
                        return new WinRtFrameGrabber(deviceNumber);
                    } catch (Exception e) {
                        log.error("Camera device lookup failed. {}", e.getMessage());
                        throw new WebcamException(ErrorCode.DEVICE_LOOKUP_FAILED, e);
                    }
                }, ExecutorFactory.newSingleThreadScheduledExecutor("look-up-device"))
                .orTimeout(20, TimeUnit.SECONDS);
    }
}
