package com.example.myapplication;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.os.Bundle;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.camera2.interop.Camera2Interop;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraControl;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.android.gms.tasks.Task;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import org.ddogleg.struct.DogArray_I8;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import boofcv.abst.fiducial.AztecCodePreciseDetector;
import boofcv.alg.color.ColorFormat;
import boofcv.alg.fiducial.aztec.AztecCode;
import boofcv.android.ConvertCameraImage;
import boofcv.factory.fiducial.ConfigAztecCode;
import boofcv.factory.fiducial.FactoryFiducial;
import boofcv.struct.image.GrayU8;
import boofcv.struct.image.ImageBase;
import boofcv.struct.image.ImageType;
import pabeles.concurrency.GrowArray;

public class MainActivity extends AppCompatActivity {

    public static final int STATE_CONCURRENT = 0;
    public static final int STATE_MLKIT_THEN_BOOTCV = 1;
    public static final int STATE_BOOTCV_THEN_MLKIT = 2;
    private static final String LOG_TAG = MainActivity.class.getName();
    private final ThreadPoolExecutor threadPool;
    private PreviewView preview;
    private BarcodeScanner scanner;

    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;
    private View indicator1;
    private View indicator2;
    private View indicator3;

    protected ImageType imageType;
    protected ColorFormat colorFormat;
    protected ImageBase stackImage;
    protected GrowArray<DogArray_I8> array;
    private Button button;

    private int state = STATE_CONCURRENT;
    private TextView status;

    private class CompareBarcodeAnalyzer implements ImageAnalysis.Analyzer {

        @SuppressLint("UnsafeExperimentalUsageError")
        @OptIn(markerClass = ExperimentalGetImage.class) @Override
        public void analyze(@NonNull ImageProxy imageProxy) {
            try {
                Image mediaImage = imageProxy.getImage();
                if (mediaImage != null) {

                    if(state == STATE_CONCURRENT) {
                        List<Barcode> barcodes = getMlKit(mediaImage);
                        if (!barcodes.isEmpty()) {
                            Log.i(LOG_TAG, "Ml kit success");
                            setIndicator1Color(Color.RED);
                        } else {
                            setIndicator1Color(Color.WHITE);
                        }

                        List<AztecCode> bootcv = getBootcv(mediaImage);
                        if (!bootcv.isEmpty()) {
                            Log.d(LOG_TAG, "Bootcv " + bootcv.size() + " detections");

                            setIndicator2Color(Color.BLUE);
                        } else {
                            setIndicator2Color(Color.WHITE);
                        }
                    } else if(state == STATE_MLKIT_THEN_BOOTCV) {
                        List<Barcode> barcodes = getMlKit(mediaImage);
                        if (!barcodes.isEmpty()) {
                            Log.i(LOG_TAG, "Ml kit success");
                            setIndicator1Color(Color.RED);
                            setIndicator2Color(Color.WHITE);
                        } else {
                            setIndicator1Color(Color.WHITE);

                            List<AztecCode> bootcv = getBootcv(mediaImage);
                            if(!bootcv.isEmpty()) {
                                Log.d(LOG_TAG, "Bootcv " + bootcv.size() + " detections");

                                setIndicator2Color(Color.BLUE);
                            } else {
                                setIndicator2Color(Color.WHITE);
                            }
                        }
                    } else if(state == STATE_BOOTCV_THEN_MLKIT) {
                        List<AztecCode> bootcv = getBootcv(mediaImage);
                        if(!bootcv.isEmpty()) {
                            Log.d(LOG_TAG, "Bootcv " + bootcv.size() + " detections");

                            setIndicator2Color(Color.BLUE);
                            setIndicator1Color(Color.WHITE);
                        } else {
                            setIndicator2Color(Color.WHITE);
                            List<Barcode> barcodes = getMlKit(mediaImage);
                            if (!barcodes.isEmpty()) {
                                Log.i(LOG_TAG, "Ml kit success");
                                setIndicator1Color(Color.RED);
                            } else {
                                setIndicator1Color(Color.WHITE);
                            }
                        }
                    }
                }
                //Log.i(LOG_TAG, "Process image done");
            } catch (Exception e) {
                Log.w(LOG_TAG, "Process image done", e);
            } finally {
                imageProxy.close();
            }
        }

    }

    protected LinkedBlockingQueue threadQueue;

    private AztecCodePreciseDetector<GrayU8> detector;

    public MainActivity() {
        this.threadQueue = new LinkedBlockingQueue();
        this.threadPool = new ThreadPoolExecutor(1, 1, 50L, TimeUnit.MILLISECONDS, this.threadQueue);

        this.imageType = ImageType.SB_U8;
        this.colorFormat = ColorFormat.RGB;
        this.stackImage = imageType.createImage(1, 1);
        this.array = new GrowArray(DogArray_I8::new);
    }

    @OptIn(markerClass = ExperimentalCamera2Interop.class) @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.preview);

        ConfigAztecCode config = new ConfigAztecCode();

        detector = FactoryFiducial.aztec(config, GrayU8.class);

        preview = findViewById(R.id.previewView);
        indicator1 = findViewById(R.id.indicator1);
        indicator2 = findViewById(R.id.indicator2);
        indicator3 = findViewById(R.id.indicator3);

        status = findViewById(R.id.status);

        button = findViewById(R.id.button);
        button.setOnClickListener((e) -> {
            state = (state + 1) % 3;
            refreshStateDescription();
        });
        refreshStateDescription();

        BarcodeScannerOptions barcodeOptions = new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_AZTEC, Barcode.FORMAT_QR_CODE)
                .build();

        scanner = BarcodeScanning.getClient(barcodeOptions);

        cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            Log.i(LOG_TAG, "Configure camera..");
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                ImageAnalysis.Builder builder = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(1080, 768))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)

                        ;

                // https://stackoverflow.com/questions/57485050/how-to-increase-frame-rate-with-android-camerax-imageanalysis
                // https://stackoverflow.com/questions/71953493/how-to-set-exposure-on-camera2-api

                Camera2Interop.Extender ext = new Camera2Interop.Extender<>(builder);
                //ext.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                        //ext.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(30, 30))
                        //.setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF)
                //ext.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
                        //.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                ext.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT);
                //ext.setCaptureRequestOption(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, -10);

                ext.setCaptureRequestOption(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO);

                ext.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(15, 30));

                ext.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, 100); // LOWER SEEMS TO BE LESS AFFECTED BY DISPLAY REFRESH EFFECTS
                ext.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                ext.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);

                ImageAnalysis imageAnalysis = builder.build();

                imageAnalysis.setAnalyzer(threadPool, new CompareBarcodeAnalyzer());

                bindPreview(cameraProvider, imageAnalysis);

            } catch (Exception e) {
                // No errors need to be handled for this Future.
                // This should never be reached.
                Log.e(LOG_TAG, "Problem setting up image analysis");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void refreshStateDescription() {
        switch (state) {
            case STATE_CONCURRENT: {
                status.setText("Concurrent");
                break;
            }
            case STATE_BOOTCV_THEN_MLKIT: {
                status.setText("bootcv then ml-kit");
                break;
            }
            case STATE_MLKIT_THEN_BOOTCV: {
                status.setText("ml-kit then bootcv");
                break;
            }
        }
    }

    void bindPreview(ProcessCameraProvider cameraProvider, ImageAnalysis imageAnalysis) {
        Preview preview = new Preview.Builder().build();

        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build();

        preview.setSurfaceProvider(this.preview.getSurfaceProvider());

        Camera camera = cameraProvider.bindToLifecycle((LifecycleOwner) this, cameraSelector, preview, imageAnalysis);
        CameraInfo cameraInfo = camera.getCameraInfo();

        // https://medium.com/androiddevelopers/using-camerax-exposure-compensation-api-11fd75785bf
        CameraControl cameraControl = camera.getCameraControl();

        this.preview.setScaleType(PreviewView.ScaleType.FILL_CENTER);
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    public void setIndicator1Color(int color) {
        runOnUiThread(() -> {
            indicator1.setBackgroundColor(color);
        });
    }

    public void setIndicator3Color(int color) {
        runOnUiThread(() -> {
            indicator3.setBackgroundColor(color);
        });
    }

    public void setIndicator2Color(int color) {
        runOnUiThread(() -> {
            indicator2.setBackgroundColor(color);
        });
    }

    protected List<AztecCode> processImage(ImageBase input) {
        detector.process((GrayU8) input);

        return detector.getDetections();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        threadPool.shutdownNow();
    }


    private List<AztecCode> getBootcv(Image mediaImage) {
        //Log.i(LOG_TAG, "Process bootcv image..");

        ConvertCameraImage.imageToBoof(mediaImage, colorFormat, stackImage, array);

        return processImage(stackImage);
    }

    private List<Barcode> getMlKit(Image mediaImage) throws InterruptedException {
        InputImage image = InputImage.fromMediaImage(mediaImage, 0);

        CountDownLatch countDownLatch = new CountDownLatch(1);

        Task<List<Barcode>> listTask = scanner.process(image)
                .addOnFailureListener((e) -> {
                    Log.w(LOG_TAG, "Problem scanning ml kit", e);
                })
                .addOnCompleteListener((e) -> {
                    countDownLatch.countDown();
                });

        countDownLatch.await();

        List<Barcode> barcodes = listTask.getResult();
        return barcodes;
    }


}