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

// Prefixed to avoid colliding with the ERROR_* macros from <winerror.h> (e.g. CAPTURE_ERR_INVALID_HANDLE, CAPTURE_ERR_TIMEOUT).
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

    // Copies the latest WinRT frame (converted to Bgra8 by the reader) into the BGR buffer and signals waiters.
    void onFrameArrived(MediaFrameReader const& sender) {
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
        // auto avoids naming winrt::Windows::Foundation::IMemoryBufferReference (that namespace is not imported here).
        auto memoryReference = bitmapBuffer.CreateReference();

        auto byteAccess = memoryReference.as<IMemoryBufferByteAccess>();
        uint8_t* data = nullptr;
        uint32_t capacity = 0;
        check_hresult(byteAccess->GetBuffer(&data, &capacity));

        const int frameWidth = plane.Width;
        const int frameHeight = plane.Height;
        const int stride = plane.Stride;
        const int startIndex = plane.StartIndex;

        {
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
        frameAvailable.notify_all();

        memoryReference.Close();
        bitmapBuffer.Close();
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

CaptureContext* asContext(jlong handle) {
    return reinterpret_cast<CaptureContext*>(handle);
}

// Diagnostics for the open path: a single failure means "no camera" to Java, so the actual stage + HRESULT are
// printed to stderr (forwarded to the console / launcher log) to distinguish a fundamental AppContainer block from a
// consent/identity denial (E_ACCESSDENIED 0x80070005) or a no-frames timeout.
void logFailStage(const wchar_t* stage) {
    fwprintf(stderr, L"winrt_open_fail stage=%ls\n", stage);
    fflush(stderr);
}

void logFailHresult(const wchar_t* stage, hresult_error const& e) {
    fwprintf(stderr, L"winrt_open_fail stage=%ls hr=0x%08X msg=%ls\n",
             stage, static_cast<unsigned int>(e.code()), e.message().c_str());
    fflush(stderr);
}

void cleanupContextOnError(CaptureContext* context) {
    if (context == nullptr) {
        return;
    }
    try {
        if (context->frameReader) {
            context->frameReader.StopAsync().get();
        }
    } catch (...) {
        // Ignore cleanup failures.
    }
    delete context;
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
    CaptureContext* context = nullptr;
    try {
        ensureApartment();

        hstring deviceId = deviceIdAt(deviceIndex);
        if (deviceId.empty()) {
            logFailStage(L"device_enum_empty");
            return 0;
        }

        context = new CaptureContext();

        MediaCaptureInitializationSettings settings;
        settings.StreamingCaptureMode(StreamingCaptureMode::Video);
        settings.MemoryPreference(MediaCaptureMemoryPreference::Cpu);
        settings.VideoDeviceId(deviceId);

        context->mediaCapture = MediaCapture();
        context->mediaCapture.InitializeAsync(settings).get();

        MediaFrameSource source = findColorSource(context->mediaCapture);
        if (!source) {
            logFailStage(L"no_color_source");
            delete context;
            return 0;
        }

        // Best-effort: prefer a source format matching the requested resolution. The reader still converts to Bgra8
        // regardless of the source subtype, so a mismatch here is non-fatal.
        if (requestedWidth > 0 && requestedHeight > 0) {
            for (auto const& format : source.SupportedFormats()) {
                VideoMediaFrameFormat videoFormat = format.VideoFormat();
                if (videoFormat &&
                    static_cast<int>(videoFormat.Width()) == requestedWidth &&
                    static_cast<int>(videoFormat.Height()) == requestedHeight) {
                    try {
                        source.SetFormatAsync(format).get();
                    } catch (...) {
                        // Ignore: keep the default format.
                    }
                    break;
                }
            }
        }

        context->frameReader =
                context->mediaCapture.CreateFrameReaderAsync(source, MediaEncodingSubtypes::Bgra8()).get();

        CaptureContext* contextForHandler = context;
        context->frameArrivedToken = context->frameReader.FrameArrived(
                [contextForHandler](MediaFrameReader const& sender, MediaFrameArrivedEventArgs const&) {
                    contextForHandler->onFrameArrived(sender);
                });

        MediaFrameReaderStartStatus startStatus = context->frameReader.StartAsync().get();
        if (startStatus != MediaFrameReaderStartStatus::Success) {
            fwprintf(stderr, L"winrt_open_fail stage=reader_start status=%d\n", static_cast<int>(startStatus));
            fflush(stderr);
            context->frameReader.FrameArrived(context->frameArrivedToken);
            context->frameReader.StopAsync().get();
            delete context;
            return 0;
        }

        // Block until the first frame so the negotiated resolution is known to the caller.
        {
            std::unique_lock<std::mutex> lock(context->mutex);
            bool received = context->frameAvailable.wait_for(
                    lock,
                    std::chrono::milliseconds(OPEN_FIRST_FRAME_TIMEOUT_MILLIS),
                    [context] { return context->firstFrameReceived; });
            if (!received) {
                logFailStage(L"first_frame_timeout");
                lock.unlock();
                context->frameReader.FrameArrived(context->frameArrivedToken);
                context->frameReader.StopAsync().get();
                delete context;
                return 0;
            }
        }

        return reinterpret_cast<jlong>(context);
    } catch (hresult_error const& e) {
        // Most diagnostic case: MediaCapture.InitializeAsync throwing E_ACCESSDENIED (0x80070005) here means the
        // camera consent broker denied the capture - typically because this synthetic AppContainer has no real MSIX
        // package identity - rather than WinRT being unable to run in a sandbox at all.
        logFailHresult(L"exception", e);
        cleanupContextOnError(context);
        return 0;
    } catch (std::exception const& e) {
        fprintf(stderr, "winrt_open_fail stage=exception std=%s\n", e.what());
        fflush(stderr);
        cleanupContextOnError(context);
        return 0;
    } catch (...) {
        logFailStage(L"exception_unknown");
        cleanupContextOnError(context);
        return 0;
    }
}

JNIEXPORT jint JNICALL
Java_bisq_webcam_service_capture_WinRtCamera_nativeWidth(JNIEnv*, jclass, jlong handle) {
    CaptureContext* context = asContext(handle);
    if (context == nullptr) {
        return 0;
    }
    std::lock_guard<std::mutex> lock(context->mutex);
    return static_cast<jint>(context->width);
}

JNIEXPORT jint JNICALL
Java_bisq_webcam_service_capture_WinRtCamera_nativeHeight(JNIEnv*, jclass, jlong handle) {
    CaptureContext* context = asContext(handle);
    if (context == nullptr) {
        return 0;
    }
    std::lock_guard<std::mutex> lock(context->mutex);
    return static_cast<jint>(context->height);
}

JNIEXPORT jint JNICALL
Java_bisq_webcam_service_capture_WinRtCamera_nativeGrab(JNIEnv* env, jclass,
                                                        jlong handle,
                                                        jobject directDestination,
                                                        jint timeoutMillis) {
    CaptureContext* context = asContext(handle);
    if (context == nullptr) {
        return CAPTURE_ERR_INVALID_HANDLE;
    }

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
    CaptureContext* context = asContext(handle);
    if (context == nullptr) {
        return;
    }
    try {
        if (context->frameReader) {
            context->frameReader.FrameArrived(context->frameArrivedToken);
            context->frameReader.StopAsync().get();
        }
    } catch (...) {
        // Ignore cleanup failures.
    }
    delete context;
}

} // extern "C"
