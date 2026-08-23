package com.fantest.ownerguard;

import android.Manifest;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.AudioManager;
import android.media.FaceDetector;
import android.media.Image;
import android.media.ImageReader;
import android.media.ToneGenerator;
import android.speech.tts.TextToSpeech;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Locale;

public class EnrollmentActivity extends SecureActivity {
    private static final String BG = "#07111F";
    private static final String SURFACE = "#10213A";
    private static final String SURFACE_ALT = "#172C49";
    private static final String PRIMARY = "#30C5FF";
    private static final String TEXT = "#F8FAFC";
    private static final String MUTED = "#AFC0D5";
    private static final String SUCCESS = "#22C55E";
    private static final String WARNING = "#F59E0B";
    private static final String DANGER = "#EF4444";

    private static final String[] POSE_NAMES = {"center", "left", "right", "up", "down"};
    private static final String[] POSE_TITLES = {
            "Look straight at the camera",
            "Turn your head slightly to either side",
            "Turn your head slightly to the other side",
            "Raise your chin slightly",
            "Lower your chin slightly"
    };
    private static final String[] POSE_HINTS = {
            "Keep your face centered and look directly into the lens.",
            "Only a small head turn is needed. Do not move your whole body.",
            "Return through the center, then make a small turn to the other side.",
            "Lift your chin a little while keeping your eyes on the screen.",
            "Lower your chin a little while keeping your eyes on the screen."
    };

    private TextureView preview;
    private FaceGuideView guideView;
    private ProgressBar progressBar;
    private TextView progress;
    private TextView instruction;
    private TextView quality;
    private TextView autoBadge;
    private Button useCurrentAngle;
    private CameraManager cameraManager;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ToneGenerator tones;
    private TextToSpeech voice;
    private boolean voiceReady;
    private String lastVoiceMessage="";
    private long lastVoiceAt;
    private String cameraId;
    private int sensorOrientation = 270;
    private Size photoSize = new Size(1280, 960);
    private int poseIndex;
    private int capturePoseIndex;
    private int stableFrames;
    private int leftYawSign;
    private int leftMoveSign;
    private int upPitchSign;
    private int upMoveSign;
    private float centerFaceX = 0.5f;
    private float centerFaceY = 0.45f;
    private float firstSideX = 0.5f;
    private float firstVerticalY = 0.45f;
    private long poseStartedAt;
    private boolean captureBusy;
    private boolean enrollmentReset;
    private boolean enrollmentCompleted;
    private boolean analysisRunning;
    private long cooldownUntil;
    private LiveFace lastLiveFace;

    private final Runnable faceLoop = new Runnable() {
        @Override public void run() {
            if (!analysisRunning || isFinishing()) return;
            if (!captureBusy && session != null && preview != null && preview.isAvailable()
                    && System.currentTimeMillis() >= cooldownUntil) {
                analyzeLivePreview();
            }
            mainHandler.postDelayed(this, 280L);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (!AuthSession.isUnlocked()) { finish(); return; }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Camera permission is required for owner enrollment.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        tones = new ToneGenerator(AudioManager.STREAM_MUSIC, 78);
        voice = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                voiceReady = voice.setLanguage(Locale.US) >= 0;
                voice.setSpeechRate(0.92f);
                if (voiceReady && enrollmentReset) speak(POSE_TITLES[poseIndex] + ". " + POSE_HINTS[poseIndex], true);
            }
        });
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        buildUi();
        new AlertDialog.Builder(this)
                .setTitle("Automatic five-angle enrollment")
                .setMessage("OwnerGuard will use the front camera, detect your face live, guide each angle, and capture automatically when your face is stable. Keep sound enabled for confirmation tones.")
                .setPositiveButton("Start", (d, w) -> {
                    FaceSimilarity.clear(this);
                    enrollmentReset = true;
                    updateGuide();
                    if (preview.isAvailable()) openFrontCamera();
                })
                .setNegativeButton("Cancel", (d, w) -> finish())
                .setCancelable(false)
                .show();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.parseColor(BG));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(18); root.setPadding(p, p, p, p);
        scroll.addView(root);

        TextView title = text("Secure face enrollment", 28, true, TEXT);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title);
        TextView subtitle = text("Front camera • automatic detection • five angles • local storage", 14, false, MUTED);
        subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(subtitle, bottomMargin(14));

        LinearLayout progressCard = cardLayout(SURFACE);
        progressCard.addView(text("Enrollment progress", 14, true, MUTED));
        progress = text("Preparing…", 18, true, TEXT);
        progressCard.addView(progress);
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(5); progressBar.setProgress(0);
        progressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.parseColor(PRIMARY)));
        progressBar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor(SURFACE_ALT)));
        progressCard.addView(progressBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)));
        root.addView(progressCard, bottomMargin(14));

        instruction = text("", 21, true, TEXT);
        instruction.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(instruction);
        quality = text("", 14, false, MUTED);
        quality.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(quality, bottomMargin(12));

        FrameLayout cameraCard = new FrameLayout(this);
        cameraCard.setBackground(rounded("#08101D", 22));
        cameraCard.setClipToOutline(true);
        LinearLayout.LayoutParams cameraLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(440));
        cameraLp.setMargins(0, 0, 0, dp(14));
        root.addView(cameraCard, cameraLp);

        preview = new TextureView(this);
        preview.setOpaque(false);
        preview.setScaleX(-1f);
        cameraCard.addView(preview, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        guideView = new FaceGuideView(this);
        cameraCard.addView(guideView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        autoBadge = text("● Automatic capture waiting", 14, true, MUTED);
        autoBadge.setGravity(Gravity.CENTER);
        autoBadge.setBackground(rounded(SURFACE_ALT, 18));
        autoBadge.setPadding(dp(14), dp(10), dp(14), dp(10));
        root.addView(autoBadge, bottomMargin(10));

        useCurrentAngle = secondaryButton("Use current clear angle");
        useCurrentAngle.setEnabled(false);
        useCurrentAngle.setAlpha(0.45f);
        useCurrentAngle.setOnClickListener(v -> {
            if (lastLiveFace == null || !lastLiveFace.found || captureBusy) {
                playTone(ToneGenerator.TONE_PROP_NACK, 150);
                return;
            }
            stableFrames = 3;
            captureAngle();
        });
        root.addView(useCurrentAngle, bottomMargin(14));

        TextView privacy = text("Capture is automatic. If Android cannot report a head angle, keep a clear face visible and use the fallback button after it becomes available.", 13, false, MUTED);
        privacy.setBackground(rounded(SURFACE, 16));
        privacy.setPadding(dp(14), dp(14), dp(14), dp(14));
        root.addView(privacy, bottomMargin(14));

        Button cancel = secondaryButton("Cancel enrollment");
        cancel.setOnClickListener(v -> cancelEnrollment());
        root.addView(cancel, buttonParams());
        setContentView(scroll);

        preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                if (enrollmentReset) openFrontCamera();
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {}
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) { return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
        });
    }

    private void updateGuide() {
        int done = FaceSimilarity.sampleCount(this);
        poseIndex = Math.min(done, POSE_NAMES.length - 1);
        progress.setText("Angle " + (done + 1) + " of 5");
        progressBar.setProgress(done);
        instruction.setText(POSE_TITLES[poseIndex]);
        quality.setText(POSE_HINTS[poseIndex]);
        autoBadge.setText("● Looking for your face");
        autoBadge.setTextColor(Color.parseColor(MUTED));
        stableFrames = 0;
        poseStartedAt = System.currentTimeMillis();
        if (useCurrentAngle != null) {
            useCurrentAngle.setEnabled(false);
            useCurrentAngle.setAlpha(0.45f);
        }
        guideView.setState(false, false, 0.5f, 0.45f, 0.18f);
        speak(POSE_TITLES[poseIndex] + ". " + POSE_HINTS[poseIndex], true);
    }

    private void startCameraThread() {
        if (cameraThread != null) return;
        cameraThread = new HandlerThread("OwnerGuardEnrollmentCamera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    private void chooseFrontCamera() throws CameraAccessException {
        for (String id : cameraManager.getCameraIdList()) {
            CameraCharacteristics c = cameraManager.getCameraCharacteristics(id);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                cameraId = id;
                Integer orientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
                if (orientation != null) sensorOrientation = orientation;
                StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map != null) photoSize = selectPhotoSize(map.getOutputSizes(ImageFormat.JPEG));
                return;
            }
        }
        throw new IllegalStateException("No front camera found");
    }

    private Size selectPhotoSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return new Size(1280, 960);
        Size best = sizes[0];
        long bestScore = Long.MAX_VALUE;
        for (Size s : sizes) {
            long pixels = (long) s.getWidth() * s.getHeight();
            if (pixels < 900_000L || pixels > 5_000_000L) continue;
            long score = Math.abs(pixels - 2_000_000L);
            if (score < bestScore) { best = s; bestScore = score; }
        }
        return best;
    }

    @SuppressLint("MissingPermission")
    private void openFrontCamera() {
        if (camera != null || !preview.isAvailable()) return;
        try {
            startCameraThread();
            chooseFrontCamera();
            reader = ImageReader.newInstance(photoSize.getWidth(), photoSize.getHeight(), ImageFormat.JPEG, 2);
            reader.setOnImageAvailableListener(this::handleCapturedImage, cameraHandler);
            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice c) { camera = c; createPreview(); }
                @Override public void onDisconnected(CameraDevice c) { c.close(); camera = null; showCameraError("Front camera disconnected"); }
                @Override public void onError(CameraDevice c, int error) { c.close(); camera = null; showCameraError("Front camera error " + error); }
            }, cameraHandler);
        } catch (Exception e) {
            showCameraError("Unable to open front camera: " + e.getMessage());
        }
    }

    private void createPreview() {
        try {
            SurfaceTexture texture = preview.getSurfaceTexture();
            if (texture == null) throw new IllegalStateException("Preview surface unavailable");
            texture.setDefaultBufferSize(1280, 960);
            Surface previewSurface = new Surface(texture);
            camera.createCaptureSession(Arrays.asList(previewSurface, reader.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession s) {
                    session = s;
                    try {
                        CaptureRequest.Builder b = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        b.addTarget(previewSurface);
                        b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                        s.setRepeatingRequest(b.build(), null, cameraHandler);
                        runOnUiThread(() -> {
                            updateGuide();
                            startFaceAnalysis();
                        });
                    } catch (Exception e) { showCameraError("Could not start front-camera preview"); }
                }
                @Override public void onConfigureFailed(CameraCaptureSession s) { showCameraError("Could not configure front-camera preview"); }
            }, cameraHandler);
        } catch (Exception e) {
            showCameraError("Preview error: " + e.getMessage());
        }
    }

    private void startFaceAnalysis() {
        analysisRunning = true;
        mainHandler.removeCallbacks(faceLoop);
        mainHandler.post(faceLoop);
    }

    private void analyzeLivePreview() {
        Bitmap bitmap = null;
        try {
            bitmap = preview.getBitmap(360, 480);
            if (bitmap == null) return;
            LiveFace face = detectLiveFace(bitmap);
            lastLiveFace = face;
            applyLiveFace(face);
        } catch (Throwable ignored) {
            stableFrames = 0;
        } finally {
            if (bitmap != null) bitmap.recycle();
        }
    }

    private LiveFace detectLiveFace(Bitmap source) {
        Bitmap rgb565 = source.copy(Bitmap.Config.RGB_565, false);
        if (rgb565 == null) return LiveFace.none("Preparing face detector…");
        FaceDetector.Face[] faces = new FaceDetector.Face[1];
        int count;
        try {
            count = new FaceDetector(rgb565.getWidth(), rgb565.getHeight(), 1).findFaces(rgb565, faces);
        } catch (Throwable t) {
            rgb565.recycle();
            return LiveFace.none("Face detector unavailable");
        }
        if (count < 1 || faces[0] == null) {
            rgb565.recycle();
            return LiveFace.none("Place your face inside the oval");
        }
        FaceDetector.Face f = faces[0];
        PointF mid = new PointF(); f.getMidPoint(mid);
        float eye = f.eyesDistance();
        float nx = mid.x / Math.max(1f, rgb565.getWidth());
        float ny = mid.y / Math.max(1f, rgb565.getHeight());
        float ratio = eye / Math.max(1f, rgb565.getWidth());
        float yaw = safePose(f, FaceDetector.Face.EULER_Y);
        float pitch = safePose(f, FaceDetector.Face.EULER_X);
        float roll = safePose(f, FaceDetector.Face.EULER_Z);
        double brightness = sampleBrightness(rgb565);
        rgb565.recycle();
        boolean centered = nx > 0.31f && nx < 0.69f && ny > 0.29f && ny < 0.66f;
        if (ratio < 0.085f) return new LiveFace(true, false, nx, ny, ratio, yaw, pitch, roll, brightness, "Move closer");
        if (ratio > 0.245f) return new LiveFace(true, false, nx, ny, ratio, yaw, pitch, roll, brightness, "Move slightly farther away");
        if (!centered) return new LiveFace(true, false, nx, ny, ratio, yaw, pitch, roll, brightness, "Center your face in the oval");
        if (brightness < 42) return new LiveFace(true, false, nx, ny, ratio, yaw, pitch, roll, brightness, "More light is needed");
        if (brightness > 230) return new LiveFace(true, false, nx, ny, ratio, yaw, pitch, roll, brightness, "Reduce strong light on your face");
        if (Math.abs(roll) > 16) return new LiveFace(true, false, nx, ny, ratio, yaw, pitch, roll, brightness, "Keep your head upright");
        PoseCheck check = poseCheck(nx, ny, yaw, pitch);
        return new LiveFace(true, check.ready, nx, ny, ratio, yaw, pitch, roll, brightness, check.message);
    }

    private float safePose(FaceDetector.Face face, int axis) {
        try { return face.pose(axis); } catch (Throwable ignored) { return 0f; }
    }

    private double sampleBrightness(Bitmap b) {
        long sum = 0; int count = 0;
        int stepX = Math.max(1, b.getWidth() / 24);
        int stepY = Math.max(1, b.getHeight() / 32);
        for (int y = 0; y < b.getHeight(); y += stepY) {
            for (int x = 0; x < b.getWidth(); x += stepX) {
                int p = b.getPixel(x, y);
                sum += (((p >> 16) & 255) * 30 + ((p >> 8) & 255) * 59 + (p & 255) * 11) / 100;
                count++;
            }
        }
        return count == 0 ? 0 : (double) sum / count;
    }

    private PoseCheck poseCheck(float nx, float ny, float yaw, float pitch) {
        float ay = Math.abs(yaw), ap = Math.abs(pitch);
        float dxCenter = nx - centerFaceX;
        float dyCenter = ny - centerFaceY;
        long elapsed = System.currentTimeMillis() - poseStartedAt;
        boolean detectorFallback = elapsed >= 5_500L;
        switch (poseIndex) {
            case 0:
                return ay <= 14f && ap <= 14f
                        ? PoseCheck.ready("Perfect. Hold still")
                        : PoseCheck.waiting("Face the camera directly");
            case 1: {
                boolean yawMoved = ay >= 3.5f && ay <= 42f;
                boolean centerMoved = Math.abs(dxCenter) >= 0.020f;
                if (yawMoved || centerMoved || detectorFallback)
                    return PoseCheck.ready(detectorFallback && !yawMoved && !centerMoved
                            ? "Clear side sample accepted. Hold still"
                            : "Good small side angle. Hold still");
                return PoseCheck.waiting("Turn your head only a little to either side");
            }
            case 2: {
                boolean yawOpposite = leftYawSign != 0 && ay >= 3f && sign(yaw) == -leftYawSign;
                boolean movementOpposite = leftMoveSign != 0 && Math.abs(dxCenter) >= 0.018f && sign(dxCenter) == -leftMoveSign;
                boolean clearlyDifferent = Math.abs(nx - firstSideX) >= 0.028f;
                if (yawOpposite || movementOpposite || clearlyDifferent || detectorFallback)
                    return PoseCheck.ready(detectorFallback && !yawOpposite && !movementOpposite && !clearlyDifferent
                            ? "Clear opposite-side sample accepted. Hold still"
                            : "Good opposite angle. Hold still");
                return PoseCheck.waiting("Return through center, then turn slightly the other way");
            }
            case 3: {
                boolean pitchMoved = ap >= 3.5f && ap <= 34f;
                boolean verticalMoved = Math.abs(dyCenter) >= 0.018f;
                if (pitchMoved || verticalMoved || detectorFallback)
                    return PoseCheck.ready(detectorFallback && !pitchMoved && !verticalMoved
                            ? "Clear upward sample accepted. Hold still"
                            : "Good chin-up angle. Hold still");
                return PoseCheck.waiting("Raise your chin only a little");
            }
            case 4: {
                boolean pitchOpposite = upPitchSign != 0 && ap >= 3f && sign(pitch) == -upPitchSign;
                boolean movementOpposite = upMoveSign != 0 && Math.abs(dyCenter) >= 0.016f && sign(dyCenter) == -upMoveSign;
                boolean clearlyDifferent = Math.abs(ny - firstVerticalY) >= 0.024f;
                if (pitchOpposite || movementOpposite || clearlyDifferent || detectorFallback)
                    return PoseCheck.ready(detectorFallback && !pitchOpposite && !movementOpposite && !clearlyDifferent
                            ? "Clear downward sample accepted. Hold still"
                            : "Good chin-down angle. Hold still");
                return PoseCheck.waiting("Return through center, then lower your chin a little");
            }
            default:
                return PoseCheck.ready("Hold still");
        }
    }

    private int sign(float v) { return v < -0.0001f ? -1 : v > 0.0001f ? 1 : 0; }

    private void applyLiveFace(LiveFace face) {
        guideView.setState(face.found, face.ready, face.x, face.y, face.faceRatio);
        boolean basicQuality = face.found
                && face.faceRatio >= 0.085f && face.faceRatio <= 0.245f
                && face.x > 0.28f && face.x < 0.72f && face.y > 0.26f && face.y < 0.69f
                && face.brightness >= 42 && face.brightness <= 230
                && Math.abs(face.roll) <= 18f;
        boolean fallbackAvailable = basicQuality && System.currentTimeMillis() - poseStartedAt >= 3_500L;
        if (useCurrentAngle != null) {
            useCurrentAngle.setEnabled(fallbackAvailable && !captureBusy);
            useCurrentAngle.setAlpha(fallbackAvailable ? 1f : 0.45f);
        }
        if (!face.found || !face.ready) {
            stableFrames = 0;
            quality.setText(face.message + (fallbackAvailable ? " • You may use the current clear angle" : ""));
            autoBadge.setText("● " + (face.found ? "Adjust position" : "Face not detected"));
            autoBadge.setTextColor(Color.parseColor(face.found ? WARNING : DANGER));
            speakFeedback(face.message);
            return;
        }
        stableFrames++;
        if (stableFrames == 1) { playTone(ToneGenerator.TONE_PROP_BEEP, 90); speak("Hold still", false); }
        int remaining = Math.max(1, 3 - stableFrames);
        quality.setText(face.message + " • Capturing in " + remaining);
        autoBadge.setText("● Face locked — automatic capture");
        autoBadge.setTextColor(Color.parseColor(SUCCESS));
        if (stableFrames >= 3) captureAngle();
    }

    private void captureAngle() {
        if (captureBusy || session == null || camera == null || reader == null) return;
        captureBusy = true;
        if (useCurrentAngle != null) { useCurrentAngle.setEnabled(false); useCurrentAngle.setAlpha(0.45f); }
        capturePoseIndex = poseIndex;
        stableFrames = 0;
        playTone(ToneGenerator.TONE_PROP_ACK, 120);
        quality.setText("Capturing securely…");
        autoBadge.setText("● Capturing");
        try {
            CaptureRequest.Builder b = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            b.addTarget(reader.getSurface());
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            b.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation());
            session.capture(b.build(), null, cameraHandler);
        } catch (Exception e) {
            captureBusy = false;
            playTone(ToneGenerator.TONE_PROP_NACK, 180);
            quality.setText("Capture failed. Hold still and try again.");
        }
    }

    private void handleCapturedImage(ImageReader r) {
        Image image = null;
        try {
            image = r.acquireLatestImage();
            if (image == null) return;
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()]; buffer.get(bytes);
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bitmap == null) throw new IllegalStateException("Front-camera JPEG could not be decoded");
            Bitmap upright = rotate(bitmap, jpegOrientation());
            if (upright != bitmap) bitmap.recycle();
            bitmap = upright;
            String pose = POSE_NAMES[Math.min(capturePoseIndex, POSE_NAMES.length - 1)];
            FaceSimilarity.EnrollmentResult result = FaceSimilarity.enrollGuided(this, bitmap, pose);
            bitmap.recycle();
            LiveFace capturedFace = lastLiveFace;
            runOnUiThread(() -> {
                captureBusy = false;
                cooldownUntil = System.currentTimeMillis() + 1_100L;
                if (!result.accepted) {
                    playTone(ToneGenerator.TONE_PROP_NACK, 190);
                    quality.setText(result.message);
                    autoBadge.setText("● Retrying automatically");
                    autoBadge.setTextColor(Color.parseColor(WARNING));
                    return;
                }
                if (capturedFace != null) {
                    if (capturePoseIndex == 0) {
                        centerFaceX = capturedFace.x;
                        centerFaceY = capturedFace.y;
                    } else if (capturePoseIndex == 1) {
                        leftYawSign = Math.abs(capturedFace.yaw) >= 2f ? sign(capturedFace.yaw) : 0;
                        leftMoveSign = sign(capturedFace.x - centerFaceX);
                        firstSideX = capturedFace.x;
                    } else if (capturePoseIndex == 3) {
                        upPitchSign = Math.abs(capturedFace.pitch) >= 2f ? sign(capturedFace.pitch) : 0;
                        upMoveSign = sign(capturedFace.y - centerFaceY);
                        firstVerticalY = capturedFace.y;
                    }
                }
                int count = FaceSimilarity.sampleCount(this);
                progressBar.setProgress(count);
                playTone(ToneGenerator.TONE_PROP_ACK, 180);
                Toast.makeText(this, "Angle accepted (" + count + "/5)", Toast.LENGTH_SHORT).show();
                speak("Angle accepted", true);
                if (count >= 5) {
                    enrollmentCompleted = true;
                    analysisRunning = false;
                    mainHandler.removeCallbacks(faceLoop);
                    setResult(RESULT_OK);
                    playTone(ToneGenerator.TONE_PROP_ACK, 350);
                    speak("Enrollment complete. Your owner face profile is ready.", true);
                    new AlertDialog.Builder(this)
                            .setTitle("Enrollment complete")
                            .setMessage("Five automatic front-camera angles were accepted. Future captures will be compared against the complete owner profile.")
                            .setPositiveButton("Done", (d, w) -> finish())
                            .setCancelable(false).show();
                } else {
                    updateGuide();
                }
            });
        } catch (Exception e) {
            runOnUiThread(() -> {
                captureBusy = false;
                cooldownUntil = System.currentTimeMillis() + 850L;
                playTone(ToneGenerator.TONE_PROP_NACK, 180);
                quality.setText("Could not process this frame. Hold still; automatic detection will retry.");
            });
        } finally {
            if (image != null) image.close();
        }
    }

    private Bitmap rotate(Bitmap source, int degrees) {
        int normalized = ((degrees % 360) + 360) % 360;
        if (normalized == 0) return source;
        Matrix m = new Matrix(); m.postRotate(normalized);
        return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), m, true);
    }


    private void speak(String message, boolean flush) {
        if (!voiceReady || voice == null || message == null || message.isEmpty()) return;
        try { voice.speak(message, flush ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, null, "ownerguard_enroll"); } catch (Exception ignored) {}
    }

    private void speakFeedback(String message) {
        long now = System.currentTimeMillis();
        if (message == null || message.equals(lastVoiceMessage) || now - lastVoiceAt < 2800L) return;
        lastVoiceMessage = message; lastVoiceAt = now; speak(message, true);
    }

    private void playTone(int tone, int durationMs) {
        try { if (tones != null) tones.startTone(tone, durationMs); } catch (Exception ignored) {}
    }

    private void cancelEnrollment() {
        if (enrollmentReset && !enrollmentCompleted) FaceSimilarity.clear(this);
        setResult(RESULT_CANCELED);
        finish();
    }

    @Override public void onBackPressed() { cancelEnrollment(); }

    private int jpegOrientation() {
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        int degrees = rotation == Surface.ROTATION_90 ? 90 : rotation == Surface.ROTATION_180 ? 180 : rotation == Surface.ROTATION_270 ? 270 : 0;
        return (sensorOrientation + degrees) % 360;
    }

    private void showCameraError(String message) {
        runOnUiThread(() -> {
            analysisRunning = false;
            mainHandler.removeCallbacks(faceLoop);
            quality.setText(message);
            autoBadge.setText("● Camera unavailable");
            autoBadge.setTextColor(Color.parseColor(DANGER));
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        });
    }

    private void closeCamera() {
        analysisRunning = false;
        mainHandler.removeCallbacks(faceLoop);
        try { if (session != null) session.close(); } catch (Exception ignored) {}
        session = null;
        try { if (camera != null) camera.close(); } catch (Exception ignored) {}
        camera = null;
        try { if (reader != null) reader.close(); } catch (Exception ignored) {}
        reader = null;
        if (cameraThread != null) {
            cameraThread.quitSafely();
            try { cameraThread.join(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            cameraThread = null; cameraHandler = null;
        }
    }

    private TextView text(String value, int sp, boolean bold, String color) {
        TextView t = new TextView(this);
        t.setText(value); t.setTextSize(sp); t.setTextColor(Color.parseColor(color));
        t.setPadding(0, dp(5), 0, dp(5));
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return t;
    }

    private LinearLayout cardLayout(String color) {
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(rounded(color, 18)); l.setPadding(dp(16), dp(14), dp(16), dp(14));
        return l;
    }

    private Button secondaryButton(String label) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false);
        b.setTextColor(Color.parseColor(TEXT)); b.setTextSize(15);
        b.setBackground(rounded(SURFACE_ALT, 16));
        b.setPadding(dp(16), dp(13), dp(16), dp(13));
        return b;
    }

    private GradientDrawable rounded(String color, int radiusDp) {
        GradientDrawable d = new GradientDrawable(); d.setColor(Color.parseColor(color));
        d.setCornerRadius(dp(radiusDp)); return d;
    }

    private LinearLayout.LayoutParams bottomMargin(int bottomDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(bottomDp)); return lp;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(7), 0, dp(7)); return lp;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onPause() { closeCamera(); super.onPause(); }
    @Override protected void onResume() {
        super.onResume();
        if(!AuthSession.isUnlocked()){
            startActivity(new android.content.Intent(this,MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP));
            finish(); return;
        }
        if (enrollmentReset && preview != null && preview.isAvailable()) openFrontCamera();
    }
    @Override protected void onDestroy() {
        mainHandler.removeCallbacks(faceLoop);
        if (tones != null) { try { tones.release(); } catch (Exception ignored) {} tones = null; }
        if (voice != null) { try { voice.stop(); voice.shutdown(); } catch (Exception ignored) {} voice = null; }
        if (isFinishing() && enrollmentReset && !enrollmentCompleted) FaceSimilarity.clear(this);
        if (guideView != null) guideView.stopAnimation();
        super.onDestroy();
    }

    private static final class PoseCheck {
        final boolean ready; final String message;
        PoseCheck(boolean ready, String message) { this.ready = ready; this.message = message; }
        static PoseCheck ready(String m) { return new PoseCheck(true, m); }
        static PoseCheck waiting(String m) { return new PoseCheck(false, m); }
    }

    private static final class LiveFace {
        final boolean found, ready;
        final float x, y, faceRatio, yaw, pitch, roll;
        final double brightness;
        final String message;
        LiveFace(boolean found, boolean ready, float x, float y, float ratio, float yaw, float pitch, float roll, double brightness, String message) {
            this.found = found; this.ready = ready; this.x = x; this.y = y; this.faceRatio = ratio;
            this.yaw = yaw; this.pitch = pitch; this.roll = roll; this.brightness = brightness; this.message = message;
        }
        static LiveFace none(String message) { return new LiveFace(false, false, 0.5f, 0.45f, 0.17f, 0, 0, 0, 0, message); }
    }

    private static final class FaceGuideView extends View {
        private final Paint ovalPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint scanPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ValueAnimator animator;
        private boolean found, ready;
        private float faceX = 0.5f, faceY = 0.45f, faceRatio = 0.17f;
        private float phase;

        FaceGuideView(Context c) {
            super(c);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(1600L);
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.setRepeatMode(ValueAnimator.REVERSE);
            animator.addUpdateListener(a -> { phase = (float) a.getAnimatedValue(); invalidate(); });
            animator.start();
        }

        void setState(boolean found, boolean ready, float x, float y, float ratio) {
            this.found = found; this.ready = ready; faceX = x; faceY = y; faceRatio = ratio; invalidate();
        }

        void stopAnimation() { try { animator.cancel(); } catch (Exception ignored) {} }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth(), h = getHeight();
            RectF oval = new RectF(w * 0.16f, h * 0.10f, w * 0.84f, h * 0.88f);
            shadePaint.setColor(Color.argb(78, 0, 0, 0));
            canvas.drawRect(0, 0, w, h, shadePaint);
            shadePaint.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR));
            canvas.drawOval(oval, shadePaint);
            shadePaint.setXfermode(null);

            int color = Color.parseColor(ready ? SUCCESS : found ? WARNING : MUTED);
            ovalPaint.setStyle(Paint.Style.STROKE);
            ovalPaint.setStrokeWidth(ready ? 7f : 5f);
            ovalPaint.setColor(color);
            ovalPaint.setAlpha((int) (175 + phase * 80));
            canvas.drawOval(oval, ovalPaint);

            scanPaint.setStrokeWidth(3f);
            scanPaint.setColor(Color.parseColor(PRIMARY));
            scanPaint.setAlpha(ready ? 230 : 125);
            float scanY = oval.top + phase * oval.height();
            float dx = (float) (oval.width() * Math.sqrt(Math.max(0, 1 - Math.pow((scanY - oval.centerY()) / (oval.height() / 2f), 2))) / 2f);
            canvas.drawLine(oval.centerX() - dx, scanY, oval.centerX() + dx, scanY, scanPaint);

            if (found) {
                float cx = faceX * w;
                float cy = faceY * h;
                float r = Math.max(28f, faceRatio * w * 1.35f);
                dotPaint.setStyle(Paint.Style.STROKE);
                dotPaint.setStrokeWidth(3f);
                dotPaint.setColor(color);
                canvas.drawCircle(cx, cy, r, dotPaint);
            }
        }
    }
}
