import org.bytedeco.opencv.opencv_videoio.VideoCapture;

import static org.bytedeco.opencv.global.opencv_videoio.CAP_ANY;
import static org.bytedeco.opencv.global.opencv_videoio.CAP_DSHOW;
import static org.bytedeco.opencv.global.opencv_videoio.CAP_MSMF;

public final class CamProbe {
    private CamProbe() {
    }

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        int backendId = backendId(options.backend);

        System.out.println("java.version=" + System.getProperty("java.version"));
        System.out.println("os.name=" + System.getProperty("os.name"));
        System.out.println("device=" + options.device);
        System.out.println("backend=" + options.backend);
        System.out.println("backend_id=" + backendId);

        int grabbedCount = 0;
        try (VideoCapture capture = new VideoCapture()) {
            boolean opened = backendId == CAP_ANY
                    ? capture.open(options.device)
                    : capture.open(options.device, backendId);
            System.out.println("open_result=" + opened);
            System.out.println("is_opened=" + capture.isOpened());

            if (!opened || !capture.isOpened()) {
                System.out.println("result=camera_open_failed");
                System.exit(20);
            }

            for (int frameIndex = 0; frameIndex < options.frames; frameIndex++) {
                boolean grabbed = capture.grab();
                System.out.println("grab[" + frameIndex + "]=" + grabbed);
                if (grabbed) {
                    grabbedCount++;
                }
                if (options.delayMillis > 0) {
                    Thread.sleep(options.delayMillis);
                }
            }
        }

        System.out.println("grabbed_count=" + grabbedCount);
        if (grabbedCount == 0) {
            System.out.println("result=no_frames");
            System.exit(21);
        }

        System.out.println("result=frames_flowing");
    }

    private static int backendId(String backend) {
        return switch (backend) {
            case "any" -> CAP_ANY;
            case "dshow" -> CAP_DSHOW;
            case "msmf" -> CAP_MSMF;
            default -> throw new IllegalArgumentException("Unsupported backend: " + backend);
        };
    }

    private record Options(int device, String backend, int frames, int delayMillis) {
        private static Options parse(String[] args) {
            int device = 0;
            String backend = "msmf";
            int frames = 10;
            int delayMillis = 100;

            for (int index = 0; index < args.length; index++) {
                switch (args[index]) {
                    case "--device" -> device = parseIntValue(args, ++index, "--device");
                    case "--backend" -> backend = parseStringValue(args, ++index, "--backend");
                    case "--frames" -> frames = parseIntValue(args, ++index, "--frames");
                    case "--delay-ms" -> delayMillis = parseIntValue(args, ++index, "--delay-ms");
                    default -> throw new IllegalArgumentException("Unsupported argument: " + args[index]);
                }
            }

            if (frames < 1) {
                throw new IllegalArgumentException("--frames must be at least 1");
            }
            if (delayMillis < 0) {
                throw new IllegalArgumentException("--delay-ms must be non-negative");
            }
            return new Options(device, backend, frames, delayMillis);
        }

        private static int parseIntValue(String[] args, int index, String name) {
            return Integer.parseInt(parseStringValue(args, index, name));
        }

        private static String parseStringValue(String[] args, int index, String name) {
            if (index >= args.length) {
                throw new IllegalArgumentException(name + " requires a value");
            }
            return args[index];
        }
    }
}
