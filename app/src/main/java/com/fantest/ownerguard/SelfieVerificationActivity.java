package com.fantest.ownerguard;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.PointF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.FaceDetector;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class SelfieVerificationActivity extends SecureActivity {
    private static final int REQUIRED_FRAMES = 3;

    private TextureView preview;
    private TextView status;
    private TextView result;
    private CameraManager manager;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private HandlerThread thread;
    private Handler worker;
    private final Handler main = new Handler(Looper.getMainLooper());
    private String cameraId;
    private Size photoSize = new Size(1280, 960);
    private int sensorOrientation = 270;
    private int stable;
    private boolean capturing;
    private int completedFrames;
    private final List<FaceSimilarity.Result> frameResults = new ArrayList<>();

    private final Runnable analyzer = new Runnable() {
        @Override public void run() {
            if (isFinishing() || capturing || completedFrames >= REQUIRED_FRAMES) return;
            analyze();
            main.postDelayed(this, 320L);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (!AuthSession.isUnlocked()) {
            finish();
            return;
        }
        if (!FaceSimilarity.isEnrolled(this)) {
            Toast.makeText(this, "Complete five-angle owner enrollment first", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        build();
    }

    private void build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        root.setBackgroundColor(Color.parseColor("#07111F"));

        TextView title = text("Owner selfie test", 25, Color.WHITE);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        status = text("Center your face. OwnerGuard will compare three clear frames.",
                14, Color.parseColor("#B8C5D6"));
        status.setGravity(Gravity.CENTER);
        root.addView(status);

        FrameLayout frame = new FrameLayout(this);
        preview = new TextureView(this);
        preview.setScaleX(-1f);
        frame.addView(preview, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(frame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        result = text("Waiting for a clear face…", 17, Color.WHITE);
        result.setGravity(Gravity.CENTER);
        result.setPadding(dp(10), dp(12), dp(10), dp(12));
        root.addView(result);

        Button retry = new Button(this);
        retry.setText("Run three-frame test again");
        retry.setAllCaps(false);
        retry.setOnClickListener(v -> resetTest());
        root.addView(retry);

        Button close = new Button(this);
        close.setText("Close");
        close.setAllCaps(false);
        close.setOnClickListener(v -> finish());
        root.addView(close);

        setContentView(root);
        preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                open();
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {}
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) { return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
        });
    }

    private void resetTest() {
        main.removeCallbacks(analyzer);
        stable = 0;
        capturing = false;
        completedFrames = 0;
        frameResults.clear();
        result.setTextColor(Color.WHITE);
        result.setText("Waiting for a clear face…");
        status.setText("Center your face. OwnerGuard will compare three clear frames.");
        main.postDelayed(analyzer, 250L);
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setPadding(0, dp(7), 0, dp(7));
        return view;
    }

    private void chooseCamera() throws CameraAccessException {
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                cameraId = id;
                Integer orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                if (orientation != null) sensorOrientation = orientation;
                StreamConfigurationMap map = characteristics.get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map != null) {
                    Size[] sizes = map.getOutputSizes(ImageFormat.JPEG);
                    if (sizes != null && sizes.length > 0) photoSize = choosePhotoSize(sizes);
                }
                return;
            }
        }
        throw new IllegalStateException("No front camera");
    }

    private Size choosePhotoSize(Size[] sizes) {
        Size best = sizes[0];
        long bestScore = Long.MAX_VALUE;
        for (Size size : sizes) {
            long pixels = (long) size.getWidth() * size.getHeight();
            if (pixels < 420_000L || pixels > 3_500_000L) continue;
            long score = Math.abs(pixels - 1_200_000L);
            if (score < bestScore) {
                bestScore = score;
                best = size;
            }
        }
        return best;
    }

    @SuppressLint("MissingPermission")
    private void open() {
        try {
            chooseCamera();
            thread = new HandlerThread("OwnerTestCamera");
            thread.start();
            worker = new Handler(thread.getLooper());
            reader = ImageReader.newInstance(photoSize.getWidth(), photoSize.getHeight(),
                    ImageFormat.JPEG, 3);
            reader.setOnImageAvailableListener(this::captured, worker);
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice device) {
                    camera = device;
                    createPreview();
                }
                @Override public void onDisconnected(CameraDevice device) {
                    device.close();
                    runOnUiThread(() -> status.setText("Front camera disconnected"));
                }
                @Override public void onError(CameraDevice device, int error) {
                    device.close();
                    runOnUiThread(() -> status.setText("Front camera error " + error));
                }
            }, worker);
        } catch (Exception error) {
            status.setText("Unable to start front camera: " + safe(error.getMessage()));
        }
    }

    private void createPreview() {
        try {
            SurfaceTexture texture = preview.getSurfaceTexture();
            if (texture == null) throw new IllegalStateException("Preview surface unavailable");
            texture.setDefaultBufferSize(1280, 960);
            Surface surface = new Surface(texture);
            camera.createCaptureSession(Arrays.asList(surface, reader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession configured) {
                            session = configured;
                            try {
                                CaptureRequest.Builder request = camera.createCaptureRequest(
                                        CameraDevice.TEMPLATE_PREVIEW);
                                request.addTarget(surface);
                                request.set(CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                request.set(CaptureRequest.CONTROL_AE_MODE,
                                        CaptureRequest.CONTROL_AE_MODE_ON);
                                configured.setRepeatingRequest(request.build(), null, worker);
                                main.postDelayed(analyzer, 450L);
                            } catch (Exception ignored) {
                                runOnUiThread(() -> status.setText("Could not start camera preview"));
                            }
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession configured) {
                            runOnUiThread(() -> status.setText("Front-camera preview failed"));
                        }
                    }, worker);
        } catch (Exception error) {
            runOnUiThread(() -> status.setText("Preview failed: " + safe(error.getMessage())));
        }
    }

    private void analyze() {
        Bitmap bitmap = null;
        Bitmap rgb = null;
        try {
            bitmap = preview.getBitmap(360, 480);
            if (bitmap == null) return;
            rgb = bitmap.copy(Bitmap.Config.RGB_565, false);
            FaceDetector.Face[] faces = new FaceDetector.Face[1];
            int found = new FaceDetector(rgb.getWidth(), rgb.getHeight(), 1).findFaces(rgb, faces);
            if (found < 1 || faces[0] == null) {
                stable = 0;
                status.setText("No face detected — face the camera directly");
                return;
            }
            PointF midpoint = new PointF();
            faces[0].getMidPoint(midpoint);
            float x = midpoint.x / rgb.getWidth();
            float y = midpoint.y / rgb.getHeight();
            float ratio = faces[0].eyesDistance() / rgb.getWidth();
            boolean good = x > 0.20f && x < 0.80f
                    && y > 0.18f && y < 0.80f
                    && ratio > 0.050f && ratio < 0.34f;
            if (!good) {
                stable = 0;
                status.setText("Center your face and adjust the distance");
                return;
            }
            stable++;
            status.setText("Clear face — sample " + (completedFrames + 1) + " of " + REQUIRED_FRAMES
                    + " in " + Math.max(1, 2 - stable));
            if (stable >= 2) capture();
        } catch (Throwable ignored) {
            stable = 0;
        } finally {
            if (rgb != null && !rgb.isRecycled()) rgb.recycle();
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
    }

    private void capture() {
        if (capturing || session == null || camera == null) return;
        capturing = true;
        main.removeCallbacks(analyzer);
        try {
            CaptureRequest.Builder request = camera.createCaptureRequest(
                    CameraDevice.TEMPLATE_STILL_CAPTURE);
            request.addTarget(reader.getSurface());
            request.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            request.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation());
            session.capture(request.build(), null, worker);
        } catch (Exception error) {
            capturing = false;
            stable = 0;
            main.postDelayed(analyzer, 400L);
        }
    }

    private void captured(ImageReader imageReader) {
        Image image = null;
        File file = null;
        try {
            image = imageReader.acquireLatestImage();
            if (image == null) return;
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] data = new byte[buffer.remaining()];
            buffer.get(data);
            file = new File(getCacheDir(), "owner_test_" + System.nanoTime() + ".jpg");
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write(data);
            }
            FaceSimilarity.Result match = FaceSimilarity.compare(this, file);
            frameResults.add(match);
            completedFrames++;

            if (completedFrames < REQUIRED_FRAMES) {
                int next = completedFrames + 1;
                runOnUiThread(() -> {
                    capturing = false;
                    stable = 0;
                    status.setText("Sample " + completedFrames + " captured. Hold still for sample " + next + ".");
                    result.setText("Comparing " + completedFrames + " of " + REQUIRED_FRAMES + " frames…");
                    main.postDelayed(analyzer, 600L);
                });
            } else {
                FaceSimilarity.Result aggregate = FaceSimilarity.aggregate(frameResults);
                showResult(aggregate);
            }
        } catch (Exception error) {
            runOnUiThread(() -> {
                capturing = false;
                stable = 0;
                result.setText("One sample failed. OwnerGuard will retry.");
                main.postDelayed(analyzer, 550L);
            });
        } finally {
            if (file != null) file.delete();
            if (image != null) image.close();
        }
    }

    private void showResult(FaceSimilarity.Result match) {
        String label;
        int color;
        double score = match.similarity;
        boolean pass = match.ownerLikely
                || (score >= match.threshold - 0.012 && match.matchedSamples >= 2)
                || match.bestSimilarity >= match.threshold + 0.095;
        boolean uncertain = !pass && (score >= match.threshold - 0.075
                || match.bestSimilarity >= match.threshold + 0.015);

        if (!match.faceFound) {
            label = "NO CLEAR FACE — RETRY";
            color = Color.parseColor("#F59E0B");
        } else if (pass) {
            label = "LIKELY OWNER — PASS";
            color = Color.parseColor("#22C55E");
        } else if (uncertain) {
            label = "UNCERTAIN — RETRY IN BETTER LIGHT";
            color = Color.parseColor("#F59E0B");
        } else {
            label = "OWNER NOT CONFIRMED";
            color = Color.parseColor("#EF4444");
        }

        final String message = label
                + "\nThree-frame score: " + format(score)
                + " • required: " + format(match.threshold)
                + "\nBest angle: " + format(match.bestSimilarity)
                + " • matching angles: " + match.matchedSamples + " / 5"
                + "\nConfidence: " + Math.round(match.confidence * 100.0) + "%"
                + "\nLocal owner-likeness test; not biometric authentication.";
        final int finalColor = color;
        runOnUiThread(() -> {
            capturing = true;
            result.setText(message);
            result.setTextColor(finalColor);
            status.setText("Three-frame test completed");
        });
    }

    private String format(double value) {
        return String.format(Locale.US, "%.3f", value);
    }

    private int jpegOrientation() {
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        int degrees = rotation == Surface.ROTATION_90 ? 90
                : rotation == Surface.ROTATION_180 ? 180
                : rotation == Surface.ROTATION_270 ? 270 : 0;
        return (sensorOrientation + degrees) % 360;
    }

    private void closeCamera() {
        main.removeCallbacks(analyzer);
        try { if (session != null) session.close(); } catch (Exception ignored) {}
        session = null;
        try { if (camera != null) camera.close(); } catch (Exception ignored) {}
        camera = null;
        try { if (reader != null) reader.close(); } catch (Exception ignored) {}
        reader = null;
        if (thread != null) {
            thread.quitSafely();
            try { thread.join(500L); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            thread = null;
            worker = null;
        }
    }

    @Override protected void onDestroy() {
        closeCamera();
        super.onDestroy();
    }

    private String safe(String value) {
        return value == null || value.trim().isEmpty() ? "unknown error" : value;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
