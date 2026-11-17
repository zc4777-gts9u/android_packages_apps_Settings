/* SPDX-License-Identifier: Apache-2.0 */
package com.android.settings.deviceinfo;

import android.os.Build;
import android.text.TextUtils;

/** Shared build label for About device and Android version, including signing tags. */
public final class BuildNumberUtils {
    private BuildNumberUtils() {}

    public static String getBuildNumber() {
        String display = Build.DISPLAY;
        if (!TextUtils.isEmpty(Build.TAGS) && !display.endsWith(" " + Build.TAGS)) {
            display += " " + Build.TAGS;
        }
        return display;
    }
}
