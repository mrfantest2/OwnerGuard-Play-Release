package com.fantest.ownerguard;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.media.ExifInterface;
import android.media.FaceDetector;
import android.util.Base64;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Lightweight offline owner-similarity signal.
 *
 * v1.0.22 is intentionally still not biometric authentication. It improves the
 * owner test by comparing three frames, testing camera rotations/mirroring, and
 * combining aligned appearance, edge, block, and local-pattern similarity. The
 * decision threshold is calibrated from the five enrolled owner samples.
 */
final class FaceSimilarity {
    private static final String PREF = "owner_guard_security";
    private static final String OWNER_COUNT = "owner_face_descriptor_count";
    private static final String OWNER_SLOT = "owner_face_descriptor_";
    private static final String OWNER_POSE = "owner_face_pose_";
    private static final int N = 32;
    static final int MAX_SAMPLES = 5;

    static final class Result {
        final boolean faceFound;
        final double similarity;
        final double bestSimilarity;
        final int matchedSamples;
        final double threshold;
        final double confidence;
        final boolean ownerLikely;

        Result(boolean found, double score) {
            this(found, score, score, score > 0 ? 1 : 0, 0.64, 0.0, false);
        }

        Result(boolean found, double score, double best, int matches) {
            this(found, score, best, matches, 0.64, 0.0, false);
        }

        Result(boolean found, double score, double best, int matches,
               double threshold, double confidence, boolean ownerLikely) {
            this.faceFound = found;
            this.similarity = score;
            this.bestSimilarity = best;
            this.matchedSamples = matches;
            this.threshold = threshold;
            this.confidence = confidence;
            this.ownerLikely = ownerLikely;
        }
    }

    static final class EnrollmentResult {
        final boolean accepted;
        final String message;
        EnrollmentResult(boolean accepted, String message) {
            this.accepted = accepted;
            this.message = message;
        }
    }

    private static final class DescriptorData {
        final byte[] bytes;
        final String error;
        DescriptorData(byte[] bytes, String error) {
            this.bytes = bytes;
            this.error = error;
        }
    }

    private static final class FaceFrame {
        final Bitmap bitmap;
        final PointF midpoint;
        final float eye;
        FaceFrame(Bitmap bitmap, PointF midpoint, float eye) {
            this.bitmap = bitmap;
            this.midpoint = midpoint;
            this.eye = eye;
        }
    }

    private FaceSimilarity() {}

    static boolean isEnrolled(Context c) {
        return sampleCount(c) == MAX_SAMPLES;
    }

    static int sampleCount(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(OWNER_COUNT, 0);
    }

    static void clear(Context c) {
        SharedPreferences.Editor e = c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit();
        for (int i = 0; i < MAX_SAMPLES; i++) {
            e.remove(OWNER_SLOT + i);
            e.remove(OWNER_POSE + i);
        }
        e.remove(OWNER_COUNT).apply();
    }

    static EnrollmentResult enrollGuided(Context c, Bitmap bitmap, String pose) {
        DescriptorData data = descriptor(bitmap, true);
        if (data.bytes == null) return new EnrollmentResult(false, data.error);
        SharedPreferences prefs = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        int count = prefs.getInt(OWNER_COUNT, 0);
        if (count >= MAX_SAMPLES) {
            return new EnrollmentResult(false,
                    "Five owner angles are already enrolled. Start a new enrollment to replace them.");
        }
        prefs.edit()
                .putString(OWNER_SLOT + count, Base64.encodeToString(data.bytes, Base64.NO_WRAP))
                .putString(OWNER_POSE + count, pose)
                .putInt(OWNER_COUNT, count + 1)
                .apply();
        return new EnrollmentResult(true, "Angle accepted");
    }

    static boolean enroll(Context c, Bitmap bitmap) {
        return enrollGuided(c, bitmap, "sample_" + (sampleCount(c) + 1)).accepted;
    }

    static Result compare(Context c, File jpeg) {
        Bitmap source = null;
        try {
            source = decodeUpright(jpeg);
            if (source == null) return noFace();
            List<byte[]> candidates = descriptorVariants(source);
            if (candidates.isEmpty()) return noFace();

            List<byte[]> owners = ownerDescriptors(c);
            if (owners.isEmpty()) return new Result(true, -1.0, -1.0, 0, 0.64, 0.0, false);

            double threshold = calibratedThreshold(owners);
            double[] sampleScores = new double[owners.size()];
            int used = 0;
            for (byte[] owner : owners) {
                double bestForSample = 0.0;
                for (byte[] candidate : candidates) {
                    bestForSample = Math.max(bestForSample, descriptorScore(owner, candidate));
                }
                sampleScores[used++] = bestForSample;
            }
            if (used == 0) return noFace();

            sampleScores = Arrays.copyOf(sampleScores, used);
            Arrays.sort(sampleScores);
            double best = sampleScores[used - 1];
            double second = used > 1 ? sampleScores[used - 2] : best;
            double third = used > 2 ? sampleScores[used - 3] : second;
            double averageTop = used > 2 ? (best + second + third) / 3.0 : (best + second) / 2.0;
            int matched = 0;
            for (double score : sampleScores) if (score >= threshold - 0.025) matched++;

            double calibrated = clamp(best * 0.48 + second * 0.32 + averageTop * 0.20, 0.0, 1.0);
            boolean likely = (calibrated >= threshold && matched >= 2)
                    || (best >= threshold + 0.075 && second >= threshold - 0.035);
            double confidence = clamp((calibrated - threshold + 0.12) / 0.24, 0.0, 1.0);
            return new Result(true, calibrated, best, matched, threshold, confidence, likely);
        } catch (Throwable ignored) {
            return noFace();
        } finally {
            if (source != null && !source.isRecycled()) source.recycle();
        }
    }

    static Result aggregate(List<Result> frames) {
        List<Result> valid = new ArrayList<>();
        for (Result frame : frames) if (frame != null && frame.faceFound) valid.add(frame);
        if (valid.isEmpty()) return noFace();

        List<Double> scores = new ArrayList<>();
        double best = 0.0;
        double threshold = 0.0;
        int votes = 0;
        int matched = 0;
        for (Result r : valid) {
            scores.add(r.similarity);
            best = Math.max(best, r.bestSimilarity);
            threshold += r.threshold;
            matched = Math.max(matched, r.matchedSamples);
            if (r.ownerLikely) votes++;
        }
        threshold /= valid.size();
        Collections.sort(scores);
        double median = scores.get(scores.size() / 2);
        double average = 0.0;
        for (double s : scores) average += s;
        average /= scores.size();
        double finalScore = clamp(median * 0.55 + best * 0.25 + average * 0.20, 0.0, 1.0);
        boolean likely = votes >= 2 || (valid.size() >= 2 && finalScore >= threshold && matched >= 2);
        double confidence = clamp((finalScore - threshold + 0.12) / 0.24, 0.0, 1.0);
        return new Result(true, finalScore, best, matched, threshold, confidence, likely);
    }

    private static Result noFace() {
        return new Result(false, 0.0, 0.0, 0, 0.64, 0.0, false);
    }

    private static List<byte[]> ownerDescriptors(Context c) {
        List<byte[]> owners = new ArrayList<>();
        SharedPreferences prefs = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        int count = sampleCount(c);
        for (int i = 0; i < count; i++) {
            String raw = prefs.getString(OWNER_SLOT + i, null);
            if (raw == null) continue;
            try {
                byte[] descriptor = Base64.decode(raw, Base64.NO_WRAP);
                if (descriptor.length == N * N) owners.add(descriptor);
            } catch (Throwable ignored) {}
        }
        return owners;
    }

    private static double calibratedThreshold(List<byte[]> owners) {
        List<Double> pairs = new ArrayList<>();
        for (int i = 0; i < owners.size(); i++) {
            for (int j = i + 1; j < owners.size(); j++) {
                pairs.add(descriptorScore(owners.get(i), owners.get(j)));
            }
        }
        if (pairs.isEmpty()) return 0.635;
        Collections.sort(pairs, Collections.reverseOrder());
        int take = Math.min(4, pairs.size());
        double baseline = 0.0;
        for (int i = 0; i < take; i++) baseline += pairs.get(i);
        baseline /= take;
        return clamp(0.620 + Math.max(0.0, baseline - 0.650) * 0.20, 0.620, 0.680);
    }

    private static List<byte[]> descriptorVariants(Bitmap source) {
        List<byte[]> out = new ArrayList<>();
        int[] rotations = new int[]{0, 90, 270, 180};
        for (int degrees : rotations) {
            Bitmap oriented = degrees == 0 ? source : rotateCopy(source, degrees);
            if (oriented == null) continue;
            FaceFrame frame = detect(oriented);
            if (degrees != 0 && oriented != source && !oriented.isRecycled()) oriented.recycle();
            if (frame == null) continue;

            float[] scales = new float[]{3.65f, 4.05f, 4.40f, 4.80f, 5.15f};
            float[] vertical = new float[]{0.37f, 0.41f, 0.45f};
            for (float scale : scales) {
                for (float anchor : vertical) {
                    Bitmap crop = cropFace(frame, scale, anchor);
                    if (crop == null) continue;
                    DescriptorData normal = normalizeCrop(crop, false);
                    if (normal.bytes != null) out.add(normal.bytes);
                    Bitmap mirrored = mirror(crop);
                    if (mirrored != null) {
                        DescriptorData mirroredData = normalizeCrop(mirrored, false);
                        if (mirroredData.bytes != null) out.add(mirroredData.bytes);
                        mirrored.recycle();
                    }
                    crop.recycle();
                }
            }
            frame.bitmap.recycle();
            // Once one orientation produces a clear face, its crop and mirrored variants are enough.
            // Additional rotations are only fallbacks for older cameras with missing EXIF orientation.
            if (!out.isEmpty()) break;
        }
        return out;
    }

    private static DescriptorData descriptor(Bitmap source, boolean strictEnrollment) {
        FaceFrame frame = detect(source);
        if (frame == null) {
            return new DescriptorData(null,
                    "No clear face detected. Keep your full face inside the guide and look toward the camera.");
        }
        float ratio = frame.eye / Math.max(1f, frame.bitmap.getWidth());
        if (strictEnrollment && ratio < 0.060f) {
            frame.bitmap.recycle();
            return new DescriptorData(null, "Move closer. Your face is too small for a secure enrollment sample.");
        }
        Bitmap crop = cropFace(frame, 4.40f, 0.42f);
        frame.bitmap.recycle();
        if (crop == null || crop.getWidth() < 96) {
            if (crop != null) crop.recycle();
            return new DescriptorData(null, "Move closer and keep your face centered.");
        }
        DescriptorData result = normalizeCrop(crop, strictEnrollment);
        crop.recycle();
        return result;
    }

    private static FaceFrame detect(Bitmap source) {
        if (source == null || source.getWidth() < 120 || source.getHeight() < 120) return null;
        int maxSide = Math.max(source.getWidth(), source.getHeight());
        Bitmap working = source;
        if (maxSide > 1280) {
            float scale = 1280f / maxSide;
            working = Bitmap.createScaledBitmap(source,
                    Math.max(2, Math.round(source.getWidth() * scale)),
                    Math.max(2, Math.round(source.getHeight() * scale)), true);
        }
        Bitmap rgb565 = working.copy(Bitmap.Config.RGB_565, false);
        if (working != source) working.recycle();
        if (rgb565 == null) return null;
        FaceDetector.Face[] faces = new FaceDetector.Face[1];
        try {
            int found = new FaceDetector(rgb565.getWidth(), rgb565.getHeight(), 1).findFaces(rgb565, faces);
            if (found < 1 || faces[0] == null) {
                rgb565.recycle();
                return null;
            }
            PointF mid = new PointF();
            faces[0].getMidPoint(mid);
            return new FaceFrame(rgb565, mid, faces[0].eyesDistance());
        } catch (Throwable ignored) {
            rgb565.recycle();
            return null;
        }
    }

    private static Bitmap cropFace(FaceFrame frame, float scale, float verticalAnchor) {
        int max = Math.min(frame.bitmap.getWidth(), frame.bitmap.getHeight());
        int size = Math.min(max, Math.round(Math.max(20f, frame.eye) * scale));
        if (size < 90) return null;
        int left = Math.max(0, Math.min(frame.bitmap.getWidth() - size,
                Math.round(frame.midpoint.x - size / 2f)));
        int top = Math.max(0, Math.min(frame.bitmap.getHeight() - size,
                Math.round(frame.midpoint.y - size * verticalAnchor)));
        try {
            return Bitmap.createBitmap(frame.bitmap, left, top, size, size);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static DescriptorData normalizeCrop(Bitmap crop, boolean strictEnrollment) {
        Bitmap small = Bitmap.createScaledBitmap(crop, N, N, true);
        int[] pixels = new int[N * N];
        small.getPixels(pixels, 0, N, 0, 0, N, N);
        small.recycle();

        int[] gray = new int[pixels.length];
        double mean = 0.0;
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            int g = (((p >> 16) & 255) * 30 + ((p >> 8) & 255) * 59 + (p & 255) * 11) / 100;
            gray[i] = g;
            mean += g;
        }
        mean /= gray.length;
        double variance = 0.0;
        for (int g : gray) variance += (g - mean) * (g - mean);
        double std = Math.sqrt(variance / gray.length) + 1e-6;

        if (strictEnrollment && mean < 28) {
            return new DescriptorData(null, "The scene is too dark. Face a light source and try again.");
        }
        if (strictEnrollment && mean > 238) {
            return new DescriptorData(null, "The face is overexposed. Reduce strong direct light and try again.");
        }
        if (strictEnrollment && std < 10) {
            return new DescriptorData(null, "The image lacks detail. Improve lighting and hold the phone steady.");
        }

        byte[] out = new byte[gray.length];
        for (int i = 0; i < gray.length; i++) {
            int q = (int) Math.round(((gray[i] - mean) / std) * 32.0);
            q = Math.max(-127, Math.min(127, q));
            out[i] = (byte) q;
        }
        return new DescriptorData(out, "");
    }

    private static double descriptorScore(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != N * N || b.length != N * N) return 0.0;
        double pixel = unit(bestShiftedCosine(a, b, 2));
        double gradient = unit(bestShiftedGradientCosine(a, b, 1));
        double block = unit(cosine(blockDescriptor(a), blockDescriptor(b)));
        double pattern = localPatternSimilarity(a, b);
        return clamp(pixel * 0.50 + gradient * 0.24 + block * 0.17 + pattern * 0.09, 0.0, 1.0);
    }

    private static double bestShiftedCosine(byte[] a, byte[] b, int radius) {
        double best = -1.0;
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                best = Math.max(best, shiftedCosine(a, b, dx, dy));
            }
        }
        return best;
    }

    private static double shiftedCosine(byte[] a, byte[] b, int dx, int dy) {
        double dot = 0.0, aa = 0.0, bb = 0.0;
        for (int y = 0; y < N; y++) {
            int by = y + dy;
            if (by < 0 || by >= N) continue;
            for (int x = 0; x < N; x++) {
                int bx = x + dx;
                if (bx < 0 || bx >= N) continue;
                double av = a[y * N + x];
                double bv = b[by * N + bx];
                dot += av * bv;
                aa += av * av;
                bb += bv * bv;
            }
        }
        return dot / (Math.sqrt(aa) * Math.sqrt(bb) + 1e-9);
    }

    private static double bestShiftedGradientCosine(byte[] a, byte[] b, int radius) {
        double[] ax = gradientX(a), ay = gradientY(a);
        double[] bx = gradientX(b), by = gradientY(b);
        double best = -1.0;
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                double sx = shiftedCosine(ax, bx, dx, dy);
                double sy = shiftedCosine(ay, by, dx, dy);
                best = Math.max(best, (sx + sy) / 2.0);
            }
        }
        return best;
    }

    private static double shiftedCosine(double[] a, double[] b, int dx, int dy) {
        double dot = 0.0, aa = 0.0, bb = 0.0;
        for (int y = 1; y < N - 1; y++) {
            int by = y + dy;
            if (by < 1 || by >= N - 1) continue;
            for (int x = 1; x < N - 1; x++) {
                int bx = x + dx;
                if (bx < 1 || bx >= N - 1) continue;
                double av = a[y * N + x];
                double bv = b[by * N + bx];
                dot += av * bv;
                aa += av * av;
                bb += bv * bv;
            }
        }
        return dot / (Math.sqrt(aa) * Math.sqrt(bb) + 1e-9);
    }

    private static double[] gradientX(byte[] d) {
        double[] out = new double[d.length];
        for (int y = 1; y < N - 1; y++) {
            for (int x = 1; x < N - 1; x++) {
                out[y * N + x] = d[y * N + x + 1] - d[y * N + x - 1];
            }
        }
        return out;
    }

    private static double[] gradientY(byte[] d) {
        double[] out = new double[d.length];
        for (int y = 1; y < N - 1; y++) {
            for (int x = 1; x < N - 1; x++) {
                out[y * N + x] = d[(y + 1) * N + x] - d[(y - 1) * N + x];
            }
        }
        return out;
    }

    private static double[] blockDescriptor(byte[] d) {
        int cells = 8;
        int cellSize = N / cells;
        double[] out = new double[cells * cells];
        double mean = 0.0;
        int index = 0;
        for (int cy = 0; cy < cells; cy++) {
            for (int cx = 0; cx < cells; cx++) {
                double sum = 0.0;
                for (int y = 0; y < cellSize; y++) {
                    for (int x = 0; x < cellSize; x++) {
                        sum += d[(cy * cellSize + y) * N + cx * cellSize + x];
                    }
                }
                out[index] = sum / (cellSize * cellSize);
                mean += out[index++];
            }
        }
        mean /= out.length;
        for (int i = 0; i < out.length; i++) out[i] -= mean;
        return out;
    }

    private static double localPatternSimilarity(byte[] a, byte[] b) {
        int same = 0;
        int total = 0;
        int[] offsets = new int[]{-N - 1, -N, -N + 1, -1, 1, N - 1, N, N + 1};
        for (int y = 1; y < N - 1; y++) {
            for (int x = 1; x < N - 1; x++) {
                int i = y * N + x;
                for (int offset : offsets) {
                    boolean ap = a[i + offset] >= a[i];
                    boolean bp = b[i + offset] >= b[i];
                    if (ap == bp) same++;
                    total++;
                }
            }
        }
        return total == 0 ? 0.0 : (double) same / total;
    }

    private static double cosine(double[] a, double[] b) {
        if (a.length != b.length) return 0.0;
        double dot = 0.0, aa = 0.0, bb = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            aa += a[i] * a[i];
            bb += b[i] * b[i];
        }
        return dot / (Math.sqrt(aa) * Math.sqrt(bb) + 1e-9);
    }

    private static double unit(double raw) {
        return clamp((raw + 1.0) / 2.0, 0.0, 1.0);
    }

    private static Bitmap decodeUpright(File jpeg) {
        Bitmap source = BitmapFactory.decodeFile(jpeg.getAbsolutePath());
        if (source == null) return null;
        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try {
            ExifInterface exif = new ExifInterface(jpeg.getAbsolutePath());
            orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (Exception ignored) {}
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.setScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.setRotate(180f);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.setScale(1f, -1f);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                matrix.setRotate(90f);
                matrix.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.setRotate(90f);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                matrix.setRotate(-90f);
                matrix.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.setRotate(270f);
                break;
            default:
                return source;
        }
        try {
            Bitmap corrected = Bitmap.createBitmap(source, 0, 0,
                    source.getWidth(), source.getHeight(), matrix, true);
            if (corrected != source) source.recycle();
            return corrected;
        } catch (Throwable ignored) {
            return source;
        }
    }

    private static Bitmap rotateCopy(Bitmap source, int degrees) {
        try {
            Matrix matrix = new Matrix();
            matrix.postRotate(degrees);
            return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Bitmap mirror(Bitmap source) {
        try {
            Matrix matrix = new Matrix();
            matrix.preScale(-1f, 1f);
            return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
