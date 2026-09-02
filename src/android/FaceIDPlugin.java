package com.company.faceidnative;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Rect;
import android.util.Base64;

import androidx.exifinterface.media.ExifInterface;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;
import com.google.mlkit.vision.face.FaceLandmark;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class FaceIDPlugin extends CordovaPlugin {

    private static final int CAMERA_PERMISSION_REQUEST = 4104;

    private static final double DEFAULT_THRESHOLD = 0.60;
    private static final double DEFAULT_MIN_GAP = 0.05;
    private static final double MIN_FACE_WIDTH_FRACTION = 0.15;

    private static final int MAX_DECODE_DIMENSION = 1600;

    private volatile List<EmployeeTemplate> employeeTemplates =
            Collections.emptyList();

    private final Object engineLock = new Object();
    private final Object detectorLock = new Object();
    private final Object captureLock = new Object();

    private MobileFaceNetEngine engine;
    private FaceDetector faceDetector;
    private FaceCameraOverlay cameraOverlay;

    private CallbackContext pendingCaptureCallback;
    private double pendingCaptureThreshold = DEFAULT_THRESHOLD;
    private double pendingCaptureMinGap = DEFAULT_MIN_GAP;

    @Override
    protected void pluginInitialize() {
        // Intentionally empty.
        // CameraX, ML Kit and MobileFaceNet are all initialized lazily.
    }

    @Override
    public boolean execute(
            String action,
            JSONArray args,
            CallbackContext callbackContext
    ) throws JSONException {

        switch (action) {

            case "isAvailable":
                cordova.getThreadPool().execute(
                        () -> handleIsAvailable(callbackContext)
                );
                return true;

            case "createDescriptor": {
                final String imageBase64 = args.getString(0);

                cordova.getThreadPool().execute(
                        () -> handleCreateDescriptor(
                                imageBase64,
                                callbackContext
                        )
                );
                return true;
            }

            case "setEmployees": {
                final String employeesJson = args.getString(0);

                cordova.getThreadPool().execute(
                        () -> handleSetEmployees(
                                employeesJson,
                                callbackContext
                        )
                );
                return true;
            }

            case "findBestMatch": {
                final String imageBase64 = args.getString(0);
                final double threshold = normalizeThreshold(
                        args.optDouble(
                                1,
                                DEFAULT_THRESHOLD
                        )
                );
                final double minGap = normalizeMinGap(
                        args.optDouble(
                                2,
                                DEFAULT_MIN_GAP
                        )
                );

                cordova.getThreadPool().execute(
                        () -> handleFindBestMatchBase64(
                                imageBase64,
                                threshold,
                                minGap,
                                callbackContext
                        )
                );
                return true;
            }

            case "captureAndMatch": {
                final double threshold = normalizeThreshold(
                        args.optDouble(
                                0,
                                DEFAULT_THRESHOLD
                        )
                );
                final double minGap = normalizeMinGap(
                        args.optDouble(
                                1,
                                DEFAULT_MIN_GAP
                        )
                );

                requestCaptureAndMatch(
                        threshold,
                        minGap,
                        callbackContext
                );

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
                    callbackContext.error(
                            safeMessage(e)
                    );
                }

                return true;

            case "dispose":
                disposeNative();

                try {
                    JSONObject result = new JSONObject();
                    result.put("success", true);
                    callbackContext.success(result);
                } catch (JSONException e) {
                    callbackContext.error(
                            safeMessage(e)
                    );
                }

                return true;

            default:
                return false;
        }
    }

    private void handleIsAvailable(
            CallbackContext callbackContext
    ) {
        try {
            boolean modelPresent = false;

            try (InputStream ignored =
                         cordova.getActivity()
                                 .getApplicationContext()
                                 .getAssets()
                                 .open("facenet.tflite")) {

                modelPresent = true;
            }

            JSONObject result = new JSONObject();
            result.put("available", modelPresent);
            result.put(
                    "embeddingSize",
                    MobileFaceNetEngine.EMBEDDING_SIZE
            );
            result.put(
                    "captureMode",
                    "CAMERAX_IN_APP"
            );
            result.put("model", "FACENET_128D_SLIM");
            result.put("preprocess", "LANDMARK_ALIGN_5PT_TTA_160_V3");

            callbackContext.success(result);

        } catch (Exception e) {
            callbackContext.error(
                    message(
                            "FACEID_NOT_AVAILABLE",
                            e
                    )
            );
        }
    }

    private void handleCreateDescriptor(
            String imageBase64,
            CallbackContext callbackContext
    ) {
        Bitmap bitmap = null;
        Bitmap faceCrop = null;

        try {
            ensureEngine();

            bitmap = decodeBase64BitmapOriented(
                    imageBase64
            );

            faceCrop = detectAndCropSingleFace(
                    bitmap
            );

            float[] descriptor =
                    embeddingWithFlipTta(faceCrop);

            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put(
                    "descriptor",
                    descriptorToJson(descriptor).toString()
            );
            result.put(
                    "embeddingSize",
                    MobileFaceNetEngine.EMBEDDING_SIZE
            );

            callbackContext.success(result);

        } catch (Exception e) {
            callbackContext.error(
                    message(
                            "DESCRIPTOR_FAILED",
                            e
                    )
            );

        } finally {
            recycle(faceCrop);
            recycle(bitmap);
        }
    }

    private void handleSetEmployees(
            String employeesJson,
            CallbackContext callbackContext
    ) {
        try {
            JSONArray array =
                    new JSONArray(employeesJson);

            List<EmployeeTemplate> parsed =
                    new ArrayList<>();

            int skipped = 0;

            for (int i = 0;
                 i < array.length();
                 i++) {

                JSONObject item =
                        array.optJSONObject(i);

                if (item == null) {
                    skipped++;
                    continue;
                }

                if (item.has("Active") &&
                        !item.optBoolean(
                                "Active",
                                true
                        )) {
                    continue;
                }

                String employeeId =
                        firstNonEmpty(
                                item.optString(
                                        "EmployeeId",
                                        ""
                                ),
                                item.optString(
                                        "employeeId",
                                        ""
                                )
                        );

                String name =
                        firstNonEmpty(
                                item.optString(
                                        "Name",
                                        ""
                                ),
                                item.optString(
                                        "EmployeeName",
                                        ""
                                ),
                                item.optString(
                                        "name",
                                        ""
                                )
                        );

                Object descriptorValue = null;

                if (item.has(
                        "FaceDescriptorJson"
                )) {
                    descriptorValue =
                            item.opt(
                                    "FaceDescriptorJson"
                            );

                } else if (item.has(
                        "DescriptorJson"
                )) {
                    descriptorValue =
                            item.opt(
                                    "DescriptorJson"
                            );

                } else if (item.has(
                        "descriptor"
                )) {
                    descriptorValue =
                            item.opt(
                                    "descriptor"
                            );
                }

                float[] descriptor =
                        parseDescriptor(
                                descriptorValue
                        );

                if (employeeId.isEmpty() ||
                        descriptor == null) {
                    skipped++;
                    continue;
                }

                parsed.add(
                        new EmployeeTemplate(
                                employeeId,
                                name,
                                descriptor
                        )
                );
            }

            employeeTemplates =
                    Collections.unmodifiableList(
                            parsed
                    );

            JSONObject result =
                    new JSONObject();

            result.put("success", true);
            result.put(
                    "loaded",
                    parsed.size()
            );
            result.put(
                    "skipped",
                    skipped
            );

            callbackContext.success(result);

        } catch (Exception e) {
            callbackContext.error(
                    message(
                            "SET_EMPLOYEES_FAILED",
                            e
                    )
            );
        }
    }

    private void handleFindBestMatchBase64(
            String imageBase64,
            double threshold,
            double minGap,
            CallbackContext callbackContext
    ) {
        Bitmap bitmap = null;

        try {
            bitmap =
                    decodeBase64BitmapOriented(
                            imageBase64
                    );

            JSONObject result =
                    matchBitmap(
                            bitmap,
                            threshold,
                            minGap
                    );

            callbackContext.success(result);

        } catch (Exception e) {
            callbackContext.error(
                    message(
                            "MATCH_FAILED",
                            e
                    )
            );

        } finally {
            recycle(bitmap);
        }
    }

    private void requestCaptureAndMatch(
            double threshold,
            double minGap,
            CallbackContext callbackContext
    ) {
        synchronized (captureLock) {

            if (pendingCaptureCallback != null ||
                    cameraOverlay != null) {

                callbackContext.error(
                        "CAPTURE_ALREADY_RUNNING"
                );

                return;
            }

            if (employeeTemplates == null ||
                    employeeTemplates.isEmpty()) {

                callbackContext.error(
                        "NO_EMPLOYEES_LOADED: " +
                        "Call FaceID_SetEmployees before capture."
                );

                return;
            }

            pendingCaptureCallback =
                    callbackContext;

            pendingCaptureThreshold =
                    threshold;

            pendingCaptureMinGap =
                    minGap;
        }

        if (cordova.hasPermission(
                Manifest.permission.CAMERA
        )) {
            openCameraOverlay();

        } else {
            cordova.requestPermission(
                    this,
                    CAMERA_PERMISSION_REQUEST,
                    Manifest.permission.CAMERA
            );
        }
    }

    private void openCameraOverlay() {
        final CallbackContext callback;

        synchronized (captureLock) {
            callback =
                    pendingCaptureCallback;
        }

        if (callback == null) {
            return;
        }

        cordova.getActivity()
                .runOnUiThread(() -> {

                    try {
                        cameraOverlay =
                                new FaceCameraOverlay(
                                        cordova.getActivity(),
                                        new FaceCameraOverlay.Listener() {

                                            @Override
                                            public void onCaptured(
                                                    File file
                                            ) {
                                                cameraOverlay = null;

                                                processCapturedFile(
                                                        file
                                                );
                                            }

                                            @Override
                                            public void onCancelled() {
                                                cameraOverlay = null;

                                                finishCaptureCancelled();
                                            }

                                            @Override
                                            public void onError(
                                                    String error
                                            ) {
                                                cameraOverlay = null;

                                                finishCaptureError(
                                                        error
                                                );
                                            }
                                        }
                                );

                        cameraOverlay.open();

                    } catch (Exception e) {
                        cameraOverlay = null;

                        finishCaptureError(
                                "CAMERA_OPEN_FAILED: " +
                                safeMessage(e)
                        );
                    }
                });
    }

    private void processCapturedFile(
            File file
    ) {
        final CallbackContext callback;
        final double threshold;
        final double minGap;

        synchronized (captureLock) {
            callback =
                    pendingCaptureCallback;

            threshold =
                    pendingCaptureThreshold;

            minGap =
                    pendingCaptureMinGap;
        }

        if (callback == null) {
            safeDelete(file);
            clearPendingCapture();
            return;
        }

        cordova.getThreadPool().execute(() -> {
            Bitmap bitmap = null;

            try {
                bitmap =
                        decodeFileBitmapOriented(
                                file
                        );

                JSONObject result =
                        matchBitmap(
                                bitmap,
                                threshold,
                                minGap
                        );

                callback.success(result);

            } catch (Exception e) {
                callback.error(
                        message(
                                "CAPTURE_MATCH_FAILED",
                                e
                        )
                );

            } finally {
                recycle(bitmap);
                safeDelete(file);
                clearPendingCapture();
            }
        });
    }

    private void finishCaptureCancelled() {
        CallbackContext callback;

        synchronized (captureLock) {
            callback =
                    pendingCaptureCallback;
        }

        if (callback != null) {
            try {
                JSONObject result =
                        new JSONObject();

                result.put("success", true);
                result.put("found", false);
                result.put("employeeId", "");
                result.put("employeeName", "");
                result.put("similarity", -1);
                result.put(
                        "secondSimilarity",
                        -1
                );
                result.put(
                        "reason",
                        "CANCELLED"
                );

                callback.success(result);

            } catch (JSONException e) {
                callback.error(
                        safeMessage(e)
                );
            }
        }

        clearPendingCapture();
    }

    private void finishCaptureError(
            String error
    ) {
        CallbackContext callback;

        synchronized (captureLock) {
            callback =
                    pendingCaptureCallback;
        }

        if (callback != null) {
            callback.error(
                    error == null
                            ? "CAMERA_ERROR"
                            : error
            );
        }

        clearPendingCapture();
    }

    private JSONObject matchBitmap(
            Bitmap bitmap,
            double threshold,
            double minGap
    ) throws Exception {

        ensureEngine();

        List<EmployeeTemplate> templates =
                employeeTemplates;

        if (templates == null ||
                templates.isEmpty()) {

            throw new IllegalStateException(
                    "NO_EMPLOYEES_LOADED: " +
                    "Call FaceID_SetEmployees first."
            );
        }

        Bitmap faceCrop =
                detectAndCropSingleFace(bitmap);

        try {
            // Test-time augmentation: average the embedding of the aligned
            // face and its horizontal flip, then L2-normalize. Registration
            // and recognition use exactly the same representation.
            float[] current =
                    embeddingWithFlipTta(faceCrop);

            EmployeeTemplate best = null;

            double bestSimilarity = -1.0;
            double secondSimilarity = -1.0;

            for (EmployeeTemplate template :
                    templates) {

                double similarity =
                        MobileFaceNetEngine
                                .cosineSimilarity(
                                        current,
                                        template.descriptor
                                );

                if (similarity >
                        bestSimilarity) {

                    secondSimilarity =
                            bestSimilarity;

                    bestSimilarity =
                            similarity;

                    best =
                            template;

                } else if (similarity >
                        secondSimilarity) {

                    secondSimilarity =
                            similarity;
                }
            }

            boolean aboveThreshold =
                    best != null &&
                    bestSimilarity >= threshold;

            boolean unambiguous =
                    secondSimilarity < 0.0 ||
                    (
                            bestSimilarity -
                            secondSimilarity
                    ) >= minGap;

            boolean found =
                    aboveThreshold &&
                    unambiguous;

            JSONObject result =
                    new JSONObject();

            result.put("success", true);
            result.put("found", found);
            result.put(
                    "similarity",
                    bestSimilarity
            );
            result.put(
                    "secondSimilarity",
                    secondSimilarity
            );
            result.put(
                    "threshold",
                    threshold
            );
            result.put(
                    "minGap",
                    minGap
            );
            result.put(
                    "preprocess",
                    "LANDMARK_ALIGN_5PT_TTA_160_V3"
            );
            result.put("model", "FACENET_128D_SLIM");
            result.put("mirrorUsed", true);

            if (found && best != null) {
                result.put(
                        "employeeId",
                        best.employeeId
                );
                result.put(
                        "employeeName",
                        best.name
                );
                result.put(
                        "reason",
                        "MATCH"
                );

            } else {
                result.put(
                        "employeeId",
                        ""
                );
                result.put(
                        "employeeName",
                        ""
                );

                result.put(
                        "reason",
                        !aboveThreshold
                                ? "BELOW_THRESHOLD"
                                : "AMBIGUOUS"
                );
            }

            return result;

        } finally {
            recycle(faceCrop);
        }
    }

    private Bitmap detectAndCropSingleFace(
            Bitmap bitmap
    ) throws Exception {

        if (bitmap == null) {
            throw new IllegalArgumentException(
                    "Image is empty."
            );
        }

        ensureFaceDetector();

        InputImage input =
                InputImage.fromBitmap(
                        bitmap,
                        0
                );

        List<Face> faces =
                Tasks.await(
                        faceDetector.process(
                                input
                        )
                );

        if (faces == null ||
                faces.isEmpty()) {

            throw new IllegalStateException(
                    "NO_FACE: No face detected."
            );
        }

        if (faces.size() != 1) {
            throw new IllegalStateException(
                    "MULTIPLE_FACES: " +
                    "Exactly one face is required."
            );
        }

        Face face =
                faces.get(0);

        Rect box =
                face.getBoundingBox();

        double widthFraction =
                box.width() /
                (double) bitmap.getWidth();

        if (widthFraction <
                MIN_FACE_WIDTH_FRACTION) {

            throw new IllegalStateException(
                    "FACE_TOO_SMALL: " +
                    "Move closer to the camera."
            );
        }

        Bitmap aligned =
                alignFaceFromLandmarks(
                        bitmap,
                        face
                );

        if (aligned != null) {
            return aligned;
        }

        // Fallback for rare images where ML Kit cannot provide the
        // landmarks. This keeps descriptor generation functional.
        return cropSquareWithMargin(
                bitmap,
                box,
                0.25f
        );
    }

    private void ensureFaceDetector() {
        if (faceDetector != null) {
            return;
        }

        synchronized (detectorLock) {
            if (faceDetector == null) {
                FaceDetectorOptions options =
                        new FaceDetectorOptions.Builder()
                                .setPerformanceMode(
                                        FaceDetectorOptions
                                                .PERFORMANCE_MODE_ACCURATE
                                )
                                .setLandmarkMode(
                                        FaceDetectorOptions
                                                .LANDMARK_MODE_ALL
                                )
                                .setClassificationMode(
                                        FaceDetectorOptions
                                                .CLASSIFICATION_MODE_NONE
                                )
                                .setMinFaceSize(
                                        (float)
                                        MIN_FACE_WIDTH_FRACTION
                                )
                                .build();

                faceDetector =
                        FaceDetection.getClient(
                                options
                        );
            }
        }
    }

    private void ensureEngine()
            throws Exception {

        if (engine != null) {
            return;
        }

        synchronized (engineLock) {
            if (engine == null) {
                engine =
                        new MobileFaceNetEngine(
                                cordova.getActivity()
                                        .getApplicationContext()
                        );
            }
        }
    }

    @Override
    public void onRequestPermissionResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) throws JSONException {

        if (requestCode !=
                CAMERA_PERMISSION_REQUEST) {

            return;
        }

        boolean granted =
                grantResults != null &&
                grantResults.length > 0;

        if (granted) {
            for (int result :
                    grantResults) {

                if (result ==
                        PackageManager
                                .PERMISSION_DENIED) {

                    granted = false;
                    break;
                }
            }
        }

        if (!granted) {
            finishCaptureError(
                    "CAMERA_PERMISSION_DENIED"
            );

            return;
        }

        openCameraOverlay();
    }

    private static Bitmap decodeBase64BitmapOriented(
            String imageBase64
    ) throws Exception {

        if (imageBase64 == null ||
                imageBase64.trim().isEmpty()) {

            throw new IllegalArgumentException(
                    "ImageBase64 is empty."
            );
        }

        String value =
                imageBase64.trim();

        int comma =
                value.indexOf(',');

        if (value.startsWith("data:") &&
                comma >= 0) {

            value =
                    value.substring(
                            comma + 1
                    );
        }

        byte[] bytes =
                Base64.decode(
                        value,
                        Base64.DEFAULT
                );

        int orientation =
                ExifInterface
                        .ORIENTATION_NORMAL;

        try (ByteArrayInputStream stream =
                     new ByteArrayInputStream(
                             bytes
                     )) {

            ExifInterface exif =
                    new ExifInterface(
                            stream
                    );

            orientation =
                    exif.getAttributeInt(
                            ExifInterface
                                    .TAG_ORIENTATION,
                            ExifInterface
                                    .ORIENTATION_NORMAL
                    );

        } catch (Exception ignored) {
        }

        Bitmap bitmap =
                decodeByteArraySampled(
                        bytes
                );

        return applyExifOrientation(
                bitmap,
                orientation
        );
    }

    private static Bitmap decodeFileBitmapOriented(
            File file
    ) throws Exception {

        if (file == null ||
                !file.exists()) {

            throw new IllegalArgumentException(
                    "Captured file does not exist."
            );
        }

        BitmapFactory.Options bounds =
                new BitmapFactory.Options();

        bounds.inJustDecodeBounds =
                true;

        BitmapFactory.decodeFile(
                file.getAbsolutePath(),
                bounds
        );

        BitmapFactory.Options options =
                new BitmapFactory.Options();

        options.inSampleSize =
                calculateInSampleSize(
                        bounds.outWidth,
                        bounds.outHeight
                );

        Bitmap bitmap =
                BitmapFactory.decodeFile(
                        file.getAbsolutePath(),
                        options
                );

        if (bitmap == null) {
            throw new IllegalArgumentException(
                    "Could not decode captured image."
            );
        }

        int orientation =
                ExifInterface
                        .ORIENTATION_NORMAL;

        try {
            ExifInterface exif =
                    new ExifInterface(
                            file.getAbsolutePath()
                    );

            orientation =
                    exif.getAttributeInt(
                            ExifInterface
                                    .TAG_ORIENTATION,
                            ExifInterface
                                    .ORIENTATION_NORMAL
                    );

        } catch (Exception ignored) {
        }

        return applyExifOrientation(
                bitmap,
                orientation
        );
    }

    private static Bitmap decodeByteArraySampled(
            byte[] bytes
    ) {
        BitmapFactory.Options bounds =
                new BitmapFactory.Options();

        bounds.inJustDecodeBounds =
                true;

        BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.length,
                bounds
        );

        BitmapFactory.Options options =
                new BitmapFactory.Options();

        options.inSampleSize =
                calculateInSampleSize(
                        bounds.outWidth,
                        bounds.outHeight
                );

        Bitmap bitmap =
                BitmapFactory.decodeByteArray(
                        bytes,
                        0,
                        bytes.length,
                        options
                );

        if (bitmap == null) {
            throw new IllegalArgumentException(
                    "Could not decode image."
            );
        }

        return bitmap;
    }

    private static int calculateInSampleSize(
            int width,
            int height
    ) {
        int sample = 1;

        while (
                width / sample >
                        MAX_DECODE_DIMENSION ||
                height / sample >
                        MAX_DECODE_DIMENSION
        ) {
            sample *= 2;
        }

        return Math.max(
                sample,
                1
        );
    }

    private static Bitmap applyExifOrientation(
            Bitmap bitmap,
            int orientation
    ) {
        if (bitmap == null) {
            return null;
        }

        Matrix matrix =
                new Matrix();

        switch (orientation) {

            case ExifInterface
                    .ORIENTATION_FLIP_HORIZONTAL:
                matrix.setScale(
                        -1,
                        1
                );
                break;

            case ExifInterface
                    .ORIENTATION_ROTATE_180:
                matrix.setRotate(
                        180
                );
                break;

            case ExifInterface
                    .ORIENTATION_FLIP_VERTICAL:
                matrix.setRotate(
                        180
                );
                matrix.postScale(
                        -1,
                        1
                );
                break;

            case ExifInterface
                    .ORIENTATION_TRANSPOSE:
                matrix.setRotate(
                        90
                );
                matrix.postScale(
                        -1,
                        1
                );
                break;

            case ExifInterface
                    .ORIENTATION_ROTATE_90:
                matrix.setRotate(
                        90
                );
                break;

            case ExifInterface
                    .ORIENTATION_TRANSVERSE:
                matrix.setRotate(
                        -90
                );
                matrix.postScale(
                        -1,
                        1
                );
                break;

            case ExifInterface
                    .ORIENTATION_ROTATE_270:
                matrix.setRotate(
                        -90
                );
                break;

            default:
                return bitmap;
        }

        Bitmap rotated =
                Bitmap.createBitmap(
                        bitmap,
                        0,
                        0,
                        bitmap.getWidth(),
                        bitmap.getHeight(),
                        matrix,
                        true
                );

        if (rotated != bitmap) {
            recycle(bitmap);
        }

        return rotated;
    }

    /**
     * Aligns the face to the canonical 160x160 geometry used by common
     * MobileFaceNet/ArcFace pipelines. Three landmarks are enough for an
     * affine transformation: image-left eye, image-right eye and nose base.
     *
     * Sorting the eyes by X makes the transform stable regardless of ML Kit's
     * anatomical left/right naming. The match path also tests a mirrored copy
     * to tolerate front-camera OEM mirroring differences.
     */
    /**
     * Five-point ArcFace-style similarity alignment.
     *
     * Uses both eyes, nose base and both mouth corners. A least-squares
     * similarity transform (scale + rotation + translation, no perspective)
     * maps the detected landmarks to the canonical 160x160 face template.
     *
     * Falls back to the previous 3/2 point transform if mouth landmarks are
     * unavailable.
     */
    private static Bitmap alignFaceFromLandmarks(
            Bitmap bitmap,
            Face face
    ) {
        FaceLandmark leftEyeLandmark =
                face.getLandmark(FaceLandmark.LEFT_EYE);
        FaceLandmark rightEyeLandmark =
                face.getLandmark(FaceLandmark.RIGHT_EYE);

        if (leftEyeLandmark == null ||
                rightEyeLandmark == null) {
            return null;
        }

        PointF eyeA = leftEyeLandmark.getPosition();
        PointF eyeB = rightEyeLandmark.getPosition();

        PointF imageLeftEye;
        PointF imageRightEye;

        if (eyeA.x <= eyeB.x) {
            imageLeftEye = eyeA;
            imageRightEye = eyeB;
        } else {
            imageLeftEye = eyeB;
            imageRightEye = eyeA;
        }

        if (distance(imageLeftEye, imageRightEye) < 8.0f) {
            return null;
        }

        FaceLandmark noseLandmark =
                face.getLandmark(FaceLandmark.NOSE_BASE);
        FaceLandmark mouthALandmark =
                face.getLandmark(FaceLandmark.MOUTH_LEFT);
        FaceLandmark mouthBLandmark =
                face.getLandmark(FaceLandmark.MOUTH_RIGHT);

        Matrix transform = null;

        if (noseLandmark != null &&
                mouthALandmark != null &&
                mouthBLandmark != null) {

            PointF mouthA = mouthALandmark.getPosition();
            PointF mouthB = mouthBLandmark.getPosition();

            PointF imageLeftMouth;
            PointF imageRightMouth;

            if (mouthA.x <= mouthB.x) {
                imageLeftMouth = mouthA;
                imageRightMouth = mouthB;
            } else {
                imageLeftMouth = mouthB;
                imageRightMouth = mouthA;
            }

            PointF[] src = new PointF[] {
                    imageLeftEye,
                    imageRightEye,
                    noseLandmark.getPosition(),
                    imageLeftMouth,
                    imageRightMouth
            };

            PointF[] dst = new PointF[] {
                    new PointF(54.7066f, 73.8519f),
                    new PointF(105.0454f, 73.5734f),
                    new PointF(80.0360f, 102.4809f),
                    new PointF(59.3561f, 131.9507f),
                    new PointF(101.0427f, 131.7201f)
            };

            transform =
                    estimateSimilarityTransform(
                            src,
                            dst
                    );
        }

        if (transform == null) {
            // Fallback: 3-point eye/eye/nose transform.
            FaceLandmark nose =
                    face.getLandmark(FaceLandmark.NOSE_BASE);

            if (nose != null) {
                float[] src = new float[] {
                        imageLeftEye.x,
                        imageLeftEye.y,
                        imageRightEye.x,
                        imageRightEye.y,
                        nose.getPosition().x,
                        nose.getPosition().y
                };

                float[] dst = new float[] {
                        54.7066f, 73.8519f,
                        105.0454f, 73.5734f,
                        80.0360f, 102.4809f
                };

                Matrix fallback = new Matrix();

                if (fallback.setPolyToPoly(
                        src, 0, dst, 0, 3)) {
                    transform = fallback;
                }
            }
        }

        if (transform == null) {
            // Last fallback: eyes only.
            float[] src = new float[] {
                    imageLeftEye.x,
                    imageLeftEye.y,
                    imageRightEye.x,
                    imageRightEye.y
            };

            float[] dst = new float[] {
                    54.7066f, 73.8519f,
                    105.0454f, 73.5734f
            };

            Matrix fallback = new Matrix();

            if (fallback.setPolyToPoly(
                    src, 0, dst, 0, 2)) {
                transform = fallback;
            }
        }

        if (transform == null) {
            return null;
        }

        Bitmap aligned =
                Bitmap.createBitmap(
                        MobileFaceNetEngine.INPUT_SIZE,
                        MobileFaceNetEngine.INPUT_SIZE,
                        Bitmap.Config.ARGB_8888
                );

        Canvas canvas = new Canvas(aligned);
        canvas.drawColor(Color.BLACK);

        Paint paint =
                new Paint(
                        Paint.ANTI_ALIAS_FLAG |
                                Paint.FILTER_BITMAP_FLAG |
                                Paint.DITHER_FLAG
                );

        canvas.drawBitmap(
                bitmap,
                transform,
                paint
        );

        return aligned;
    }

    /**
     * Least-squares 2D similarity transform:
     *
     * x' = a*x - b*y + tx
     * y' = b*x + a*y + ty
     *
     * This is stable with 5 landmarks and avoids perspective distortion.
     */
    private static Matrix estimateSimilarityTransform(
            PointF[] src,
            PointF[] dst
    ) {
        if (src == null ||
                dst == null ||
                src.length != dst.length ||
                src.length < 2) {
            return null;
        }

        double srcMeanX = 0.0;
        double srcMeanY = 0.0;
        double dstMeanX = 0.0;
        double dstMeanY = 0.0;

        for (int i = 0; i < src.length; i++) {
            srcMeanX += src[i].x;
            srcMeanY += src[i].y;
            dstMeanX += dst[i].x;
            dstMeanY += dst[i].y;
        }

        srcMeanX /= src.length;
        srcMeanY /= src.length;
        dstMeanX /= dst.length;
        dstMeanY /= dst.length;

        double denom = 0.0;
        double numA = 0.0;
        double numB = 0.0;

        for (int i = 0; i < src.length; i++) {
            double sx = src[i].x - srcMeanX;
            double sy = src[i].y - srcMeanY;
            double dx = dst[i].x - dstMeanX;
            double dy = dst[i].y - dstMeanY;

            denom += sx * sx + sy * sy;
            numA += sx * dx + sy * dy;
            numB += sx * dy - sy * dx;
        }

        if (denom < 1e-9) {
            return null;
        }

        double a = numA / denom;
        double b = numB / denom;

        double tx =
                dstMeanX -
                        a * srcMeanX +
                        b * srcMeanY;

        double ty =
                dstMeanY -
                        b * srcMeanX -
                        a * srcMeanY;

        float[] values = new float[] {
                (float) a,
                (float) -b,
                (float) tx,

                (float) b,
                (float) a,
                (float) ty,

                0.0f,
                0.0f,
                1.0f
        };

        Matrix matrix = new Matrix();
        matrix.setValues(values);

        return matrix;
    }

    /**
     * Flip test-time augmentation used on BOTH enrollment and recognition.
     * Average normal + mirrored embeddings, then normalize the centroid.
     */
    private float[] embeddingWithFlipTta(
            Bitmap alignedFace
    ) {
        Bitmap mirrored = null;

        try {
            float[] normal =
                    engine.embedding(alignedFace);

            mirrored =
                    mirrorHorizontal(alignedFace);

            float[] flipped =
                    engine.embedding(mirrored);

            float[] averaged =
                    new float[
                            MobileFaceNetEngine
                                    .EMBEDDING_SIZE
                            ];

            double sumSquares = 0.0;

            for (int i = 0;
                 i < averaged.length;
                 i++) {

                averaged[i] =
                        (normal[i] + flipped[i]) *
                                0.5f;

                sumSquares +=
                        averaged[i] *
                                averaged[i];
            }

            double norm =
                    Math.sqrt(
                            Math.max(
                                    sumSquares,
                                    1e-12
                            )
                    );

            for (int i = 0;
                 i < averaged.length;
                 i++) {

                averaged[i] =
                        (float)
                                (
                                        averaged[i] /
                                        norm
                                );
            }

            return averaged;

        } finally {
            recycle(mirrored);
        }
    }

    private static float distance(
            PointF a,
            PointF b
    ) {
        float dx =
                a.x - b.x;

        float dy =
                a.y - b.y;

        return (float)
                Math.sqrt(
                        dx * dx +
                                dy * dy
                );
    }

    private static Bitmap mirrorHorizontal(
            Bitmap bitmap
    ) {
        if (bitmap == null) {
            return null;
        }

        Matrix matrix =
                new Matrix();

        matrix.setScale(
                -1.0f,
                1.0f,
                bitmap.getWidth() / 2.0f,
                bitmap.getHeight() / 2.0f
        );

        return Bitmap.createBitmap(
                bitmap,
                0,
                0,
                bitmap.getWidth(),
                bitmap.getHeight(),
                matrix,
                true
        );
    }

    private static Bitmap cropSquareWithMargin(
            Bitmap bitmap,
            Rect box,
            float marginFraction
    ) {
        float centerX =
                box.exactCenterX();

        float centerY =
                box.exactCenterY();

        float side =
                Math.max(
                        box.width(),
                        box.height()
                );

        side *=
                (
                        1.0f +
                        2.0f *
                        marginFraction
                );

        int left =
                Math.max(
                        0,
                        Math.round(
                                centerX -
                                side / 2.0f
                        )
                );

        int top =
                Math.max(
                        0,
                        Math.round(
                                centerY -
                                side / 2.0f
                        )
                );

        int right =
                Math.min(
                        bitmap.getWidth(),
                        Math.round(
                                centerX +
                                side / 2.0f
                        )
                );

        int bottom =
                Math.min(
                        bitmap.getHeight(),
                        Math.round(
                                centerY +
                                side / 2.0f
                        )
                );

        int width =
                right - left;

        int height =
                bottom - top;

        if (width <= 0 ||
                height <= 0) {

            throw new IllegalStateException(
                    "Invalid face crop."
            );
        }

        int square =
                Math.min(
                        width,
                        height
                );

        int squareLeft =
                left +
                (
                        width -
                        square
                ) / 2;

        int squareTop =
                top +
                (
                        height -
                        square
                ) / 2;

        return Bitmap.createBitmap(
                bitmap,
                squareLeft,
                squareTop,
                square,
                square
        );
    }

    private static JSONArray descriptorToJson(
            float[] descriptor
    ) throws JSONException {

        JSONArray array =
                new JSONArray();

        for (float value :
                descriptor) {

            array.put(
                    (double) value
            );
        }

        return array;
    }

    private static float[] parseDescriptor(
            Object raw
    ) {
        if (raw == null ||
                raw == JSONObject.NULL) {

            return null;
        }

        try {
            JSONArray values;

            if (raw instanceof JSONArray) {
                values =
                        (JSONArray) raw;

            } else {
                String text =
                        String.valueOf(raw)
                                .trim();

                if (text.isEmpty()) {
                    return null;
                }

                values =
                        new JSONArray(
                                text
                        );
            }

            if (values.length() !=
                    MobileFaceNetEngine
                            .EMBEDDING_SIZE) {

                return null;
            }

            float[] descriptor =
                    new float[
                            MobileFaceNetEngine
                                    .EMBEDDING_SIZE
                            ];

            double sum =
                    0.0;

            for (int i = 0;
                 i < descriptor.length;
                 i++) {

                float value =
                        (float)
                        values.getDouble(i);

                descriptor[i] =
                        value;

                sum +=
                        value *
                        value;
            }

            double norm =
                    Math.sqrt(
                            Math.max(
                                    sum,
                                    1e-12
                            )
                    );

            for (int i = 0;
                 i < descriptor.length;
                 i++) {

                descriptor[i] =
                        (float)
                        (
                                descriptor[i] /
                                norm
                        );
            }

            return descriptor;

        } catch (Exception ignored) {
            return null;
        }
    }

    private static String firstNonEmpty(
            String... values
    ) {
        if (values == null) {
            return "";
        }

        for (String value :
                values) {

            if (value != null &&
                    !value.trim().isEmpty()) {

                return value.trim();
            }
        }

        return "";
    }

    private static double normalizeThreshold(
            double value
    ) {
        if (Double.isNaN(value) ||
                value <= 0.0 ||
                value > 1.0) {

            return DEFAULT_THRESHOLD;
        }

        return value;
    }

    private static double normalizeMinGap(
            double value
    ) {
        if (Double.isNaN(value) ||
                value < 0.0 ||
                value > 1.0) {

            return DEFAULT_MIN_GAP;
        }

        return value;
    }

    private void clearPendingCapture() {
        synchronized (captureLock) {
            pendingCaptureCallback =
                    null;

            pendingCaptureThreshold =
                    DEFAULT_THRESHOLD;

            pendingCaptureMinGap =
                    DEFAULT_MIN_GAP;
        }
    }

    private static String message(
            String code,
            Exception e
    ) {
        return code +
                ": " +
                safeMessage(e);
    }

    private static String safeMessage(
            Throwable throwable
    ) {
        if (throwable == null ||
                throwable.getMessage() == null) {

            return "Unknown error.";
        }

        return throwable.getMessage();
    }

    private static void recycle(
            Bitmap bitmap
    ) {
        if (bitmap != null &&
                !bitmap.isRecycled()) {

            bitmap.recycle();
        }
    }

    private static void safeDelete(
            File file
    ) {
        try {
            if (file != null &&
                    file.exists()) {

                file.delete();
            }
        } catch (Exception ignored) {
        }
    }

    private void disposeNative() {
        employeeTemplates =
                Collections.emptyList();

        if (cameraOverlay != null) {
            try {
                cameraOverlay.dismissSilently();
            } catch (Exception ignored) {
            }

            cameraOverlay =
                    null;
        }

        clearPendingCapture();

        synchronized (engineLock) {
            if (engine != null) {
                try {
                    engine.close();
                } catch (Exception ignored) {
                }

                engine =
                        null;
            }
        }

        synchronized (detectorLock) {
            if (faceDetector != null) {
                try {
                    faceDetector.close();
                } catch (Exception ignored) {
                }

                faceDetector =
                        null;
            }
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
                float[] descriptor
        ) {
            this.employeeId =
                    employeeId;

            this.name =
                    name;

            this.descriptor =
                    descriptor;
        }
    }
}
