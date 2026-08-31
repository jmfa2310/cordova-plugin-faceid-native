package com.company.faceidnative;

import android.content.Context;
import android.graphics.Bitmap;

import com.google.ai.edge.litert.Interpreter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class MobileFaceNetEngine implements AutoCloseable {

    public static final int INPUT_SIZE = 112;
    public static final int EMBEDDING_SIZE = 192;
    private static final String MODEL_ASSET = "mobilefacenet.tflite";

    private final Interpreter interpreter;

    public MobileFaceNetEngine(Context context) throws IOException {
        ByteBuffer model = loadModel(context, MODEL_ASSET);

        Interpreter.Options options = new Interpreter.Options();
        options.setNumThreads(Math.max(2, Math.min(4,
                Runtime.getRuntime().availableProcessors())));

        interpreter = new Interpreter(model, options);

        int[] inputShape = interpreter.getInputTensor(0).shape();
        int[] outputShape = interpreter.getOutputTensor(0).shape();

        if (inputShape.length != 4 || inputShape[0] != 1 ||
                inputShape[1] != INPUT_SIZE ||
                inputShape[2] != INPUT_SIZE ||
                inputShape[3] != 3) {
            interpreter.close();
            throw new IllegalStateException(
                    "Unexpected model input. Expected [1,112,112,3].");
        }

        if (outputShape.length != 2 || outputShape[0] != 1 ||
                outputShape[1] != EMBEDDING_SIZE) {
            interpreter.close();
            throw new IllegalStateException(
                    "Unexpected model output. Expected [1,192].");
        }
    }

    public synchronized float[] embedding(Bitmap faceBitmap) {
        if (faceBitmap == null) {
            throw new IllegalArgumentException("Face bitmap is null.");
        }

        Bitmap resized = Bitmap.createScaledBitmap(
                faceBitmap, INPUT_SIZE, INPUT_SIZE, true);

        ByteBuffer input = ByteBuffer.allocateDirect(
                INPUT_SIZE * INPUT_SIZE * 3 * 4)
                .order(ByteOrder.nativeOrder());

        int[] pixels = new int[INPUT_SIZE * INPUT_SIZE];
        resized.getPixels(pixels, 0, INPUT_SIZE,
                0, 0, INPUT_SIZE, INPUT_SIZE);

        // MobileFaceNet preprocessing: (RGB - 127.5) / 128.0
        for (int pixel : pixels) {
            input.putFloat((((pixel >> 16) & 0xFF) - 127.5f) / 128.0f);
            input.putFloat((((pixel >> 8) & 0xFF) - 127.5f) / 128.0f);
            input.putFloat(((pixel & 0xFF) - 127.5f) / 128.0f);
        }

        input.rewind();

        float[][] output = new float[1][EMBEDDING_SIZE];
        interpreter.run(input, output);

        float[] result = output[0];
        l2Normalize(result);

        if (resized != faceBitmap && !resized.isRecycled()) {
            resized.recycle();
        }

        return result;
    }

    private static void l2Normalize(float[] vector) {
        double sum = 0.0;
        for (float value : vector) sum += value * value;

        double norm = Math.sqrt(Math.max(sum, 1e-12));
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) (vector[i] / norm);
        }
    }

    public static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            throw new IllegalArgumentException(
                    "Descriptor dimensions do not match.");
        }

        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }

        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom <= 1e-12 ? -1.0 : dot / denom;
    }

    private static ByteBuffer loadModel(
            Context context, String assetName) throws IOException {

        try (InputStream inputStream =
                     context.getAssets().open(assetName);
             ByteArrayOutputStream outputStream =
                     new ByteArrayOutputStream()) {

            byte[] buffer = new byte[64 * 1024];
            int read;

            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }

            byte[] bytes = outputStream.toByteArray();

            if (bytes.length < 1_000_000) {
                throw new IOException(
                        "mobilefacenet.tflite missing/invalid. " +
                        "Run tools/download-model.ps1 before building.");
            }

            ByteBuffer direct = ByteBuffer
                    .allocateDirect(bytes.length)
                    .order(ByteOrder.nativeOrder());

            direct.put(bytes);
            direct.rewind();
            return direct;
        }
    }

    @Override
    public void close() {
        interpreter.close();
    }
}
