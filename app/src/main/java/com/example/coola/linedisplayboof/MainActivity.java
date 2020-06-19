package com.example.coola.linedisplayboof;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Size;
import android.view.View;

import com.example.coola.linedisplayboof.detect.CannyEdgeActivity;
import com.example.coola.linedisplayboof.segmentation.ColorHistogramSegmentationActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.Arrays;
import java.util.List;

import boofcv.io.calibration.CalibrationIO;
import boofcv.struct.calib.CameraPinholeBrown;

public class MainActivity extends AppCompatActivity {


    public static final String TAG = "DemoMain";

    boolean waitingCameraPermissions = true;

    DemoApplication app;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);



        app = (DemoApplication)getApplication();
        if( app == null )
            throw new RuntimeException("App is null!");

        try {
            loadCameraSpecs();
        } catch( NoClassDefFoundError e ) {
            // Some people like trying to run this app on really old versions of android and
            // seem to enjoy crashing and reporting the errors.
            e.printStackTrace();
            abortDialog("Camera2 API Required");
            return;
        }
//
//        Intent canny = new Intent(this, CannyEdgeActivity.class);
//        startActivity(canny);

    }

    @Override
    protected void onResume() {
        super.onResume();
        if( !waitingCameraPermissions && app.changedPreferences ) {
            loadIntrinsics(this,app.preference.cameraId, app.preference.calibration,null);
        }
    }

    private void loadCameraSpecs() {
        int permissionCheck = ContextCompat.checkSelfPermission(this,
                Manifest.permission.CAMERA);

        if( permissionCheck != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA},
                    0);
        } else {
            waitingCameraPermissions = false;

            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            if( manager == null )
                throw new RuntimeException("No cameras?!");
            try {
                String[] cameras = manager.getCameraIdList();

                for ( String cameraId : cameras ) {
                    CameraSpecs c = new CameraSpecs();
                    app.specs.add(c);
                    c.deviceId = cameraId;
                    CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
                    Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                    c.facingBack = facing != null && facing==CameraCharacteristics.LENS_FACING_BACK;
                    StreamConfigurationMap map = characteristics.
                            get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    if (map == null) {
                        continue;
                    }
                    Size[] sizes = map.getOutputSizes(ImageFormat.YUV_420_888);
                    if( sizes == null )
                        continue;
                    c.sizes.addAll(Arrays.asList(sizes));
                }
            } catch (CameraAccessException e) {
                throw new RuntimeException("No camera access??? Wasn't it just granted?");
            }

            // Now that it can read the camera set the default settings
            setDefaultPreferences();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String permissions[], int[] grantResults) {
        switch (requestCode) {
            case 0: {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    loadCameraSpecs();
                    setDefaultPreferences();
                } else {
                    dialogNoCameraPermission();
                }
                return;
            }
        }
    }

    private void setDefaultPreferences() {
        app.preference.showSpeed = false;
        app.preference.autoReduce = true;

        // There are no cameras.  This is possible due to the hardware camera setting being set to false
        // which was a work around a bad design decision where front facing cameras wouldn't be accepted as hardware
        // which is an issue on tablets with only front facing cameras
        if( app.specs.size() == 0 ) {
            dialogNoCamera();
        }
        // select a front facing camera as the default
        for (int i = 0; i < app.specs.size(); i++) {
            CameraSpecs c = app.specs.get(i);

            app.preference.cameraId = c.deviceId;
            if( c.facingBack) {
                break;
            }
        }

        if( !app.specs.isEmpty() ) {
            loadIntrinsics(this, app.preference.cameraId, app.preference.calibration,null);
        }
    }

    private void dialogNoCamera() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setMessage("Your device has no cameras!")
                .setCancelable(false)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        System.exit(0);
                    }
                });
        AlertDialog alert = builder.create();
        alert.show();
    }

    private void dialogNoCameraPermission() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setMessage("Denied access to the camera! Exiting.")
                .setCancelable(false)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        System.exit(0);
                    }
                });
        AlertDialog alert = builder.create();
        alert.show();
    }

    public static void loadIntrinsics(Activity activity,
                                      String cameraId,
                                      List<CameraPinholeBrown> intrinsics,
                                      List<File> locations ) {
        intrinsics.clear();
        if( locations != null )
            locations.clear();

        File directory = new File(getExternalDirectory(activity),"calibration");
        if( !directory.exists() )
            return;
        File files[] = directory.listFiles();
        if( files == null )
            return;
        String prefix = "camera"+cameraId;
        for( File f : files ) {
            if( !f.getName().startsWith(prefix))
                continue;
            try {
                FileInputStream fos = new FileInputStream(f);
                Reader reader = new InputStreamReader(fos);
                CameraPinholeBrown intrinsic = CalibrationIO.load(reader);
                intrinsics.add(intrinsic);
                if( locations != null ) {
                    locations.add(f);
                }
            } catch( RuntimeException | FileNotFoundException ignore ) {}
        }
    }

    public static File getExternalDirectory( Activity activity ) {
        // if possible use a public directory. If that fails use a private one
//		if(Objects.equals(Environment.getExternalStorageState(), Environment.MEDIA_MOUNTED)) {
//			File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
//			if( !dir.exists() )
//				dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
//			return new File(dir,"org.boofcv.android");
//		} else {
        return activity.getExternalFilesDir(null);
//		}
    }


    public static CameraSpecs defaultCameraSpecs( DemoApplication app ) {
        for(int i = 0; i < app.specs.size(); i++ ) {
            CameraSpecs s = app.specs.get(i);
            if( s.deviceId.equals(app.preference.cameraId))
                return s;
        }
        throw new RuntimeException("Can't find default camera");
    }

    /**
     * Displays a warning dialog and then exits the activity
     */
    private void abortDialog( String message ) {
        AlertDialog alertDialog = new AlertDialog.Builder(this).create();
        alertDialog.setTitle("Fatal error");
        alertDialog.setMessage(message);
        alertDialog.setButton(AlertDialog.BUTTON_NEUTRAL, "OK",
                (dialog, which) -> {
                    dialog.dismiss();
                    this.finish();
                });
        alertDialog.show();
    }

    public void startColourSeg(View view) {
        Intent canny = new Intent(this, ColorHistogramSegmentationActivity.class);
        startActivity(canny);
    }

    public void startCannyEdge(View view) {
        Intent canny = new Intent(this, CannyEdgeActivity.class);
        startActivity(canny);
    }
}
