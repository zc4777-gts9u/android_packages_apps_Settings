/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.settings.biometrics.face;

import static android.hardware.biometrics.BiometricFaceConstants.FACE_ACQUIRED_VENDOR_BASE;

import android.hardware.face.FaceEnrollStages;

import com.android.settings.R;

/** Samsung enrollment pose guidance, enabled by a device overlay. */
final class SamsungFaceEnrollGuidance {
    static final int GLASSES_HELP_CODE = FACE_ACQUIRED_VENDOR_BASE + 1016;
    private SamsungFaceEnrollGuidance() {}

    static boolean isGlassesHelpCode(int helpCode) {
        return helpCode == GLASSES_HELP_CODE;
    }

    static boolean isPoseHelpCode(int helpCode) {
        switch (helpCode - FACE_ACQUIRED_VENDOR_BASE) {
            case 1024:
            case 1025:
            case 1029:
            case 1030:
            case 1050:
            case 1051:
                return true;
            default:
                return false;
        }
    }

    static int getMessageResId(int helpCode, int stage) {
        // FaceCallback adds FACE_ACQUIRED_VENDOR_BASE before delivering vendor codes.
        switch (helpCode - FACE_ACQUIRED_VENDOR_BASE) {
            case 1024:
            case 1029:
                return R.string.face_enroll_pose_look_ahead;
            case 1025:
            case 1030:
                return R.string.face_enroll_pose_look_up;
            case 1050:
            case 1051:
                if (stage == FaceEnrollStages.HOLD_STILL_IN_CENTER) {
                    return R.string.face_enroll_pose_look_ahead;
                }
                if (stage == FaceEnrollStages.ENROLLING_MOVEMENT_1) {
                    return R.string.face_enroll_pose_look_up;
                }
                // Never infer a pose from progress if the HAL did not specify one.
                return 0;
            default:
                return 0;
        }
    }
}
