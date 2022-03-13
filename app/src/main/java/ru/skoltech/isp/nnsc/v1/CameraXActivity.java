package ru.skoltech.isp.nnsc.v1;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.hardware.Camera;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;

import android.util.Log;
import android.util.Size;
import android.widget.Toast;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;


public class CameraXActivity  extends AppCompatActivity {

    private static final String TAG = "CameraXActivity";
    private ListenableFuture<ProcessCameraProvider> mCameraProviderFuture;
    private static final int REQUEST_CODE_CAMERA_PERMISSION = 200;
    private static final String[] PERMISSIONS = {Manifest.permission.CAMERA};
    private ImageCapture mImageCapture;

    private static final String mOutputFileName = "image_to_process.jpg";
    private ExecutorService mCameraExecutor;

    private PreviewView mPreviewView;


    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);
        findViewById(R.id.camera_capture_button).setOnClickListener(v -> takePhoto());
        mPreviewView = findViewById(R.id.viewFinder);
//        Log.e(TAG, "onCreate");

        mCameraExecutor = Executors.newSingleThreadExecutor();
    }

    @Override
    protected void onResume() {
        super.onResume();
//        Log.e(TAG, "onResume");
//        Log.e(TAG, mPreviewView.toString());
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this,
                    PERMISSIONS,
                    REQUEST_CODE_CAMERA_PERMISSION);
        } else {
            mPreviewView.post(() -> {
//                Log.e(TAG, mPreviewView.getDisplay().toString());
                Log.e(TAG, String.valueOf(mPreviewView.getDisplay().getRotation()));
                startCamera();
            });
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQUEST_CODE_CAMERA_PERMISSION) {
            if (grantResults[0] == PackageManager.PERMISSION_DENIED) {
                Toast.makeText(
                        this,
                        "You can't use image classification example without granting CAMERA permission",
                        Toast.LENGTH_LONG)
                        .show();
                finish();
            } else {
                startCamera();
            }
        }
    }



    private void takePhoto() {
        File outputFile = new File(getCacheDir(), mOutputFileName);
        Log.e(TAG, "Print to" + outputFile.getAbsolutePath());
        ImageCapture.OutputFileOptions outputFileOptions =
                new ImageCapture.OutputFileOptions.Builder(outputFile).build();

        mImageCapture.takePicture(outputFileOptions, mCameraExecutor,
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(ImageCapture.OutputFileResults outputFileResults) {
                        setResult(Activity.RESULT_OK);
                        Log.e(TAG, "Photo capture finished");
                        finish();
                    }
                    @Override
                    public void onError(ImageCaptureException error) {
                        Log.e(TAG, "Photo capture failed:", error);
                        setResult(Activity.RESULT_CANCELED);
                        finish();
                    }
                }
        );

    }

    private void startCamera() {
        Log.e(TAG, "startCamera");
        mCameraProviderFuture = ProcessCameraProvider.getInstance(this);
        mCameraProviderFuture.addListener(() -> {
            try {

                ProcessCameraProvider cameraProvider = mCameraProviderFuture.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(mPreviewView.getSurfaceProvider());

                CameraSelector cameraSelector = new CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                        .build();

                mImageCapture = new ImageCapture.Builder()
                        .setTargetRotation(mPreviewView.getDisplay().getRotation())
//                        .setTargetResolution(new Size(224, 224))
                        .build();

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(
                        this, cameraSelector, preview, mImageCapture);


            } catch (Exception exc) {
                Log.e(TAG, "Error in Listener", exc);
                // No errors need to be handled for this Future.
                // This should never be reached.
            }
        }, ContextCompat.getMainExecutor(this));

    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        mCameraProviderFuture.cancel(true);
        mCameraExecutor.shutdown();
    }

}