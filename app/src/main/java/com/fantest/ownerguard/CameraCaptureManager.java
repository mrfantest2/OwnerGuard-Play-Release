package com.fantest.ownerguard;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.media.MediaCodecInfo;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.Surface;
import android.view.WindowManager;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Robust front-camera incident capture pipeline.
 *
 * v1.0.7 fixes:
 * - selects front-camera supported JPEG and MediaRecorder sizes
 * - warms autofocus/exposure on a private dummy preview surface
 * - waits before reopening the Samsung camera HAL for video
 * - records detailed encrypted capture diagnostics
 */
final class CameraCaptureManager {
    interface Callback { void onComplete(Result result); }
    static final class Result {
        final File eventDirectory;
        final double ownerSimilarity;
        final int photosSaved;
        final boolean videoSaved;
        Result(File dir, double score, int photos, boolean video) {
            eventDirectory = dir; ownerSimilarity = score; photosSaved = photos; videoSaved = video;
        }
    }

    private final Context context;
    private final CameraManager manager;
    private final HandlerThread thread = new HandlerThread("OwnerGuardCamera");
    private Handler worker;
    private String cameraId;
    private int sensorOrientation;
    private Size photoSize;
    private Size videoSize;
    private File eventDir;
    private String reason;
    private int failedCredentials;
    private long startedAt;
    private Callback callback;
    private ImageReader reader;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private MediaRecorder recorder;
    private File tempVideo;
    private SurfaceTexture dummyTexture;
    private Surface dummySurface;
    private boolean recorderStarted;
    private boolean videoSaved;
    private boolean photoSessionReady;
    private boolean videoSessionReady;
    private final AtomicInteger photosReceived = new AtomicInteger();
    private final AtomicInteger photosEncrypted = new AtomicInteger();
    private final AtomicBoolean videoStarted = new AtomicBoolean();
    private final AtomicBoolean finished = new AtomicBoolean();
    private volatile double bestOwnerSimilarity = -1.0;
    private volatile int faceFrames;
    private final StringBuilder errors = new StringBuilder();
    private JSONObject locationSnapshot;

    CameraCaptureManager(Context c) {
        context = c.getApplicationContext();
        manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
    }

    void capture(String reason, int failedCredentials, Callback callback) {
        this.reason = reason;
        this.failedCredentials = failedCredentials;
        this.callback = callback;
        startedAt = System.currentTimeMillis();
        locationSnapshot = LocationSnapshot.capture(context);
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date(startedAt));
        eventDir = new File(context.getFilesDir(), "vault/events/EVT_" + stamp);
        if (!eventDir.mkdirs() && !eventDir.isDirectory()) {
            callback.onComplete(new Result(eventDir, -1, 0, false));
            return;
        }
        thread.start();
        worker = new Handler(thread.getLooper());
        try {
            selectFrontCameraAndSizes();
            openForPhotos();
            worker.postDelayed(() -> {
                appendError("capture_watchdog_28s");
                finish();
            }, 28_000L);
        } catch (Exception e) {
            appendError("camera_setup:" + e.getClass().getSimpleName() + ":" + safeMessage(e));
            finish();
        }
    }

    private void selectFrontCameraAndSizes() throws CameraAccessException {
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics c = manager.getCameraCharacteristics(id);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                cameraId = id;
                Integer orientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
                sensorOrientation = orientation == null ? 270 : orientation;
                StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) throw new IllegalStateException("Front camera has no stream configuration map");
                photoSize = choosePhotoSize(map.getOutputSizes(ImageFormat.JPEG));
                videoSize = chooseVideoSize(map.getOutputSizes(MediaRecorder.class));
                if (photoSize == null) throw new IllegalStateException("No supported front-camera JPEG size");
                if (videoSize == null) throw new IllegalStateException("No supported front-camera video size");
                return;
            }
        }
        throw new IllegalStateException("No front camera found");
    }

    private Size choosePhotoSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return null;
        Size best = null;
        long bestScore = Long.MAX_VALUE;
        for (Size s : sizes) {
            long pixels = (long) s.getWidth() * s.getHeight();
            if (pixels < 700_000L || pixels > 4_500_000L) continue;
            double ratio = (double) Math.max(s.getWidth(), s.getHeight()) / Math.min(s.getWidth(), s.getHeight());
            long ratioPenalty = Math.round(Math.abs(ratio - (4.0 / 3.0)) * 1_000_000L);
            long score = Math.abs(pixels - 1_500_000L) + ratioPenalty;
            if (score < bestScore) { best = s; bestScore = score; }
        }
        if (best != null) return best;
        return smallestAtLeast(sizes, 640, 480);
    }

    private Size chooseVideoSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return null;
        Size best = null;
        long bestScore = Long.MAX_VALUE;
        for (Size s : sizes) {
            int longEdge = Math.max(s.getWidth(), s.getHeight());
            int shortEdge = Math.min(s.getWidth(), s.getHeight());
            if (longEdge > 1920 || shortEdge > 1080 || longEdge < 640 || shortEdge < 360) continue;
            long pixels = (long) s.getWidth() * s.getHeight();
            double ratio = (double) longEdge / shortEdge;
            long ratioPenalty = Math.round(Math.abs(ratio - (16.0 / 9.0)) * 2_000_000L);
            long score = Math.abs(pixels - (1280L * 720L)) + ratioPenalty;
            if (score < bestScore) { best = s; bestScore = score; }
        }
        if (best != null) return best;
        return smallestAtLeast(sizes, 640, 480);
    }

    private Size smallestAtLeast(Size[] sizes, int minWidth, int minHeight) {
        Size best = sizes[0];
        long bestPixels = Long.MAX_VALUE;
        for (Size s : sizes) {
            int longEdge = Math.max(s.getWidth(), s.getHeight());
            int shortEdge = Math.min(s.getWidth(), s.getHeight());
            long pixels = (long) s.getWidth() * s.getHeight();
            if (longEdge >= Math.max(minWidth, minHeight) && shortEdge >= Math.min(minWidth, minHeight) && pixels < bestPixels) {
                best = s; bestPixels = pixels;
            }
        }
        return best;
    }

    @SuppressLint("MissingPermission")
    private void openForPhotos() throws CameraAccessException {
        reader = ImageReader.newInstance(photoSize.getWidth(), photoSize.getHeight(), ImageFormat.JPEG, 4);
        reader.setOnImageAvailableListener(this::handlePhoto, worker);
        manager.openCamera(cameraId, new CameraDevice.StateCallback() {
            @Override public void onOpened(CameraDevice c) {
                camera = c;
                try {
                    createDummySurface(photoSize.getWidth(), photoSize.getHeight());
                    c.createCaptureSession(Arrays.asList(dummySurface, reader.getSurface()), new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession s) {
                            session = s;
                            photoSessionReady = true;
                            try {
                                CaptureRequest.Builder preview = c.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                preview.addTarget(dummySurface);
                                preview.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                                preview.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                preview.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                                s.setRepeatingRequest(preview.build(), null, worker);
                                worker.postDelayed(() -> takePhoto(s), 900L);
                                worker.postDelayed(() -> takePhoto(s), 1_800L);
                                worker.postDelayed(() -> takePhoto(s), 2_700L);
                                worker.postDelayed(() -> startVideoOnce("photo_phase_timeout"), 5_500L);
                            } catch (Exception e) {
                                appendError("photo_preview:" + e.getClass().getSimpleName() + ":" + safeMessage(e));
                                startVideoOnce("photo_preview_error");
                            }
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession s) {
                            appendError("photo_session_failed:" + sizeText(photoSize));
                            startVideoOnce("photo_session_failed");
                        }
                    }, worker);
                } catch (Exception e) {
                    appendError("photo_session:" + e.getClass().getSimpleName() + ":" + safeMessage(e));
                    startVideoOnce("photo_session_exception");
                }
            }
            @Override public void onDisconnected(CameraDevice c) {
                c.close();
                appendError("photo_camera_disconnected");
                startVideoOnce("camera_disconnected");
            }
            @Override public void onError(CameraDevice c, int code) {
                c.close();
                appendError("photo_camera_error:" + code);
                startVideoOnce("camera_error");
            }
        }, worker);
    }

    private void handlePhoto(ImageReader r) {
        Image image = null;
        File temp = null;
        try {
            image = r.acquireNextImage();
            if (image == null) return;
            int index = photosReceived.incrementAndGet();
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            if (bytes.length < 512) throw new IllegalStateException("Front-camera JPEG was empty");
            temp = new File(context.getCacheDir(), "og_photo_" + System.nanoTime() + ".jpg");
            try (FileOutputStream out = new FileOutputStream(temp)) { out.write(bytes); }
            FaceSimilarity.Result face = FaceSimilarity.compare(context, temp);
            if (face.faceFound) faceFrames++;
            if (face.similarity > bestOwnerSimilarity) bestOwnerSimilarity = face.similarity;
            File encrypted = new File(eventDir, String.format(Locale.US, "photo_%02d.ogv", index));
            VaultCrypto.encryptFile(temp, encrypted);
            photosEncrypted.incrementAndGet();
            if (index >= 3) startVideoOnce("photos_complete");
        } catch (Exception e) {
            appendError("photo_write:" + e.getClass().getSimpleName() + ":" + safeMessage(e));
        } finally {
            if (temp != null) temp.delete();
            if (image != null) image.close();
        }
    }

    private void takePhoto(CameraCaptureSession s) {
        try {
            if (camera == null || reader == null || videoStarted.get() || finished.get()) return;
            CaptureRequest.Builder b = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            b.addTarget(reader.getSurface());
            b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            b.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation());
            s.capture(b.build(), null, worker);
        } catch (Exception e) {
            appendError("take_photo:" + e.getClass().getSimpleName() + ":" + safeMessage(e));
        }
    }

    private void startVideoOnce(String trigger) {
        if (!videoStarted.compareAndSet(false, true) || finished.get()) return;
        closeCameraResources();
        worker.postDelayed(() -> {
            if (finished.get()) return;
            try {
                prepareRecorder();
                openForVideo();
            } catch (Exception e) {
                appendError("video_setup:" + e.getClass().getSimpleName() + ":" + safeMessage(e));
                finish();
            }
        }, 1_500L);
    }

    private void prepareRecorder() throws Exception {
        tempVideo = new File(context.getCacheDir(), "og_video_" + System.nanoTime() + ".mp4");
        recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(context) : new MediaRecorder();
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setOutputFile(tempVideo.getAbsolutePath());
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        long pixels = (long) videoSize.getWidth() * videoSize.getHeight();
        recorder.setVideoEncodingBitRate(pixels >= 900_000L ? 3_000_000 : 1_800_000);
        recorder.setVideoFrameRate(24);
        try { recorder.setVideoEncodingProfileLevel(MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline, MediaCodecInfo.CodecProfileLevel.AVCLevel31); } catch (Throwable ignored) {}
        recorder.setVideoSize(videoSize.getWidth(), videoSize.getHeight());
        recorder.setOrientationHint(jpegOrientation());
        recorder.prepare();
    }

    @SuppressLint("MissingPermission")
    private void openForVideo() throws CameraAccessException {
        manager.openCamera(cameraId, new CameraDevice.StateCallback() {
            @Override public void onOpened(CameraDevice c) {
                camera = c;
                try {
                    Surface recordSurface = recorder.getSurface();
                    createDummySurface(videoSize.getWidth(), videoSize.getHeight());
                    c.createCaptureSession(Arrays.asList(recordSurface, dummySurface), new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession s) {
                            session = s;
                            videoSessionReady = true;
                            try {
                                CaptureRequest.Builder b = c.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                                b.addTarget(recordSurface);
                                b.addTarget(dummySurface);
                                b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                                s.setRepeatingRequest(b.build(), null, worker);
                                recorder.start();
                                recorderStarted = true;
                                worker.postDelayed(() -> stopVideoAndFinish(), 5_100L);
                            } catch (Exception e) {
                                appendError("video_start:" + e.getClass().getSimpleName() + ":" + safeMessage(e));
                                finish();
                            }
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession s) {
                            appendError("video_session_failed:" + sizeText(videoSize));
                            finish();
                        }
                    }, worker);
                } catch (Exception e) {
                    appendError("video_session:" + e.getClass().getSimpleName() + ":" + safeMessage(e));
                    finish();
                }
            }
            @Override public void onDisconnected(CameraDevice c) {
                c.close();
                appendError("video_camera_disconnected");
                finish();
            }
            @Override public void onError(CameraDevice c, int code) {
                c.close();
                appendError("video_camera_error:" + code);
                finish();
            }
        }, worker);
    }

    private void stopVideoAndFinish() {
        try {
            if (session != null) {
                try { session.stopRepeating(); } catch (Exception ignored) {}
                try { session.abortCaptures(); } catch (Exception ignored) {}
            }
            if (recorder != null && recorderStarted) {
                recorder.stop();
                recorderStarted = false;
            }
            if (tempVideo != null && tempVideo.isFile() && tempVideo.length() > 2_048) {
                MediaMetadataRetriever check = new MediaMetadataRetriever();
                try {
                    check.setDataSource(tempVideo.getAbsolutePath());
                    String duration = check.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                    if (duration == null || Long.parseLong(duration) <= 0) throw new IllegalStateException("recorded MP4 has no duration");
                } finally { try { check.release(); } catch (Exception ignored) {} }
                VaultCrypto.encryptFile(tempVideo, new File(eventDir, "video_05s.ogv"));
                videoSaved = true;
            } else {
                appendError("video_file_empty_or_missing");
            }
        } catch (Exception e) {
            appendError("video_stop:" + e.getClass().getSimpleName() + ":" + safeMessage(e));
        } finally {
            if (tempVideo != null) tempVideo.delete();
            finish();
        }
    }

    private void createDummySurface(int width, int height) {
        releaseDummySurface();
        dummyTexture = new SurfaceTexture(10);
        dummyTexture.setDefaultBufferSize(width, height);
        dummySurface = new Surface(dummyTexture);
    }

    private int jpegOrientation() {
        int rotation = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRotation();
        int degrees = rotation == Surface.ROTATION_90 ? 90 : rotation == Surface.ROTATION_180 ? 180 : rotation == Surface.ROTATION_270 ? 270 : 0;
        return (sensorOrientation + degrees) % 360;
    }

    private void finish() {
        if (!finished.compareAndSet(false, true)) return;
        closeCameraResources();
        releaseRecorder();
        try {
            JSONObject meta = new JSONObject();
            meta.put("version", 4);
            meta.put("vaultFormat", "OGV2");
            meta.put("capturePipeline", "S25_VIDEO_BASELINE_VALIDATED_V2");
            meta.put("startedAt", startedAt);
            meta.put("finishedAt", System.currentTimeMillis());
            meta.put("reason", reason);
            meta.put("failedCredentialAttempts", failedCredentials);
            meta.put("credentialTypes", "pattern,pin,password");
            meta.put("frontCameraId", cameraId == null ? "" : cameraId);
            meta.put("photoSize", sizeText(photoSize));
            meta.put("videoSize", sizeText(videoSize));
            meta.put("photoSessionReady", photoSessionReady);
            meta.put("videoSessionReady", videoSessionReady);
            meta.put("photosRequested", 3);
            meta.put("photosReceived", photosReceived.get());
            meta.put("photosCaptured", photosEncrypted.get());
            meta.put("videoSecondsRequested", 5);
            meta.put("videoSaved", videoSaved);
            File finalVideo = new File(eventDir, "video_05s.ogv");
            meta.put("encryptedVideoBytes", finalVideo.isFile() ? finalVideo.length() : 0);
            meta.put("videoCodec", "H264 baseline MP4");
            meta.put("ownerSelfieEnrolled", FaceSimilarity.isEnrolled(context));
            meta.put("ownerSelfieSamples", FaceSimilarity.sampleCount(context));
            meta.put("faceFrames", faceFrames);
            meta.put("ownerSimilarityPrototype", bestOwnerSimilarity);
            LocationSnapshot.addToMetadata(meta, locationSnapshot);
            meta.put("error", errors.toString());
            VaultCrypto.encryptBytes(meta.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), new File(eventDir, "metadata.ogv"));
        } catch (Exception ignored) {}
        try { thread.quitSafely(); } catch (Exception ignored) {}
        Callback cb = callback;
        if (cb != null) cb.onComplete(new Result(eventDir, bestOwnerSimilarity, photosEncrypted.get(), videoSaved));
    }

    private void closeCameraResources() {
        try { if (session != null) session.close(); } catch (Exception ignored) {}
        session = null;
        try { if (camera != null) camera.close(); } catch (Exception ignored) {}
        camera = null;
        try { if (reader != null) reader.close(); } catch (Exception ignored) {}
        reader = null;
        releaseDummySurface();
    }

    private void releaseDummySurface() {
        try { if (dummySurface != null) dummySurface.release(); } catch (Exception ignored) {}
        dummySurface = null;
        try { if (dummyTexture != null) dummyTexture.release(); } catch (Exception ignored) {}
        dummyTexture = null;
    }

    private void releaseRecorder() {
        try { if (recorder != null) recorder.reset(); } catch (Exception ignored) {}
        try { if (recorder != null) recorder.release(); } catch (Exception ignored) {}
        recorder = null;
        recorderStarted = false;
    }

    private void appendError(String value) {
        if (value == null || value.isEmpty()) return;
        synchronized (errors) {
            if (errors.length() > 0) errors.append(" | ");
            errors.append(value);
        }
    }

    private static String safeMessage(Throwable t) {
        String m = t.getMessage();
        return m == null ? "" : m.replace('\n', ' ').replace('\r', ' ');
    }

    private static String sizeText(Size s) {
        return s == null ? "unknown" : s.getWidth() + "x" + s.getHeight();
    }
}
