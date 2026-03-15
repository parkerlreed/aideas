package com.lvonasek.tofviewer;

import android.Manifest;
import android.app.AlertDialog;
import android.content.res.Configuration;
import android.content.SharedPreferences;
import android.widget.Toast;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Point;
import android.opengl.GLES20;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;

import com.google.vr.sdk.base.AndroidCompat;
import com.google.vr.sdk.base.Eye;
import com.google.vr.sdk.base.GvrActivity;
import com.google.vr.sdk.base.GvrView;
import com.google.vr.sdk.base.HeadTransform;
import com.google.vr.sdk.base.Viewport;
import com.lvonasek.gles.GLESSurfaceView;
import com.lvonasek.record.Recorder;

import java.io.IOException;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class TofViewerActivity extends GvrActivity implements GLESSurfaceView.Renderer, View.OnTouchListener {

  private static final String KEY_EYE_DOWN = "KEY_EYE_DOWN";
  private static final String KEY_EYE_SIDE = "KEY_EYE_SIDE";
  private static final String KEY_EYE_ZOOM = "KEY_EYE_ZOOM";
  private static final String KEY_SCHEME = "KEY_SCHEME";

  private final String[] permissions = {
          Manifest.permission.CAMERA,
          Manifest.permission.RECORD_AUDIO,
          Manifest.permission.READ_EXTERNAL_STORAGE,
          Manifest.permission.WRITE_EXTERNAL_STORAGE
  };

  private static final int REQUEST_PERMISSIONS = 200;

  private final DepthmapRenderer depthmapRenderer = new DepthmapRenderer();

  private static boolean connected = false;
  private static boolean recordNight = false;
  private static boolean vrMode = false;

  private boolean initialized = false;
  private boolean mMakePhoto = false;

  private final DepthStreamServer depthServer = new DepthStreamServer();
  private java.nio.ByteBuffer mStreamPixelBuf;
  private int mStreamPixelBufSize;

  private LinearLayout mVRSetup;
  private GLESSurfaceView mSurfaceView;
  private GestureDetector mGestureDetector;
  private ScaleGestureDetector mScaleDetector;
  private GvrView mGVRview;

  private float eyeZoom = 0.4f;
  private float eyeSide = 0.21f;
  private float eyeDown = 0.1f;
  private float zoom = 1.0f;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);

    mVRSetup = findViewById(R.id.vrLayout);
    mSurfaceView = findViewById(R.id.glsurfaceview);
    mSurfaceView.setOnTouchListener(this);
    mSurfaceView.setRenderer(this);

    //stereo view
    mGVRview = findViewById(R.id.gvr_view);
    mGVRview.setEGLConfigChooser(8, 8, 8, 8, 24, 8);
    mGVRview.setEGLContextClientVersion(3);
    mGVRview.setRenderer(new GvrView.Renderer() {
      @Override
      public void onDrawFrame(HeadTransform headTransform, Eye eye, Eye eye1) {
        float[] angles = new float[3];
        headTransform.getEulerAngles(angles, 0);
        depthmapRenderer.setAngles(angles);
      }

      @Override
      public void onFinishFrame(Viewport viewport) {
      }

      @Override
      public void onSurfaceChanged(int i, int i1) {
      }

      @Override
      public void onSurfaceCreated(EGLConfig eglConfig) {
      }

      @Override
      public void onRendererShutdown() {
      }
    });
    mGVRview.setTransitionViewEnabled(true);
    mGVRview.setDistortionCorrectionEnabled(true);
    if (mGVRview.setAsyncReprojectionEnabled(true))
      AndroidCompat.setSustainedPerformanceMode(this, true);
    setGvrView(mGVRview);

    getWindow().setNavigationBarColor(Color.BLACK);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);

    WindowManager.LayoutParams winParams = getWindow().getAttributes();
    winParams.rotationAnimation = WindowManager.LayoutParams.ROTATION_ANIMATION_CROSSFADE;
    getWindow().setAttributes(winParams);

    WindowManager.LayoutParams lp = getWindow().getAttributes();
    lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
    getWindow().setAttributes(lp);

    mScaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.OnScaleGestureListener() {

      float last;

      @Override
      public void onScaleEnd(ScaleGestureDetector detector) {
      }
      @Override
      public boolean onScaleBegin(ScaleGestureDetector detector) {
        last = 0;
        return true;
      }
      @Override
      public boolean onScale(ScaleGestureDetector detector) {
        float f = detector.getScaleFactor() - 1.0f;
        zoom += f - last;
        if (zoom < 1) zoom = 1;
        if (zoom > 2) zoom = 2;
        last = f;
        return false;
      }
    });

    mGestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
      @Override
      public boolean onSingleTapConfirmed(MotionEvent e) {
        new Thread(() -> captureBitmap()).start();
        return true;
      }
      @Override
      public void onLongPress(MotionEvent e) {
        if (recordNight) {
          cancelRecordingNight();
        } else {
          runOnUiThread(() -> showMoreFeatures());
        }
      }
    });

    if (recordNight) {
      lp.screenBrightness = 0;
      getWindow().setAttributes(lp);
    }
    if (vrMode) {
      mVRSetup.setVisibility(View.VISIBLE);
    }

    //VR calibration
    SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(this);
    findViewById(R.id.down).setOnClickListener(v -> {
      eyeDown += 0.01f;
      SharedPreferences.Editor e = pref.edit();
      e.putFloat(KEY_EYE_DOWN, eyeDown);
      e.apply();
    });
    findViewById(R.id.up).setOnClickListener(v -> {
      eyeDown -= 0.01f;
      SharedPreferences.Editor e = pref.edit();
      e.putFloat(KEY_EYE_DOWN, eyeDown);
      e.apply();
    });
    findViewById(R.id.left).setOnClickListener(v -> {
      eyeSide -= 0.01f;
      if (eyeSide < 0.01f)
        eyeSide = 0.01f;
      SharedPreferences.Editor e = pref.edit();
      e.putFloat(KEY_EYE_SIDE, eyeSide);
      e.apply();
    });
    findViewById(R.id.right).setOnClickListener(v -> {
      eyeSide += 0.01f;
      SharedPreferences.Editor e = pref.edit();
      e.putFloat(KEY_EYE_SIDE, eyeSide);
      e.apply();
    });
    findViewById(R.id.reset).setOnClickListener(v -> {
      eyeZoom = 1.0f;
      eyeSide = 0.21f;
      eyeDown = 0.1f;
      SharedPreferences.Editor e = pref.edit();
      e.putFloat(KEY_EYE_ZOOM, eyeZoom);
      e.putFloat(KEY_EYE_DOWN, eyeDown);
      e.putFloat(KEY_EYE_SIDE, eyeSide);
      e.apply();
    });
    findViewById(R.id.zoomIn).setOnClickListener(v -> {
      eyeZoom += 0.01f;
      SharedPreferences.Editor e = pref.edit();
      e.putFloat(KEY_EYE_ZOOM, eyeZoom);
      e.apply();
    });
    findViewById(R.id.zoomOut).setOnClickListener(v -> {
      eyeZoom -= 0.01f;
      SharedPreferences.Editor e = pref.edit();
      e.putFloat(KEY_EYE_ZOOM, eyeZoom);
      e.apply();
    });
  }

  private void cancelRecordingNight() {
    if (recordNight) {
      new Thread(() -> {
        Recorder.stopCapturingVideo(this, true);
        try {
          Thread.sleep(500);
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
        System.exit(0);
      }).start();
    }
  }

  @Override
  public void onConfigurationChanged(Configuration newConfig) {
    super.onConfigurationChanged(newConfig);
  }

  @Override
  public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
  }

  @Override
  public void onSurfaceCreated(GL10 gl, EGLConfig config) {
    // Prepare the rendering objects. This involves reading shaders, so may throw an IOException.
    try {
      depthmapRenderer.createOnGlThread(this);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  @Override
  public void onSurfaceChanged(GL10 gl, int width, int height) {
    if (!connected) {
      updateParams();
      connected = true;
    }
  }

  @Override
  public void onDrawFrame(GL10 gl) {
    gl.glClear(GL10.GL_COLOR_BUFFER_BIT | GL10.GL_DEPTH_BUFFER_BIT);

    if (vrMode) {
      renderEye(eyeZoom * 0.5f, -eyeSide, -eyeDown);
      renderEye(eyeZoom * 0.5f, eyeSide, -eyeDown);
    } else {

      Point size = new Point();
      size.x = mSurfaceView.getWidth();
      size.y = mSurfaceView.getHeight();
      int depthWidth = depthmapRenderer.getDepthWidth();
      int depthHeight = depthmapRenderer.getDepthHeight();

      int rotation = getWindowManager().getDefaultDisplay().getRotation();
      boolean landscape = (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270);
      float effectiveAspect = landscape
              ? depthWidth / (float) depthHeight
              : depthHeight / (float) depthWidth;
      int vpWidth, vpHeight;
      if (size.x / (float) size.y >= effectiveAspect) {
        vpHeight = (int) (size.y * zoom);
        vpWidth  = (int) (vpHeight * effectiveAspect);
      } else {
        vpWidth  = (int) (size.x * zoom);
        vpHeight = (int) (vpWidth / effectiveAspect);
      }
      int vpX = (size.x - vpWidth) / 2;
      int vpY = (size.y - vpHeight) / 2;
      GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
      GLES20.glScissor(vpX, vpY, vpWidth, vpHeight);
      GLES20.glViewport(vpX, vpY, vpWidth, vpHeight);
      depthmapRenderer.draw(this, zoom, vpWidth, vpHeight);

      if (depthServer.isRunning() && depthServer.hasClient()) {
        int bufSize = vpWidth * vpHeight * 4;
        if (mStreamPixelBuf == null || mStreamPixelBufSize != bufSize) {
          mStreamPixelBuf = java.nio.ByteBuffer.allocateDirect(bufSize);
          mStreamPixelBuf.order(java.nio.ByteOrder.LITTLE_ENDIAN);
          mStreamPixelBufSize = bufSize;
        }
        mStreamPixelBuf.rewind();
        GLES20.glReadPixels(vpX, vpY, vpWidth, vpHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, mStreamPixelBuf);
        byte[] pixels = new byte[bufSize];
        mStreamPixelBuf.rewind();
        mStreamPixelBuf.get(pixels);
        depthServer.queueFrame(vpWidth, vpHeight, pixels);
      }
    }
    GLES20.glDisable(GLES20.GL_SCISSOR_TEST);

    if (mMakePhoto) {
      Recorder.capturePhoto(gl, mSurfaceView);
      mMakePhoto = false;
    } else if (recordNight) {
      Recorder.captureVideoFrame(gl, mSurfaceView, true, Recorder.getVideoFPS() * 60 / 5, true);
      gl.glClear(GL10.GL_COLOR_BUFFER_BIT | GL10.GL_DEPTH_BUFFER_BIT);
    } else {
      Recorder.captureVideoFrame(gl, mSurfaceView, false, 0, true);
    }
  }

  private void renderEye(float zoom, float dx, float dy) {
    Point size = new Point();
    size.x = mSurfaceView.getWidth();
    size.y = mSurfaceView.getHeight();
    int depthWidth = depthmapRenderer.getDepthWidth();
    int depthHeight = depthmapRenderer.getDepthHeight();
    dx *= (float) size.x;
    dy *= (float) size.y;

    int rotation = getWindowManager().getDefaultDisplay().getRotation();
    boolean rotated = (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270);
    float effectiveAspect = rotated
            ? depthHeight / (float) depthWidth
            : depthWidth / (float) depthHeight;
    int vpWidth, vpHeight;
    if (size.x / (float) size.y >= effectiveAspect) {
      vpHeight = (int) (size.y * zoom);
      vpWidth  = (int) (vpHeight * effectiveAspect);
    } else {
      vpWidth  = (int) (size.x * zoom);
      vpHeight = (int) (vpWidth / effectiveAspect);
    }
    GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
    GLES20.glScissor((size.x - vpWidth) / 2 + (int) dx, (size.y - vpHeight) / 2 + (int) dy, vpWidth, vpHeight);
    GLES20.glViewport((size.x - vpWidth) / 2 + (int) dx, (size.y - vpHeight) / 2 + (int) dy, vpWidth, vpHeight);
    depthmapRenderer.draw(this, 1, vpWidth, vpHeight);
  }

  @Override
  protected void onPause() {
    if (!isChangingConfigurations()) {
      depthServer.stop();
      if (recordNight) {
        cancelRecordingNight();
      } else {
        depthmapRenderer.closeCamera();
      }
    }
    super.onPause();
  }

  @Override
  protected void onResume() {
    super.onResume();
    for (String permission : permissions) {
      if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
        requestPermissions(permissions, REQUEST_PERMISSIONS);
        return;
      }
    }

    if (!initialized) {
      mSurfaceView.setVisibility(View.VISIBLE);
      mGVRview.setVisibility(View.VISIBLE);
      depthmapRenderer.initCamera(this);
      initialized = true;
    }
    depthmapRenderer.openCamera(this);
  }

  @Override
  public void onBackPressed() {
    if (recordNight) cancelRecordingNight();
    super.onBackPressed();
  }

  private void captureBitmap() {
    synchronized (this) {
      mMakePhoto = true;
    }
    try {
      while (mMakePhoto) {
        Thread.sleep(20);
      }
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
  }

  private void showMoreFeatures() {
    SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(this);

    CharSequence[] items = {
            Recorder.isVideoRecording() ? getString(R.string.record_stop) : getString(R.string.record_start),
            getString(R.string.scheme),
            getString(R.string.sleep),
            getString(R.string.vr),
            depthServer.isRunning() ? getString(R.string.stream_stop) : getString(R.string.stream_start)
    };

    AlertDialog.Builder dialog = new AlertDialog.Builder(TofViewerActivity.this);
    dialog.setTitle(R.string.app_name);
    dialog.setItems(items, (dialog1, which) -> {
      AlertDialog.Builder dlg = new AlertDialog.Builder(TofViewerActivity.this);
      switch (which) {
        case 0:
          if (Recorder.isVideoRecording()) {
            new Thread(() -> Recorder.stopCapturingVideo(TofViewerActivity.this, true)).start();
          } else {
            Recorder.setCustomRoot(getDataDir());
            Recorder.startCapturingVideo(TofViewerActivity.this, true);
          }
          break;
        case 1:
          dlg.setTitle(R.string.scheme);
          dlg.setItems(R.array.scheme_variants, (dialogInterface, i) -> {
            SharedPreferences.Editor e = PreferenceManager.getDefaultSharedPreferences(TofViewerActivity.this).edit();
            e.putInt(KEY_SCHEME, i);
            e.commit();
            updateParams();
          });
          dlg.setNegativeButton(android.R.string.cancel, null);
          dlg.show();
          break;
        case 2:
          if (Settings.System.getInt(getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) == 0) {
            dlg.setTitle(R.string.sleep);
            dlg.setMessage(R.string.sleep_airplane);
            dlg.setPositiveButton(android.R.string.ok, null);
            dlg.show();
            return;
          }

          dlg.setTitle(R.string.sleep);
          dlg.setMessage(R.string.sleep_description);
          dlg.setNegativeButton(android.R.string.cancel, null);
          dlg.setPositiveButton(android.R.string.ok, (dialogInterface, i) -> {
            recordNight = true;
            new Thread(() -> {
              try {
                Thread.sleep(500);
              } catch (InterruptedException e) {
                e.printStackTrace();
              }
              runOnUiThread(() -> {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.screenBrightness = 0;
                getWindow().setAttributes(lp);
                Recorder.startCapturingVideo(TofViewerActivity.this, false);
              });
            }).start();
          });
          dlg.show();
          break;
        case 3:
          dlg.setTitle(R.string.vr);
          dlg.setMessage(R.string.vr_description);
          dlg.setNegativeButton(android.R.string.cancel, null);
          dlg.setPositiveButton(android.R.string.ok, (dialogInterface, i) -> {
            vrMode = true;
            new Thread(() -> {
              try {
                Thread.sleep(500);
              } catch (InterruptedException e) {
                e.printStackTrace();
              }
              runOnUiThread(() -> {
                eyeDown = pref.getFloat(KEY_EYE_DOWN, eyeDown);
                eyeSide = pref.getFloat(KEY_EYE_SIDE, eyeSide);
                eyeZoom = pref.getFloat(KEY_EYE_ZOOM, eyeZoom);
                mVRSetup.setVisibility(View.VISIBLE);
              });
            }).start();
          });
          dlg.show();
          break;
        case 4:
          if (depthServer.isRunning()) {
            depthServer.stop();
            Toast.makeText(this, R.string.stream_stopped, Toast.LENGTH_SHORT).show();
          } else {
            try {
              depthServer.start();
              Toast.makeText(this,
                      getString(R.string.stream_listening) + "\n\n" + DepthStreamServer.getLocalAddresses(),
                      Toast.LENGTH_LONG).show();
            } catch (Exception e) {
              Toast.makeText(this, getString(R.string.stream_error) + e.getMessage(), Toast.LENGTH_LONG).show();
            }
          }
          break;
      }
    });
    dialog.show();
  }

  private void updateParams() {
    SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(this);
    int scheme = pref.getInt(KEY_SCHEME, 0);
    depthmapRenderer.setColorScheme(scheme);
  }

  @Override
  public boolean onTouch(View v, MotionEvent event) {
    mGestureDetector.onTouchEvent(event);
    mScaleDetector.onTouchEvent(event);
    return true;
  }
}
