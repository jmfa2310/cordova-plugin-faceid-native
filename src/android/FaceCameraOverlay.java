package com.company.faceidnative;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.Image;
import android.os.Handler;
import android.os.Looper;
import android.util.Size;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.google.android.gms.tasks.Tasks;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.io.File;
import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Native CameraX overlay with basic active liveness for attendance/access use.
 *
 * The liveness gate is intentionally performed before ImageCapture. The user
 * must complete BOTH actions (blink + turn-and-return) in a random order.
 * This blocks the simple static-photo presentation attack that motivated
 * v1.1.3. It is not a certified presentation-attack-detection (PAD) system and
 * must not be represented as protection against all replay/deepfake attacks.
 */
public final class FaceCameraOverlay implements LifecycleOwner {

    public interface Listener {
        void onCaptured(File file, String livenessSequence);
        void onCancelled();
        void onError(String error);
    }

    private enum LivenessAction {
        BLINK,
        TURN
    }

    private static final long LIVENESS_TIMEOUT_MS = 18000L;
    private static final long FACE_LOST_RESET_MS = 1200L;

    private static final float EYE_OPEN_THRESHOLD = 0.65f;
    private static final float EYE_CLOSED_THRESHOLD = 0.42f;
    private static final float HEAD_CENTER_THRESHOLD = 10.0f;
    private static final float HEAD_TURN_THRESHOLD = 19.0f;

    private final Activity activity;
    private final Listener listener;
    private final LifecycleRegistry lifecycleRegistry;
    private final Executor mainExecutor;
    private final ExecutorService analysisExecutor;
    private final Handler mainHandler;
    private final SecureRandom secureRandom = new SecureRandom();
    private final AtomicBoolean frameBusy = new AtomicBoolean(false);

    private FrameLayout overlay;
    private PreviewView previewView;
    private TextView instructionView;
    private TextView statusView;
    private ProcessCameraProvider cameraProvider;
    private ImageCapture imageCapture;
    private ImageAnalysis imageAnalysis;
    private FaceDetector livenessDetector;

    private boolean closed = false;
    private boolean livenessPassed = false;
    private boolean captureTriggered = false;
    private boolean challengeStarted = false;

    private LivenessAction[] sequence;
    private int actionIndex = 0;

    // Blink state: 0=open baseline, 1=wait closed, 2=wait reopened.
    private int blinkStage = 0;
    private int blinkOpenFrames = 0;
    private int blinkReopenFrames = 0;

    // Turn state: 0=center baseline, 1=wait turn, 2=wait return.
    private int turnStage = 0;
    private int turnStableFrames = 0;

    private long lastSingleFaceAt = 0L;
    private String livenessSequence = "";

    private final Runnable timeoutRunnable = () -> {
        if (!closed && !livenessPassed) {
            fail("LIVENESS_FAILED: TIMEOUT");
        }
    };

    public FaceCameraOverlay(
            Activity activity,
            Listener listener
    ) {
        this.activity = activity;
        this.listener = listener;
        this.lifecycleRegistry = new LifecycleRegistry(this);
        this.mainExecutor = ContextCompat.getMainExecutor(activity);
        this.analysisExecutor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return lifecycleRegistry;
    }

    public void open() {
        activity.runOnUiThread(() -> {
            if (closed) {
                return;
            }

            try {
                buildUi();
                configureLiveness();

                lifecycleRegistry.setCurrentState(Lifecycle.State.CREATED);
                lifecycleRegistry.setCurrentState(Lifecycle.State.STARTED);
                lifecycleRegistry.setCurrentState(Lifecycle.State.RESUMED);

                startCamera();

            } catch (Exception e) {
                fail("CAMERA_OPEN_FAILED: " + safeMessage(e));
            }
        });
    }

    private void buildUi() {
        FrameLayout content =
                activity.findViewById(android.R.id.content);

        overlay = new FrameLayout(activity);
        overlay.setBackgroundColor(Color.BLACK);
        overlay.setClickable(true);
        overlay.setFocusable(true);

        previewView = new PreviewView(activity);
        previewView.setImplementationMode(
                PreviewView.ImplementationMode.COMPATIBLE
        );
        previewView.setScaleType(
                PreviewView.ScaleType.FILL_CENTER
        );

        FrameLayout.LayoutParams previewParams =
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                );

        overlay.addView(previewView, previewParams);

        instructionView = new TextView(activity);
        instructionView.setText("A iniciar validação de presença…");
        instructionView.setTextColor(Color.WHITE);
        instructionView.setTextSize(20);
        instructionView.setTypeface(Typeface.DEFAULT_BOLD);
        instructionView.setGravity(Gravity.CENTER);
        instructionView.setBackgroundColor(0x88000000);
        instructionView.setPadding(
                dp(18),
                dp(14),
                dp(18),
                dp(14)
        );

        FrameLayout.LayoutParams instructionParams =
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP
                );

        instructionParams.leftMargin = dp(16);
        instructionParams.rightMargin = dp(16);
        instructionParams.topMargin = dp(76);
        overlay.addView(instructionView, instructionParams);

        statusView = new TextView(activity);
        statusView.setText("Mostre apenas o seu rosto e olhe de frente");
        statusView.setTextColor(Color.WHITE);
        statusView.setTextSize(16);
        statusView.setGravity(Gravity.CENTER);
        statusView.setBackgroundColor(0x88000000);
        statusView.setPadding(
                dp(16),
                dp(12),
                dp(16),
                dp(12)
        );

        FrameLayout.LayoutParams statusParams =
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM
                );

        statusParams.leftMargin = dp(16);
        statusParams.rightMargin = dp(16);
        statusParams.bottomMargin = dp(28);
        overlay.addView(statusView, statusParams);

        Button cancelButton = new Button(activity);
        cancelButton.setText("Cancelar");
        cancelButton.setAllCaps(false);

        FrameLayout.LayoutParams cancelParams =
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        dp(52),
                        Gravity.TOP | Gravity.START
                );

        cancelParams.leftMargin = dp(16);
        cancelParams.topMargin = dp(18);

        overlay.addView(cancelButton, cancelParams);
        cancelButton.setOnClickListener(v -> cancel());

        content.addView(
                overlay,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                )
        );
    }

    private void configureLiveness() {
        // Every attempt requires both actions. Only their order is random.
        // Requiring a blink means a single static photograph cannot pass.
        if (secureRandom.nextBoolean()) {
            sequence = new LivenessAction[]{
                    LivenessAction.BLINK,
                    LivenessAction.TURN
            };
        } else {
            sequence = new LivenessAction[]{
                    LivenessAction.TURN,
                    LivenessAction.BLINK
            };
        }

        livenessSequence =
                actionName(sequence[0]) + ">" + actionName(sequence[1]);

        actionIndex = 0;
        resetCurrentActionState();
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(activity);

        future.addListener(() -> {
            if (closed) {
                return;
            }

            try {
                cameraProvider = future.get();

                Preview preview =
                        new Preview.Builder()
                                .setTargetResolution(
                                        new Size(640, 480)
                                )
                                .build();

                ImageCapture.Builder imageCaptureBuilder =
                        new ImageCapture.Builder()
                                .setCaptureMode(
                                        ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                                )
                                .setJpegQuality(75)
                                .setTargetResolution(
                                        new Size(640, 640)
                                );

                if (previewView.getDisplay() != null) {
                    imageCaptureBuilder.setTargetRotation(
                            previewView.getDisplay().getRotation()
                    );
                }

                imageCapture = imageCaptureBuilder.build();

                imageAnalysis =
                        new ImageAnalysis.Builder()
                                .setTargetResolution(
                                        new Size(640, 480)
                                )
                                .setBackpressureStrategy(
                                        ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                                )
                                .build();

                FaceDetectorOptions detectorOptions =
                        new FaceDetectorOptions.Builder()
                                .setPerformanceMode(
                                        FaceDetectorOptions.PERFORMANCE_MODE_FAST
                                )
                                .setClassificationMode(
                                        FaceDetectorOptions.CLASSIFICATION_MODE_ALL
                                )
                                .setLandmarkMode(
                                        FaceDetectorOptions.LANDMARK_MODE_NONE
                                )
                                .enableTracking()
                                .setMinFaceSize(0.18f)
                                .build();

                livenessDetector =
                        FaceDetection.getClient(detectorOptions);

                imageAnalysis.setAnalyzer(
                        analysisExecutor,
                        this::analyzeFrame
                );

                CameraSelector selector =
                        CameraSelector.DEFAULT_FRONT_CAMERA;

                if (!cameraProvider.hasCamera(selector)) {
                    selector = CameraSelector.DEFAULT_BACK_CAMERA;
                }

                cameraProvider.unbindAll();

                preview.setSurfaceProvider(
                        previewView.getSurfaceProvider()
                );

                cameraProvider.bindToLifecycle(
                        this,
                        selector,
                        preview,
                        imageAnalysis,
                        imageCapture
                );

                challengeStarted = true;
                updateInstructionForCurrentAction();
                mainHandler.postDelayed(
                        timeoutRunnable,
                        LIVENESS_TIMEOUT_MS
                );

            } catch (Exception e) {
                fail("CAMERA_BIND_FAILED: " + safeMessage(e));
            }
        }, mainExecutor);
    }

    @ExperimentalGetImage
    private void analyzeFrame(ImageProxy imageProxy) {
        if (closed || livenessPassed || !challengeStarted) {
            imageProxy.close();
            return;
        }

        if (!frameBusy.compareAndSet(false, true)) {
            imageProxy.close();
            return;
        }

        try {
            Image mediaImage = imageProxy.getImage();

            if (mediaImage == null || livenessDetector == null) {
                return;
            }

            InputImage inputImage =
                    InputImage.fromMediaImage(
                            mediaImage,
                            imageProxy.getImageInfo().getRotationDegrees()
                    );

            List<Face> faces =
                    Tasks.await(
                            livenessDetector.process(inputImage)
                    );

            handleFaces(faces);

        } catch (Exception e) {
            // Keep the session alive on an isolated analyzer failure. A
            // persistent failure naturally reaches the liveness timeout.
            updateStatus("A validar presença… mantenha o rosto visível");

        } finally {
            frameBusy.set(false);
            imageProxy.close();
        }
    }

    private void handleFaces(List<Face> faces) {
        if (closed || livenessPassed) {
            return;
        }

        long now = System.currentTimeMillis();

        if (faces == null || faces.size() != 1) {
            if (lastSingleFaceAt > 0 &&
                    now - lastSingleFaceAt > FACE_LOST_RESET_MS) {
                resetCurrentActionState();
            }

            updateStatus(
                    faces != null && faces.size() > 1
                            ? "Apenas uma pessoa de cada vez"
                            : "Posicione o rosto ao centro"
            );
            return;
        }

        lastSingleFaceAt = now;
        Face face = faces.get(0);

        LivenessAction action = sequence[actionIndex];
        boolean complete;

        if (action == LivenessAction.BLINK) {
            complete = processBlink(face);
        } else {
            complete = processTurn(face);
        }

        if (complete) {
            actionIndex++;

            if (actionIndex >= sequence.length) {
                completeLiveness();
            } else {
                resetCurrentActionState();
                updateInstructionForCurrentAction();
            }
        }
    }

    private boolean processBlink(Face face) {
        Float left = face.getLeftEyeOpenProbability();
        Float right = face.getRightEyeOpenProbability();

        if (left == null || right == null) {
            updateStatus("Olhe de frente para conseguirmos ver os olhos");
            return false;
        }

        boolean eyesOpen =
                left >= EYE_OPEN_THRESHOLD &&
                right >= EYE_OPEN_THRESHOLD;

        boolean eyesClosed =
                left <= EYE_CLOSED_THRESHOLD &&
                right <= EYE_CLOSED_THRESHOLD;

        if (blinkStage == 0) {
            if (eyesOpen) {
                blinkOpenFrames++;
            } else {
                blinkOpenFrames = 0;
            }

            if (blinkOpenFrames >= 2) {
                blinkStage = 1;
                updateStatus("Agora pisque os olhos");
            }

        } else if (blinkStage == 1) {
            if (eyesClosed) {
                blinkStage = 2;
                blinkReopenFrames = 0;
            }

        } else if (blinkStage == 2) {
            if (eyesOpen) {
                blinkReopenFrames++;
            } else {
                blinkReopenFrames = 0;
            }

            if (blinkReopenFrames >= 2) {
                updateStatus("Piscar confirmado");
                return true;
            }
        }

        return false;
    }

    private boolean processTurn(Face face) {
        float yaw = Math.abs(face.getHeadEulerAngleY());

        if (turnStage == 0) {
            if (yaw <= HEAD_CENTER_THRESHOLD) {
                turnStableFrames++;
            } else {
                turnStableFrames = 0;
            }

            if (turnStableFrames >= 2) {
                turnStage = 1;
                turnStableFrames = 0;
                updateStatus("Vire a cabeça para um dos lados");
            }

        } else if (turnStage == 1) {
            if (yaw >= HEAD_TURN_THRESHOLD) {
                turnStableFrames++;
            } else {
                turnStableFrames = 0;
            }

            if (turnStableFrames >= 2) {
                turnStage = 2;
                turnStableFrames = 0;
                updateStatus("Volte a olhar de frente");
            }

        } else if (turnStage == 2) {
            if (yaw <= HEAD_CENTER_THRESHOLD) {
                turnStableFrames++;
            } else {
                turnStableFrames = 0;
            }

            if (turnStableFrames >= 2) {
                updateStatus("Movimento confirmado");
                return true;
            }
        }

        return false;
    }

    private void completeLiveness() {
        if (closed || livenessPassed) {
            return;
        }

        livenessPassed = true;
        mainHandler.removeCallbacks(timeoutRunnable);

        if (imageAnalysis != null) {
            imageAnalysis.clearAnalyzer();
        }

        updateInstruction("Presença confirmada");
        updateStatus("A capturar para reconhecimento…");

        activity.runOnUiThread(() -> {
            mainHandler.postDelayed(
                    this::captureAfterLiveness,
                    250L
            );
        });
    }

    private void captureAfterLiveness() {
        if (closed || captureTriggered || imageCapture == null) {
            return;
        }

        captureTriggered = true;

        File output = new File(
                activity.getCacheDir(),
                "faceid_capture_" +
                        System.currentTimeMillis() +
                        ".jpg"
        );

        ImageCapture.OutputFileOptions options =
                new ImageCapture.OutputFileOptions.Builder(output)
                        .build();

        imageCapture.takePicture(
                options,
                mainExecutor,
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(
                            @NonNull ImageCapture.OutputFileResults outputFileResults
                    ) {
                        if (closed) {
                            safeDelete(output);
                            return;
                        }

                        String sequenceResult = livenessSequence;
                        closeInternal();
                        listener.onCaptured(
                                output,
                                sequenceResult
                        );
                    }

                    @Override
                    public void onError(
                            @NonNull ImageCaptureException exception
                    ) {
                        safeDelete(output);
                        fail(
                                "CAMERA_CAPTURE_FAILED: " +
                                        safeMessage(exception)
                        );
                    }
                }
        );
    }

    private void resetCurrentActionState() {
        blinkStage = 0;
        blinkOpenFrames = 0;
        blinkReopenFrames = 0;
        turnStage = 0;
        turnStableFrames = 0;
    }

    private void updateInstructionForCurrentAction() {
        if (sequence == null || actionIndex >= sequence.length) {
            return;
        }

        int step = actionIndex + 1;
        String actionText =
                sequence[actionIndex] == LivenessAction.BLINK
                        ? "Pisque os olhos"
                        : "Vire a cabeça e volte ao centro";

        updateInstruction(
                "Validação " + step + "/" + sequence.length +
                        ": " + actionText
        );

        if (sequence[actionIndex] == LivenessAction.BLINK) {
            updateStatus("Olhe de frente e mantenha os olhos abertos");
        } else {
            updateStatus("Comece a olhar de frente");
        }
    }

    private static String actionName(LivenessAction action) {
        return action == LivenessAction.BLINK
                ? "BLINK"
                : "TURN_RETURN";
    }

    private void updateInstruction(String text) {
        activity.runOnUiThread(() -> {
            if (!closed && instructionView != null) {
                instructionView.setText(text);
            }
        });
    }

    private void updateStatus(String text) {
        activity.runOnUiThread(() -> {
            if (!closed && statusView != null) {
                statusView.setText(text);
            }
        });
    }

    private void cancel() {
        if (closed) {
            return;
        }

        closeInternal();
        listener.onCancelled();
    }

    private void fail(String message) {
        if (closed) {
            return;
        }

        closeInternal();
        listener.onError(message);
    }

    public void dismissSilently() {
        activity.runOnUiThread(() -> {
            if (!closed) {
                closeInternal();
            }
        });
    }

    private void closeInternal() {
        closed = true;
        mainHandler.removeCallbacks(timeoutRunnable);

        try {
            if (imageAnalysis != null) {
                imageAnalysis.clearAnalyzer();
            }
        } catch (Exception ignored) {
        }

        try {
            if (cameraProvider != null) {
                cameraProvider.unbindAll();
            }
        } catch (Exception ignored) {
        }

        try {
            if (livenessDetector != null) {
                livenessDetector.close();
            }
        } catch (Exception ignored) {
        }

        try {
            analysisExecutor.shutdownNow();
        } catch (Exception ignored) {
        }

        try {
            lifecycleRegistry.setCurrentState(
                    Lifecycle.State.DESTROYED
            );
        } catch (Exception ignored) {
        }

        if (overlay != null &&
                overlay.getParent() instanceof ViewGroup) {

            ((ViewGroup) overlay.getParent())
                    .removeView(overlay);
        }

        overlay = null;
        previewView = null;
        instructionView = null;
        statusView = null;
        imageCapture = null;
        imageAnalysis = null;
        cameraProvider = null;
        livenessDetector = null;
    }

    private int dp(int value) {
        float density =
                activity.getResources()
                        .getDisplayMetrics()
                        .density;

        return Math.round(value * density);
    }

    private static String safeMessage(Throwable throwable) {
        if (throwable == null ||
                throwable.getMessage() == null) {
            return "Unknown error";
        }

        return throwable.getMessage();
    }

    private static void safeDelete(File file) {
        try {
            if (file != null && file.exists()) {
                file.delete();
            }
        } catch (Exception ignored) {
        }
    }
}
