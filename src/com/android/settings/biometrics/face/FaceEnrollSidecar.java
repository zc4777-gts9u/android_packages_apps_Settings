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

import android.app.Activity;
import android.app.settings.SettingsEnums;
import android.content.Intent;
import android.content.res.Resources;
import android.hardware.face.FaceEnrollCell;
import android.hardware.face.FaceManager;
import android.view.Surface;

import com.android.settings.R;
import com.android.settings.biometrics.BiometricEnrollSidecar;

import java.util.Arrays;

/**
 * Sidecar fragment to handle the state around face enrollment
 */
public class FaceEnrollSidecar extends BiometricEnrollSidecar {

    private final int[] mDisabledFeatures;

    private FaceUpdater mFaceUpdater;
    private Resources mResources;
    private boolean mWaitingForGlassesRemoval;
    private boolean mGlassesStageHandled;

    private Intent mIntent;

    private final Surface mPreviewSurface;

    public FaceEnrollSidecar(int[] disabledFeatures, Intent intent, Surface previewSurface) {
        mDisabledFeatures = Arrays.copyOf(disabledFeatures, disabledFeatures.length);
        mIntent = intent;
        mPreviewSurface = previewSurface;
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        mFaceUpdater = new FaceUpdater(activity.getApplicationContext());
        mResources = activity.getResources();
    }

    @Override
    public void startEnrollment() {
        super.startEnrollment();
        mFaceUpdater.enroll(mUserId, mToken, mEnrollmentCancel,
                mEnrollmentCallback, mDisabledFeatures, mPreviewSurface,
                false /* debugConsent */, mIntent);
    }

    boolean isWaitingForGlassesRemoval() {
        return mWaitingForGlassesRemoval;
    }

    public void resumeEnrollment() {
        if (!mWaitingForGlassesRemoval) return;
        if (mFaceUpdater.resumeEnrollment()) {
            mWaitingForGlassesRemoval = false;
            mGlassesStageHandled = true;
        } else {
            super.onEnrollmentError(
                    android.hardware.biometrics.BiometricFaceConstants.FACE_ERROR_HW_UNAVAILABLE,
                    mResources.getText(
                            R.string.security_settings_face_enroll_error_generic_dialog_message));
        }
    }

    private FaceManager.EnrollmentCallback mEnrollmentCallback
            = new FaceManager.EnrollmentCallback() {

        @Override
        public void onEnrollmentProgress(int remaining) {
            FaceEnrollSidecar.super.onEnrollmentProgress(remaining);
        }

        @Override
        public void onEnrollmentFrame(int helpCode, CharSequence helpMessage,
                FaceEnrollCell cell, int stage, float pan, float tilt, float distance) {
            if (mResources.getBoolean(R.bool.config_face_enroll_samsung_pose_guidance)) {
                final int message = SamsungFaceEnrollGuidance.getMessageResId(helpCode, stage);
                if (message != 0) {
                    helpMessage = mResources.getText(message);
                }
            }
            onEnrollmentHelp(helpCode, helpMessage);
        }

        @Override
        public void onEnrollmentHelp(int helpMsgId, CharSequence helpString) {
            if (mResources.getBoolean(R.bool.config_face_enroll_samsung_pose_guidance)
                    && SamsungFaceEnrollGuidance.isGlassesHelpCode(helpMsgId)) {
                if (mGlassesStageHandled) return;
                mWaitingForGlassesRemoval = true;
            }
            FaceEnrollSidecar.super.onEnrollmentHelp(helpMsgId, helpString);
        }

        @Override
        public void onEnrollmentError(int errMsgId, CharSequence errString) {
            FaceEnrollSidecar.super.onEnrollmentError(errMsgId, errString);
        }
    };

    @Override
    public int getMetricsCategory() {
        return SettingsEnums.FACE_ENROLL_SIDECAR;
    }
}
