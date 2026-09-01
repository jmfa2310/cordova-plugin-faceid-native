package com.company.faceidnative;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.Size;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.util.concurrent.Executor;

public final class FaceCameraOverlay implements LifecycleOwner {

    public interface Listener {
        void onCaptured(File file);
        void onCancelled();
        void onError(String error);
    }

    private final Activity activity;
    private final Listener listener;
    private final LifecycleRegistry lifecycleRegistry;
    private final Executor mainExecutor;

    private FrameLayout overlay;
    private PreviewView previewView;
    private Button captureButton;
    private ProcessCameraProvider cameraProvider;
    private ImageCapture imageCapture;
    private boolean closed = false;

    public FaceCameraOverlay(
            Activity activity,
            Listener listener
    ) {
        this.activity = activity;
        this.listener = listener;
        this.lifecycleRegistry = new LifecycleRegistry(this);
        this.mainExecutor = ContextCompat.getMainExecutor(activity);
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

        TextView instruction = new TextView(activity);
        instruction.setText("Posicione o rosto ao centro");
        instruction.setTextColor(Color.WHITE);
        instruction.setTextSize(18);
        instruction.setTypeface(Typeface.DEFAULT_BOLD);
        instruction.setGravity(Gravity.CENTER);
        instruction.setBackgroundColor(0x66000000);
        instruction.setPadding(
                dp(16),
                dp(10),
                dp(16),
                dp(10)
        );

        FrameLayout.LayoutParams instructionParams =
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP | Gravity.CENTER_HORIZONTAL
                );

        instructionParams.topMargin = dp(28);
        overlay.addView(instruction, instructionParams);

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

        captureButton = new Button(activity);
        captureButton.setText("Capturar");
        captureButton.setAllCaps(false);
        captureButton.setEnabled(false);

        FrameLayout.LayoutParams captureParams =
                new FrameLayout.LayoutParams(
                        dp(150),
                        dp(58),
                        Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL
                );

        captureParams.bottomMargin = dp(34);
        overlay.addView(captureButton, captureParams);

        cancelButton.setOnClickListener(v -> cancel());
        captureButton.setOnClickListener(v -> capture());

        content.addView(
                overlay,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                )
        );
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
                        imageCapture
                );

                captureButton.setEnabled(true);

            } catch (Exception e) {
                fail("CAMERA_BIND_FAILED: " + safeMessage(e));
            }
        }, mainExecutor);
    }

    private void capture() {
        if (closed || imageCapture == null) {
            return;
        }

        captureButton.setEnabled(false);

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

                        closeInternal();
                        listener.onCaptured(output);
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

        try {
            if (cameraProvider != null) {
                cameraProvider.unbindAll();
            }
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
        captureButton = null;
        imageCapture = null;
        cameraProvider = null;
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
