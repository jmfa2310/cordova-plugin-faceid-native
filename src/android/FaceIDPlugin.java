package com.company.faceidnative;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.util.Base64;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class FaceIDPlugin extends CordovaPlugin {

    private static final double DEFAULT_THRESHOLD = 0.60;
    private static final double DEFAULT_MIN_GAP = 0.05;
    private static final double MIN_FACE_WIDTH_FRACTION = 0.15;

    private volatile List<EmployeeTemplate> employeeTemplates =
            Collections.emptyList();

    private FaceDetector faceDetector;
    private MobileFaceNetEngine engine;
    private final Object engineLock = new Object();
    private final Object faceDetectorLock = new Object();

    @Override
    protected void pluginInitialize() {
        FaceDetectorOptions options =
                new FaceDetectorOptions.Builder()
                        .setPerformanceMode(
                                FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                        .setLandmarkMode(
                                FaceDetectorOptions.LANDMARK_MODE_ALL)
                        .setClassificationMode(
                                FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                        .setMinFaceSize((float) MIN_FACE_WIDTH_FRACTION)
                        .build();

        faceDetector = FaceDetection.getClient(options);
    }

    @Override
    public boolean execute(
            String action,
            JSONArray args,
            CallbackContext callbackContext) throws JSONException {

        switch (action) {
            case "isAvailable":
                cordova.getThreadPool().execute(
                        () -> handleIsAvailable(callbackContext));
                return true;

            case "createDescriptor": {
                final String imageBase64 = args.getString(0);
                cordova.getThreadPool().execute(
                        () -> handleCreateDescriptor(
                                imageBase64, callbackContext));
                return true;
            }

            case "setEmployees": {
                final String employeesJson = args.getString(0);
                cordova.getThreadPool().execute(
                        () -> handleSetEmployees(
                                employeesJson, callbackContext));
                return true;
            }

            case "findBestMatch": {
                final String imageBase64 = args.getString(0);
                final double threshold = normalizeThreshold(
                        args.optDouble(1, DEFAULT_THRESHOLD));
                final double minGap = normalizeMinGap(
                        args.optDouble(2, DEFAULT_MIN_GAP));

                cordova.getThreadPool().execute(
                        () -> handleFindBestMatch(
                                imageBase64,
                                threshold,
                                minGap,
                                callbackContext));
                return true;
            }

            case "clearEmployees":
                employeeTemplates = Collections.emptyList();
                try {
                    JSONObject result = new JSONObject();
                    result.put("success", true);
                    result.put("count", 0);
                    callbackContext.success(result);
                } catch (JSONException e) {
                    callbackContext.error(e.getMessage());
                }
                return true;

            case "dispose":
                disposeNative();
                try {
                    JSONObject result = new JSONObject();
                    result.put("success", true);
                    callbackContext.success(result);
                } catch (JSONException e) {
                    callbackContext.error(e.getMessage());
                }
                return true;

            default:
                return false;
        }
    }

    private void handleIsAvailable(CallbackContext callbackContext) {
        try {
            JSONObject result = new JSONObject();
            result.put("available", true);
            result.put("embeddingSize",
                    MobileFaceNetEngine.EMBEDDING_SIZE);

            callbackContext.success(result);

        } catch (Exception e) {
            callbackContext.error(
                    message("FACEID_NOT_AVAILABLE", e));
        }
    }

    private void handleCreateDescriptor(
            String imageBase64,
            CallbackContext callbackContext) {

        Bitmap bitmap = null;
        Bitmap faceCrop = null;

        try {
            ensureEngine();

            bitmap = decodeBitmap(imageBase64);
            faceCrop = detectAndCropSingleFace(bitmap);

            float[] descriptor = engine.embedding(faceCrop);

            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("descriptor",
                    descriptorToJson(descriptor).toString());
            result.put("embeddingSize",
                    MobileFaceNetEngine.EMBEDDING_SIZE);

            callbackContext.success(result);

        } catch (Exception e) {
            callbackContext.error(
                    message("DESCRIPTOR_FAILED", e));

        } finally {
            recycle(faceCrop);
            recycle(bitmap);
        }
    }

    private void handleSetEmployees(
            String employeesJson,
            CallbackContext callbackContext) {

        try {
            JSONArray array = new JSONArray(employeesJson);

            List<EmployeeTemplate> parsed = new ArrayList<>();
            int skipped = 0;

            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);

                if (item == null) {
                    skipped++;
                    continue;
                }

                if (item.has("Active") &&
                        !item.optBoolean("Active", true)) {
                    continue;
                }

                String employeeId = firstNonEmpty(
                        item.optString("EmployeeId", ""),
                        item.optString("employeeId", ""));

                String name = firstNonEmpty(
                        item.optString("Name", ""),
                        item.optString("EmployeeName", ""),
                        item.optString("name", ""));

                Object descriptorValue = null;

                if (item.has("FaceDescriptorJson")) {
                    descriptorValue =
                            item.opt("FaceDescriptorJson");
                } else if (item.has("DescriptorJson")) {
                    descriptorValue =
                            item.opt("DescriptorJson");
                } else if (item.has("descriptor")) {
                    descriptorValue =
                            item.opt("descriptor");
                }

                float[] descriptor =
                        parseDescriptor(descriptorValue);

                if (employeeId.isEmpty() ||
                        descriptor == null) {
                    skipped++;
                    continue;
                }

                parsed.add(new EmployeeTemplate(
                        employeeId, name, descriptor));
            }

            employeeTemplates =
                    Collections.unmodifiableList(parsed);

            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("loaded", parsed.size());
            result.put("skipped", skipped);

            callbackContext.success(result);

        } catch (Exception e) {
            callbackContext.error(
                    message("SET_EMPLOYEES_FAILED", e));
        }
    }

    private void handleFindBestMatch(
            String imageBase64,
            double threshold,
            double minGap,
            CallbackContext callbackContext) {

        Bitmap bitmap = null;
        Bitmap faceCrop = null;

        try {
            ensureEngine();

            List<EmployeeTemplate> templates =
                    employeeTemplates;

            if (templates == null || templates.isEmpty()) {
                throw new IllegalStateException(
                        "No employee descriptors loaded. " +
                        "Call setEmployees first.");
            }

            bitmap = decodeBitmap(imageBase64);
            faceCrop = detectAndCropSingleFace(bitmap);

            float[] current = engine.embedding(faceCrop);

            EmployeeTemplate best = null;
            double bestSimilarity = -1.0;
            double secondSimilarity = -1.0;

            for (EmployeeTemplate template : templates) {
                double similarity =
                        MobileFaceNetEngine.cosineSimilarity(
                                current, template.descriptor);

                if (similarity > bestSimilarity) {
                    secondSimilarity = bestSimilarity;
                    bestSimilarity = similarity;
                    best = template;
                } else if (similarity > secondSimilarity) {
                    secondSimilarity = similarity;
                }
            }

            boolean aboveThreshold =
                    best != null &&
                    bestSimilarity >= threshold;

            boolean unambiguous =
                    secondSimilarity < 0.0 ||
                    (bestSimilarity - secondSimilarity) >= minGap;

            boolean found =
                    aboveThreshold && unambiguous;

            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("found", found);
            result.put("similarity", bestSimilarity);
            result.put("secondSimilarity", secondSimilarity);
            result.put("threshold", threshold);
            result.put("minGap", minGap);

            if (found && best != null) {
                result.put("employeeId", best.employeeId);
                result.put("employeeName", best.name);
                result.put("reason", "MATCH");
            } else {
                result.put("employeeId", "");
                result.put("employeeName", "");
                result.put("reason",
                        !aboveThreshold
                                ? "BELOW_THRESHOLD"
                                : "AMBIGUOUS");
            }

            callbackContext.success(result);

        } catch (Exception e) {
            callbackContext.error(
                    message("MATCH_FAILED", e));

        } finally {
            recycle(faceCrop);
            recycle(bitmap);
        }
    }

    private void ensureFaceDetector() {

        if (faceDetector != null) {
            return;
        }

        synchronized (faceDetectorLock) {

            if (faceDetector == null) {

                FaceDetectorOptions options =
                        new FaceDetectorOptions.Builder()
                                .setPerformanceMode(
                                        FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                                .setLandmarkMode(
                                        FaceDetectorOptions.LANDMARK_MODE_ALL)
                                .setClassificationMode(
                                        FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                                .setMinFaceSize((float) MIN_FACE_WIDTH_FRACTION)
                                .build();

                faceDetector =
                        FaceDetection.getClient(options);
            }
        }
    }

    private void ensureEngine() throws Exception {
        if (engine != null) return;

        synchronized (engineLock) {
            if (engine == null) {
                engine = new MobileFaceNetEngine(
                        cordova.getActivity()
                                .getApplicationContext());
            }
        }
    }

    private Bitmap detectAndCropSingleFace(
            Bitmap bitmap) throws Exception {

        if (bitmap == null) {
            throw new IllegalArgumentException(
                    "Image is empty.");
        }

        ensureFaceDetector();

        InputImage input =
                InputImage.fromBitmap(bitmap, 0);

        List<Face> faces =
                Tasks.await(faceDetector.process(input));

        if (faces == null || faces.isEmpty()) {
            throw new IllegalStateException(
                    "NO_FACE: No face detected.");
        }

        if (faces.size() != 1) {
            throw new IllegalStateException(
                    "MULTIPLE_FACES: Exactly one face is required.");
        }

        Face face = faces.get(0);
        Rect box = face.getBoundingBox();

        double widthFraction =
                box.width() / (double) bitmap.getWidth();

        if (widthFraction < MIN_FACE_WIDTH_FRACTION) {
            throw new IllegalStateException(
                    "FACE_TOO_SMALL: Move closer to the camera.");
        }

        return cropSquareWithMargin(
                bitmap, box, 0.25f);
    }

    private static Bitmap cropSquareWithMargin(
            Bitmap bitmap,
            Rect box,
            float marginFraction) {

        float centerX = box.exactCenterX();
        float centerY = box.exactCenterY();

        float side =
                Math.max(box.width(), box.height());

        side *= (1.0f + 2.0f * marginFraction);

        int left = Math.max(
                0,
                Math.round(centerX - side / 2.0f));

        int top = Math.max(
                0,
                Math.round(centerY - side / 2.0f));

        int right = Math.min(
                bitmap.getWidth(),
                Math.round(centerX + side / 2.0f));

        int bottom = Math.min(
                bitmap.getHeight(),
                Math.round(centerY + side / 2.0f));

        int width = right - left;
        int height = bottom - top;

        if (width <= 0 || height <= 0) {
            throw new IllegalStateException(
                    "Invalid face crop.");
        }

        int square = Math.min(width, height);

        int squareLeft =
                left + (width - square) / 2;

        int squareTop =
                top + (height - square) / 2;

        return Bitmap.createBitmap(
                bitmap,
                squareLeft,
                squareTop,
                square,
                square);
    }

    private static Bitmap decodeBitmap(
            String imageBase64) {

        if (imageBase64 == null ||
                imageBase64.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "ImageBase64 is empty.");
        }

        String value = imageBase64.trim();
        int comma = value.indexOf(',');

        if (value.startsWith("data:") && comma >= 0) {
            value = value.substring(comma + 1);
        }

        byte[] bytes;

        try {
            bytes = Base64.decode(
                    value, Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid Base64 image.", e);
        }

        Bitmap bitmap =
                BitmapFactory.decodeByteArray(
                        bytes, 0, bytes.length);

        if (bitmap == null) {
            throw new IllegalArgumentException(
                    "Could not decode image.");
        }

        return bitmap;
    }

    private static JSONArray descriptorToJson(
            float[] descriptor) throws JSONException {

        JSONArray array = new JSONArray();
        for (float value : descriptor) {
            array.put((double) value);
        }
        return array;
    }

    private static float[] parseDescriptor(
            Object raw) {

        if (raw == null || raw == JSONObject.NULL) {
            return null;
        }

        try {
            JSONArray values;

            if (raw instanceof JSONArray) {
                values = (JSONArray) raw;
            } else {
                String text =
                        String.valueOf(raw).trim();

                if (text.isEmpty()) return null;
                values = new JSONArray(text);
            }

            if (values.length() !=
                    MobileFaceNetEngine.EMBEDDING_SIZE) {
                return null;
            }

            float[] descriptor =
                    new float[
                            MobileFaceNetEngine.EMBEDDING_SIZE];

            double sum = 0.0;

            for (int i = 0; i < descriptor.length; i++) {
                float value =
                        (float) values.getDouble(i);
                descriptor[i] = value;
                sum += value * value;
            }

            double norm =
                    Math.sqrt(Math.max(sum, 1e-12));

            for (int i = 0; i < descriptor.length; i++) {
                descriptor[i] =
                        (float) (descriptor[i] / norm);
            }

            return descriptor;

        } catch (Exception ignored) {
            return null;
        }
    }

    private static String firstNonEmpty(
            String... values) {

        if (values == null) return "";

        for (String value : values) {
            if (value != null &&
                    !value.trim().isEmpty()) {
                return value.trim();
            }
        }

        return "";
    }

    private static double normalizeThreshold(
            double value) {

        if (Double.isNaN(value) ||
                value <= 0.0 || value > 1.0) {
            return DEFAULT_THRESHOLD;
        }

        return value;
    }

    private static double normalizeMinGap(
            double value) {

        if (Double.isNaN(value) ||
                value < 0.0 || value > 1.0) {
            return DEFAULT_MIN_GAP;
        }

        return value;
    }

    private static String message(
            String code, Exception e) {

        String detail =
                e == null || e.getMessage() == null
                        ? "Unknown error."
                        : e.getMessage();

        return code + ": " + detail;
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
    }

    private void disposeNative() {
        employeeTemplates =
                Collections.emptyList();

        synchronized (engineLock) {
            if (engine != null) {
                try {
                    engine.close();
                } catch (Exception ignored) {}
                engine = null;
            }
        }

        if (faceDetector != null) {
            try {
                faceDetector.close();
            } catch (Exception ignored) {}
            faceDetector = null;
        }
    }

    @Override
    public void onDestroy() {
        disposeNative();
        super.onDestroy();
    }

    private static final class EmployeeTemplate {
        final String employeeId;
        final String name;
        final float[] descriptor;

        EmployeeTemplate(
                String employeeId,
                String name,
                float[] descriptor) {
            this.employeeId = employeeId;
            this.name = name;
            this.descriptor = descriptor;
        }
    }
}
