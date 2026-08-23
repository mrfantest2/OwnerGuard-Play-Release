#!/usr/bin/env python3
"""Apply deterministic OwnerGuard 1.0.27 Samsung compatibility patches before CI build."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace(path: str, old: str, new: str, *, count: int = 1) -> None:
    file = ROOT / path
    text = file.read_text(encoding="utf-8")
    actual = text.count(old)
    if actual < count:
        raise SystemExit(f"Patch anchor missing in {path}: expected at least {count}, found {actual}: {old[:80]!r}")
    text = text.replace(old, new, count)
    file.write_text(text, encoding="utf-8")


def replace_all(path: str, old: str, new: str) -> None:
    file = ROOT / path
    text = file.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"Patch anchor missing in {path}: {old!r}")
    file.write_text(text.replace(old, new), encoding="utf-8")


replace("app/src/main/java/com/fantest/ownerguard/AppUpdateManager.java",
        'private static final String APP_VERSION = "1.0.24";',
        'private static final String APP_VERSION = "1.0.27";')
replace_all("app/src/main/java/com/fantest/ownerguard/CloudAccountManager.java",
            "OwnerGuard-Android/1.0.26", "OwnerGuard-Android/1.0.27")
replace_all("app/src/main/java/com/fantest/ownerguard/CloudEscrow.java",
            "OwnerGuard-Android/1.0.26", "OwnerGuard-Android/1.0.27")

camera = "app/src/main/java/com/fantest/ownerguard/CameraCaptureManager.java"
replace(camera,
        "    private JSONObject locationSnapshot;\n",
        "    private JSONObject locationSnapshot;\n"
        "    private int photoOpenAttempts;\n"
        "    private int videoOpenAttempts;\n"
        "    private int[] supportedAfModes = new int[0];\n"
        "    private int[] supportedAeModes = new int[0];\n")
replace(camera, "            }, 28_000L);", "            }, 35_000L);")
replace(camera,
        "                StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);\n",
        "                int[] af = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);\n"
        "                int[] ae = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES);\n"
        "                supportedAfModes = af == null ? new int[0] : af;\n"
        "                supportedAeModes = ae == null ? new int[0] : ae;\n"
        "                StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);\n")
replace(camera,
        "            if (longEdge > 1920 || shortEdge > 1080 || longEdge < 640 || shortEdge < 360) continue;",
        "            if (longEdge > 1280 || shortEdge > 720 || longEdge < 640 || shortEdge < 360) continue;")
replace_all(camera, "image = r.acquireNextImage();", "image = r.acquireLatestImage();")
replace_all(camera,
            "preview.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);\n"
            "                                preview.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);\n"
            "                                preview.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);",
            "preview.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);\n"
            "                                applySafeAutoControls(preview, false);")
replace_all(camera,
            "b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);\n"
            "            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);\n"
            "            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);",
            "b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);\n"
            "            applySafeAutoControls(b, false);")
replace_all(camera,
            "b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);\n"
            "                                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);\n"
            "                                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);",
            "b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);\n"
            "                                applySafeAutoControls(b, true);")
replace(camera, "        }, 1_500L);", "        }, samsungCameraHandoffDelayMs());")
replace(camera, "        recorder.setVideoFrameRate(24);", "        recorder.setVideoFrameRate(30);")
replace(camera,
        "            @Override public void onDisconnected(CameraDevice c) {\n"
        "                c.close();\n"
        "                appendError(\"photo_camera_disconnected\");\n"
        "                startVideoOnce(\"camera_disconnected\");\n"
        "            }\n"
        "            @Override public void onError(CameraDevice c, int code) {\n"
        "                c.close();\n"
        "                appendError(\"photo_camera_error:\" + code);\n"
        "                startVideoOnce(\"camera_error\");\n"
        "            }",
        "            @Override public void onDisconnected(CameraDevice c) {\n"
        "                c.close();\n"
        "                retryPhotoOpen(\"photo_camera_disconnected\");\n"
        "            }\n"
        "            @Override public void onError(CameraDevice c, int code) {\n"
        "                c.close();\n"
        "                retryPhotoOpen(\"photo_camera_error:\" + code);\n"
        "            }")
replace(camera,
        "            @Override public void onDisconnected(CameraDevice c) {\n"
        "                c.close();\n"
        "                appendError(\"video_camera_disconnected\");\n"
        "                finish();\n"
        "            }\n"
        "            @Override public void onError(CameraDevice c, int code) {\n"
        "                c.close();\n"
        "                appendError(\"video_camera_error:\" + code);\n"
        "                finish();\n"
        "            }",
        "            @Override public void onDisconnected(CameraDevice c) {\n"
        "                c.close();\n"
        "                retryVideoOpen(\"video_camera_disconnected\");\n"
        "            }\n"
        "            @Override public void onError(CameraDevice c, int code) {\n"
        "                c.close();\n"
        "                retryVideoOpen(\"video_camera_error:\" + code);\n"
        "            }")
replace(camera,
        '            meta.put("capturePipeline", "S25_VIDEO_BASELINE_VALIDATED_V2");',
        '            meta.put("capturePipeline", "SAMSUNG_ALL_MODELS_RETRY_V3");\n'
        '            meta.put("manufacturer", Build.MANUFACTURER);\n'
        '            meta.put("model", Build.MODEL);\n'
        '            meta.put("sdkInt", Build.VERSION.SDK_INT);\n'
        '            meta.put("photoOpenAttempts", photoOpenAttempts);\n'
        '            meta.put("videoOpenAttempts", videoOpenAttempts);')
replace(camera,
        "    private static String safeMessage(Throwable t) {\n",
        "    private void retryPhotoOpen(String error) {\n"
        "        appendError(error);\n"
        "        photoOpenAttempts++;\n"
        "        closeCameraResources();\n"
        "        if (photoOpenAttempts >= 3 || finished.get() || videoStarted.get()) {\n"
        "            startVideoOnce(\"photo_open_retry_exhausted\");\n"
        "            return;\n"
        "        }\n"
        "        worker.postDelayed(() -> {\n"
        "            try { openForPhotos(); }\n"
        "            catch (Exception e) { retryPhotoOpen(\"photo_reopen:\" + e.getClass().getSimpleName()); }\n"
        "        }, samsungCameraRetryDelayMs(photoOpenAttempts));\n"
        "    }\n\n"
        "    private void retryVideoOpen(String error) {\n"
        "        appendError(error);\n"
        "        videoOpenAttempts++;\n"
        "        try { if (session != null) session.close(); } catch (Exception ignored) {}\n"
        "        session = null;\n"
        "        try { if (camera != null) camera.close(); } catch (Exception ignored) {}\n"
        "        camera = null;\n"
        "        releaseDummySurface();\n"
        "        if (videoOpenAttempts >= 3 || finished.get()) { finish(); return; }\n"
        "        worker.postDelayed(() -> {\n"
        "            try { openForVideo(); }\n"
        "            catch (Exception e) { retryVideoOpen(\"video_reopen:\" + e.getClass().getSimpleName()); }\n"
        "        }, samsungCameraRetryDelayMs(videoOpenAttempts));\n"
        "    }\n\n"
        "    private void applySafeAutoControls(CaptureRequest.Builder builder, boolean video) {\n"
        "        int desiredAf = video ? CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO : CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE;\n"
        "        if (containsMode(supportedAfModes, desiredAf)) builder.set(CaptureRequest.CONTROL_AF_MODE, desiredAf);\n"
        "        else if (containsMode(supportedAfModes, CaptureRequest.CONTROL_AF_MODE_AUTO)) builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);\n"
        "        else if (containsMode(supportedAfModes, CaptureRequest.CONTROL_AF_MODE_OFF)) builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);\n"
        "        if (containsMode(supportedAeModes, CaptureRequest.CONTROL_AE_MODE_ON)) builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);\n"
        "    }\n\n"
        "    private static boolean containsMode(int[] values, int expected) {\n"
        "        if (values == null) return false;\n"
        "        for (int value : values) if (value == expected) return true;\n"
        "        return false;\n"
        "    }\n\n"
        "    private static long samsungCameraHandoffDelayMs() {\n"
        "        boolean samsung = Build.MANUFACTURER != null && Build.MANUFACTURER.equalsIgnoreCase(\"samsung\");\n"
        "        if (!samsung) return 1_200L;\n"
        "        return Build.VERSION.SDK_INT >= 34 ? 2_200L : 1_800L;\n"
        "    }\n\n"
        "    private static long samsungCameraRetryDelayMs(int attempt) {\n"
        "        boolean samsung = Build.MANUFACTURER != null && Build.MANUFACTURER.equalsIgnoreCase(\"samsung\");\n"
        "        return (samsung ? 900L : 500L) * Math.max(1, attempt);\n"
        "    }\n\n"
        "    private static String safeMessage(Throwable t) {\n")

print("OwnerGuard 1.0.27 Samsung compatibility patch applied")
