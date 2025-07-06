package com.example.wasteclassifier;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.YuvImage;
import android.media.Image;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import org.tensorflow.lite.Interpreter;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.io.FileInputStream;
import android.content.res.AssetFileDescriptor;

import androidx.camera.view.PreviewView;

public class MainActivity extends AppCompatActivity {
    private static final int PICK_IMAGE = 1;
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 100;
    private ImageView imageView;
    private ImageView overlayImageView;
    private TextView textViewResult;
    private Interpreter tflite;
    private static final String MODEL_PATH = "model.tflite";
    private static final int IMAGE_SIZE = 224;

    private PreviewView previewView;
    private FrameLayout previewContainer;
    private ImageButton buttonSelectImage;
    private ImageButton buttonScan;
    private ExecutorService cameraExecutor;

    // Classification result images
    private Bitmap organicBitmap;
    private Bitmap recycleBitmap;

    // For tracking classification result and position
    private PointF lastDetectionPoint;
    private String lastClassification = "";
    private float lastConfidence = 0f;
    private boolean isScanning = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable classificationRunnable;
    private static final long CLASSIFICATION_INTERVAL = 500; // Classify every 500ms

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Set fullscreen
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);

        setContentView(R.layout.activity_main);

        imageView = findViewById(R.id.imageView);
        overlayImageView = findViewById(R.id.overlayImageView);
        textViewResult = findViewById(R.id.textViewResult);
        buttonSelectImage = findViewById(R.id.buttonSelectImage);
        buttonScan = findViewById(R.id.buttonScan);
        previewView = findViewById(R.id.previewView);
        previewContainer = findViewById(R.id.previewContainer);

        // Load classification result images
        organicBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.organic);
        recycleBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.recycle);

        cameraExecutor = Executors.newSingleThreadExecutor();

        try {
            tflite = new Interpreter(loadModelFile());
            Log.d("MainActivity", "Model loaded successfully.");
        } catch (Exception e) {
            e.printStackTrace();
            Log.e("MainActivity", "Error loading model: " + e.getMessage());
        }

        buttonSelectImage.setOnClickListener(v -> {
            stopScanning();
            openGallery();
            switchToGalleryMode();
            textViewResult.setText("EcoScope");
        });

        buttonScan.setOnClickListener(v -> {
            checkCameraPermissionForScan();
            switchToCameraMode();
            textViewResult.setText("Arahkan kamera ke sampah...");
            startScanning();
        });
    }

    private void switchToCameraMode() {
        previewView.setVisibility(View.VISIBLE);
        imageView.setVisibility(View.GONE);
        overlayImageView.setVisibility(View.VISIBLE);
    }

    private void switchToGalleryMode() {
        imageView.setVisibility(View.VISIBLE);
        previewView.setVisibility(View.GONE);
        overlayImageView.setVisibility(View.GONE);
    }

    private void startScanning() {
        isScanning = true;
        classificationRunnable = new Runnable() {
            @Override
            public void run() {
                if (isScanning) {
                    // Continue scanning while the flag is true
                    handler.postDelayed(this, CLASSIFICATION_INTERVAL);
                }
            }
        };
        handler.post(classificationRunnable);
    }

    private void stopScanning() {
        isScanning = false;
        if (classificationRunnable != null) {
            handler.removeCallbacks(classificationRunnable);
        }
    }

    private void openGallery() {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        startActivityForResult(intent, PICK_IMAGE);
    }

    private void checkCameraPermissionForScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST_CODE);
        } else {
            startCameraForScan();
        }
    }

    private void startCameraForScan() {
        try {
            ProcessCameraProvider cameraProvider = ProcessCameraProvider.getInstance(this).get();

            // Clear previous bindings
            cameraProvider.unbindAll();

            bindAnalysisForScan(cameraProvider);
        } catch (ExecutionException | InterruptedException e) {
            Log.e("MainActivity", "Camera initialization failed: " + e.getMessage());
            Toast.makeText(this, "Gagal memulai kamera", Toast.LENGTH_SHORT).show();
        }
    }

    private void bindAnalysisForScan(ProcessCameraProvider cameraProvider) {
        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build();

        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                .setTargetResolution(new Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();

        imageAnalysis.setAnalyzer(cameraExecutor, new ImageAnalysis.Analyzer() {
            @Override
            public void analyze(@NonNull ImageProxy imageProxy) {
                if (isScanning) {
                    processImageProxy(imageProxy);
                } else {
                    imageProxy.close();
                }
            }
        });

        Camera camera = cameraProvider.bindToLifecycle((LifecycleOwner) this, cameraSelector, preview, imageAnalysis);
        Log.d("MainActivity", "Camera started for scanning.");
    }

    private void processImageProxy(ImageProxy imageProxy) {
        try {
            Bitmap bitmap = imageProxyToBitmap(imageProxy);
            if (bitmap != null) {
                // Calculate center point of the image as default detection point
                lastDetectionPoint = new PointF(bitmap.getWidth() / 2f, bitmap.getHeight() / 2f);

                runOnUiThread(() -> {
                    classifyImage(bitmap);
                    updateOverlayImage();
                });
            } else {
                Log.e("MainActivity", "Bitmap is null");
            }
        } catch (Exception e) {
            Log.e("MainActivity", "Error processing image proxy: " + e.getMessage());
            e.printStackTrace();
        } finally {
            imageProxy.close();
        }
    }

    private void updateOverlayImage() {
        if (lastClassification.isEmpty() || lastConfidence < 0.6f) {
            // Hide overlay if no confident classification
            overlayImageView.setVisibility(View.INVISIBLE);
            return;
        }

        // Show overlay
        overlayImageView.setVisibility(View.VISIBLE);

        // Select correct bitmap based on classification
        Bitmap resultBitmap = lastClassification.equals("Organik") ? organicBitmap : recycleBitmap;

        // Scale bitmap to appropriate size (40% of container width)
        int scaledWidth = (int)(previewContainer.getWidth() * 0.8);
        int scaledHeight = (int)(scaledWidth * ((float)resultBitmap.getHeight() / resultBitmap.getWidth()));

        Bitmap scaledBitmap = Bitmap.createScaledBitmap(resultBitmap, scaledWidth, scaledHeight, true);

        // Set the image
        overlayImageView.setImageBitmap(scaledBitmap);
        overlayImageView.setTranslationY(-100);
        // Animate overlay appearance
        overlayImageView.setAlpha(0f);
        overlayImageView.animate()
                .alpha(1f)
                .setDuration(300)
                .start();
    }

    private Bitmap imageProxyToBitmap(ImageProxy imageProxy) {
        try {
            Image image = imageProxy.getImage();
            if (image == null) {
                return null;
            }

            // Convert Image to YUV format
            ByteBuffer yBuffer = image.getPlanes()[0].getBuffer();
            ByteBuffer uBuffer = image.getPlanes()[1].getBuffer();
            ByteBuffer vBuffer = image.getPlanes()[2].getBuffer();

            int ySize = yBuffer.remaining();
            int uSize = uBuffer.remaining();
            int vSize = vBuffer.remaining();

            byte[] nv21 = new byte[ySize + uSize + vSize];

            yBuffer.get(nv21, 0, ySize);
            vBuffer.get(nv21, ySize, vSize);
            uBuffer.get(nv21, ySize + vSize, uSize);

            YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, image.getWidth(), image.getHeight(), null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            yuvImage.compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()), 100, out);

            byte[] imageBytes = out.toByteArray();
            Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
            return bitmap;
        } catch (Exception e) {
            Log.e("MainActivity", "Error converting image proxy to bitmap: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopScanning();
        if (tflite != null) {
            tflite.close();
        }
        cameraExecutor.shutdown();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCameraForScan();
            } else {
                Log.e("MainActivity", "Camera permission denied.");
                textViewResult.setText("Izin kamera ditolak");
                Toast.makeText(this, "Aplikasi memerlukan izin kamera untuk pemindaian sampah", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_IMAGE && resultCode == RESULT_OK && data != null) {
            Uri imageUri = data.getData();
            try {
                InputStream inputStream = getContentResolver().openInputStream(imageUri);
                Bitmap bitmap = BitmapFactory.decodeStream(inputStream);

                // Display original image first
                imageView.setImageBitmap(bitmap);

                // Classify the image
                classifyImage(bitmap);

                Log.d("MainActivity", "Image selected successfully.");
            } catch (Exception e) {
                e.printStackTrace();
                Log.e("MainActivity", "Error processing image: " + e.getMessage());
                textViewResult.setText("Gagal memproses gambar");
            }
        }
    }

    private void classifyImage(Bitmap bitmap) {
        if (tflite == null) {
            Log.e("MainActivity", "TensorFlow Lite Interpreter is not initialized.");
            textViewResult.setText("Model tidak siap");
            return;
        }

        try {
            Bitmap resizedBitmap = Bitmap.createScaledBitmap(bitmap, IMAGE_SIZE, IMAGE_SIZE, true);
            ByteBuffer inputBuffer = convertBitmapToByteBuffer(resizedBitmap);

            float[][] output = new float[1][2];
            tflite.run(inputBuffer, output);

            int predictedClass = getPredictedClass(output);
            lastClassification = getClassLabel(predictedClass);
            lastConfidence = output[0][predictedClass];

            // Display result with confidence
            String resultText;
            if (lastConfidence >= 0.7f) {
                resultText = "Sampah: " + lastClassification + "\nKepastian: " + String.format("%.1f", lastConfidence * 100) + "%";
            } else if (lastConfidence >= 0.5f) {
                resultText = "Mungkin " + lastClassification + "\nKepastian: " + String.format("%.1f", lastConfidence * 100) + "%";
            } else {
                resultText = "Tidak dapat mengidentifikasi\nCoba arahkan kamera dengan benar";
                lastClassification = ""; // Reset classification if confidence is too low
            }

            textViewResult.setText(resultText);
            Log.d("MainActivity", "Prediction: " + lastClassification + " with confidence: " + (lastConfidence * 100));

            // If in gallery mode, overlay the result on the image
            if (previewView.getVisibility() != View.VISIBLE && lastConfidence >= 0.6f) {
                // Get the correct classification image
                Bitmap resultBitmap = lastClassification.equals("Organik") ? organicBitmap : recycleBitmap;

                // Create a new bitmap that combines the original image and the classification overlay
                Bitmap combinedBitmap = createCombinedBitmap(bitmap, resultBitmap);

                // Display the combined image
                imageView.setImageBitmap(combinedBitmap);
            }
        } catch (Exception e) {
            Log.e("MainActivity", "Error classifying image: " + e.getMessage());
            e.printStackTrace();
            textViewResult.setText("Gagal mengklasifikasikan sampah");
        }
    }

    private Bitmap createCombinedBitmap(Bitmap originalBitmap, Bitmap overlayBitmap) {
        // Create a mutable copy of the original bitmap
        Bitmap combinedBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(combinedBitmap);

        // Calculate size of overlay (40% of original width)
        int overlayWidth = (int)(originalBitmap.getWidth() * 0.4);
        int overlayHeight = (int)(overlayWidth * ((float)overlayBitmap.getHeight() / overlayBitmap.getWidth()));

        // Position overlay in center
        float left = (originalBitmap.getWidth() - overlayWidth) / 2f;
        float top = (originalBitmap.getHeight() - overlayHeight) / 2f;

        // Create a destination rectangle for the overlay
        RectF destRect = new RectF(left, top, left + overlayWidth, top + overlayHeight);

        // Draw the overlay on the canvas
        canvas.drawBitmap(overlayBitmap, null, destRect, null);

        return combinedBitmap;
    }

    private ByteBuffer convertBitmapToByteBuffer(Bitmap bitmap) {
        ByteBuffer byteBuffer = ByteBuffer.allocateDirect(4 * IMAGE_SIZE * IMAGE_SIZE * 3);
        byteBuffer.order(java.nio.ByteOrder.nativeOrder());
        int[] intValues = new int[IMAGE_SIZE * IMAGE_SIZE];
        bitmap.getPixels(intValues, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());

        int pixel = 0;
        for (int i = 0; i < IMAGE_SIZE; ++i) {
            for (int j = 0; j < IMAGE_SIZE; ++j) {
                final int val = intValues[pixel++];
                // Normalize pixel values
                byteBuffer.putFloat(((val >> 16) & 0xFF) / 255.0f);
                byteBuffer.putFloat(((val >> 8) & 0xFF) / 255.0f);
                byteBuffer.putFloat((val & 0xFF) / 255.0f);
            }
        }
        return byteBuffer;
    }

    private int getPredictedClass(float[][] output) {
        float max = -Float.MAX_VALUE;
        int predictedClass = -1;
        for (int i = 0; i < output[0].length; i++) {
            if (output[0][i] > max) {
                max = output[0][i];
                predictedClass = i;
            }
        }
        return predictedClass;
    }

    private String getClassLabel(int classIndex) {
        String[] classLabels = {
                "Organik", "Daur Ulang"
        };

        if (classIndex >= 0 && classIndex < classLabels.length) {
            return classLabels[classIndex];
        } else {
            return "Tidak Dikenal";
        }
    }

    private MappedByteBuffer loadModelFile() throws IOException {
        AssetFileDescriptor fileDescriptor = getAssets().openFd(MODEL_PATH);
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopScanning();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (previewView.getVisibility() == View.VISIBLE) {
            startScanning();
        }
    }
}