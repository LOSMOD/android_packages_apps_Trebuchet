/*
 * Copyright (C) 2021 The Android Open Source Project
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
 * limitations under the License.
 */
package com.android.launcher3.taskbar;

import static com.android.launcher3.LauncherAnimUtils.SCALE_PROPERTY;
import static com.android.launcher3.LauncherAnimUtils.VIEW_ALPHA;
import static com.android.launcher3.LauncherAnimUtils.VIEW_TRANSLATE_X;
import static com.android.launcher3.LauncherAnimUtils.VIEW_TRANSLATE_Y;
import static com.android.launcher3.Utilities.squaredHypot;
import static com.android.launcher3.anim.AnimatedFloat.VALUE;
import static com.android.launcher3.anim.AnimatorListeners.forEndCallback;
import static com.android.launcher3.anim.Interpolators.FINAL_FRAME;
import static com.android.launcher3.anim.Interpolators.LINEAR;
import static com.android.launcher3.logging.StatsLogManager.LauncherEvent.LAUNCHER_TASKBAR_ALLAPPS_BUTTON_TAP;
import static com.android.launcher3.taskbar.TaskbarManager.isPhoneMode;
import static com.android.launcher3.touch.SingleAxisSwipeDetector.DIRECTION_NEGATIVE;
import static com.android.launcher3.touch.SingleAxisSwipeDetector.VERTICAL;
import static com.android.launcher3.util.MultiPropertyFactory.MULTI_PROPERTY_VALUE;
import static com.android.launcher3.util.MultiTranslateDelegate.INDEX_TASKBAR_ALIGNMENT_ANIM;
import static com.android.launcher3.util.MultiTranslateDelegate.INDEX_TASKBAR_REVEAL_ANIM;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.annotation.NonNull;
import android.graphics.Rect;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.Interpolator;

import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.OneShotPreDrawListener;

import com.android.launcher3.DeviceProfile;
import com.android.launcher3.LauncherAppState;
import com.android.launcher3.R;
import com.android.launcher3.Reorderable;
import com.android.launcher3.Utilities;
import com.android.launcher3.anim.AlphaUpdateListener;
import com.android.launcher3.anim.AnimatedFloat;
import com.android.launcher3.anim.AnimatorPlaybackController;
import com.android.launcher3.anim.Interpolators;
import com.android.launcher3.anim.PendingAnimation;
import com.android.launcher3.anim.RevealOutlineAnimation;
import com.android.launcher3.anim.RoundedRectRevealOutlineProvider;
import com.android.launcher3.config.FeatureFlags;
import com.android.launcher3.icons.ThemedIconDrawable;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.touch.SingleAxisSwipeDetector;
import com.android.launcher3.util.DisplayController;
import com.android.launcher3.util.ItemInfoMatcher;
import com.android.launcher3.util.LauncherBindableItemsContainer;
import com.android.launcher3.util.MultiPropertyFactory;
import com.android.launcher3.util.MultiTranslateDelegate;
import com.android.launcher3.util.MultiValueAlpha;

import java.io.PrintWriter;
import java.util.function.Predicate;

/**
 * Handles properties/data collection, then passes the results to TaskbarView to render.
 */
public class TaskbarViewController implements TaskbarControllers.LoggableTaskbarController {

    private static final String TAG = TaskbarViewController.class.getSimpleName();

    private static final Runnable NO_OP = () -> { };

    public static final int ALPHA_INDEX_HOME = 0;
    public static final int ALPHA_INDEX_KEYGUARD = 1;
    public static final int ALPHA_INDEX_STASH = 2;
    public static final int ALPHA_INDEX_RECENTS_DISABLED = 3;
    public static final int ALPHA_INDEX_NOTIFICATION_EXPANDED = 4;
    public static final int ALPHA_INDEX_ASSISTANT_INVOKED = 5;
    public static final int ALPHA_INDEX_SMALL_SCREEN = 6;
    private static final int NUM_ALPHA_CHANNELS = 7;

    // This allows the icons on the edge to stay within the taskbar background bounds.
    private static final float ICON_REVEAL_X_DURATION_MULTIPLIER = 0.8f;

    private static final float TASKBAR_DARK_THEME_ICONS_BACKGROUND_LUMINANCE = 0.30f;

    private final TaskbarActivityContext mActivity;
    private final TaskbarView mTaskbarView;
    private final MultiValueAlpha mTaskbarIconAlpha;
    private final AnimatedFloat mTaskbarIconScaleForStash = new AnimatedFloat(this::updateScale);
    private final AnimatedFloat mTaskbarIconTranslationYForHome = new AnimatedFloat(
            this::updateTranslationY);
    private final AnimatedFloat mTaskbarIconTranslationYForStash = new AnimatedFloat(
            this::updateTranslationY);
    private AnimatedFloat mTaskbarNavButtonTranslationY;
    private AnimatedFloat mTaskbarNavButtonTranslationYForInAppDisplay;
    private float mTaskbarIconTranslationYForSwipe;

    private final int mTaskbarBottomMargin;
    private final int mStashedHandleHeight;
    private final int mLauncherThemedIconsBackgroundColor;
    private final int mTaskbarThemedIconsBackgroundColor;

    /** Progress from {@code 0} for Launcher's color to {@code 1} for Taskbar's color. */
    private final AnimatedFloat mThemedIconsBackgroundProgress = new AnimatedFloat(
            this::updateIconsBackground);

    private final TaskbarModelCallbacks mModelCallbacks;

    // Captures swipe down action to close transient Taskbar.
    protected @Nullable SingleAxisSwipeDetector mSwipeDownDetector;

    // Initialized in init.
    private TaskbarControllers mControllers;

    // Animation to align icons with Launcher, created lazily. This allows the controller to be
    // active only during the animation and does not need to worry about layout changes.
    private AnimatorPlaybackController mIconAlignControllerLazy = null;
    private Runnable mOnControllerPreCreateCallback = NO_OP;

    private boolean mIsHotseatIconOnTopWhenAligned;

    private final DeviceProfile.OnDeviceProfileChangeListener mDeviceProfileChangeListener =
            dp -> commitRunningAppsToUI();

    public TaskbarViewController(TaskbarActivityContext activity, TaskbarView taskbarView) {
        mActivity = activity;
        mTaskbarView = taskbarView;
        mTaskbarIconAlpha = new MultiValueAlpha(mTaskbarView, NUM_ALPHA_CHANNELS);
        mTaskbarIconAlpha.setUpdateVisibility(true);
        mModelCallbacks = new TaskbarModelCallbacks(activity, mTaskbarView);
        mTaskbarBottomMargin = DisplayController.isTransientTaskbar(activity)
                ? activity.getResources().getDimensionPixelSize(R.dimen.transient_taskbar_margin)
                : 0;
        mStashedHandleHeight = activity.getResources()
                .getDimensionPixelSize(R.dimen.taskbar_stashed_handle_height);
        mLauncherThemedIconsBackgroundColor = ThemedIconDrawable.getColors(mActivity)[0];
        if (!Utilities.isDarkTheme(mActivity)) {
            mTaskbarThemedIconsBackgroundColor = mLauncherThemedIconsBackgroundColor;
        } else {
            // Increase luminance for dark themed icons given they are on a dark Taskbar background.
            float[] colorHSL = new float[3];
            ColorUtils.colorToHSL(mLauncherThemedIconsBackgroundColor, colorHSL);
            colorHSL[2] = TASKBAR_DARK_THEME_ICONS_BACKGROUND_LUMINANCE;
            mTaskbarThemedIconsBackgroundColor = ColorUtils.HSLToColor(colorHSL);
        }

        if (DisplayController.isTransientTaskbar(mActivity)) {
            mSwipeDownDetector = new SingleAxisSwipeDetector(activity,
                    new SingleAxisSwipeDetector.Listener() {
                        private float mLastDisplacement;

                        @Override
                        public boolean onDrag(float displacement) {
                            mLastDisplacement = displacement;
                            return false;
                        }

                        @Override
                        public void onDragEnd(float velocity) {
                            if (mLastDisplacement > 0) {
                                mControllers.taskbarStashController
                                        .updateAndAnimateTransientTaskbar(true);
                            }
                        }

                        @Override
                        public void onDragStart(boolean start, float startDisplacement) {}
                    }, VERTICAL);
            mSwipeDownDetector.setDetectableScrollConditions(DIRECTION_NEGATIVE, false);
        }
    }

    public void init(TaskbarControllers controllers) {
        mControllers = controllers;
        mTaskbarView.init(new TaskbarViewCallbacks());
        mTaskbarView.getLayoutParams().height = isPhoneMode(mActivity.getDeviceProfile())
                ? mActivity.getResources().getDimensionPixelSize(R.dimen.taskbar_size)
                : mActivity.getDeviceProfile().taskbarSize;

        mTaskbarIconScaleForStash.updateValue(1f);

        mModelCallbacks.init(controllers);
        if (mActivity.isUserSetupComplete()) {
            // Only load the callbacks if user setup is completed
            LauncherAppState.getInstance(mActivity).getModel().addCallbacksAndLoad(mModelCallbacks);
        }
        mTaskbarNavButtonTranslationY =
                controllers.navbarButtonsViewController.getTaskbarNavButtonTranslationY();
        mTaskbarNavButtonTranslationYForInAppDisplay = controllers.navbarButtonsViewController
                .getTaskbarNavButtonTranslationYForInAppDisplay();

        mActivity.addOnDeviceProfileChangeListener(mDeviceProfileChangeListener);
    }

    /**
     * Announcement for Accessibility when Taskbar stashes/unstashes.
     */
    public void announceForAccessibility() {
        mTaskbarView.announceAccessibilityChanges();
    }

    public void onDestroy() {
        LauncherAppState.getInstance(mActivity).getModel().removeCallbacks(mModelCallbacks);
        mActivity.removeOnDeviceProfileChangeListener(mDeviceProfileChangeListener);
        mModelCallbacks.unregisterListeners();
    }

    public boolean areIconsVisible() {
        return mTaskbarView.areIconsVisible();
    }

    public MultiPropertyFactory<View> getTaskbarIconAlpha() {
        return mTaskbarIconAlpha;
    }

    /**
     * Should be called when the recents button is disabled, so we can hide Taskbar icons as well.
     */
    public void setRecentsButtonDisabled(boolean isDisabled) {
        // TODO: check TaskbarStashController#supportsStashing(), to stash instead of setting alpha.
        mTaskbarIconAlpha.get(ALPHA_INDEX_RECENTS_DISABLED).setValue(isDisabled ? 0 : 1);
    }

    /**
     * Sets OnClickListener and OnLongClickListener for the given view.
     */
    public void setClickAndLongClickListenersForIcon(View icon) {
        mTaskbarView.setClickAndLongClickListenersForIcon(icon);
    }

    /**
     * Adds one time pre draw listener to the Taskbar view, it is called before
     * drawing a frame and invoked only once
     * @param listener callback that will be invoked before drawing the next frame
     */
    public void addOneTimePreDrawListener(@NonNull Runnable listener) {
        OneShotPreDrawListener.add(mTaskbarView, listener);
    }

    public Rect getIconLayoutBounds() {
        return mTaskbarView.getIconLayoutBounds();
    }

    public int getIconLayoutWidth() {
        return mTaskbarView.getIconLayoutWidth();
    }

    public View[] getIconViews() {
        return mTaskbarView.getIconViews();
    }

    @Nullable
    public View getAllAppsButtonView() {
        return mTaskbarView.getAllAppsButtonView();
    }

    public AnimatedFloat getTaskbarIconScaleForStash() {
        return mTaskbarIconScaleForStash;
    }

    public AnimatedFloat getTaskbarIconTranslationYForStash() {
        return mTaskbarIconTranslationYForStash;
    }

    /**
     * Applies scale properties for the entire TaskbarView (rather than individual icons).
     */
    private void updateScale() {
        float scale = mTaskbarIconScaleForStash.value;
        mTaskbarView.setScaleX(scale);
        mTaskbarView.setScaleY(scale);
    }

    /**
     * Sets the translation of the TaskbarView during the swipe up gesture.
     */
    public void setTranslationYForSwipe(float transY) {
        mTaskbarIconTranslationYForSwipe = transY;
        updateTranslationY();
    }

    private void updateTranslationY() {
        mTaskbarView.setTranslationY(mTaskbarIconTranslationYForHome.value
                + mTaskbarIconTranslationYForStash.value
                + mTaskbarIconTranslationYForSwipe);
    }

    /**
     * Updates the Taskbar's themed icons background according to the progress between in-app/home.
     */
    protected void updateIconsBackground() {
        mTaskbarView.setThemedIconsBackgroundColor(
                ColorUtils.blendARGB(
                        mLauncherThemedIconsBackgroundColor,
                        mTaskbarThemedIconsBackgroundColor,
                        mThemedIconsBackgroundProgress.value
                ));
    }

    private ValueAnimator createRevealAnimForView(View view, boolean isStashed) {
        Rect viewBounds = new Rect(0, 0, view.getWidth(), view.getHeight());
        int centerY = viewBounds.centerY();
        int halfHandleHeight = mStashedHandleHeight / 2;

        Rect stashedRect = new Rect(viewBounds.left,
                centerY - halfHandleHeight,
                viewBounds.right,
                centerY + halfHandleHeight);

        float radius = 0;
        float stashedRadius = viewBounds.width() / 2f;

        return new RoundedRectRevealOutlineProvider(radius, stashedRadius, viewBounds, stashedRect)
                .createRevealAnimator(view, !isStashed, 0);
    }

    /**
     * Creates and returns a {@link RevealOutlineAnimation} Animator that updates the icon shape
     * and size.
     * @param as The AnimatorSet to add all animations to.
     * @param isStashed When true, the icon crops vertically to the size of the stashed handle.
     *                  When false, the reverse happens.
     * @param duration The duration of the animation.
     * @param interpolator The interpolator to use for all animations.
     */
    public void addRevealAnimToIsStashed(AnimatorSet as, boolean isStashed, long duration,
            Interpolator interpolator) {
        AnimatorSet reveal = new AnimatorSet();

        Rect stashedBounds = new Rect();
        mControllers.stashedHandleViewController.getStashedHandleBounds(stashedBounds);

        boolean isQsbInline = mActivity.getDeviceProfile().isQsbInline;
        int numIcons = mTaskbarView.getChildCount() - (isQsbInline ? 1 : 0);
        // We do not actually modify the width of the icons, but we will use this width to position
        // the children to overlay the nav handle.
        float virtualChildWidth = stashedBounds.width() / (float) numIcons;

        // All children move the same y-amount since they will be cropped to the same centerY.
        float croppedTransY = mTaskbarView.getIconTouchSize() - stashedBounds.height();

        boolean isRtl = Utilities.isRtl(mTaskbarView.getResources());
        for (int i = mTaskbarView.getChildCount() - 1; i >= 0; i--) {
            View child = mTaskbarView.getChildAt(i);
            boolean isQsb = child == mTaskbarView.getQsb();

            // Crop the icons to/from the nav handle shape.
            reveal.play(createRevealAnimForView(child, isStashed).setDuration(duration));

            // Translate the icons to/from their locations as the "nav handle."
            // We look at 'left' and 'right' values to ensure that the children stay within the
            // bounds of the stashed handle.

            // All of the Taskbar icons will overlap the entirety of the stashed handle
            // And the QSB, if inline, will overlap part of stashed handle as well.
            int positionInHandle = (isQsbInline && !isQsb)
                    ? i + (isRtl ? 1 : -1)
                    : i;
            float currentPosition = isQsb ? child.getX() : child.getLeft();
            float newPosition = stashedBounds.left + (virtualChildWidth * positionInHandle);
            final float croppedTransX;
            if (currentPosition > newPosition) {
                float newRight = stashedBounds.right - (virtualChildWidth
                        * (numIcons - 1 - positionInHandle));
                croppedTransX = -(currentPosition + child.getWidth() - newRight);
            } else {
                croppedTransX = newPosition - currentPosition;
            }

            long transXDuration = (long) (duration * ICON_REVEAL_X_DURATION_MULTIPLIER);
            float[] transX = isStashed
                    ? new float[] {croppedTransX}
                    : new float[] {croppedTransX, 0};
            float[] transY = isStashed
                    ? new float[] {croppedTransY}
                    : new float[] {croppedTransY, 0};

            if (child instanceof Reorderable) {
                MultiTranslateDelegate mtd = ((Reorderable) child).getTranslateDelegate();

                reveal.play(ObjectAnimator.ofFloat(mtd.getTranslationX(INDEX_TASKBAR_REVEAL_ANIM),
                        MULTI_PROPERTY_VALUE, transX)
                        .setDuration(transXDuration));
                reveal.play(ObjectAnimator.ofFloat(mtd.getTranslationY(INDEX_TASKBAR_REVEAL_ANIM),
                        MULTI_PROPERTY_VALUE, transY));
                as.addListener(forEndCallback(() ->
                        mtd.setTranslation(INDEX_TASKBAR_REVEAL_ANIM, 0, 0)));
            } else {
                reveal.play(ObjectAnimator.ofFloat(child, VIEW_TRANSLATE_X, transX)
                        .setDuration(transXDuration));
                reveal.play(ObjectAnimator.ofFloat(child, VIEW_TRANSLATE_Y, transY));
                as.addListener(forEndCallback(() -> {
                    child.setTranslationX(0);
                    child.setTranslationY(0);
                }));
            }
        }

        reveal.setInterpolator(interpolator);
        as.play(reveal);
    }

    /**
     * Sets the Taskbar icon alignment relative to Launcher hotseat icons
     * @param alignmentRatio [0, 1]
     *                       0 => not aligned
     *                       1 => fully aligned
     */
    public void setLauncherIconAlignment(float alignmentRatio, DeviceProfile launcherDp) {
        boolean isHotseatIconOnTopWhenAligned =
                mControllers.uiController.isHotseatIconOnTopWhenAligned();
        // When mIsHotseatIconOnTopWhenAligned changes, animation needs to be re-created.
        if (mIconAlignControllerLazy == null
                || mIsHotseatIconOnTopWhenAligned != isHotseatIconOnTopWhenAligned) {
            mIsHotseatIconOnTopWhenAligned = isHotseatIconOnTopWhenAligned;
            mIconAlignControllerLazy = createIconAlignmentController(launcherDp);
        }
        mIconAlignControllerLazy.setPlayFraction(alignmentRatio);
        if (alignmentRatio <= 0 || alignmentRatio >= 1) {
            // Cleanup lazy controller so that it is created again in next animation
            mIconAlignControllerLazy = null;
        }
    }

    /**
     * Creates an animation for aligning the Taskbar icons with the provided Launcher device profile
     */
    private AnimatorPlaybackController createIconAlignmentController(DeviceProfile launcherDp) {
        mOnControllerPreCreateCallback.run();
        PendingAnimation setter = new PendingAnimation(100);
        DeviceProfile taskbarDp = mActivity.getDeviceProfile();
        Rect hotseatPadding = launcherDp.getHotseatLayoutPadding(mActivity);
        float scaleUp = ((float) launcherDp.iconSizePx) / taskbarDp.iconSizePx;
        int borderSpacing = launcherDp.hotseatBorderSpace;
        int hotseatCellSize = DeviceProfile.calculateCellWidth(
                launcherDp.availableWidthPx - hotseatPadding.left - hotseatPadding.right,
                borderSpacing,
                launcherDp.numShownHotseatIcons);

        boolean isToHome = mControllers.uiController.isIconAlignedWithHotseat();
        // If Hotseat is not the top element, Taskbar should maintain in-app state as it fades out,
        // or fade in while already in in-app state.
        Interpolator interpolator = mIsHotseatIconOnTopWhenAligned ? LINEAR : FINAL_FRAME;

        int offsetY = launcherDp.getTaskbarOffsetY();
        setter.setFloat(mTaskbarIconTranslationYForHome, VALUE, -offsetY, interpolator);
        setter.setFloat(mTaskbarNavButtonTranslationY, VALUE, -offsetY, interpolator);
        setter.setFloat(mTaskbarNavButtonTranslationYForInAppDisplay, VALUE, offsetY, interpolator);

        if (Utilities.isDarkTheme(mTaskbarView.getContext())) {
            setter.addFloat(mThemedIconsBackgroundProgress, VALUE, 1f, 0f, LINEAR);
        }

        int collapsedHeight = mActivity.getDefaultTaskbarWindowHeight();
        int expandedHeight = Math.max(collapsedHeight, taskbarDp.taskbarSize + offsetY);
        setter.addOnFrameListener(anim -> mActivity.setTaskbarWindowHeight(
                anim.getAnimatedFraction() > 0 ? expandedHeight : collapsedHeight));

        for (int i = 0; i < mTaskbarView.getChildCount(); i++) {
            View child = mTaskbarView.getChildAt(i);
            boolean isAllAppsButton = child == mTaskbarView.getAllAppsButtonView();
            if (!mIsHotseatIconOnTopWhenAligned) {
                // When going to home, the EMPHASIZED interpolator in TaskbarLauncherStateController
                // plays iconAlignment to 1 really fast, therefore moving the fading towards the end
                // to avoid icons disappearing rather than fading out visually.
                setter.setViewAlpha(child, 0, Interpolators.clampToProgress(LINEAR, 0.8f, 1f));
            } else if ((isAllAppsButton && !FeatureFlags.ENABLE_ALL_APPS_BUTTON_IN_HOTSEAT.get())) {
                if (!isToHome
                        && mIsHotseatIconOnTopWhenAligned
                        && mControllers.taskbarStashController.isStashed()) {
                    // Prevent All Apps icon from appearing when going from hotseat to nav handle.
                    setter.setViewAlpha(child, 0, Interpolators.clampToProgress(LINEAR, 0f, 0f));
                } else {
                    setter.setViewAlpha(child, 0,
                            isToHome
                                    ? Interpolators.clampToProgress(LINEAR, 0f, 0.17f)
                                    : Interpolators.clampToProgress(LINEAR, 0.72f, 0.84f));
                }
            }

            if (child == mTaskbarView.getQsb()) {
                boolean isRtl = Utilities.isRtl(child.getResources());
                float hotseatIconCenter = isRtl
                        ? launcherDp.widthPx - hotseatPadding.right + borderSpacing
                        + launcherDp.hotseatQsbWidth / 2f
                        : hotseatPadding.left - borderSpacing - launcherDp.hotseatQsbWidth / 2f;
                float childCenter = (child.getLeft() + child.getRight()) / 2f;
                float halfQsbIconWidthDiff =
                        (launcherDp.hotseatQsbWidth - taskbarDp.iconSizePx) / 2f;
                float scale = ((float) taskbarDp.iconSizePx) / launcherDp.hotseatQsbVisualHeight;
                setter.addFloat(child, SCALE_PROPERTY, scale, 1f, interpolator);

                float fromX = isRtl ? -halfQsbIconWidthDiff : halfQsbIconWidthDiff;
                float toX = hotseatIconCenter - childCenter;
                if (child instanceof Reorderable) {
                    MultiTranslateDelegate mtd = ((Reorderable) child).getTranslateDelegate();

                    setter.addFloat(mtd.getTranslationX(INDEX_TASKBAR_ALIGNMENT_ANIM),
                            MULTI_PROPERTY_VALUE, fromX, toX, interpolator);
                    setter.setFloat(mtd.getTranslationY(INDEX_TASKBAR_ALIGNMENT_ANIM),
                            MULTI_PROPERTY_VALUE, mTaskbarBottomMargin, interpolator);
                } else {
                    setter.addFloat(child, VIEW_TRANSLATE_X, fromX, toX, interpolator);
                    setter.setFloat(child, VIEW_TRANSLATE_Y, mTaskbarBottomMargin, interpolator);
                }

                if (mIsHotseatIconOnTopWhenAligned) {
                    setter.addFloat(child, VIEW_ALPHA, 0f, 1f,
                            isToHome
                                    ? Interpolators.clampToProgress(LINEAR, 0f, 0.35f)
                                    : mActivity.getDeviceProfile().isQsbInline
                                            ? Interpolators.clampToProgress(LINEAR, 0f, 1f)
                                            : Interpolators.clampToProgress(LINEAR, 0.84f, 1f));
                }
                setter.addOnFrameListener(animator -> AlphaUpdateListener.updateVisibility(child));
                continue;
            }

            int positionInHotseat;
            if (isAllAppsButton) {
                // Note that there is no All Apps button in the hotseat, this position is only used
                // as its convenient for animation purposes.
                positionInHotseat = Utilities.isRtl(child.getResources())
                        ? taskbarDp.numShownHotseatIcons
                        : -1;
            } else if (child.getTag() instanceof ItemInfo) {
                positionInHotseat = ((ItemInfo) child.getTag()).screenId;
            } else {
                Log.w(TAG, "Unsupported view found in createIconAlignmentController, v=" + child);
                continue;
            }

            float hotseatIconCenter = hotseatPadding.left
                    + (hotseatCellSize + borderSpacing) * positionInHotseat
                    + hotseatCellSize / 2f;
            float childCenter = (child.getLeft() + child.getRight()) / 2f;
            float toX = hotseatIconCenter - childCenter;
            if (child instanceof Reorderable) {
                MultiTranslateDelegate mtd = ((Reorderable) child).getTranslateDelegate();

                setter.setFloat(mtd.getTranslationX(INDEX_TASKBAR_ALIGNMENT_ANIM),
                        MULTI_PROPERTY_VALUE, toX, interpolator);
                setter.setFloat(mtd.getTranslationY(INDEX_TASKBAR_ALIGNMENT_ANIM),
                        MULTI_PROPERTY_VALUE, mTaskbarBottomMargin, interpolator);
            } else {
                setter.setFloat(child, VIEW_TRANSLATE_X, toX, interpolator);
                setter.setFloat(child, VIEW_TRANSLATE_Y, mTaskbarBottomMargin, interpolator);
            }
            setter.setFloat(child, SCALE_PROPERTY, scaleUp, interpolator);
        }

        AnimatorPlaybackController controller = setter.createPlaybackController();
        mOnControllerPreCreateCallback = () -> controller.setPlayFraction(0);
        return controller;
    }

    public void onRotationChanged(DeviceProfile deviceProfile) {
        if (!mControllers.uiController.isIconAlignedWithHotseat()) {
            // We only translate on rotation when icon is aligned with hotseat
            return;
        }
        mActivity.setTaskbarWindowHeight(
                deviceProfile.taskbarSize + deviceProfile.getTaskbarOffsetY());
        mTaskbarNavButtonTranslationY.updateValue(-deviceProfile.getTaskbarOffsetY());
    }

    /**
     * Maps the given operator to all the top-level children of TaskbarView.
     */
    public void mapOverItems(LauncherBindableItemsContainer.ItemOperator op) {
        mTaskbarView.mapOverItems(op);
    }

    /**
     * Returns the first icon to match the given parameter, in priority from:
     * 1) Icons directly on Taskbar
     * 2) FolderIcon of the Folder containing the given icon
     * 3) All Apps button
     */
    public View getFirstIconMatch(Predicate<ItemInfo> matcher) {
        Predicate<ItemInfo> folderMatcher = ItemInfoMatcher.forFolderMatch(matcher);
        return mTaskbarView.getFirstMatch(matcher, folderMatcher);
    }

    /**
     * Returns whether the given MotionEvent, *in screen coorindates*, is within any Taskbar item's
     * touch bounds.
     */
    public boolean isEventOverAnyItem(MotionEvent ev) {
        return mTaskbarView.isEventOverAnyItem(ev);
    }

    @Override
    public void dumpLogs(String prefix, PrintWriter pw) {
        pw.println(prefix + "TaskbarViewController:");

        mTaskbarIconAlpha.dump(
                prefix + "\t",
                pw,
                "mTaskbarIconAlpha",
                "ALPHA_INDEX_HOME",
                "ALPHA_INDEX_KEYGUARD",
                "ALPHA_INDEX_STASH",
                "ALPHA_INDEX_RECENTS_DISABLED",
                "ALPHA_INDEX_NOTIFICATION_EXPANDED",
                "ALPHA_INDEX_ASSISTANT_INVOKED",
                "ALPHA_INDEX_IME_BUTTON_NAV",
                "ALPHA_INDEX_SMALL_SCREEN");

        mModelCallbacks.dumpLogs(prefix + "\t", pw);
    }

    /** Called when there's a change in running apps to update the UI. */
    public void commitRunningAppsToUI() {
        mModelCallbacks.commitRunningAppsToUI();
    }

    /** Call TaskbarModelCallbacks to update running apps. */
    public void updateRunningApps() {
        mModelCallbacks.updateRunningApps();
    }

    /**
     * Callbacks for {@link TaskbarView} to interact with its controller.
     */
    public class TaskbarViewCallbacks {
        private final float mSquaredTouchSlop = Utilities.squaredTouchSlop(mActivity);

        private float mDownX, mDownY;
        private boolean mCanceledStashHint;

        private boolean mTouchInProgress;

        public View.OnClickListener getIconOnClickListener() {
            return mActivity.getItemOnClickListener();
        }

        public View.OnClickListener getAllAppsButtonClickListener() {
            return v -> {
                mActivity.getStatsLogManager().logger().log(LAUNCHER_TASKBAR_ALLAPPS_BUTTON_TAP);
                mControllers.taskbarAllAppsController.show();
            };
        }

        public View.OnLongClickListener getIconOnLongClickListener() {
            return mControllers.taskbarDragController::startDragOnLongClick;
        }

        public View.OnLongClickListener getBackgroundOnLongClickListener() {
            return view -> mControllers.taskbarStashController
                    .updateAndAnimateIsManuallyStashedInApp(true);
        }

        /**
         * Simply listens to all intercept touch events passed to TaskbarView.
         */
        public void onInterceptTouchEvent(MotionEvent ev) {
            if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                mTouchInProgress = true;
            }

            if (mTouchInProgress && mSwipeDownDetector != null) {
                mSwipeDownDetector.onTouchEvent(ev);
            }

            if (ev.getAction() == MotionEvent.ACTION_UP
                    || ev.getAction() == MotionEvent.ACTION_CANCEL) {
                clearTouchInProgress();
            }
        }

        /**
         * Get the first chance to handle TaskbarView#onTouchEvent, and return whether we want to
         * consume the touch so TaskbarView treats it as an ACTION_CANCEL.
         */
        public boolean onTouchEvent(MotionEvent motionEvent) {
            boolean shouldConsumeTouch = false;
            boolean clearTouchInProgress = false;

            final float x = motionEvent.getRawX();
            final float y = motionEvent.getRawY();
            switch (motionEvent.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    mTouchInProgress = true;
                    mDownX = x;
                    mDownY = y;
                    mControllers.taskbarStashController.startStashHint(/* animateForward = */ true);
                    mCanceledStashHint = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (mTouchInProgress
                            && !mCanceledStashHint
                            && squaredHypot(mDownX - x, mDownY - y) > mSquaredTouchSlop) {
                        mControllers.taskbarStashController.startStashHint(
                                /* animateForward= */ false);
                        mCanceledStashHint = true;
                        shouldConsumeTouch = true;
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (mTouchInProgress && !mCanceledStashHint) {
                        mControllers.taskbarStashController.startStashHint(
                                /* animateForward= */ false);
                    }
                    clearTouchInProgress = true;
                    break;
            }

            if (mTouchInProgress && mSwipeDownDetector != null) {
                mSwipeDownDetector.onTouchEvent(motionEvent);
            }
            if (clearTouchInProgress) {
                clearTouchInProgress();
            }
            return shouldConsumeTouch;
        }

        /**
         * Ensures that we do not pass any more touch events to the SwipeDetector.
         */
        public void clearTouchInProgress() {
            mTouchInProgress = false;
        }

        /**
         * Notifies launcher to update icon alignment.
         */
        public void notifyIconLayoutBoundsChanged() {
            mControllers.uiController.onIconLayoutBoundsChanged();
        }
    }
}
