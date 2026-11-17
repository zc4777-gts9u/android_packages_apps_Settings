/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.settings.biometrics.face;

import android.app.settings.SettingsEnums;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Size;
import android.util.TypedValue;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;

import com.android.settings.R;
import com.android.settings.biometrics.BiometricEnrollSidecar;
import com.android.settings.core.InstrumentedPreferenceFragment;

import java.util.Arrays;

/**
 * Fragment that contains the logic for showing and controlling the camera preview, circular
 * overlay, as well as the enrollment animations.
 */
public class FaceEnrollPreviewFragment extends InstrumentedPreferenceFragment
        implements BiometricEnrollSidecar.Listener {

    private static final String TAG = "FaceEnrollPreviewFragment";

    private static final long PREVIEW_REVEAL_FALLBACK_MS = 600;

    private static final int MAX_PREVIEW_WIDTH = 1920;
    private static final int MAX_PREVIEW_HEIGHT = 1080;

    private Handler mHandler = new Handler(Looper.getMainLooper());
    private CameraManager mCameraManager;
    private DisplayManager mDisplayManager;
    private String mCameraId;
    private CameraDevice mCameraDevice;
    private CaptureRequest.Builder mPreviewRequestBuilder;
    private CameraCaptureSession mCaptureSession;
    private CaptureRequest mPreviewRequest;
    private Size mPreviewSize;
    private ParticleCollection.Listener mListener;
    private boolean mHalOwnsCamera;
    private boolean mHalUsesTextureView;
    private Surface mPreviewSurface;
    private SurfaceView mSurfaceView;
    private Runnable mSurfaceReadyListener;
    private boolean mPreviewRevealed;

    // View used to contain the circular cutout and enrollment animation drawable
    private FaceEnrollProgressView mProgressView;

    // Texture used for showing the camera preview
    private FaceSquareTextureView mTextureView;

    // Listener sent to the animation drawable
    private final ParticleCollection.Listener mAnimationListener
            = new ParticleCollection.Listener() {
        @Override
        public void onEnrolled() {
            mListener.onEnrolled();
        }
    };

    private final SurfaceHolder.Callback mSurfaceHolderCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            mPreviewSurface = holder.getSurface();
            notifySurfaceReady();
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            mPreviewSurface = null;
        }
    };

    private final DisplayManager.DisplayListener mDisplayListener =
            new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {}

        @Override
        public void onDisplayRemoved(int displayId) {}

        @Override
        public void onDisplayChanged(int displayId) {
            final Display display = mTextureView.getDisplay();
            if (display != null && display.getDisplayId() == displayId) {
                updatePreviewRotation();
            }
        }
    };

    private final TextureView.SurfaceTextureListener mSurfaceTextureListener =
            new TextureView.SurfaceTextureListener() {

        @Override
        public void onSurfaceTextureAvailable(
                SurfaceTexture surfaceTexture, int width, int height) {
            if (mHalUsesTextureView) {
                configureHalTextureTransform(width, height);
                if (mPreviewSurface == null) {
                    mPreviewSurface = new Surface(surfaceTexture);
                }
                notifySurfaceReady();
            } else {
                openCamera(width, height);
            }
        }

        @Override
        public void onSurfaceTextureSizeChanged(
                SurfaceTexture surfaceTexture, int width, int height) {
            if (mHalUsesTextureView) {
                configureHalTextureTransform(width, height);
            } else {
                configureTransform(width, height);
            }
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
            releaseHalTextureSurface();
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {

        }
    };

    private final CameraDevice.StateCallback mCameraStateCallback =
            new CameraDevice.StateCallback() {

        @Override
        public void onOpened(CameraDevice cameraDevice) {
            SurfaceTexture texture = mTextureView.getSurfaceTexture();
            if (!isResumed() || texture == null) {
                cameraDevice.close();
                return;
            }
            mCameraDevice = cameraDevice;
            try {
                // Configure the size of default buffer
                texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());

                // This is the output Surface we need to start preview
                Surface surface = new Surface(texture);

                // Set up a CaptureRequest.Builder with the output Surface
                mPreviewRequestBuilder =
                        mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                mPreviewRequestBuilder.addTarget(surface);

                // Create a CameraCaptureSession for camera preview
                mCameraDevice.createCaptureSession(Arrays.asList(surface),
                    new CameraCaptureSession.StateCallback() {

                        @Override
                        public void onConfigured(CameraCaptureSession cameraCaptureSession) {
                            if (mCameraDevice != cameraDevice) {
                                cameraCaptureSession.close();
                                return;
                            }
                            // When the session is ready, we start displaying the preview.
                            mCaptureSession = cameraCaptureSession;
                            try {
                                // Auto focus should be continuous for camera preview.
                                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

                                // Finally, we start displaying the camera preview.
                                mPreviewRequest = mPreviewRequestBuilder.build();
                                mCaptureSession.setRepeatingRequest(mPreviewRequest,
                                        null /* listener */, mHandler);
                            } catch (CameraAccessException e) {
                                Log.e(TAG, "Unable to access camera", e);
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession cameraCaptureSession) {
                            Log.e(TAG, "Unable to configure camera");
                        }
                    }, null /* handler */);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onDisconnected(CameraDevice cameraDevice) {
            cameraDevice.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(CameraDevice cameraDevice, int error) {
            cameraDevice.close();
            mCameraDevice = null;
        }
    };

    @Override
    public int getMetricsCategory() {
        return SettingsEnums.FACE_ENROLL_PREVIEW;
    }

    void bindViews(FaceEnrollEnrolling activity) {
        if (mTextureView != null) {
            return;
        }

        // Fragment restoration can run before the activity has inflated its layout.
        mHalOwnsCamera = activity.getResources().getBoolean(R.bool.config_face_enroll_hal_owns_camera);
        mTextureView = activity.requireViewById(R.id.texture_view);
        mSurfaceView = activity.requireViewById(R.id.surface_view);
        mProgressView = activity.requireViewById(R.id.progress_view);

        if (mHalOwnsCamera) {
            // The face HAL keeps rendering into the initial preview surface, which is gone once
            // the activity is recreated. Unlike portrait, locked is respected on large screens.
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
            mHalUsesTextureView = activity.getResources().getBoolean(
                    R.bool.config_face_enroll_hal_preview_uses_texture_view);
            if (mHalUsesTextureView) {
                return;
            }

            // The HAL renders into a SurfaceView buffer.
            mTextureView.setVisibility(View.GONE);

            // The preview surface is a separate, opaque layer that is not affected by the page's
            // enter animation, so creating it early briefly reveals an unmasked square through the
            // still-fading page. Keep it hidden until the page has settled.
            mSurfaceView.postDelayed(this::revealSurface, PREVIEW_REVEAL_FALLBACK_MS);

            // Pin the surface to a size the HAL supports.
            mSurfaceView.getHolder().setFixedSize(
                    activity.getResources().getInteger(R.integer.face_preview_width),
                    activity.getResources().getInteger(R.integer.face_preview_height));

            return;
        }

        mCameraManager = activity.getSystemService(CameraManager.class);
        if (activity.getResources().getBoolean(R.bool.config_face_enroll_follow_user_rotation)) {
            mDisplayManager = activity.getSystemService(DisplayManager.class);
        }
    }

    public void revealSurface() {
        if (!mHalOwnsCamera || mHalUsesTextureView || mSurfaceView == null || mPreviewRevealed) {
            return;
        }
        mPreviewRevealed = true;
        mSurfaceView.setVisibility(View.VISIBLE);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mTextureView == null) {
            return;
        }

        if (mHalOwnsCamera && !mHalUsesTextureView) {
            // The face HAL opens the camera itself and renders into this SurfaceView.
            final SurfaceHolder holder = mSurfaceView.getHolder();
            holder.addCallback(mSurfaceHolderCallback);
            if (holder.getSurface() != null && holder.getSurface().isValid()) {
                mPreviewSurface = holder.getSurface();
                notifySurfaceReady();
            }
            return;
        }

        if (mDisplayManager != null) {
            mDisplayManager.registerDisplayListener(mDisplayListener, mHandler);
            updatePreviewRotation();
        }

        // When the screen is turned off and turned back on, the SurfaceTexture is already
        // available, and "onSurfaceTextureAvailable" will not be called. In that case, we can open
        // a camera and start preview from here (otherwise, we wait until the surface is ready in
        // the SurfaceTextureListener).
        mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        if (mTextureView.isAvailable()) {
            mSurfaceTextureListener.onSurfaceTextureAvailable(mTextureView.getSurfaceTexture(),
                    mTextureView.getWidth(), mTextureView.getHeight());
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mTextureView == null) {
            return;
        }

        if (mHalOwnsCamera) {
            if (!mHalUsesTextureView) {
                mSurfaceView.getHolder().removeCallback(mSurfaceHolderCallback);
                mPreviewSurface = null;
            }
        } else {
            if (mDisplayManager != null) {
                mDisplayManager.unregisterDisplayListener(mDisplayListener);
            }
            mTextureView.setSurfaceTextureListener(null);
            closeCamera();
        }
    }

    @Override
    public void onDestroy() {
        releaseHalTextureSurface();
        super.onDestroy();
    }

    private void releaseHalTextureSurface() {
        if (mHalUsesTextureView && mPreviewSurface != null) {
            mPreviewSurface.release();
            mPreviewSurface = null;
        }
    }

    @Override
    public void onEnrollmentError(int errMsgId, CharSequence errString) {
        mProgressView.setErrorState();
    }

    @Override
    public void onEnrollmentHelp(int helpMsgId, CharSequence helpString) {
        mProgressView.setHelpState();
    }

    @Override
    public void onEnrollmentProgressChange(int steps, int remaining) {
        mProgressView.setProgress(steps, remaining);
        if (remaining == 0) mProgressView.setCompleteState();
    }

    public void setListener(ParticleCollection.Listener listener) {
        mListener = listener;
    }

    /** Runs the listener once the surface returned by {@link #getPreviewSurface} is ready. */
    public void setSurfaceReadyListener(Runnable listener) {
        mSurfaceReadyListener = listener;
        notifySurfaceReady();
    }

    /** Returns the surface the face HAL should render its camera preview into. */
    public Surface getPreviewSurface() {
        return mPreviewSurface;
    }

    private void notifySurfaceReady() {
        if (mPreviewSurface != null && mSurfaceReadyListener != null) {
            final Runnable listener = mSurfaceReadyListener;
            mSurfaceReadyListener = null;
            listener.run();
        }
    }

    /**
     * Sets up member variables related to camera.
     */
    private void setUpCameraOutputs() {
        try {
            for (String cameraId : mCameraManager.getCameraIdList()) {
                CameraCharacteristics characteristics =
                        mCameraManager.getCameraCharacteristics(cameraId);

                // Find front facing camera
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing == null || facing != CameraCharacteristics.LENS_FACING_FRONT) {
                    continue;
                }
                mCameraId = cameraId;

                // Get the stream configurations
                StreamConfigurationMap map = characteristics.get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class));
                break;
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Unable to access camera", e);
        }
    }

    /**
     * Opens the camera specified by mCameraId.
     * @param width  The width of the texture view
     * @param height The height of the texture view
     */
    private void openCamera(int width, int height) {
        try {
            setUpCameraOutputs();
            mCameraManager.openCamera(mCameraId, mCameraStateCallback, mHandler);
            configureTransform(width, height);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Unable to open camera", e);
        }
    }

    /**
     * Chooses the optimal resolution for the camera to open.
     */
    private Size chooseOptimalSize(Size[] choices) {
        for (int i = 0; i < choices.length; i++) {
            if (choices[i].getHeight() == MAX_PREVIEW_HEIGHT
                    && choices[i].getWidth() == MAX_PREVIEW_WIDTH) {
                return choices[i];
            }
        }
        Log.w(TAG, "Unable to find a good resolution");
        return choices[0];
    }

    /**
     * Configures the necessary {@link android.graphics.Matrix} transformation to `mTextureView`.
     * This method should be called after the camera preview size is determined in
     * setUpCameraOutputs and also the size of `mTextureView` is fixed.
     *
     * @param viewWidth  The width of `mTextureView`
     * @param viewHeight The height of `mTextureView`
     */
    private void configureTransform(int viewWidth, int viewHeight) {
        if (mTextureView == null) {
            return;
        }

        // Fix the aspect ratio
        float scaleX = (float) viewWidth / mPreviewSize.getWidth();
        float scaleY = (float) viewHeight / mPreviewSize.getHeight();

        // Now divide by smaller one so it fills up the original space.
        float smaller = Math.min(scaleX, scaleY);
        scaleX = scaleX / smaller;
        scaleY = scaleY / smaller;

        final TypedValue tx = new TypedValue();
        final TypedValue ty = new TypedValue();
        final TypedValue scale = new TypedValue();
        getResources().getValue(R.dimen.face_preview_translate_x, tx, true /* resolveRefs */);
        getResources().getValue(R.dimen.face_preview_translate_y, ty, true /* resolveRefs */);
        getResources().getValue(R.dimen.face_preview_scale, scale, true /* resolveRefs */);

        // Apply the transformation/scale
        final Matrix transform = new Matrix();
        mTextureView.getTransform(transform);
        transform.setScale(scaleX * scale.getFloat(), scaleY * scale.getFloat());
        transform.postTranslate(tx.getFloat(), ty.getFloat());
        mTextureView.setTransform(transform);
        updatePreviewRotation();
    }

    private void configureHalTextureTransform(int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        final Display display = mTextureView.getDisplay();
        final int displayRotation = display == null ? Surface.ROTATION_0 : display.getRotation();
        final int sensorOrientation = getResources().getInteger(
                com.android.internal.R.integer.config_faceAuthVendorSensorOrientation);
        final int bufferRotation = sensorOrientation < 0 ? 270
                : (sensorOrientation + displayRotation * 90) % 360;
        // The Samsung HAL applies the same angle to preview and recognition.
        // Crop according to its rotated buffer dimensions; an extra view rotation
        // would rotate the preview twice after the HAL has been synchronized.
        final boolean swapDimensions = bufferRotation == 90 || bufferRotation == 270;
        final float cameraWidth = getResources().getInteger(R.integer.face_preview_width);
        final float cameraHeight = getResources().getInteger(R.integer.face_preview_height);
        final float bufferWidth = swapDimensions ? cameraHeight : cameraWidth;
        final float bufferHeight = swapDimensions ? cameraWidth : cameraHeight;
        final float scale = Math.max(width / bufferWidth, height / bufferHeight);
        final Matrix transform = new Matrix();
        transform.setScale(bufferWidth * scale / width, bufferHeight * scale / height,
                width / 2f, height / 2f);
        mTextureView.setTransform(transform);
    }

    private void updatePreviewRotation() {
        if (mDisplayManager == null) {
            return;
        }
        final Display display = mTextureView.getDisplay();
        if (display != null) {
            // The texture already applies the sensor transform. Rotate the square preview view
            // about its center to account for the current display rotation.
            mTextureView.setRotation(-90f * display.getRotation());
        }
    }

    private void closeCamera() {
        if (mCaptureSession != null) {
            mCaptureSession.close();
            mCaptureSession = null;
        }
        if (mCameraDevice != null) {
            mCameraDevice.close();
            mCameraDevice = null;
        }
    }
}
