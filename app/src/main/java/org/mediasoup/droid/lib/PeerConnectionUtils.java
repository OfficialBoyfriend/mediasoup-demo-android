package org.mediasoup.droid.lib;

import android.content.Context;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.MainThread;

import org.mediasoup.droid.Logger;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera1Enumerator;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.Logging;
import org.webrtc.MediaConstraints;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.ThreadUtils;
import org.webrtc.VideoDecoderFactory;
import org.webrtc.VideoEncoderFactory;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;
import org.webrtc.audio.AudioDeviceModule;
import org.webrtc.audio.JavaAudioDeviceModule;

import java.io.File;
import java.io.IOException;

@SuppressWarnings("WeakerAccess")
public class PeerConnectionUtils {

    private static final String TAG = "PeerConnectionUtils";
    private static String mPreferCameraFace;
    private static final EglBase mEglBase = EglBase.create();

    private final ThreadUtils.ThreadChecker mThreadChecker = new ThreadUtils.ThreadChecker();
    private PeerConnectionFactory mPeerConnectionFactory;
    private AudioSource mAudioSource;
    private VideoSource mVideoSource;
    private CameraVideoCapturer mCamCapture;

    public PeerConnectionUtils() {
        Logging.enableLogToDebugOutput(Logging.Severity.LS_VERBOSE);
    }

    public static EglBase.Context getEglContext() {
        return mEglBase.getEglBaseContext();
    }

    public static void setPreferCameraFace(String preferCameraFace) {
        mPreferCameraFace = preferCameraFace;
    }

    // PeerConnection factory creation.
    private void createPeerConnectionFactory(Context context) {
        Logger.d(TAG, "createPeerConnectionFactory()");
        mThreadChecker.checkIsOnValidThread();

        // TODO(zeronumber)
        // startInternalTracingCapture(
        //          Environment.getExternalStorageDirectory().getAbsolutePath() + File.separator
        //          + "webrtc-trace.txt");
        PeerConnectionFactory.Builder builder = PeerConnectionFactory.builder();
        builder.setOptions(null);

        AudioDeviceModule adm = createJavaAudioDevice(context);
        VideoEncoderFactory encoderFactory = new DefaultVideoEncoderFactory(mEglBase.getEglBaseContext(), true, true);
        VideoDecoderFactory decoderFactory = new DefaultVideoDecoderFactory(mEglBase.getEglBaseContext());

        mPeerConnectionFactory = builder.setAudioDeviceModule(adm).setVideoEncoderFactory(encoderFactory).setVideoDecoderFactory(decoderFactory).createPeerConnectionFactory();

        try {
            ParcelFileDescriptor aecDumpFileDescriptor = ParcelFileDescriptor.open(new File(Environment.getExternalStorageDirectory().getPath() + File.separator + "Download/audio.aecdump"), ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE);
            mPeerConnectionFactory.startAecDump(aecDumpFileDescriptor.detachFd(), -1);
        } catch (IOException e) {
            Log.e(TAG, "Can not open aecdump file", e);
        }
    }

    private AudioDeviceModule createJavaAudioDevice(Context appContext) {
        Logger.d(TAG, "createJavaAudioDevice()");
        mThreadChecker.checkIsOnValidThread();
        // Enable/disable OpenSL ES playback.
        // Set audio record error callbacks.
        JavaAudioDeviceModule.AudioRecordErrorCallback audioRecordErrorCallback = new JavaAudioDeviceModule.AudioRecordErrorCallback() {
            @Override
            public void onWebRtcAudioRecordInitError(String errorMessage) {
                Logger.e(TAG, "onWebRtcAudioRecordInitError: " + errorMessage);
            }

            @Override
            public void onWebRtcAudioRecordStartError(JavaAudioDeviceModule.AudioRecordStartErrorCode errorCode, String errorMessage) {
                Logger.e(TAG, "onWebRtcAudioRecordStartError: " + errorCode + ". " + errorMessage);
            }

            @Override
            public void onWebRtcAudioRecordError(String errorMessage) {
                Logger.e(TAG, "onWebRtcAudioRecordError: " + errorMessage);
            }
        };

        JavaAudioDeviceModule.AudioTrackErrorCallback audioTrackErrorCallback = new JavaAudioDeviceModule.AudioTrackErrorCallback() {
            @Override
            public void onWebRtcAudioTrackInitError(String errorMessage) {
                Log.e(TAG, "onWebRtcAudioTrackInitError: " + errorMessage);
            }

            @Override
            public void onWebRtcAudioTrackStartError(JavaAudioDeviceModule.AudioTrackStartErrorCode errorCode, String errorMessage) {
                Log.e(TAG, "onWebRtcAudioTrackStartError: " + errorCode + ". " + errorMessage);
            }

            @Override
            public void onWebRtcAudioTrackError(String errorMessage) {
                Log.e(TAG, "onWebRtcAudioTrackError: " + errorMessage);
            }
        };

        // ...
        // final boolean isDeviceSupportHWAec = WebRtcAudioEffects.canUseAcousticEchoCanceler();
        // final boolean isDeviceSupportHWNs = WebRtcAudioEffects.canUseNoiseSuppressor();

        return JavaAudioDeviceModule.builder(appContext).setAudioRecordErrorCallback(audioRecordErrorCallback).setAudioTrackErrorCallback(audioTrackErrorCallback).createAudioDeviceModule();
    }

    private void createAudioSource(Context context) {
        Logger.d(TAG, "createAudioSource()");
        mThreadChecker.checkIsOnValidThread();
        if (mPeerConnectionFactory == null) {
            createPeerConnectionFactory(context);
        }

        final MediaConstraints audioConstraints = new MediaConstraints();
        // 回声消除
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googEchoCancellation", "true"));
        // 自动增益
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googAutoGainControl", "true"));
        // 高音过滤
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googHighpassFilter", "true"));
        // 噪音处理
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googNoiseSuppression", "true"));

        // mAudioSource = mPeerConnectionFactory.createAudioSource(new MediaConstraints());
        mAudioSource = mPeerConnectionFactory.createAudioSource(audioConstraints);
    }

    private void createCamCapture(Context context) {
        Logger.d(TAG, "createCamCapture()");
        mThreadChecker.checkIsOnValidThread();
        boolean isCamera2Supported = Camera2Enumerator.isSupported(context);
        CameraEnumerator cameraEnumerator;

        if (isCamera2Supported) {
            cameraEnumerator = new Camera2Enumerator(context);
        } else {
            cameraEnumerator = new Camera1Enumerator();
        }
        final String[] deviceNames = cameraEnumerator.getDeviceNames();
        for (String deviceName : deviceNames) {
            boolean needFrontFacing = "front".endsWith(mPreferCameraFace);
            String selectedDeviceName = null;
            if (needFrontFacing) {
                if (cameraEnumerator.isFrontFacing(deviceName)) {
                    selectedDeviceName = deviceName;
                }
            } else {
                if (!cameraEnumerator.isFrontFacing(deviceName)) {
                    selectedDeviceName = deviceName;
                }
            }

            if (!TextUtils.isEmpty(selectedDeviceName)) {
                mCamCapture = cameraEnumerator.createCapturer(selectedDeviceName, new CameraVideoCapturer.CameraEventsHandler() {
                    @Override
                    public void onCameraError(String s) {
                        Logger.e(TAG, "onCameraError, " + s);
                    }

                    @Override
                    public void onCameraDisconnected() {
                        Logger.w(TAG, "onCameraDisconnected");
                    }

                    @Override
                    public void onCameraFreezed(String s) {
                        Logger.w(TAG, "onCameraFreezed, " + s);
                    }

                    @Override
                    public void onCameraOpening(String s) {
                        Logger.d(TAG, "onCameraOpening, " + s);
                    }

                    @Override
                    public void onFirstFrameAvailable() {
                        Logger.d(TAG, "onFirstFrameAvailable");
                    }

                    @Override
                    public void onCameraClosed() {
                        Logger.d(TAG, "onCameraClosed");
                    }
                });
                break;
            }
        }

        if (mCamCapture == null) {
            throw new IllegalStateException("Failed to create Camera Capture");
        }
    }

    public void switchCam(CameraVideoCapturer.CameraSwitchHandler switchHandler) {
        Logger.d(TAG, "switchCam()");
        mThreadChecker.checkIsOnValidThread();
        if (mCamCapture != null) {
            mCamCapture.switchCamera(switchHandler);
        }
    }

    // Video source creation.
    @MainThread
    private void createVideoSource(Context context) {
        Logger.d(TAG, "createVideoSource()");
        mThreadChecker.checkIsOnValidThread();
        if (mPeerConnectionFactory == null) {
            createPeerConnectionFactory(context);
        }
        if (mCamCapture == null) {
            createCamCapture(context);
        }

        mVideoSource = mPeerConnectionFactory.createVideoSource(false);
        SurfaceTextureHelper surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", mEglBase.getEglBaseContext());

        mCamCapture.initialize(surfaceTextureHelper, context, mVideoSource.getCapturerObserver());
        mCamCapture.startCapture(640, 480, 30);
    }

    // Audio track creation.
    public AudioTrack createAudioTrack(Context context, String id) {
        Logger.d(TAG, "createAudioTrack()");
        mThreadChecker.checkIsOnValidThread();
        if (mAudioSource == null) {
            createAudioSource(context);
        }
        return mPeerConnectionFactory.createAudioTrack(id, mAudioSource);
    }

    // Video track creation.
    public VideoTrack createVideoTrack(Context context, String id) {
        Logger.d(TAG, "createVideoTrack()");
        mThreadChecker.checkIsOnValidThread();
        if (mVideoSource == null) {
            createVideoSource(context);
        }
        return mPeerConnectionFactory.createVideoTrack(id, mVideoSource);
    }

    public void dispose() {
        Logger.w(TAG, "dispose()");

        mThreadChecker.checkIsOnValidThread();

        if (mCamCapture != null) {
            mCamCapture.dispose();
            mCamCapture = null;
        }

        if (mVideoSource != null) {
            mVideoSource.dispose();
            mVideoSource = null;
        }

        if (mAudioSource != null) {
            mAudioSource.dispose();
            mAudioSource = null;
        }

        if (mPeerConnectionFactory != null) {
            mPeerConnectionFactory.stopAecDump();
            mPeerConnectionFactory.dispose();
            mPeerConnectionFactory = null;
        }
    }
}
