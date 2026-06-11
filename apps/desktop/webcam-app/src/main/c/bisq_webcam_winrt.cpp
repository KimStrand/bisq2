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

/*
 * Windows WinRT camera capture shim for the Bisq webcam helper.
 *
 * Captures frames via Windows.Media.Capture (MediaCapture + MediaFrameReader) and exposes them to Java over a small
 * JNI surface (see bisq.webcam.service.capture.WinRtCamera). WinRT is the AppContainer-sanctioned capture path: it
 * honours the "webcam" capability and the system consent broker, whereas OpenCV's VideoCapture/MSMF path cannot open
 * the camera from inside the sandbox. Capture therefore stays fully in-process inside the AppContainer; only the
 * capture backend differs from the OpenCV-based grabber used on Linux/macOS.
 *
 * Frames are delivered as tightly packed 24-bit BGR (the channel order JavaCV/OpenCV expect) written straight into the
 * Java Frame's native image buffer, so the existing preview and ZXing decode pipeline is unchanged.
 *
 * Lifetime/threading: the FrameArrived handler runs on WinRT thread-pool threads, concurrently with the Java capture
 * thread (grab) and the close call. Revoking the event and StopAsync do NOT wait for an in-flight handler, so the
 * capture context is owned by a std::shared_ptr and the handler holds only a std::weak_ptr; closing drops the owning
 * reference, and an in-flight handler keeps the context alive (via weak_ptr::lock) until it returns. This removes the
 * use-after-free that a plain delete would cause.
 */

#include <jni.h>

#include <windows.h>
#include <unknwn.h>

#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Foundation.Collections.h>
#include <winrt/Windows.Devices.Enumeration.h>
#include <winrt/Windows.Graphics.Imaging.h>
#include <winrt/Windows.Media.Capture.h>
#include <winrt/Windows.Media.Capture.Frames.h>
#include <winrt/Windows.Media.MediaProperties.h>

#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <exception>
#include <memory>
#include <mutex>
#include <vector>

using namespace winrt;
using namespace winrt::Windows::Devices::Enumeration;
using namespace winrt::Windows::Graphics::Imaging;
using namespace winrt::Windows::Media::Capture;
using namespace winrt::Windows::Media::Capture::Frames;
using namespace winrt::Windows::Media::MediaProperties;

// Classic-COM accessor for the raw bytes behind a WinRT IMemoryBufferReference.
struct __declspec(uuid("5B0D3235-4DBA-4D44-865E-8F1D0E4FD04D")) IMemoryBufferByteAccess : ::IUnknown {
    virtual HRESULT __stdcall GetBuffer(uint8_t** value, uint32_t* capacity) = 0;
};

namespace {

// Prefixed to avoid colliding with the ERROR_* macros from <winerror.h> (e.g. ERROR_INVALID_HANDLE, ERROR_TIMEOUT).
constexpr int32_t CAPTURE_ERR_INVALID_HANDLE = -1;
constexpr int32_t CAPTURE_ERR_TIMEOUT = -2;
constexpr int32_t CAPTURE_ERR_NO_DESTINATION = -3;
constexpr int32_t CAPTURE_ERR_DESTINATION_TOO_SMALL = -4;
constexpr int32_t CAPTURE_ERR_EXCEPTION = -10;

constexpr int OPEN_FIRST_FRAME_TIMEOUT_MILLIS = 5000;

// Each thread entering WinRT must be in a COM apartment. JNI calls arrive on arbitrary Java threads, so initialize a
// multi-threaded apartment lazily per thread. RPC_E_CHANGED_MODE means the thread is already in an apartment we can
// use, which is fine.
void ensureApartment() {
    static thread_local bool initialized = false;
    if (initialized) {
        return;
    }
    try {
        init_apartment(apartment_type::multi_threaded);
    } catch (hresult_error const& e) {
        if (e.code() != RPC_E_CHANGED_MODE) {
            throw;
        }
    }
    initialized = true;
}

struct CaptureContext {
    MediaCapture mediaCapture{nullptr};
    MediaFrameReader frameReader{nullptr};
    event_token frameArrivedToken{};

    std::mutex mutex;
    std::condition_variable frameAvailable;
    std::vector<uint8_t> bgr; // Tightly packed width*height*3, guarded by mutex.
    int width = 0;
    int height = 0;
    uint64_t frameSequence = 0; // Incremented per delivered frame.
    uint64_t lastConsumedSequence = 0; // Highest sequence already returned by grab().
    bool firstFrameReceived = false;

    // Revokes the FrameArrived handler and stops the reader. Safe to call more than once and from the error paths.
    // Does not free the context: see the file header note on shared_ptr lifetime.
    void stop() {
        try {
            if (frameReader) {
                frameReader.FrameArrived(frameArrivedToken);
                frameReader.StopAsync().get();
            }
        } catch (...) {
            // Ignore teardown failures.
        }
    }

    // Copies the latest WinRT frame (converted to Bgra8 by the reader) into the BGR buffer and signals waiters.
    // Runs on a WinRT thread-pool thread; any exception is swallowed so it never escapes the delegate, and the
    // OS/driver-supplied plane geometry is validated against the actually locked buffer before reading (the camera is
    // untrusted input - the whole reason for the sandbox).
    void onFrameArrived(MediaFrameReader const& sender) {
        try {
            MediaFrameReference reference = sender.TryAcquireLatestFrame();
            if (!reference) {
                return;
            }
            VideoMediaFrame videoFrame = reference.VideoMediaFrame();
            if (!videoFrame) {
                return;
            }
            SoftwareBitmap bitmap = videoFrame.SoftwareBitmap();
            if (!bitmap) {
                return;
            }

            SoftwareBitmap converted = bitmap;
            if (bitmap.BitmapPixelFormat() != BitmapPixelFormat::Bgra8) {
                converted = SoftwareBitmap::Convert(bitmap, BitmapPixelFormat::Bgra8, BitmapAlphaMode::Ignore);
            }

            BitmapBuffer bitmapBuffer = converted.LockBuffer(BitmapBufferAccessMode::Read);
            BitmapPlaneDescription plane = bitmapBuffer.GetPlaneDescription(0);
            // auto avoids naming winrt::Windows::Foundation::IMemoryBufferReference (that namespace is not imported).
            auto memoryReference = bitmapBuffer.CreateReference();

            auto byteAccess = memoryReference.as<IMemoryBufferByteAccess>();
            uint8_t* data = nullptr;
            uint32_t capacity = 0;
            check_hresult(byteAccess->GetBuffer(&data, &capacity));

            const int frameWidth = plane.Width;
            const int frameHeight = plane.Height;
            const int stride = plane.Stride;
            const int startIndex = plane.StartIndex;

            // Reject geometry that would read outside the locked Bgra8 buffer (4 bytes/pixel).
            const bool valid =
                    data != nullptr &&
                    frameWidth > 0 && frameHeight > 0 &&
                    startIndex >= 0 &&
                    stride >= frameWidth * 4 &&
                    (static_cast<int64_t>(startIndex)
                     + static_cast<int64_t>(frameHeight - 1) * stride
                     + static_cast<int64_t>(frameWidth) * 4) <= static_cast<int64_t>(capacity);

            if (valid) {
                std::lock_guard<std::mutex> lock(mutex);
                width = frameWidth;
                height = frameHeight;
                bgr.resize(static_cast<size_t>(frameWidth) * frameHeight * 3);
                for (int y = 0; y < frameHeight; ++y) {
                    const uint8_t* sourceRow = data + startIndex + static_cast<size_t>(y) * stride;
                    uint8_t* destRow = bgr.data() + static_cast<size_t>(y) * frameWidth * 3;
                    for (int x = 0; x < frameWidth; ++x) {
                        // WinRT Bgra8 byte order is B, G, R, A; drop alpha to produce packed BGR.
                        destRow[x * 3 + 0] = sourceRow[x * 4 + 0];
                        destRow[x * 3 + 1] = sourceRow[x * 4 + 1];
                        destRow[x * 3 + 2] = sourceRow[x * 4 + 2];
                    }
                }
                ++frameSequence;
                firstFrameReceived = true;
            }

            memoryReference.Close();
            bitmapBuffer.Close();

            if (valid) {
                frameAvailable.notify_all();
            }
        } catch (...) {
            // Drop the malformed frame; never let an exception escape into the WinRT thread pool.
        }
    }
};

// Returns the WinRT video-capture device id at the given index, or an empty string if out of range.
hstring deviceIdAt(int deviceIndex) {
    DeviceInformationCollection devices =
            DeviceInformation::FindAllAsync(DeviceClass::VideoCapture).get();
    if (deviceIndex < 0 || static_cast<uint32_t>(deviceIndex) >= devices.Size()) {
        return hstring{};
    }
    return devices.GetAt(deviceIndex).Id();
}

// Picks the first color frame source on the MediaCapture.
MediaFrameSource findColorSource(MediaCapture const& mediaCapture) {
    for (auto const& entry : mediaCapture.FrameSources()) {
        MediaFrameSource source = entry.Value();
        if (source.Info().SourceKind() == MediaFrameSourceKind::Color) {
            return source;
        }
    }
    return nullptr;
}

// Best-effort: set a capture format. Prefer an exact match for the requested size; otherwise pick the smallest format
// that is at least the requested size, so the reader does not default to a high-resolution format that needlessly
// inflates per-frame CPU and buffer sizes. The reader still converts to Bgra8 regardless of the source subtype, so a
// mismatch here is non-fatal; failures are ignored and the default format is kept.
void selectFormat(MediaFrameSource const& source, int requestedWidth, int requestedHeight) {
    if (requestedWidth <= 0 || requestedHeight <= 0) {
        return;
    }
    MediaFrameFormat exactMatch{nullptr};
    MediaFrameFormat nearestAtLeast{nullptr};
    int64_t nearestArea = 0;
    for (auto const& format : source.SupportedFormats()) {
        VideoMediaFrameFormat videoFormat = format.VideoFormat();
        if (!videoFormat) {
            continue;
        }
        const int formatWidth = static_cast<int>(videoFormat.Width());
        const int formatHeight = static_cast<int>(videoFormat.Height());
        if (formatWidth == requestedWidth && formatHeight == requestedHeight) {
            exactMatch = format;
            break;
        }
        if (formatWidth >= requestedWidth && formatHeight >= requestedHeight) {
            const int64_t area = static_cast<int64_t>(formatWidth) * formatHeight;
            if (!nearestAtLeast || area < nearestArea) {
                nearestAtLeast = format;
                nearestArea = area;
            }
        }
    }
    MediaFrameFormat chosen = exactMatch ? exactMatch : nearestAtLeast;
    if (chosen) {
        try {
            source.SetFormatAsync(chosen).get();
        } catch (...) {
            // Keep the default format.
        }
    }
}

std::shared_ptr<CaptureContext>* asOwner(jlong handle) {
    return reinterpret_cast<std::shared_ptr<CaptureContext>*>(handle);
}

void logFailStage(const wchar_t* stage) {
    fwprintf(stderr, L"winrt_open_fail stage=%ls\n", stage);
    fflush(stderr);
}

void logFailHresult(const wchar_t* stage, hresult_error const& e) {
    fwprintf(stderr, L"winrt_open_fail stage=%ls hr=0x%08X msg=%ls\n",
             stage, static_cast<unsigned int>(e.code()), e.message().c_str());
    fflush(stderr);
}

} // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_bisq_webcam_service_capture_WinRtCamera_nativeCountDevices(JNIEnv*, jclass) {
    try {
        ensureApartment();
        DeviceInformationCollection devices =
                DeviceInformation::FindAllAsync(DeviceClass::VideoCapture).get();
        return static_cast<jint>(devices.Size());
    } catch (...) {
        return 0;
    }
}

JNIEXPORT jlong JNICALL
Java_bisq_webcam_service_capture_WinRtCamera_nativeOpen(JNIEnv*, jclass,
                                                        jint deviceIndex,
                                                        jint requestedWidth,
                                                        jint requestedHeight) {
    std::shared_ptr<CaptureContext> context;
    try {
        ensureApartment();

        hstring deviceId = deviceIdAt(deviceIndex);
        if (deviceId.empty()) {
            logFailStage(L"device_enum_empty");
            return 0;
        }

        context = std::make_shared<CaptureContext>();

        MediaCaptureInitializationSettings settings;
        settings.StreamingCaptureMode(StreamingCaptureMode::Video);
        settings.MemoryPreference(MediaCaptureMemoryPreference::Cpu);
        settings.VideoDeviceId(deviceId);

        context->mediaCapture = MediaCapture();
        context->mediaCapture.InitializeAsync(settings).get();

        MediaFrameSource source = findColorSource(context->mediaCapture);
        if (!source) {
            logFailStage(L"no_color_source");
            return 0;
        }
        selectFormat(source, requestedWidth, requestedHeight);

        context->frameReader =
                context->mediaCapture.CreateFrameReaderAsync(source, MediaEncodingSubtypes::Bgra8()).get();

        // The handler holds only a weak reference, so a frame arriving during/after close cannot resurrect or
        // outlive-and-then-touch a freed context.
        std::weak_ptr<CaptureContext> weakContext = context;
        context->frameArrivedToken = context->frameReader.FrameArrived(
                [weakContext](MediaFrameReader const& sender, MediaFrameArrivedEventArgs const&) {
                    if (auto self = weakContext.lock()) {
                        self->onFrameArrived(sender);
                    }
                });

        MediaFrameReaderStartStatus startStatus = context->frameReader.StartAsync().get();
        if (startStatus != MediaFrameReaderStartStatus::Success) {
            fwprintf(stderr, L"winrt_open_fail stage=reader_start status=%d\n", static_cast<int>(startStatus));
            fflush(stderr);
            context->stop();
            return 0;
        }

        // Block until the first frame so the negotiated resolution is known to the caller.
        {
            std::unique_lock<std::mutex> lock(context->mutex);
            bool received = context->frameAvailable.wait_for(
                    lock,
                    std::chrono::milliseconds(OPEN_FIRST_FRAME_TIMEOUT_MILLIS),
                    [&context] { return context->firstFrameReceived; });
            if (!received) {
                lock.unlock();
                logFailStage(L"first_frame_timeout");
                context->stop();
                return 0;
            }
        }

        // Hand ownership to Java as an opaque heap-allocated shared_ptr.
        return reinterpret_cast<jlong>(new std::shared_ptr<CaptureContext>(context));
    } catch (hresult_error const& e) {
        // Most diagnostic case: MediaCapture.InitializeAsync throwing E_ACCESSDENIED (0x80070005) here means the
        // camera consent broker denied the capture - typically because this synthetic AppContainer has no real MSIX
        // package identity - rather than WinRT being unable to run in a sandbox at all.
        logFailHresult(L"exception", e);
        if (context) {
            context->stop();
        }
        return 0;
    } catch (std::exception const& e) {
        fprintf(stderr, "winrt_open_fail stage=exception std=%s\n", e.what());
        fflush(stderr);
        if (context) {
            context->stop();
        }
        return 0;
    } catch (...) {
        logFailStage(L"exception_unknown");
        if (context) {
            context->stop();
        }
        return 0;
    }
    // On every early return above, the local shared_ptr drops here; the context is destroyed once no in-flight
    // handler still holds it.
}

JNIEXPORT jint JNICALL
Java_bisq_webcam_service_capture_WinRtCamera_nativeWidth(JNIEnv*, jclass, jlong handle) {
    std::shared_ptr<CaptureContext>* owner = asOwner(handle);
    if (owner == nullptr) {
        return 0;
    }
    CaptureContext* context = owner->get();
    std::lock_guard<std::mutex> lock(context->mutex);
    return static_cast<jint>(context->width);
}

JNIEXPORT jint JNICALL
Java_bisq_webcam_service_capture_WinRtCamera_nativeHeight(JNIEnv*, jclass, jlong handle) {
    std::shared_ptr<CaptureContext>* owner = asOwner(handle);
    if (owner == nullptr) {
        return 0;
    }
    CaptureContext* context = owner->get();
    std::lock_guard<std::mutex> lock(context->mutex);
    return static_cast<jint>(context->height);
}

JNIEXPORT jint JNICALL
Java_bisq_webcam_service_capture_WinRtCamera_nativeGrab(JNIEnv* env, jclass,
                                                        jlong handle,
                                                        jobject directDestination,
                                                        jint timeoutMillis) {
    std::shared_ptr<CaptureContext>* owner = asOwner(handle);
    if (owner == nullptr) {
        return CAPTURE_ERR_INVALID_HANDLE;
    }
    CaptureContext* context = owner->get();

    auto* destination = static_cast<uint8_t*>(env->GetDirectBufferAddress(directDestination));
    if (destination == nullptr) {
        return CAPTURE_ERR_NO_DESTINATION;
    }
    jlong destinationCapacity = env->GetDirectBufferCapacity(directDestination);

    try {
        std::unique_lock<std::mutex> lock(context->mutex);
        // Block until a frame newer than the last one returned by grab() is available.
        bool received = context->frameAvailable.wait_for(
                lock,
                std::chrono::milliseconds(timeoutMillis),
                [context] { return context->frameSequence > context->lastConsumedSequence; });
        if (!received) {
            return CAPTURE_ERR_TIMEOUT;
        }

        // Guards against a mid-stream resolution increase: the Java-side buffer is sized from the first frame, so a
        // larger frame is reported as an error rather than overflowing the destination.
        const size_t required = static_cast<size_t>(context->width) * context->height * 3;
        if (destinationCapacity < static_cast<jlong>(required)) {
            return CAPTURE_ERR_DESTINATION_TOO_SMALL;
        }
        std::memcpy(destination, context->bgr.data(), required);
        context->lastConsumedSequence = context->frameSequence;
        return 0;
    } catch (...) {
        return CAPTURE_ERR_EXCEPTION;
    }
}

JNIEXPORT void JNICALL
Java_bisq_webcam_service_capture_WinRtCamera_nativeClose(JNIEnv*, jclass, jlong handle) {
    std::shared_ptr<CaptureContext>* owner = asOwner(handle);
    if (owner == nullptr) {
        return;
    }
    // Revoke + stop, then drop the owning reference. An in-flight FrameArrived handler holds the context alive via its
    // weak_ptr lock until it returns, so this cannot free a context that is still being touched.
    (*owner)->stop();
    delete owner;
}

} // extern "C"
