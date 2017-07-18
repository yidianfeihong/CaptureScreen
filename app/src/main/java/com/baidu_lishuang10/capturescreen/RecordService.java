package com.baidu_lishuang10.capturescreen;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.hardware.Camera;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.support.annotation.Nullable;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;


public class RecordService extends Service {

    private static final String TAG = "RService";
    private String mVideoPath;

    private MediaProjectionManager mMediaProjectionManager;
    private MediaProjection mMediaProjection;
    private VirtualDisplay mVirtualDisplay;

    private int windowWidth;
    private int windowHeight;
    private int screenDensity;

    private Surface mSurface;
    private MediaCodec mVideoCodec;
    private MediaMuxer mMediaMuxer;

    private AudioRecord mAudioRecord;
    private MediaCodec mAudioCodec;

    private LinearLayout mCaptureLl;
    private WindowManager mWindowManager;

    private boolean isRecordOn;
    private boolean isCameraHide;

    private AtomicBoolean mIsQuit = new AtomicBoolean(false);

    private MediaCodec.BufferInfo mVideoBufferInfo = new MediaCodec.BufferInfo();
    private MediaCodec.BufferInfo mAudioBufferInfo = new MediaCodec.BufferInfo();

    private boolean mMuxerStarted = false;

    private int mVideoTrackIndex = -1;
    private int mAudioTrackIndex = -1;
    private int mNumTracksAdded = 0;

    private SurfaceView mSurfaceView;
    private SurfaceHolder mSurfaceHolder;
//    private Camera mCamera;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);
        Notification notification = new Notification.Builder(this)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .build();
        startForeground(0x7fff, notification);
        return super.onStartCommand(intent, START_STICKY_COMPATIBILITY, startId);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        mVideoPath = "/sdcard/test"; //Environment.getExternalStorageDirectory().getPath() + "/";
        mMediaProjectionManager = ((MyApplication) getApplication()).getMpmngr();
        mWindowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        windowWidth = mWindowManager.getDefaultDisplay().getWidth();
        windowHeight = mWindowManager.getDefaultDisplay().getHeight();
        DisplayMetrics displayMetrics = new DisplayMetrics();
        mWindowManager.getDefaultDisplay().getMetrics(displayMetrics);
        screenDensity = displayMetrics.densityDpi;

//        configureMedia();

        final WindowManager.LayoutParams params = new WindowManager.LayoutParams
                (WindowManager.LayoutParams.TYPE_PHONE, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.RGBA_8888);
        params.x = windowWidth;
        params.y = windowHeight/2;
        params.gravity = Gravity.LEFT | Gravity.TOP;
        params.width = WindowManager.LayoutParams.WRAP_CONTENT;
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        LayoutInflater inflater = LayoutInflater.from(getApplicationContext());
        mCaptureLl = (LinearLayout) inflater.inflate(R.layout.float_record, null);
        final ImageView mCaptureIv = (ImageView) mCaptureLl.findViewById(R.id.iv_record);
        final ImageView mMoveIv = (ImageView) mCaptureLl.findViewById(R.id.iv_move);
        final ImageView mHideIv = (ImageView) mCaptureLl.findViewById(R.id.iv_hide);
        mSurfaceView = (SurfaceView) mCaptureLl.findViewById(R.id.sv_camera);
        mSurfaceHolder = mSurfaceView.getHolder();
        mWindowManager.addView(mCaptureLl, params);

        mCaptureIv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                isRecordOn = !isRecordOn;
                if (isRecordOn) {
                    mCaptureIv.setImageResource(R.mipmap.ic_recording);
                    Toast.makeText(RecordService.this.getApplicationContext(), "开始录屏", Toast.LENGTH_SHORT).show();
                    recordStart();
                    showCameraPreview();
                } else {
                    mCaptureIv.setImageResource(R.mipmap.ic_record);
                    Toast.makeText(RecordService.this.getApplicationContext(), "结束录屏", Toast.LENGTH_SHORT).show();
                    recordStop();
                    hideCameraPreview();
                }
            }
        });

        isCameraHide = false;

        mHideIv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                isCameraHide = !isCameraHide;
                if (isCameraHide) {
                    mHideIv.setImageResource(R.mipmap.ic_show);
                    mSurfaceView.setVisibility(View.GONE);
                } else {
                    mHideIv.setImageResource(R.mipmap.ic_hide);
                    mSurfaceView.setVisibility(View.VISIBLE);
                }
            }
        });

        mCaptureLl.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent event) {
                int[] location = new int[2];
                view.getLocationOnScreen(location);
                if (event.getRawX() >= location[0] && event.getRawX() < (location[0] + mMoveIv.getMeasuredWidth())
                    && event.getRawY() >= location[1] && event.getRawY() < (location[1] + mMoveIv.getMeasuredHeight())) {
                    if (event.getAction() == MotionEvent.ACTION_DOWN) {
                        int[] loc = new int[2];
                        mCaptureLl.getLocationOnScreen(loc);
                        mOffsetX = (int) event.getRawX() - loc[0];
                        mOffsetY = (int) event.getRawY() - loc[1];
                    } else {
                        params.x = (int) (event.getRawX() - mOffsetX);
                        params.y = (int) (event.getRawY() - mOffsetY);
                        mWindowManager.updateViewLayout(mCaptureLl, params);
                    }
                }
                return false;
            }
        });
    }

    private int mOffsetX = 0;
    private int mOffsetY = 0;

    private CameraDevice mCameraDevice;

    private void showCameraPreview() {
        final CameraManager cameraManager = (CameraManager) this.getSystemService(Context.CAMERA_SERVICE);
        HandlerThread handlerThread = new HandlerThread("CameraPreviewThread");
        handlerThread.start();
        final Handler mHandler = new Handler(handlerThread.getLooper());
        try {
            final ImageReader imageReader = ImageReader.newInstance(mSurfaceView.getWidth(), mSurfaceView.getHeight(), ImageFormat.JPEG,/*maxImages*/7);
            imageReader.setOnImageAvailableListener(null, mHandler);

            cameraManager.openCamera(String.valueOf(CameraCharacteristics.LENS_FACING_BACK), new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    mCameraDevice = camera;
                    try {
                        final CaptureRequest.Builder mPreviewBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        mPreviewBuilder.addTarget(mSurfaceHolder.getSurface());
                        camera.createCaptureSession(Arrays.asList(mSurfaceHolder.getSurface(), imageReader.getSurface()),
                                new CameraCaptureSession.StateCallback() {
                                    @Override
                                    public void onConfigured(CameraCaptureSession session) {
                                        try {
                                            mPreviewBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                            mPreviewBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
                                            session.setRepeatingRequest(mPreviewBuilder.build(), null, mHandler);
                                        } catch (CameraAccessException e) {
                                            e.printStackTrace();
                                        }
                                    }

                                    @Override
                                    public void onConfigureFailed(CameraCaptureSession session) {
                                    }
                                }, mHandler);
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                }
            }, mHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        } catch (SecurityException e) {
            e.printStackTrace();
        }
    }

    private void hideCameraPreview() {
        if (mCameraDevice != null) {
            mCameraDevice.close();
            mCameraDevice = null;
        }
    }

    private void configureMedia() {

        //video
        MediaFormat mediaFormat = MediaFormat.createVideoFormat("video/avc", windowWidth, windowHeight);
        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, 6000000);
        mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
        try {
            mVideoCodec = MediaCodec.createEncoderByType("video/avc");
        } catch (IOException e) {
            e.printStackTrace();
        }
        mVideoCodec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mSurface = mVideoCodec.createInputSurface();
        mVideoCodec.start();

        //audio
        MediaFormat audioFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", 44100, 1);
        audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, 128000);
        audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        try {
            mAudioCodec = MediaCodec.createEncoderByType("audio/mp4a-latm");
        } catch (IOException e) {
            e.printStackTrace();
        }
        mAudioCodec.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mAudioCodec.start();

        int minBufferSize = AudioRecord.getMinBufferSize(44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        mAudioRecord = new AudioRecord(MediaRecorder.AudioSource.CAMCORDER,
                44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBufferSize * 2);
    }

    private void recordStop() {
        mIsQuit.set(true);
    }

    private void recordStart() {
        configureMedia();
        if (mMediaProjection == null) {
            int resultCode = ((MyApplication) getApplication()).getResultCode();
            Intent data = ((MyApplication) getApplication()).getResultIntent();
            mMediaProjection = mMediaProjectionManager.getMediaProjection(resultCode, data);
        }
        mVirtualDisplay = mMediaProjection.createVirtualDisplay("record_screen", windowWidth, windowHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, mSurface, null, null);

        mAudioRecord.startRecording();

        try {
            String filePath = mVideoPath + System.currentTimeMillis() + ".mp4";
            Log.i(TAG, "filePath = " + filePath);
            mMediaMuxer = new MediaMuxer(filePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        new Thread() {
            @Override
            public void run() {
                try {
                    audioRecording();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }.start();

        new Thread() {
            @Override
            public void run() {
                try {
                    recordVirtualDisplay();
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    release();
                }
            }
        }.start();

        new Thread() {
            @Override
            public void run() {
                try {
                    recordVirtualSound();
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    release();
                }
            }
        }.start();
    }

    private void audioRecording() {
        byte[] buffer = new byte[1024];
        int offset = 0;
        while(!mIsQuit.get()) {
            offset = mAudioRecord.read(buffer, offset, 1024); // read audio raw data
            long timestamp = System.nanoTime() / 1000;
            ByteBuffer[] inputBuffers = mAudioCodec.getInputBuffers();
            int inputBufferIndex = mAudioCodec.dequeueInputBuffer(-1);
            if (inputBufferIndex >= 0) {
                ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
                inputBuffer.clear();
                inputBuffer.put(buffer);
                mAudioCodec.queueInputBuffer(inputBufferIndex, 0, offset, timestamp, 0);
                offset = 0;
            }
        }
    }

    private void recordVirtualDisplay() {
        while (!mIsQuit.get()) {
            int index = mVideoCodec.dequeueOutputBuffer(mVideoBufferInfo, 10000);
            Log.i(TAG, "recordVirtualDisplay dequeue output buffer index=" + index);
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {//后续输出格式变化
                resetVideoOutputFormat();
            } else if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {//请求超时
                Log.i(TAG, "recordVirtualDisplay retrieving buffers time out!");
                try {
                    // wait 10ms
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                }
            } else if (index >= 0) {//有效输出
                if (mMuxerStarted) {
//                    throw new IllegalStateException("MediaMuxer dose not call addTrack(format) ");
                    encodeToVideoTrack(index);
                    mVideoCodec.releaseOutputBuffer(index, false);
                }
            }
        }
    }

    private void recordVirtualSound() {
        while (!mIsQuit.get()) {

            int index = mAudioCodec.dequeueOutputBuffer(mAudioBufferInfo, 10000);
            Log.i(TAG, "recordVirtualSound dequeue output buffer index=" + index);
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {//后续输出格式变化
                resetAudioOutputFormat();
            } else if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {//请求超时
                Log.i(TAG, "recordVirtualSound retrieving buffers time out!");
                try {
                    // wait 10ms
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                }
            } else if (index >= 0) {//有效输出
                if (mMuxerStarted) {
                    encodeToAudioTrack(index);
                    mAudioCodec.releaseOutputBuffer(index, false);
                }
            }
        }
    }

    private void encodeToVideoTrack(int index) {
        ByteBuffer encodedData = mVideoCodec.getOutputBuffer(index);

        if ((mVideoBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {//是编码需要的特定数据，不是媒体数据
            // The codec config data was pulled out and fed to the muxer when we got
            // the INFO_OUTPUT_FORMAT_CHANGED status.
            // Ignore it.
//            Log.i(TAG, "ignoring BUFFER_FLAG_CODEC_CONFIG");
            mVideoBufferInfo.size = 0;
        }
        if (mVideoBufferInfo.size == 0) {
//            Log.i(TAG, "info.size == 0, drop it.");
            encodedData = null;
        } else {
            Log.i(TAG, "encodeToVideoTrack got buffer, info: size=" + mVideoBufferInfo.size
                    + ", presentationTimeUs=" + mVideoBufferInfo.presentationTimeUs
                    + ", offset=" + mVideoBufferInfo.offset);
        }
        if (encodedData != null) {
            encodedData.position(mVideoBufferInfo.offset);
            encodedData.limit(mVideoBufferInfo.offset + mVideoBufferInfo.size);
            mMediaMuxer.writeSampleData(mVideoTrackIndex, encodedData, mVideoBufferInfo);//写入
            Log.i(TAG, "encodeToVideoTrack sent " + mVideoBufferInfo.size + " bytes to muxer...");
        }
    }

    private void encodeToAudioTrack(int index) {
        ByteBuffer encodedData = mAudioCodec.getOutputBuffer(index);

        if ((mAudioBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {//是编码需要的特定数据，不是媒体数据
            // The codec config data was pulled out and fed to the muxer when we got
            // the INFO_OUTPUT_FORMAT_CHANGED status.
            // Ignore it.
//            Log.i(TAG, "ignoring BUFFER_FLAG_CODEC_CONFIG");
            mAudioBufferInfo.size = 0;
        }
        if (mAudioBufferInfo.size == 0) {
//            Log.i(TAG, "info.size == 0, drop it.");
            encodedData = null;
        } else {
            Log.i(TAG, "encodeToAudioTrack got buffer, info: size=" + mAudioBufferInfo.size
                    + ", presentationTimeUs=" + mAudioBufferInfo.presentationTimeUs
                    + ", offset=" + mAudioBufferInfo.offset);
        }
        if (encodedData != null) {
            encodedData.position(mAudioBufferInfo.offset);
            encodedData.limit(mAudioBufferInfo.offset + mAudioBufferInfo.size);
            mMediaMuxer.writeSampleData(mAudioTrackIndex, encodedData, mAudioBufferInfo);//写入
            Log.i(TAG, "encodeToAudioTrack sent " + mAudioBufferInfo.size + " bytes to muxer...");
        }
    }

    private void resetVideoOutputFormat() {
        // should happen before receiving buffers, and should only happen once
        if (mMuxerStarted) {
            throw new IllegalStateException("output format already changed!");
        }
        MediaFormat newFormat = mVideoCodec.getOutputFormat();
        Log.i(TAG, "resetVideoOutputFormat output format changed.\n new format: " + newFormat.toString());
        mVideoTrackIndex = mMediaMuxer.addTrack(newFormat);
        mNumTracksAdded++;
        if (mNumTracksAdded == 2) {
            mMediaMuxer.start();
            mMuxerStarted = true;
            Log.i(TAG, "resetVideoOutputFormat started media muxer, videoIndex=" + mVideoTrackIndex);
            Log.i(TAG, "resetVideoOutputFormat started media muxer, audioIndex=" + mAudioTrackIndex);
        }
    }

    private void resetAudioOutputFormat() {
        // should happen before receiving buffers, and should only happen once
        if (mMuxerStarted) {
            throw new IllegalStateException("output format already changed!");
        }
        MediaFormat newFormat = mAudioCodec.getOutputFormat();
        Log.i(TAG, "resetAudioOutputFormat output format changed.\n new format: " + newFormat.toString());
        mAudioTrackIndex = mMediaMuxer.addTrack(newFormat);
        mNumTracksAdded++;
        if (mNumTracksAdded == 2) {
            mMediaMuxer.start();
            mMuxerStarted = true;
            Log.i(TAG, "resetAudioOutputFormat started media muxer, videoIndex=" + mVideoTrackIndex);
            Log.i(TAG, "resetAudioOutputFormat started media muxer, audioIndex=" + mAudioTrackIndex);
        }
    }

    synchronized private void release() {
        mIsQuit.set(false);
        mMuxerStarted = false;
        Log.i(TAG, " release() ");
        if (mVideoCodec != null) {
            mVideoCodec.stop();
            mVideoCodec.release();
            mVideoCodec = null;
        }
        if (mAudioCodec != null) {
            mAudioCodec.stop();
            mAudioCodec.release();
            mAudioCodec = null;
        }
        if (mVirtualDisplay != null) {
            mVirtualDisplay.release();
            mVirtualDisplay = null;
        }
        if (mMediaMuxer != null) {
            mMediaMuxer.stop();
            mMediaMuxer.release();
            mMediaMuxer = null;
        }
//        if (mCamera != null) {
//            mCamera.setPreviewCallback(null);
//            mCamera.release();
//            mCamera = null;
//        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        release();
        if (mMediaProjection != null) {
            mMediaProjection.stop();
        }
        if (mCaptureLl != null) {
            mWindowManager.removeView(mCaptureLl);
        }
        stopForeground(true);
    }
}
