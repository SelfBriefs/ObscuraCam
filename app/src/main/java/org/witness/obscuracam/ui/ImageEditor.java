package org.witness.obscuracam.ui;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ContentUris;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.PixelFormat;
import android.graphics.PointF;
import android.graphics.RectF;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.os.Vibrator;
import android.provider.MediaStore;
import com.google.android.material.snackbar.Snackbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnTouchListener;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Toast;

import org.witness.obscuracam.ui.adapters.ImageRegionOptionsRecyclerViewAdapter;
import org.witness.obscuracam.photo.detect.AndroidFaceDetection;
import org.witness.obscuracam.photo.detect.DetectedFace;
import org.witness.obscuracam.photo.detect.FaceDetection;
import org.witness.obscuracam.photo.filters.MaskObscure;
import org.witness.obscuracam.photo.filters.RegionProcesser;
import org.witness.obscuracam.photo.jpegredaction.JpegRedaction;
import org.witness.obscuracam.ObscuraApp;
import org.witness.sscphase1.BuildConfig;
import org.witness.sscphase1.R;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;

public class ImageEditor extends AppCompatActivity implements OnTouchListener, OnClickListener, ImageRegionOptionsRecyclerViewAdapter.ImageRegionOptionsRecyclerViewAdapterListener {

	private static final int WRITE_EXTERNAL_STORAGE_PERMISSION_REQUEST = 1;

	public final static String MIME_TYPE_JPEG = "image/jpeg";

	// Colors for region squares

	public final static int DRAW_COLOR = 0x00000000;
	public final static int DETECTED_COLOR = 0x00000000;
	public final static int OBSCURED_COLOR = 0x00000000;

	// Constants for the menu items, currently these are in an XML file (menu/image_editor_menu.xml, strings.xml)
	public final static int ABOUT_MENU_ITEM = 0;
	public final static int DELETE_ORIGINAL_MENU_ITEM = 1;
	public final static int SAVE_MENU_ITEM = 2;
	public final static int SHARE_MENU_ITEM = 3;
	public final static int NEW_REGION_MENU_ITEM = 4;

	// Selection sizes in DP
	public final static int SELECTION_BORDER_WIDTH = 5;
	public final static int SELECTION_HANDLE_RADIUS = 10;
	public final static int SELECTION_HANDLE_TOUCH_RADIUS = 15;

	// Constants for Informa
	public final static int FROM_INFORMA = 100;
	public final static String LOG = "[Image Editor ********************]";

	// Image Matrix
	Matrix matrix = new Matrix();
	Matrix matrix_inverted = new Matrix();

	// Saved Matrix for not allowing a current operation (over max zoom)
	Matrix savedMatrix = new Matrix();

	// We can be in one of these 3 states
	static final int NONE = 0;
	static final int DRAG = 1;
	static final int ZOOM = 2;
	static final int TAP = 3;
	int mode = NONE;


	// Maximum zoom scale
	static final float MAX_SCALE = 10f;

	// Constant for autodetection dialog
	static final int DIALOG_DO_AUTODETECTION = 0;

	// For Zooming
	float startFingerSpacing = 0f;
	float endFingerSpacing = 0f;
	PointF startFingerSpacingMidPoint = new PointF();

	// For Dragging
	PointF startPoint = new PointF();

	// Don't allow it to move until the finger moves more than this amount
	// Later in the code, the minMoveDistance in real pixels is calculated
	// to account for different touch screen resolutions
	float minMoveDistanceDP = 5f;
	float minMoveDistance; // = ViewConfiguration.get(this).getScaledTouchSlop();

	// zoom in and zoom out buttons
	Button zoomIn, zoomOut, btnSave, btnShare, btnPreview, btnNew;

	// ImageView for the original (scaled) image
	ImageView imageView;


	// Bitmap for the original image (scaled)
	Bitmap imageBitmap;

	// Bitmap for holding the realtime obscured image
	Bitmap obscuredBmp;

	// Canvas for drawing the realtime obscuring
	Canvas obscuredCanvas;

	// Paint obscured
	Paint obscuredPaint;

	//bitmaps for corners
    /*
    private final static float CORNER_SIZE = 26;
    
    Bitmap bitmapCornerUL;
    Bitmap bitmapCornerUR;
    Bitmap bitmapCornerLL;
    Bitmap bitmapCornerLR;*/


	// Vector to hold ImageRegions 
	ArrayList<ImageRegion> imageRegions = new ArrayList<ImageRegion>();

	// The original image dimensions (not scaled)
	int originalImageWidth;
	int originalImageHeight;

	// So we can give some haptic feedback to the user
	Vibrator vibe;

	// Original Image Uri
	Uri originalImageUri;

	// sample sized used to downsize from native photo
	int inSampleSize;

	// Saved Image Uri
	Uri savedImageUri;

	// Constant for temp filename
	public final static String TMP_FILE_NAME = "tmp.jpg";

	public final static String TMP_FILE_DIRECTORY = "/Android/data/org.witness.sscphase1/files/";


	//handles threaded events for the UI thread
	private Handler mHandler = new Handler() {

		@Override
		public void handleMessage(Message msg) {
			super.handleMessage(msg);


			switch (msg.what) {

				case 3: //completed
					mProgressDialog.dismiss();

					//Toast autodetectedToast = Toast.makeText(ImageEditor.this, result + " face(s) detected", Toast.LENGTH_SHORT);
					//autodetectedToast.show();

					break;
				default:
					super.handleMessage(msg);
			}
		}

	};

	//UI for background threads
	ProgressDialog mProgressDialog;

	// Handles when we should do realtime preview and when we shouldn't
	private boolean doRealtimePreview = true;
	private boolean needsUpdate = true;

	// Keep track of the orientation
	private int originalImageOrientation = ExifInterface.ORIENTATION_NORMAL;

	// for saving images
	private final static String EXPORT_DATE_FORMAT = "yyyy-MM-dd HH:mm:ss";

	private View imageViewOverlay;
	private RecyclerView recyclerViewRegionOptions;

    /*
	 private class mAutoDetectTask extends AsyncTask<Integer, Integer, Long> {
	     protected Long doInBackground(Integer... params) {
	    	  return (long)doAutoDetection();	         
	     }

	     protected void onProgressUpdate(Integer... progress) {
	       
	     }

	     protected void onPostExecute(Long result) {
	     
	    	mProgressDialog.dismiss();
	    	 
	 		Toast autodetectedToast = Toast.makeText(ImageEditor.this, result + " face(s) detected", Toast.LENGTH_SHORT);
	 		autodetectedToast.show();
	     }
	 }*/


	@SuppressWarnings("unused")
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

        getSupportActionBar().setTitle("");
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

		matrix.invert(matrix_inverted);

		String versNum = "";

		try {
			String pkg = getPackageName();
			versNum = getPackageManager().getPackageInfo(pkg, 0).versionName;
		} catch (Exception e) {
			versNum = "";
		}

		setTitle(getString(R.string.app_name) + " (" + versNum + ")");

		//   requestWindowFeature(Window.FEATURE_NO_TITLE);
		setContentView(R.layout.imageviewer);

		// Calculate the minimum distance
		minMoveDistance = minMoveDistanceDP * this.getResources().getDisplayMetrics().density + 0.5f;

		// The ImageView that contains the image we are working with
		imageView = (ImageView) findViewById(R.id.ImageEditorImageView);
		imageViewOverlay = new RegionOverlayView(this);
		imageViewOverlay.setBackgroundColor(0x01000000);
		((ViewGroup) imageView.getParent()).addView(imageViewOverlay);
		imageView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
			@Override
			public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) {
				ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) imageViewOverlay.getLayoutParams();
				params.leftMargin = left;
				params.topMargin = top;
				params.width = (right - left);
				params.height = (bottom - top);
				imageViewOverlay.setLayoutParams(params);
			}
		});

		recyclerViewRegionOptions = (RecyclerView) findViewById(R.id.recycler_view_region_options);
		recyclerViewRegionOptions.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
		ImageRegionOptionsRecyclerViewAdapter adapter = new ImageRegionOptionsRecyclerViewAdapter(this);
		adapter.setListener(this);
		recyclerViewRegionOptions.setAdapter(adapter);
		recyclerViewRegionOptions.bringToFront();
		recyclerViewRegionOptions.setVisibility(View.GONE);

		// Buttons for zooming
		zoomIn = (Button) this.findViewById(R.id.ZoomIn);
		zoomOut = (Button) this.findViewById(R.id.ZoomOut);
		zoomIn.setOnClickListener(this);
		zoomOut.setOnClickListener(this);
		
		/*
		btnNew = (Button) this.findViewById(R.id.New);
		btnSave = (Button) this.findViewById(R.id.Save);
		btnShare = (Button) this.findViewById(R.id.Share);
		btnPreview = (Button) this.findViewById(R.id.Preview);
		
		// this, ImageEditor will be the onClickListener for the buttons
	
		btnNew.setOnClickListener(this);
		btnSave.setOnClickListener(this);
		btnShare.setOnClickListener(this);
		btnPreview.setOnClickListener(this);
	*/

		// Instantiate the vibrator
		vibe = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

		// Passed in from CameraObscuraMainMenu
		originalImageUri = getIntent().getData();

		// If originalImageUri is null, we are likely coming from another app via "share"
		if (originalImageUri == null) {
			if (getIntent().hasExtra(Intent.EXTRA_STREAM)) {
				originalImageUri = (Uri) getIntent().getExtras().get(Intent.EXTRA_STREAM);
			} else if (getIntent().hasExtra("bitmap")) {
				Bitmap b = (Bitmap) getIntent().getExtras().get("bitmap");
				setBitmap(b);

				boolean autodetect = true;

				if (autodetect) {

					mProgressDialog = ProgressDialog.show(this, "", "Detecting faces...", true, true);

					doAutoDetectionThread();


				}

				originalImageWidth = b.getWidth();
				originalImageHeight = b.getHeight();
				return;

			}
		}


		// Load the image if it isn't null
		if (originalImageUri != null) {

//			try {
//				InputStream is;
//				if (originalImageUri.getScheme() != null && originalImageUri.getScheme().contentEquals("content"))
//					is = getContentResolver().openInputStream(originalImageUri);
//				else
//					is = new FileInputStream(new File(originalImageUri.toString()));
//				if (is != null) {
//					// Get orientation of image
//					try {
//						ExifInterface ei = new ExifInterface(is);
//						originalImageOrientation = ei.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
//						debug(ObscuraApp.TAG,"Orientation: " + originalImageOrientation);
//					} catch (IOException e1) {
//						debug(ObscuraApp.TAG,"Couldn't get Orientation");
//						e1.printStackTrace();
//					}
//					is.close();
//				}
//			} catch (Exception e) {
//				e.printStackTrace();
//			}
//		}


			// Get the orientation
			File originalFilename = pullPathFromUri(originalImageUri);
			try {
				ExifInterface ei = new ExifInterface(originalFilename.getAbsolutePath());
				originalImageOrientation = ei.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
				debug(ObscuraApp.TAG, "Orientation: " + originalImageOrientation);
			} catch (IOException e1) {
				debug(ObscuraApp.TAG, "Couldn't get Orientation");
				e1.printStackTrace();
			}

			//debug(ObscuraApp.TAG,"loading uri: " + pullPathFromUri(originalImageUri));

			// Load up smaller image
			try {
				// Load up the image's dimensions not the image itself
				BitmapFactory.Options bmpFactoryOptions = new BitmapFactory.Options();
				bmpFactoryOptions.inJustDecodeBounds = true;
				// Needs to be this config for Google Face Detection 
				bmpFactoryOptions.inPreferredConfig = Bitmap.Config.RGB_565;
				// Parse the image
				InputStream inputStream = streamFromUri(originalImageUri);
				Bitmap loadedBitmap = BitmapFactory.decodeStream(inputStream, null, bmpFactoryOptions);
				inputStream.close();

				// Hold onto the unscaled dimensions
				originalImageWidth = bmpFactoryOptions.outWidth;
				originalImageHeight = bmpFactoryOptions.outHeight;
				// If it is rotated, transpose the width and height
				// Should probably look to see if there are different rotation constants being used
				if (originalImageOrientation == ExifInterface.ORIENTATION_ROTATE_90
						|| originalImageOrientation == ExifInterface.ORIENTATION_ROTATE_270) {
					int tmpWidth = originalImageWidth;
					originalImageWidth = originalImageHeight;
					originalImageHeight = tmpWidth;
				}

				// Get the current display to calculate ratios
				Display currentDisplay = getWindowManager().getDefaultDisplay();

				// Ratios between the display and the image
                /**
				double widthRatio = Math.floor(bmpFactoryOptions.outWidth / currentDisplay.getWidth());
				double heightRatio = Math.floor(bmpFactoryOptions.outHeight / currentDisplay.getHeight());

				// If both of the ratios are greater than 1,
				// one of the sides of the image is greater than the screen
				if (heightRatio > widthRatio) {
					// Height ratio is larger, scale according to it
					inSampleSize = (int) heightRatio;
				} else {
					// Width ratio is larger, scale according to it
					inSampleSize = (int) widthRatio;
				}

				bmpFactoryOptions.inSampleSize = inSampleSize;
                 **/

                bmpFactoryOptions.inSampleSize = 2;
                boolean notLoaded = true;

                while (notLoaded) {

                    try {
                        // Decode it for real
                        bmpFactoryOptions.inJustDecodeBounds = false;
                        inputStream = streamFromUri(originalImageUri);
                        loadedBitmap = BitmapFactory.decodeStream(inputStream, null, bmpFactoryOptions);
                        inputStream.close();
                        debug(ObscuraApp.TAG, "Was: " + loadedBitmap.getConfig());
                        notLoaded = false;
                    }
                    catch (OutOfMemoryError oom)
                    {
                        bmpFactoryOptions.inSampleSize *= 2;
                        //try again but smaller!
                    }
                }

				if (loadedBitmap == null) {
					debug(ObscuraApp.TAG, "bmp is null");

				} else {
					// Only dealing with 90 and 270 degree rotations, might need to check for others
					if (originalImageOrientation == ExifInterface.ORIENTATION_ROTATE_90) {
						debug(ObscuraApp.TAG, "Rotating Bitmap 90");
						Matrix rotateMatrix = new Matrix();
						rotateMatrix.postRotate(90);
						loadedBitmap = Bitmap.createBitmap(loadedBitmap, 0, 0, loadedBitmap.getWidth(), loadedBitmap.getHeight(), rotateMatrix, false);
					} else if (originalImageOrientation == ExifInterface.ORIENTATION_ROTATE_270) {
						debug(ObscuraApp.TAG, "Rotating Bitmap 270");
						Matrix rotateMatrix = new Matrix();
						rotateMatrix.postRotate(270);
						loadedBitmap = Bitmap.createBitmap(loadedBitmap, 0, 0, loadedBitmap.getWidth(), loadedBitmap.getHeight(), rotateMatrix, false);
					}

					setBitmap(loadedBitmap);

					boolean autodetect = true;

					if (autodetect) {
						// Do auto detect popup

						mProgressDialog = ProgressDialog.show(this, "", "Detecting faces...", true, true);

						doAutoDetectionThread();
					}
				}
			} catch (Exception e) {
				Log.e(ObscuraApp.TAG, "error loading bitmap from Uri: " + e.getMessage(), e);
			}


		}
		
		/*
		bitmapCornerUL = BitmapFactory.decodeResource(getResources(),
                R.drawable.edit_region_corner_ul);
		bitmapCornerUR = BitmapFactory.decodeResource(getResources(),
                R.drawable.edit_region_corner_ur);
		bitmapCornerLL = BitmapFactory.decodeResource(getResources(),
                R.drawable.edit_region_corner_ll);
		bitmapCornerLR = BitmapFactory.decodeResource(getResources(),
                R.drawable.edit_region_corner_lr);
		 */
	}

	private void setBitmap(Bitmap nBitmap) {
		imageBitmap = nBitmap;

		// Get the current display to calculate ratios
		Display currentDisplay = getWindowManager().getDefaultDisplay();

		float matrixWidthRatio = (float) currentDisplay.getWidth() / (float) imageBitmap.getWidth();
		float matrixHeightRatio = (float) currentDisplay.getHeight() / (float) imageBitmap.getHeight();

		// Setup the imageView and matrix for scaling
		float matrixScale = matrixHeightRatio;

		if (matrixWidthRatio < matrixHeightRatio) {
			matrixScale = matrixWidthRatio;
		}

//		BitmapDrawable d = new BitmapDrawable(getResources(), imageBitmap);
//		Drawable d2 = new Drawable() {
//			private Paint paint;
//
//			@Override
//			public void draw(@NonNull Canvas canvas) {
//				try {
//					if (paint == null) {
//						paint = new Paint();
//						paint.setStyle(Style.STROKE);
//						paint.setStrokeWidth(10f);
//					}
//					for (ImageRegion currentRegion : imageRegions) {
//						RectF regionRect = currentRegion.getBounds();
//						if (currentRegion.isSelected())
//							paint.setColor(Color.CYAN);
//						else
//							paint.setColor(Color.YELLOW);
//						obscuredCanvas.drawRect(regionRect, paint);
//
//						// Draw drag handles
//						int handleRadius = 10;
//						if (currentRegion.isSelected()) {
//							obscuredCanvas.drawCircle(regionRect.centerX(), regionRect.top, handleRadius, paint);
//							obscuredCanvas.drawCircle(regionRect.centerX(), regionRect.bottom, handleRadius, paint);
//							obscuredCanvas.drawCircle(regionRect.left, regionRect.centerY(), handleRadius, paint);
//							obscuredCanvas.drawCircle(regionRect.right, regionRect.centerY(), handleRadius, paint);
//						}
//					}
//				} catch (Exception ignored) {
//				}
//			}
//
//			@Override
//			public void setAlpha(@IntRange(from = 0, to = 255) int alpha) {
//			}
//
//			@Override
//			public void setColorFilter(@Nullable ColorFilter colorFilter) {
//
//			}
//
//			@Override
//			public int getOpacity() {
//				return PixelFormat.TRANSLUCENT;
//			}
//		};
//		d2.setBounds(d.getBounds());
//		LayerDrawable ld = new LayerDrawable(new Drawable[] { d, d2 });
//		ld.setBounds(d.getBounds());
//		imageView.setImageDrawable(ld);
		imageView.setImageBitmap(imageBitmap);

		// Set the OnTouch and OnLongClick listeners to this (ImageEditor)
		imageView.setOnTouchListener(this);
		imageView.setOnClickListener(this);
		imageView.setSoundEffectsEnabled(false);

		//PointF midpoint = new PointF((float)imageBitmap.getWidth()/2f, (float)imageBitmap.getHeight()/2f);
		matrix.postScale(matrixScale, matrixScale);

		// This doesn't completely center the image but it get's closer
		//int fudge = 42;
		matrix.postTranslate((float) ((float) currentDisplay.getWidth() - (float) imageBitmap.getWidth() * (float) matrixScale) / 2f, (float) ((float) currentDisplay.getHeight() - (float) imageBitmap.getHeight() * matrixScale) / 2f);

		imageView.setImageMatrix(matrix);


	}

	public Matrix getMatrix() {
		return matrix;
	}

	public Matrix getMatrixInverted() {
		return matrix_inverted;
	}

	/*
	 * Call this to delete the original image, will ask the user
	 */
	private void showDeleteOriginalDialog() {
		final AlertDialog.Builder b = new AlertDialog.Builder(this);
		b.setIcon(android.R.drawable.ic_dialog_alert);
		b.setTitle(getString(R.string.app_name));
		b.setMessage(getString(R.string.confirm_delete));
		b.setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int whichButton) {

				try {
					// User clicked OK so go ahead and delete
					deleteOriginal();
					viewImage(savedImageUri);
				} catch (IOException e) {
					Log.e(ObscuraApp.TAG, "error saving", e);
				} finally {
					finish();
				}
			}
		});
		b.setNegativeButton(R.string.no, new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int whichButton) {

				viewImage(savedImageUri);
			}
		});
		b.show();
	}

	/*
	 * Actual deletion of original
	 */
	private void deleteOriginal() throws IOException {

		if (originalImageUri != null) {

			String origFilePath = originalImageUri.getPath();
			File fileOrig = new File(origFilePath);

			if (fileOrig.exists()) {

				String[] columnsToSelect = {MediaStore.Images.Media._ID, MediaStore.Images.Media.DATA};

				Uri[] uriBases = {MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Images.Media.INTERNAL_CONTENT_URI};

				for (Uri uriBase : uriBases) {

					Cursor imageCursor = getContentResolver().query(uriBase, columnsToSelect, MediaStore.Images.Media.DATA + " = ?", new String[]{origFilePath}, null);
					//Cursor imageCursor = getContentResolver().query(uriBase, columnsToSelect, MediaStore.Images.Media.DATE_TAKEN + " = ?",  new String[] {dateTaken+""}, null );

					while (imageCursor.moveToNext()) {

						long _id = imageCursor.getLong(imageCursor.getColumnIndex(MediaStore.Images.Media._ID));

						getContentResolver().delete(ContentUris.withAppendedId(uriBase, _id), null, null);

					}
				}

				if (fileOrig.exists())
					fileOrig.delete();

			} else {
				getContentResolver().delete(originalImageUri, null, null);
			}
		}

		originalImageUri = null;
	}

	private void doAutoDetectionThread() {
		Thread thread = new Thread() {
			public void run() {
				doAutoDetection();
				Message msg = mHandler.obtainMessage(3);
				mHandler.sendMessage(msg);
			}
		};
		thread.start();
	}
	/*
	 * Do actual auto detection and create regions
	 * 
	 * public void createImageRegion(int _scaledStartX, int _scaledStartY, 
			int _scaledEndX, int _scaledEndY, 
			int _scaledImageWidth, int _scaledImageHeight, 
			int _imageWidth, int _imageHeight, 
			int _backgroundColor) {
	 */

	private int doAutoDetection() {
		// This should be called via a pop-up/alert mechanism

		ArrayList<DetectedFace> dFaces = runFaceDetection();

		if (dFaces == null)
			return 0;

		//	for (int adr = 0; adr < autodetectedRects.length; adr++) {

		Iterator<DetectedFace> itDFace = dFaces.iterator();

		while (itDFace.hasNext()) {
			DetectedFace dFace = itDFace.next();

			//debug(ObscuraApp.TAG,"AUTODETECTED imageView Width, Height: " + imageView.getWidth() + " " + imageView.getHeight());
			//debug(ObscuraApp.TAG,"UNSCALED RECT:" + autodetectedRects[adr].left + " " + autodetectedRects[adr].top + " " + autodetectedRects[adr].right + " " + autodetectedRects[adr].bottom);

			RectF autodetectedRectScaled = new RectF(dFace.bounds.left, dFace.bounds.top, dFace.bounds.right, dFace.bounds.bottom);

			//debug(ObscuraApp.TAG,"SCALED RECT:" + autodetectedRectScaled.left + " " + autodetectedRectScaled.top + " " + autodetectedRectScaled.right + " " + autodetectedRectScaled.bottom);

			// Probably need to map autodetectedRects to scaled rects
			//debug(ObscuraApp.TAG,"MAPPED RECT:" + autodetectedRects[adr].left + " " + autodetectedRects[adr].top + " " + autodetectedRects[adr].right + " " + autodetectedRects[adr].bottom);

			float faceBuffer = (autodetectedRectScaled.right - autodetectedRectScaled.left) / 5;

			boolean isLast = !itDFace.hasNext();

			createImageRegion(
					(autodetectedRectScaled.left - faceBuffer),
					(autodetectedRectScaled.top - faceBuffer),
					(autodetectedRectScaled.right + faceBuffer),
					(autodetectedRectScaled.bottom + faceBuffer),
					isLast,
					isLast);
		}

		return dFaces.size();
	}

	/*
	 * The actual face detection calling method
	 */
	private ArrayList<DetectedFace> runFaceDetection() {
		ArrayList<DetectedFace> dFaces = new ArrayList<DetectedFace>();

		try {

			FaceDetection fd = new AndroidFaceDetection(imageBitmap.getWidth(), imageBitmap.getHeight());
			int numFaces = fd.findFaces(imageBitmap);

			if (numFaces > 0)
				dFaces.addAll(fd.getFaces(numFaces));
            else
            {
                fd.release();
                Bitmap imageGrayScale = toGrayscale(imageBitmap);
                fd = new AndroidFaceDetection(imageGrayScale.getWidth(), imageGrayScale.getHeight());
                numFaces = fd.findFaces(imageGrayScale);
                if (numFaces > 0)
                    dFaces.addAll(fd.getFaces(numFaces));
            }

			fd.release();

		} catch (NullPointerException e) {
			dFaces = null;
            Log.e(getClass().getName(),"error detecting faces",e);
		}
		return dFaces;
	}

	public Bitmap toGrayscale(Bitmap bmpOriginal) {
		int width, height;
		height = bmpOriginal.getHeight();
		width = bmpOriginal.getWidth();

		Bitmap bmpGrayscale = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
		Canvas c = new Canvas(bmpGrayscale);
		Paint paint = new Paint();
		ColorMatrix cm = new ColorMatrix();
		cm.setSaturation(0);

		ColorMatrixColorFilter f = new ColorMatrixColorFilter(cm);

		paint.setColorFilter(f);

		c.drawBitmap(bmpOriginal, 0, 0, paint);


		return bmpGrayscale;
	}

	public static Bitmap createContrast(Bitmap src, double value) {
		// image size
		int width = src.getWidth();
		int height = src.getHeight();
		// create output bitmap
		Bitmap bmOut = Bitmap.createBitmap(width, height, src.getConfig());
		// color information
		int A, R, G, B;
		int pixel;
		// get contrast value
		double contrast = Math.pow((100 + value) / 100, 2);

		// scan through all pixels
		for (int x = 0; x < width; ++x) {
			for (int y = 0; y < height; ++y) {
				// get pixel color
				pixel = src.getPixel(x, y);
				A = Color.alpha(pixel);
				// apply filter contrast for every channel R, G, B
				R = Color.red(pixel);
				R = (int) (((((R / 255.0) - 0.5) * contrast) + 0.5) * 255.0);
				if (R < 0) {
					R = 0;
				} else if (R > 255) {
					R = 255;
				}

				G = Color.red(pixel);
				G = (int) (((((G / 255.0) - 0.5) * contrast) + 0.5) * 255.0);
				if (G < 0) {
					G = 0;
				} else if (G > 255) {
					G = 255;
				}

				B = Color.red(pixel);
				B = (int) (((((B / 255.0) - 0.5) * contrast) + 0.5) * 255.0);
				if (B < 0) {
					B = 0;
				} else if (B > 255) {
					B = 255;
				}

				// set new pixel color to output bitmap
				bmOut.setPixel(x, y, Color.argb(A, R, G, B));
			}
		}

		// return final image
		return bmOut;
	}


	ImageRegion currRegion = null;

	private ImageRegion getCurrentRegion() {
		return currRegion;
	}

	private void setCurrentRegion(ImageRegion region) {
		currRegion = region;
		for (ImageRegion ir : imageRegions) {
			ir.setSelected(ir == region);
		}
		// Reorder to the front
		if (imageRegions.remove(region)) {
			imageRegions.add(region);
		}
		if (region != null) {
			((ImageRegionOptionsRecyclerViewAdapter)recyclerViewRegionOptions.getAdapter()).setCurrentItem(region.mObscureType);
			recyclerViewRegionOptions.setVisibility(View.VISIBLE);
		} else {
			recyclerViewRegionOptions.setVisibility(View.GONE);
		}
	}

	/*
	 * Handles touches on ImageView
	 */
	@Override
	public boolean onTouch(View v, MotionEvent event) {
		boolean handled = false;

		switch (event.getAction() & MotionEvent.ACTION_MASK) {
			case MotionEvent.ACTION_DOWN:
				mode = TAP;
				startPoint.set(event.getX(), event.getY());
				break;

			case MotionEvent.ACTION_POINTER_DOWN:
				// Two Fingers down
				// Get the spacing of the fingers, 2 fingers
				float sx = event.getX(0) - event.getX(1);
				float sy = event.getY(0) - event.getY(1);
				startFingerSpacing = (float) Math.sqrt(sx * sx + sy * sy);

				// Get the midpoint
				float xsum = event.getX(0) + event.getX(1);
				float ysum = event.getY(0) + event.getY(1);
				startFingerSpacingMidPoint.set(xsum / 2, ysum / 2);

				mode = ZOOM;
				setCurrentRegion(null);
				updateDisplayImage();
				break;

			case MotionEvent.ACTION_UP:
				// Single Finger Up
				setRealtimePreview(true);
				needsUpdate = true;
				updateDisplayImage();
				break;

			case MotionEvent.ACTION_MOVE:

				// Calculate distance moved
				float distance = (float) (Math.sqrt(Math.abs(startPoint.x - event.getX()) + Math.abs(startPoint.y - event.getY())));
				//debug(ObscuraApp.TAG,"Move Distance: " + distance);
				//debug(ObscuraApp.TAG,"Min Distance: " + minMoveDistance);

				// If greater than minMoveDistance, it is likely a drag or zoom
				if (distance > minMoveDistance) {

					if (mode == TAP || mode == DRAG) {
						mode = DRAG;

						matrix.postTranslate(event.getX() - startPoint.x, event.getY() - startPoint.y);
						imageView.setImageMatrix(matrix);
//						// Reset the start point
						startPoint.set(event.getX(), event.getY());

						putOnScreen();
						//redrawRegions();

						handled = true;

					} else if (mode == ZOOM) {

						// Save the current matrix so that if zoom goes to big, we can revert
						savedMatrix.set(matrix);

						if (event.getPointerCount() > 1) {
							// Get the spacing of the fingers, 2 fingers
							float ex = event.getX(0) - event.getX(1);
							float ey = event.getY(0) - event.getY(1);
							endFingerSpacing = (float) Math.sqrt(ex * ex + ey * ey);
						} else {
							endFingerSpacing = 0;
						}

						//Log.d(ObscuraApp.TAG, "End Finger Spacing=" + endFingerSpacing);

						// If we moved far enough
						if (endFingerSpacing > minMoveDistance) {

							// Ratio of spacing..  If it was 5 and goes to 10 the image is 2x as big
							float scale = endFingerSpacing / startFingerSpacing;
							// Scale from the midpoint
							matrix.postScale(scale, scale, startFingerSpacingMidPoint.x, startFingerSpacingMidPoint.y);

							// Make sure that the matrix isn't bigger than max scale/zoom
							float[] matrixValues = new float[9];
							matrix.getValues(matrixValues);

							if (matrixValues[0] > MAX_SCALE) {
								matrix.set(savedMatrix);
							}
							imageView.setImageMatrix(matrix);

							putOnScreen();
							//redrawRegions();

							// Reset Start Finger Spacing
							float esx = event.getX(0) - event.getX(1);
							float esy = event.getY(0) - event.getY(1);
							startFingerSpacing = (float) Math.sqrt(esx * esx + esy * esy);
							//Log.d(ObscuraApp.TAG, "New Start Finger Spacing=" + startFingerSpacing);

							// Reset the midpoint
							float x_sum = event.getX(0) + event.getX(1);
							float y_sum = event.getY(0) + event.getY(1);
							startFingerSpacingMidPoint.set(x_sum / 2, y_sum / 2);

							handled = true;
						}
					}
				}
				break;
		}


		return handled; // indicate event was handled
	}

	public void setRealtimePreview(boolean realtimePreview) {
		if (realtimePreview != doRealtimePreview) {
			doRealtimePreview = realtimePreview;
			needsUpdate = true;
		}
	}

	/*
	 * For live previews
	 */
	public void updateDisplayImage() {
		if (needsUpdate) {
			needsUpdate = false;
			if (doRealtimePreview) {
				imageView.setImageBitmap(createObscuredBitmap(imageBitmap.getWidth(), imageBitmap.getHeight(), true));
			} else {
				imageView.setImageBitmap(imageBitmap);
			}
		}
		imageViewOverlay.invalidate();
	}

	public void forceUpdateDisplayImage() {
		needsUpdate = true;
		updateDisplayImage();
	}

	/*
	 * Move the image onto the screen if it has been moved off
	 */
	public void putOnScreen() {
		// Get Rectangle of Tranformed Image
		RectF theRect = getScaleOfImage();

		debug(ObscuraApp.TAG, theRect.width() + " " + theRect.height());

		float deltaX = 0, deltaY = 0;
		if (theRect.width() < imageView.getWidth()) {
			deltaX = (imageView.getWidth() - theRect.width()) / 2 - theRect.left;
		} else if (theRect.left > 0) {
			deltaX = -theRect.left;
		} else if (theRect.right < imageView.getWidth()) {
			deltaX = imageView.getWidth() - theRect.right;
		}

		if (theRect.height() < imageView.getHeight()) {
			deltaY = (imageView.getHeight() - theRect.height()) / 2 - theRect.top;
		} else if (theRect.top > 0) {
			deltaY = -theRect.top;
		} else if (theRect.bottom < imageView.getHeight()) {
			deltaY = imageView.getHeight() - theRect.bottom;
		}

		//debug(ObscuraApp.TAG,"Deltas:" + deltaX + " " + deltaY);

		matrix.postTranslate(deltaX, deltaY);
		imageView.setImageMatrix(matrix);
		matrix_inverted = new Matrix();
		matrix.invert(matrix_inverted);
		updateDisplayImage();
	}

	/*
	 * Create new ImageRegion
	 */
	public ImageRegion createImageRegion(float left, float top, float right, float bottom, boolean showPopup, boolean updateNow) {
		setCurrentRegion(null);

		ImageRegion imageRegion = new ImageRegion(
				this,
				left,
				top,
				right,
				bottom,
				matrix);

		imageRegions.add(imageRegion);

		if (updateNow) {
			mHandler.post(new Runnable() {
				public void run() {
					putOnScreen();
				}
			});
		}

		return imageRegion;
	}

	/*
	 * Delete/Remove specific ImageRegion
	 */
	public void deleteRegion(ImageRegion ir) {
		imageRegions.remove(ir);
		//redrawRegions();
		needsUpdate = true;
		updateDisplayImage();
	}

	/*
	 * Returns the Rectangle of Tranformed Image
	 */
	public RectF getScaleOfImage() {
		RectF theRect = new RectF(0, 0, imageBitmap.getWidth(), imageBitmap.getHeight());
		matrix.mapRect(theRect);
		return theRect;
	}


	/*
	 * Handles normal onClicks for buttons registered to this.
	 * Currently only the zoomIn and zoomOut buttons
	 */
	@Override
	public void onClick(View v) {

		if (v == zoomIn) {
			float scale = 1.5f;

			PointF midpoint = new PointF(imageView.getWidth() / 2, imageView.getHeight() / 2);
			matrix.postScale(scale, scale, midpoint.x, midpoint.y);
			imageView.setImageMatrix(matrix);
			putOnScreen();
		} else if (v == zoomOut) {
			float scale = 0.75f;

			PointF midpoint = new PointF(imageView.getWidth() / 2, imageView.getHeight() / 2);
			matrix.postScale(scale, scale, midpoint.x, midpoint.y);
			imageView.setImageMatrix(matrix);

			putOnScreen();
		} else if (v == btnNew) {
			newDefaultRegion();
		} else if (v == btnPreview) {
			showPreview(true);
		} else if (v == btnSave) {
			//Why does this not show?
			mProgressDialog = ProgressDialog.show(this, "", "Saving...", true, true);

			mHandler.postDelayed(new Runnable() {
				@Override
				public void run() {
					// this will be done in the Pipeline Thread
					checkWritePermissionThenSave();
				}
			}, 500);
		} else if (v == btnShare) {
			// Share Image
			shareImage();
		} else if (mode != DRAG && mode != ZOOM) {
			// Don't create new areas when previewing!
			if (!isPreviewing()) {
				float defaultSize = imageView.getWidth() / 4;
				float halfSize = defaultSize / 2;

				RectF newBox = new RectF();

				newBox.left = startPoint.x - halfSize;
				newBox.top = startPoint.y - halfSize;

				newBox.right = startPoint.x + halfSize;
				newBox.bottom = startPoint.y + halfSize;

				Matrix iMatrix = new Matrix();
				matrix.invert(iMatrix);
				iMatrix.mapRect(newBox);

				ImageRegion region = createImageRegion(newBox.left, newBox.top, newBox.right, newBox.bottom, true, true);
			    setCurrentRegion(region);
			}
		}

	}

	private boolean isPreviewing() {
		return imageViewOverlay.getVisibility() == View.GONE;
	}

	/*
	 * Standard method for menu items.  Uses res/menu/image_editor_menu.xml
	 */
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {

		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.image_editor_menu, menu);

		return true;
	}

	private void newDefaultRegion() {
		// Set the Start point. 
		startPoint.set(imageView.getWidth() / 2, imageView.getHeight() / 2);

		float defaultSize = imageView.getWidth() / 4;
		float halfSize = defaultSize / 2;

		RectF newRegion = new RectF();

		newRegion.left = startPoint.x - halfSize;
		newRegion.top = startPoint.y - halfSize;

		newRegion.right = startPoint.x + defaultSize;
		newRegion.left = startPoint.y + defaultSize;

		Matrix iMatrix = new Matrix();
		matrix.invert(iMatrix);
		iMatrix.mapRect(newRegion);

		createImageRegion(newRegion.left, newRegion.top, newRegion.right, newRegion.bottom, false, true);

	}

	/*
     * Normal menu item selected method.  Uses menu items defined in XML: res/menu/image_editor_menu.xml
     */
	@Override
	public boolean onOptionsItemSelected(MenuItem item) {

		switch (item.getItemId()) {

			case R.id.menu_save:

				//Why does this not show?
				mProgressDialog = ProgressDialog.show(this, "", "Saving...", true, true);

				mHandler.postDelayed(new Runnable() {
					@Override
					public void run() {
						// this will be done in the Pipeline Thread
						checkWritePermissionThenSave();
					}
				}, 500);


				return true;

			case R.id.menu_share:
				// Share Image
				shareImage();


				return true;
        	
/*
 			case R.id.menu_delete_original:
        		// Delete Original Image
        		handleDelete();
        		
        		return true;
*/
            case android.R.id.home:
                // Pull up about screen
				finish();

				return true;

			case R.id.menu_preview:
				boolean checked = item.isChecked();
				item.setChecked(!checked);
				showPreview(!checked);
				return true;

			default:
				return false;
		}
	}

	/*
	 * Display the about screen
	 */
	private void displayAbout() {

		StringBuffer msg = new StringBuffer();

		msg.append(getString(R.string.app_name));

		String versNum = "";

		try {
			String pkg = getPackageName();
			versNum = getPackageManager().getPackageInfo(pkg, 0).versionName;
		} catch (Exception e) {
			versNum = "";
		}

		msg.append(" v" + versNum);
		msg.append('\n');
		msg.append('\n');

		msg.append(getString(R.string.about));

		msg.append('\n');
		msg.append('\n');

		msg.append(getString(R.string.about2));

		msg.append('\n');
		msg.append('\n');

		msg.append(getString(R.string.about3));

		showDialog(msg.toString());
	}

	private void showDialog(String msg) {
		new AlertDialog.Builder(this)
				.setTitle(getString(R.string.app_name))
				.setMessage(msg)
				.create().show();
	}

	/*
	 * Display preview image
	 */
	private void showPreview(boolean preview) {
		imageViewOverlay.setVisibility(preview ? View.GONE : View.VISIBLE);
	//	setCurrentRegion(null);
	}

	/*
	 * When the user selects the Share menu item
	 * Uses saveTmpImage (overwriting what is already there) and uses the standard Android Share Intent
	 */
	private void shareImage() {
		Uri tmpImageUri;

		if ((tmpImageUri = saveTmpImage()) != null) {
			Intent share = new Intent(Intent.ACTION_SEND);
			share.setType(MIME_TYPE_JPEG);
			share.putExtra(Intent.EXTRA_STREAM, tmpImageUri);
			startActivity(Intent.createChooser(share, "Share Image"));
		} else {
			Toast t = Toast.makeText(this, "Saving Temporary File Failed!", Toast.LENGTH_SHORT);
			t.show();
		}
	}

	/*
	 * When the user selects the Share menu item
	 * Uses saveTmpImage (overwriting what is already there) and uses the standard Android Share Intent
	 */
	private void viewImage(Uri imgView) {

		Uri uriToShare = null;

		if (imgView.getScheme() == null || imgView.getScheme().equals("file"))
			uriToShare = FileProvider.getUriForFile(this, BuildConfig.APPLICATION_ID + ".provider",new File(imgView.getPath()));
		else
			uriToShare = imgView;

		Intent iView = new Intent(Intent.ACTION_SEND);
        iView.setType(MIME_TYPE_JPEG);
        iView.putExtra(Intent.EXTRA_STREAM, uriToShare);
		startActivity(Intent.createChooser(iView, getString(R.string.view_image)));

	}


	/*
     * Goes through the regions that have been defined and creates a bitmap with them obscured.
     * This may introduce memory issues and therefore have to be done in a different manner.
     */
	private Bitmap createObscuredBitmap(int width, int height, boolean showBorders) {
		if (imageBitmap == null)
			return null;

		if (obscuredBmp == null || (obscuredBmp.getWidth() != width)) {
			// Create the bitmap that we'll output from this method
			Bitmap.Config config = imageBitmap.getConfig();
			if (config == null) {
				config = Bitmap.Config.RGB_565;//TODO
			}
			obscuredBmp = Bitmap.createBitmap(width, height, config);

			// Create the canvas to draw on
			obscuredCanvas = new Canvas(obscuredBmp);
		}

		// Create the paint used to draw with
		obscuredPaint = new Paint();
		// Create a default matrix
		Matrix obscuredMatrix = new Matrix();
		// Draw the scaled image on the new bitmap
		obscuredCanvas.drawBitmap(imageBitmap, obscuredMatrix, obscuredPaint);

		// Iterate through the regions that have been created
		Iterator<ImageRegion> i = imageRegions.iterator();
		while (i.hasNext()) {
			ImageRegion currentRegion = i.next();
			RegionProcesser om = currentRegion.getRegionProcessor();

			RectF regionRect = new RectF(currentRegion.getBounds());

			if (doRealtimePreview)
				om.processRegion(regionRect, obscuredCanvas, obscuredBmp);
		}

		return obscuredBmp;
	}


	private boolean canDoNative() {
		if (originalImageUri == null)
			return false;

		// Iterate through the regions that have been created
		Iterator<ImageRegion> i = imageRegions.iterator();
		while (i.hasNext()) {
			ImageRegion iRegion = i.next();
			if (iRegion.getRegionProcessor() instanceof MaskObscure)
				return false;
		}

		return true;

	}

	/*
     * Goes through the regions that have been defined and creates a bitmap with them obscured.
     * This may introduce memory issues and therefore have to be done in a different manner.
     */
	private Uri processNativeRes(Uri sourceImage) throws Exception {

		File tmpInFile = new File(getCacheDir(), 'i' + TMP_FILE_NAME);
		File fileTarget = new File(getCacheDir(), TMP_FILE_NAME);

		if (tmpInFile.exists())
			tmpInFile.delete();

		if (fileTarget.exists())
			fileTarget.delete();

		copy(sourceImage, tmpInFile);

		JpegRedaction om = new JpegRedaction();
		om.setFiles(tmpInFile, fileTarget);
		om.processRegions(imageRegions, inSampleSize, obscuredCanvas);

		if (!fileTarget.exists())
			throw new Exception("native proc failed");

		return Uri.fromFile(fileTarget);
	}

	private void copy(Uri uriSrc, File fileTarget) throws IOException {
		InputStream is = null;

        try {
            is = getContentResolver().openInputStream(uriSrc);
        }
        catch (FileNotFoundException fe)
        {
            is = new FileInputStream(uriSrc.getPath());
        }

		OutputStream os = new FileOutputStream(fileTarget);

		copyStreams(is, os);


	}

	private void copy(Uri uriSrc, Uri uriTarget) throws IOException {

		InputStream is = getContentResolver().openInputStream(uriSrc);

		OutputStream os = getContentResolver().openOutputStream(uriTarget);

		copyStreams(is, os);


	}

	private static void copyStreams(InputStream input, OutputStream output) throws IOException {
		// if both are file streams, use channel IO
		if ((output instanceof FileOutputStream) && (input instanceof FileInputStream)) {
			try {
				FileChannel target = ((FileOutputStream) output).getChannel();
				FileChannel source = ((FileInputStream) input).getChannel();

				source.transferTo(0, Integer.MAX_VALUE, target);

				source.close();
				target.close();

				return;
			} catch (Exception e) { /* failover to byte stream version */
			}
		}

		byte[] buf = new byte[8192];
		while (true) {
			int length = input.read(buf);
			if (length < 0)
				break;
			output.write(buf, 0, length);
		}

		try {
			input.close();
		} catch (IOException ignore) {
		}
		try {
			output.close();
		} catch (IOException ignore) {
		}
	}

	/*
     * Save a temporary image for sharing only
     */
	private Uri saveTmpImage() {

		String storageState = Environment.getExternalStorageState();
		if (!Environment.MEDIA_MOUNTED.equals(storageState)) {
			Toast t = Toast.makeText(this, "External storage not available", Toast.LENGTH_SHORT);
			t.show();
			return null;
		}

		// Create the bitmap that will be saved
		// Perhaps this should be smaller than screen size??
		int w = imageBitmap.getWidth();
		int h = imageBitmap.getHeight();
		Bitmap obscuredBmp = createObscuredBitmap(w, h, false);

		// Create the Uri - This can't be "private"
		File tmpFileDirectory = new File(Environment.getExternalStorageDirectory().getPath() + TMP_FILE_DIRECTORY);
		File tmpFile = new File(tmpFileDirectory, TMP_FILE_NAME);
		debug(ObscuraApp.TAG, tmpFile.getPath());

		try {
			if (!tmpFileDirectory.exists()) {
				tmpFileDirectory.mkdirs();
			}
			Uri tmpImageUri = Uri.fromFile(tmpFile);

			OutputStream imageFileOS;

			int quality = 100;
			imageFileOS = getContentResolver().openOutputStream(tmpImageUri);
			obscuredBmp.compress(CompressFormat.JPEG, quality, imageFileOS);

			return tmpImageUri;
		} catch (FileNotFoundException e) {
			mProgressDialog.cancel();
			e.printStackTrace();
			return null;
		}
	}

	private void checkWritePermissionThenSave() {
		int permissionCheck = ContextCompat.checkSelfPermission(this,
				Manifest.permission.WRITE_EXTERNAL_STORAGE);
		if (Build.VERSION.SDK_INT <= 18)
			permissionCheck = PackageManager.PERMISSION_GRANTED; // For old devices we ask in the manifest!
		if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
			ActivityCompat.requestPermissions(this, new String[]{
							Manifest.permission.READ_EXTERNAL_STORAGE,
							Manifest.permission.WRITE_EXTERNAL_STORAGE},
					WRITE_EXTERNAL_STORAGE_PERMISSION_REQUEST);
		} else {
			saveImage();
		}
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
		switch (requestCode) {
			case WRITE_EXTERNAL_STORAGE_PERMISSION_REQUEST: {
				// If request is cancelled, the result arrays are empty.
				if (grantResults.length > 1
						&& grantResults[0] == PackageManager.PERMISSION_GRANTED
						&& grantResults[1] == PackageManager.PERMISSION_GRANTED) {
					saveImage();
				}
			}
			break;
		}
	}

	/*
     * The method that actually saves the altered image.  
     * This in combination with createObscuredBitmap could/should be done in another, more memory efficient manner. 
     */
	private boolean saveImage() {

        SimpleDateFormat dateFormat = new SimpleDateFormat(EXPORT_DATE_FORMAT);
        Date date = new Date();
        String dateString = dateFormat.format(date);

        /**
		ContentValues cv = new ContentValues();

		// Add a date so it shows up in a reasonable place in the gallery - Should we do this??

		// Which one?
		cv.put(Images.Media.DATE_ADDED, dateString);
		cv.put(Images.Media.DATE_TAKEN, dateString);
		cv.put(Images.Media.DATE_MODIFIED, dateString);
		cv.put(Images.Media.TITLE, dateString);
		//    cv.put(Images.Media.BUCKET_ID, "ObscuraCam");
		//    cv.put(Images.Media.DESCRIPTION, "ObscuraCam");
		//cv.put(Images.Media.CONTENT_TYPE, MIME_TYPE_JPEG);

		// Uri is savedImageUri which is global
		// Create the Uri, this should put it in the gallery
		// New Each time
		savedImageUri = getContentResolver().insert(
				Media.EXTERNAL_CONTENT_URI, cv);

		if (savedImageUri == null)
			return false;
         **/

        File fileImageOut =
                new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                        "obscura" + new Date().getTime() + ".jpg");

        savedImageUri = Uri.fromFile(fileImageOut);

		boolean nativeSuccess = false;

        /**
		if (canDoNative()) {
			try {
				Uri savedNativeTmp = processNativeRes(originalImageUri);

				copy(savedNativeTmp, savedImageUri);

				nativeSuccess = true;

			} catch (Exception e) {
				Log.e(ObscuraApp.TAG, "error doing native redact", e);
			}
		}**/


		if (!nativeSuccess) {
			try {

				obscuredBmp = createObscuredBitmap(imageBitmap.getWidth(), imageBitmap.getHeight(), false);

				OutputStream imageFileOS;

				int quality = 100; //lossless?  good question - still a smaller version
				imageFileOS = getContentResolver().openOutputStream(savedImageUri);
				obscuredBmp.compress(CompressFormat.JPEG, quality, imageFileOS);

			} catch (Exception e) {
				Log.e(ObscuraApp.TAG, "error doing redact", e);
				return false;
			}

		}

		/**
		// package and insert exif data
		mp = new MetadataParser(dateFormat.format(date), pullPathFromUri(savedImageUri), this);
		Iterator<ImageRegion> i = imageRegions.iterator();
		while (i.hasNext()) {
			mp.addRegion(i.next().getRegionProcessor().getProperties());
		}

		mp.flushMetadata();
         **/

        Intent intent =
                new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
        intent.setData(savedImageUri);
        sendBroadcast(intent);

        mProgressDialog.cancel();

//                        showDeleteOriginalDialog();

        Snackbar snackbar = Snackbar.make(findViewById(R.id.frameRoot),R.string.processing_complete,Snackbar.LENGTH_INDEFINITE);
        snackbar.setAction("Open", new OnClickListener() {
            @Override
            public void onClick(View view) {
                viewImage(savedImageUri);
            }
        });
        snackbar.show();

		return true;
	}

	// Queries the contentResolver to pull out the path for the actual file.
    /*  This code is currently unused but i often find myself needing it so I 
     * am placing it here for safe keeping ;-) */

	/*
     * Yep, uncommenting it back out so we can use the original path to refresh media scanner
     * HNH 8/23/11
     */
	public File pullPathFromUri(Uri originalUri) {

		String originalImageFilePath = originalUri.toString();

		if (originalUri.getScheme() != null && originalUri.getScheme().equals("file")) {
			originalImageFilePath = originalUri.toString();
		} else {
			String[] columnsToSelect = {MediaStore.Images.Media.DATA};
			Cursor imageCursor = getContentResolver().query(originalUri, columnsToSelect, null, null, null);
			if (imageCursor != null) {
				if (imageCursor.moveToFirst()) {
					originalImageFilePath = imageCursor.getString(imageCursor.getColumnIndex(MediaStore.Images.Media.DATA));
				}
				imageCursor.close();
			}
		}

		return new File(originalImageFilePath);
	}


	/*
     * Handling screen configuration changes ourselves, we don't want the activity to restart on rotation
     */
	@Override
	public void onConfigurationChanged(Configuration conf) {
		super.onConfigurationChanged(conf);

        mHandler.postDelayed(new Runnable() {
            public void run() {
                putOnScreen();
            }
        }, 100);

	}


	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent data) {
		if (resultCode == Activity.RESULT_OK) {
			if (requestCode == FROM_INFORMA) {
				// replace corresponding image region
				@SuppressWarnings("unchecked")
				HashMap<String, String> informaReturn = (HashMap<String, String>) data.getSerializableExtra("informaReturn");
				Properties mProp = imageRegions.get(data.getIntExtra("irIndex", 0)).getRegionProcessor().getProperties();

				// iterate through returned hashmap and place these new properties in it.
				for (Map.Entry<String, String> entry : informaReturn.entrySet()) {
					mProp.setProperty(entry.getKey(), entry.getValue());
				}

				imageRegions.get(data.getIntExtra("irIndex", 0)).getRegionProcessor().setProperties(mProp);

			}
		}
	}

	@Override
	protected void onPostResume() {
		super.onPostResume();

	}

	public Paint getPainter() {
		return obscuredPaint;
	}

	private void debug(String tag, String message) {
		Log.d(tag, message);
	}


	public ImageView getImageView() {
		return imageView;
	}

	@Override
	public void onAttachedToWindow() {
		super.onAttachedToWindow();
		Window window = getWindow();
		window.setFormat(PixelFormat.RGBA_8888);
		window.getDecorView().getBackground().setDither(true);

	}

	private InputStream streamFromUri(Uri uri) {
		InputStream is = null;
		try {
			if (uri.getScheme() != null && uri.getScheme().contentEquals("content"))
				is = getContentResolver().openInputStream(uri);
			else
				is = new FileInputStream(new File(uri.toString()));
		} catch (Exception e) {
			e.printStackTrace();
		}
		return is;
	}

	@Override
	public void onOptionSelected(int operation) {
		if (getCurrentRegion() != null) {
			if (operation == -1) {
				deleteRegion(getCurrentRegion());
				setCurrentRegion(null);
				updateDisplayImage();
			} else {
				getCurrentRegion().setObscureType(operation);
				((ImageRegionOptionsRecyclerViewAdapter)recyclerViewRegionOptions.getAdapter()).setCurrentItem(getCurrentRegion().mObscureType);
				forceUpdateDisplayImage();
			}
		}
	}


    private class RegionOverlayView extends View implements OnTouchListener {
		private Paint paint;
		private RectF mappedRect;
		private int mode;
		private final int selectionHandleRadius;

		public RegionOverlayView(Context context) {
			super(context);
			paint = new Paint();
			paint.setStyle(Style.STROKE);
			Resources r = context.getResources();
			int selectionWidth = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, SELECTION_BORDER_WIDTH, r.getDisplayMetrics());
			selectionHandleRadius = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, SELECTION_HANDLE_RADIUS, r.getDisplayMetrics());
			paint.setStrokeWidth(selectionWidth);
			mappedRect = new RectF();
			setOnTouchListener(this);
		}

		@Override
		protected void onDraw(Canvas canvas) {
			super.onDraw(canvas);
			Matrix imageMatrix = imageView.getImageMatrix();
			try {
				for (ImageRegion currentRegion : imageRegions) {
					RectF regionRect = currentRegion.getBounds();
					imageMatrix.mapRect(mappedRect, regionRect);
					if (currentRegion.isSelected()) {
                        paint.setColor(0xffb6ff66);
                        paint.setStrokeWidth(3f);
                    }
					else {
                        paint.setColor(Color.WHITE);
                        paint.setStrokeWidth(1f);
                    }

					paint.setStyle(Style.STROKE);
					canvas.drawRect(mappedRect, paint);

					// Draw drag handles
					paint.setStyle(Style.FILL);
					if (currentRegion.isSelected()) {
						canvas.drawCircle(mappedRect.centerX(), mappedRect.top, selectionHandleRadius, paint);
						canvas.drawCircle(mappedRect.centerX(), mappedRect.bottom, selectionHandleRadius, paint);
						canvas.drawCircle(mappedRect.left, mappedRect.centerY(), selectionHandleRadius, paint);
						canvas.drawCircle(mappedRect.right, mappedRect.centerY(), selectionHandleRadius, paint);
					}
				}
			} catch (Exception ignored) {
			}
		}


		@Override
		public boolean onTouch(View v, MotionEvent event) {
			if (currRegion != null && (mode == DRAG || currRegion.containsPoint(event.getX(), event.getY())))
				return onTouchRegion(v, event, currRegion);
			else
				return onTouchImage(v, event);
		}

		public ImageRegion findRegion(MotionEvent event) {
			ImageRegion result = null;
			for (ImageRegion region : imageRegions) {
				if (region.containsPoint(event.getX(), event.getY())) {
					result = region;
					break;
				}
			}
			return result;
		}

		public boolean onTouchRegion(View v, MotionEvent event, ImageRegion iRegion) {
			boolean handled = false;

			switch (event.getAction() & MotionEvent.ACTION_MASK) {
				case MotionEvent.ACTION_DOWN:
					setRealtimePreview(false);
					iRegion.setCornerMode(event.getX(), event.getY());
					mode = DRAG;
					handled = iRegion.onTouch(v, event);

					break;

				case MotionEvent.ACTION_UP:
				case MotionEvent.ACTION_CANCEL:
					mode = NONE;
					handled = iRegion.onTouch(v, event);
					setRealtimePreview(true);
					updateDisplayImage();

					break;

				case MotionEvent.ACTION_MOVE:
					mode = DRAG;
					handled = iRegion.onTouch(v, event);

					break;

				default:
					mode = NONE;

			}

			return handled;


		}

		public boolean onTouchImage(View v, MotionEvent event) {
			switch (event.getAction() & MotionEvent.ACTION_MASK) {
				case MotionEvent.ACTION_DOWN:
					ImageRegion newRegion = findRegion(event);
					if (newRegion != null) {
						setCurrentRegion(newRegion);
						updateDisplayImage();
						return onTouchRegion(v, event, newRegion);
					} else {
						if (getCurrentRegion() != null) {
							setCurrentRegion(null);
							forceUpdateDisplayImage();
							return true;
						}
						return false;
					}
				case MotionEvent.ACTION_CANCEL:
				case MotionEvent.ACTION_UP:
					setRealtimePreview(true);
					updateDisplayImage();
			}
			return false;
		}
	}
}
