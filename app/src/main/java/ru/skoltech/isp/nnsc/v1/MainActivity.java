package ru.skoltech.isp.nnsc.v1;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;
import androidx.appcompat.app.AppCompatActivity;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import androidx.exifinterface.media.ExifInterface;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;

import org.pytorch.IValue;
import org.pytorch.LiteModuleLoader;
import org.pytorch.Module;
import org.pytorch.Tensor;
import org.pytorch.torchvision.TensorImageUtils;


public class MainActivity extends AppCompatActivity  implements AdapterView.OnItemSelectedListener {

    private final int WARMUP = 100;
    private final int ITER = 200;

    private static final String TAG = "MainActivity";
    private static final String mImageName = "image_to_process.jpg";
    private ImageView mImageView;
    private Bitmap mBitmap = null;
    private Module mModule = null;
    private static final int INPUT_TENSOR_SIZE = 224;
    private static final int TOP_K = 3;
    private TextView mTextView;
    private boolean isRotated = false;
    private boolean hasImageViewSet = false;
    private boolean isModelRun = false;
    private final List<String> mModelNames = Arrays.asList("resnet18_orig.ptl",
            "resnet18_quan.ptl", "resnet18_comp.ptl", "resnet18_comp_quan.ptl");
    private String mCurrentModelName = "";
    private String mLastModelName = "";
    private final String HAS_IMAGE_VIEW_SET = "has image view set";
    private final String IS_MODEL_RUN = "is model run";
    private final String CURRENT_MODEL = "current model";


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (savedInstanceState != null) {
            hasImageViewSet = savedInstanceState.getBoolean(HAS_IMAGE_VIEW_SET);
            isModelRun = savedInstanceState.getBoolean(IS_MODEL_RUN);
            mCurrentModelName = savedInstanceState.getString(CURRENT_MODEL);
        }


        // Connect UI elements
        mTextView  = (TextView)  findViewById(R.id.text_view);
        mImageView = (ImageView) findViewById(R.id.image_view);

        // restore previous image if exists
        if (hasImageViewSet) {
            File file = new File(this.getApplicationContext().getCacheDir(), mImageName);
            Log.e(TAG, "Print to" + file.getAbsolutePath());
            if (file.exists() && file.length() > 0) {
                isRotated = checkRotation(file);
                mBitmap = scaleBitmap(BitmapFactory.decodeFile(file.getAbsolutePath()), INPUT_TENSOR_SIZE, isRotated);
                mImageView.setImageBitmap(mBitmap);
            }
        }

        // Set callbacks for buttons
        findViewById(R.id.button_take_image).setOnClickListener(v ->
                startActivityForResult(new Intent(MainActivity.this, CameraXActivity.class), 1));

        findViewById(R.id.button_process_image).setOnClickListener((v) -> {

            if (hasImageViewSet){
                loadModel();
                isModelRun = true;
                Toast.makeText(this.getApplicationContext(), "Do some DL magic", Toast.LENGTH_SHORT).show();
                analyzeImage();
            } else {
                Toast.makeText(this.getApplicationContext(), "Take image first", Toast.LENGTH_SHORT).show();
            }
        });

        findViewById(R.id.button_estimate_inference_time).setOnClickListener((v) -> {
            if (hasImageViewSet) {
                loadModel();
                Toast.makeText(this.getApplicationContext(), "Estimate model '" + mCurrentModelName +"' inference time", Toast.LENGTH_SHORT).show();
                estimateInferenceTime();
            } else {
                Toast.makeText(this.getApplicationContext(), "Take image first", Toast.LENGTH_SHORT).show();
            }
        });

        // Set Spinner
        Spinner spinner = findViewById(R.id.spinner_models);
        spinner.setOnItemSelectedListener(this);
        // Create an ArrayAdapter using the string array and a default spinner layout
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this, R.array.array_models,
                                                                             android.R.layout.simple_spinner_item);
        // Specify the layout to use when the list of choices appears
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        // Apply the adapter to the spinner
        spinner.setAdapter(adapter);

        int pos = mModelNames.indexOf(mCurrentModelName);
        if (pos >= 0) {
        } else {
            mCurrentModelName = mModelNames.get(0);
            pos = 0;
        }
        spinner.setSelection(pos);

    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isModelRun) {
            loadModel();
            analyzeImage();
        }
    }

    @Override
    protected void onDestroy() {
        if (mModule != null) {
            mModule.destroy();
        }
        super.onDestroy();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(HAS_IMAGE_VIEW_SET, hasImageViewSet);
        outState.putBoolean(IS_MODEL_RUN, isModelRun);
        outState.putString(CURRENT_MODEL, mCurrentModelName);
    }


    public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {mCurrentModelName = mModelNames.get(pos);}

    public void onNothingSelected(AdapterView<?> parent) {}

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == 1) {
            if(resultCode == Activity.RESULT_OK){
                File file = new File(this.getApplicationContext().getCacheDir(), mImageName);
                Log.e(TAG, "Print to" + file.getAbsolutePath());
                if (file.exists() && file.length() > 0) {
                    isRotated = checkRotation(file);
                    mBitmap = scaleBitmap(BitmapFactory.decodeFile(file.getAbsolutePath()), INPUT_TENSOR_SIZE, isRotated);
                    mImageView.setImageBitmap(mBitmap);
                    hasImageViewSet = true;
                    isModelRun = false;
                } else {
                    Log.e(TAG, "Error reading image");
                }
            }

            if (resultCode == Activity.RESULT_CANCELED) {
                // Write your code if there's no result
                Toast.makeText(this.getApplicationContext(), "Fail to take photo", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void loadModel() {

        if (mModule == null || !mCurrentModelName.equals(mLastModelName)) {
            try {
                mModule = LiteModuleLoader.load(MainActivity.assetFilePath(this.getApplicationContext(), mCurrentModelName));
                System.gc();
            } catch (IOException e) {
                Log.e(TAG, "Error loading model file", e);
                finish();
            }

            mLastModelName = mCurrentModelName;
        }
    }

    @WorkerThread  //Denotes that the annotated method should only be called on a worker thread.
    protected void analyzeImage() {

            final long startTime = SystemClock.elapsedRealtime();
        Tensor mInputTensor = TensorImageUtils.bitmapToFloat32Tensor(mBitmap,
                TensorImageUtils.TORCHVISION_NORM_MEAN_RGB, TensorImageUtils.TORCHVISION_NORM_STD_RGB);

            final long forwardStartTime = SystemClock.elapsedRealtime();
            final Tensor outputTensor = mModule.forward(IValue.from(mInputTensor)).toTensor();
            final long forwardDuration = SystemClock.elapsedRealtime() - forwardStartTime;

            final float[] scores = outputTensor.getDataAsFloatArray();
            final int[] ixs = topK(scores, TOP_K);

            final String[] topKClassNames = new String[TOP_K];
            final float[] topKScores = new float[TOP_K];
            for (int i = 0; i < TOP_K; i++) {
                final int ix = ixs[i];
                topKClassNames[i] = Constants.IMAGENET_CLASSES[ix];
                topKScores[i] = scores[ix];
            }

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < TOP_K; i++) {
                sb.append(i+1)
                  .append(") ")
                  .append(topKClassNames[i])
                  .append("\n");
            }

            sb.append("\nLoad time: ")
              .append(forwardStartTime-startTime)
              .append(" ms\n")
              .append("\nProcess time: ")
              .append(forwardDuration)
              .append(" ms");

            mTextView.setText(sb.toString());
    }

    @WorkerThread  //Denotes that the annotated method should only be called on a worker thread.
    protected void estimateInferenceTime() {

        Tensor mInputTensor = TensorImageUtils.bitmapToFloat32Tensor(mBitmap,
                TensorImageUtils.TORCHVISION_NORM_MEAN_RGB, TensorImageUtils.TORCHVISION_NORM_STD_RGB);

        System.gc();

        for (int i = 0; i < WARMUP; i++) {
            mModule.forward(IValue.from(mInputTensor));
        }

        long[] timeArray = new long[ITER];
        long forwardStartTime = 0;
        for (int i = 0; i < ITER; i++) {
            forwardStartTime = SystemClock.elapsedRealtime();
            mModule.forward(IValue.from(mInputTensor));
            timeArray[i] = SystemClock.elapsedRealtime() - forwardStartTime;
        }

        double mean = getMean(timeArray);
        double std = getStd(timeArray, mean);
        long min = getMin(timeArray);
        long max = getMax(timeArray);

        StringBuilder sb = new StringBuilder();
        sb.append("\nProcess time: ")
          .append(String.format("%.3f", mean))
          .append(" +/- ")
          .append(String.format("%.3f", std))
          .append(" ms")
          .append(" ( ")
          .append(min)
          .append(", ")
          .append(max)
          .append(" )");

        mTextView.setText(sb.toString());
    }


        public static String assetFilePath(Context context, String assetName) throws IOException {
        File file = new File(context.getCacheDir(), assetName);
        if (file.exists() && file.length() > 0) {
            return file.getAbsolutePath();
        }

        try (InputStream is = context.getAssets().open(assetName)) {
            try (OutputStream os = new FileOutputStream(file)) {
                byte[] buffer = new byte[4 * 1024];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    os.write(buffer, 0, read);
                }
                os.flush();
            }
            return file.getAbsolutePath();
        }
    }

    public static int[] topK(float[] a, final int topk) {
        float[] values = new float[topk];
        Arrays.fill(values, -Float.MAX_VALUE);
        int[] ixs = new int[topk];
        Arrays.fill(ixs, -1);

        for (int i = 0; i < a.length; i++) {
            for (int j = 0; j < topk; j++) {
                if (a[i] > values[j]) {
                    for (int k = topk - 1; k >= j + 1; k--) {
                        values[k] = values[k - 1];
                        ixs[k] = ixs[k - 1];
                    }
                    values[j] = a[i];
                    ixs[j] = i;
                    break;
                }
            }
        }
        return ixs;
    }

    private boolean checkRotation(File file) {
        boolean rotated = false;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            try {
                ExifInterface exif = new ExifInterface(file);
                Log.e(TAG, "Orientation " + exif.getAttribute(ExifInterface.TAG_ORIENTATION));
                if (exif.getAttribute(ExifInterface.TAG_ORIENTATION).equals(String.valueOf(ExifInterface.ORIENTATION_ROTATE_90))) {
                    rotated = true;
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return rotated;
    }

    private Bitmap scaleBitmap(Bitmap srcBmp, int scale_size, boolean toRotate) {
        Bitmap dstBmp, dstBmp2, dstBmp3;
        if (srcBmp.getWidth() >= srcBmp.getHeight()){

            dstBmp = Bitmap.createBitmap(
                    srcBmp,
                    srcBmp.getWidth()/2 - srcBmp.getHeight()/2,
                    0,
                    srcBmp.getHeight(),
                    srcBmp.getHeight()
            );

        } else {

            dstBmp = Bitmap.createBitmap(
                    srcBmp,
                    0,
                    srcBmp.getHeight()/2 - srcBmp.getWidth()/2,
                    srcBmp.getWidth(),
                    srcBmp.getWidth()
            );
        }

        srcBmp.recycle();
        if (scale_size > 0) {
            dstBmp2 = Bitmap.createScaledBitmap(dstBmp, scale_size, scale_size, true);
            dstBmp.recycle();
        } else {
            dstBmp2 = dstBmp;
        }
        Log.e(TAG, String.valueOf(toRotate));
        if (toRotate) {
            Matrix matrix = new Matrix();
            matrix.postRotate(90);

            dstBmp3 = Bitmap.createBitmap(dstBmp2, 0, 0, dstBmp2.getWidth(), dstBmp2.getHeight(), matrix, true);
            dstBmp2.recycle();
        } else {
            dstBmp3 = dstBmp2;
        }
        return dstBmp3;
    }

    private double getMean(long[] array) {
        long size = array.length;

        double sum = 0.0;
        for(long a : array) {
            sum += a;
        }

        return sum / size;
    }

    private double getStd(long[] array, double mean) {
        long size = array.length;

        double temp = 0;
        for(long a :array) {
            temp += (a - mean) * (a - mean);
        }
        double var = temp / (size-1);

        return Math.sqrt(var);
    }

    private long getMin(long[] array) {
        long min = array[0];

        for(long a :array) {
            if (a < min) {
                min = a;
            }
        }
        return min;
    }

    private long getMax(long[] array) {
        long max = array[0];

        for(long a :array) {
            if (a > max) {
                max = a;
            }
        }
        return max;
    }
}