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

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/** Square preview layout that fits within the available width and height. */
public class FaceSquareFrameLayout extends FrameLayout {

    public FaceSquareFrameLayout(Context context) {
        super(context);
    }

    public FaceSquareFrameLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public FaceSquareFrameLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public FaceSquareFrameLayout(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    public void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int width = MeasureSpec.getSize(widthMeasureSpec);
        final int height = MeasureSpec.getSize(heightMeasureSpec);
        final int size = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED
                ? width : Math.min(width, height);
        setMeasuredDimension(size, size);

        // Set the children to be the same size (square) as well
        final int numChildren = getChildCount();
        for (int i = 0; i < numChildren; i++) {
            int spec = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY);
            this.getChildAt(i).measure(spec, spec);
        }
    }
}
