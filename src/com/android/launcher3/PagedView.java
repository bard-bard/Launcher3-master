/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.launcher3;

import static com.android.launcher3.anim.Interpolators.SCROLL;
import static com.android.launcher3.compat.AccessibilityManagerCompat.isAccessibilityEnabled;
import static com.android.launcher3.compat.AccessibilityManagerCompat.isObservedEventType;
import static com.android.launcher3.touch.OverScroll.OVERSCROLL_DAMP_FACTOR;
import static com.android.launcher3.touch.PagedOrientationHandler.VIEW_SCROLL_BY;
import static com.android.launcher3.touch.PagedOrientationHandler.VIEW_SCROLL_TO;

import android.animation.LayoutTransition;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewDebug;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.OverScroller;
import android.widget.ScrollView;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.android.launcher3.compat.AccessibilityManagerCompat;
import com.android.launcher3.config.FeatureFlags;
import com.android.launcher3.pageindicators.PageIndicator;
import com.android.launcher3.touch.PagedOrientationHandler;
import com.android.launcher3.touch.PagedOrientationHandler.ChildBounds;
import com.android.launcher3.util.EdgeEffectCompat;
import com.android.launcher3.util.Thunk;
import com.android.launcher3.views.ActivityContext;

import java.util.ArrayList;
import java.util.function.Consumer;

/**
 * An abstraction of the original Workspace which supports browsing through a
 * sequential list of "pages"
 * 通过startScroll方法来进行滑动，scrollto也能滑动，这两种差不多
 * 主要用跟踪触摸屏事件（flinging事件和其他gestures手势事件）的速率。
 * 用 addMovement(MotionEvent) 函数将Motion event加入到VelocityTracker类实例中.
 * 你可以使用 getXVelocity()  或 getXVelocity() 获得横向和竖向的速率到速率时，
 * 但是使用它们之前请先调用 computeCurrentVelocity (int) 来初始化速率的单位 。
 */
public abstract class PagedView<T extends View & PageIndicator> extends ViewGroup {
    private static final String TAG = "PagedView";
    private static final boolean DEBUG = false;
    public static final boolean DEBUG_FAILED_QUICKSWITCH = false;

    public static final int ACTION_MOVE_ALLOW_EASY_FLING = MotionEvent.ACTION_MASK - 1;
    public static final int INVALID_PAGE = -1;
    protected static final ComputePageScrollsLogic SIMPLE_SCROLL_LOGIC = (v) -> v.getVisibility() != GONE;

    public static final int PAGE_SNAP_ANIMATION_DURATION = 750;

    private static final float RETURN_TO_ORIGINAL_PAGE_THRESHOLD = 0.33f;
    // The page is moved more than halfway, automatically move to the next page on touch up.
    private static final float SIGNIFICANT_MOVE_THRESHOLD = 0.4f;

    private static final float MAX_SCROLL_PROGRESS = 1.0f;

    // The following constants need to be scaled based on density. The scaled versions will be
    // assigned to the corresponding member variables below.
    private static final int FLING_THRESHOLD_VELOCITY = 500;
    private static final int EASY_FLING_THRESHOLD_VELOCITY = 400;
    private static final int MIN_SNAP_VELOCITY = 1500;
    private static final int MIN_FLING_VELOCITY = 250;

    private boolean mFreeScroll = false;

    protected final int mFlingThresholdVelocity;
    protected final int mEasyFlingThresholdVelocity;
    protected final int mMinFlingVelocity;
    protected final int mMinSnapVelocity;

    protected boolean mFirstLayout = true;

    @ViewDebug.ExportedProperty(category = "launcher")
    protected int mCurrentPage;

    @ViewDebug.ExportedProperty(category = "launcher")
    protected int mNextPage = INVALID_PAGE;
    protected int mMaxScroll;
    protected int mMinScroll;
    protected OverScroller mScroller;
    private VelocityTracker mVelocityTracker;
    protected int mPageSpacing = 0;

    private float mDownMotionX;
    private float mDownMotionY;
    private float mDownMotionPrimary;
    private float mLastMotion;
    private float mLastMotionRemainder;
    private float mTotalMotion;
    // Used in special cases where the fling checks can be relaxed for an intentional gesture
    private boolean mAllowEasyFling;
    protected PagedOrientationHandler mOrientationHandler = PagedOrientationHandler.PORTRAIT;

    protected int[] mPageScrolls;
    private boolean mIsBeingDragged;

    // The amount of movement to begin scrolling
    protected int mTouchSlop;
    // The amount of movement to begin paging
    protected int mPageSlop;
    private int mMaximumVelocity;
    protected boolean mAllowOverScroll = true;

    protected static final int INVALID_POINTER = -1;

    protected int mActivePointerId = INVALID_POINTER;

    protected boolean mIsPageInTransition = false;
    private Runnable mOnPageTransitionEndCallback;

    // Page Indicator
    @Thunk int mPageIndicatorViewId;
    protected T mPageIndicator;

    protected final Rect mInsets = new Rect();
    protected boolean mIsRtl;

    // Similar to the platform implementation of isLayoutValid();
    protected boolean mIsLayoutValid;

    private int[] mTmpIntPair = new int[2];

    protected EdgeEffectCompat mEdgeGlowLeft;
    protected EdgeEffectCompat mEdgeGlowRight;

    public PagedView(Context context) {
        this(context, null);
    }

    public PagedView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public PagedView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        TypedArray a = context.obtainStyledAttributes(attrs,
                R.styleable.PagedView, defStyle, 0);
        mPageIndicatorViewId = a.getResourceId(R.styleable.PagedView_pageIndicator, -1);
        a.recycle();

        //设置震动反馈
        setHapticFeedbackEnabled(false);
        //判断布局是不是从右向左
        mIsRtl = Utilities.isRtl(getResources());

        mScroller = new OverScroller(context, SCROLL);
        mCurrentPage = 0;

        //获取滑动距离最小值，大于该值认为可以滑动。等
        final ViewConfiguration configuration = ViewConfiguration.get(context);
        mTouchSlop = configuration.getScaledTouchSlop();
        mPageSlop = configuration.getScaledPagingTouchSlop();
        mMaximumVelocity = configuration.getScaledMaximumFlingVelocity();



        //用于DP和PX的转换
        float density = getResources().getDisplayMetrics().density;
        mFlingThresholdVelocity = (int) (FLING_THRESHOLD_VELOCITY * density);
        mEasyFlingThresholdVelocity = (int) (EASY_FLING_THRESHOLD_VELOCITY * density);
        mMinFlingVelocity = (int) (MIN_FLING_VELOCITY * density);
        mMinSnapVelocity = (int) (MIN_SNAP_VELOCITY * density);

        //初始化滑倒边界的效果
        initEdgeEffect();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            //获取焦点时默认的高亮
            setDefaultFocusHighlightEnabled(false);
        }
        //如果需要重写Viewgroup方法需要set false。
        setWillNotDraw(false);
    }

    //初始化边缘动画效果
    protected void initEdgeEffect() {
        mEdgeGlowLeft = new EdgeEffectCompat(getContext());
        mEdgeGlowRight = new EdgeEffectCompat(getContext());
    }

    //初始化
    public void initParentViews(View parent) {
        if (mPageIndicatorViewId > -1) {
            mPageIndicator = parent.findViewById(mPageIndicatorViewId);
            mPageIndicator.setMarkersCount(getChildCount());
        }
    }

    public T getPageIndicator() {
        return mPageIndicator;
    }

    /**
     * Returns the index of the currently displayed page. When in free scroll mode, this is the page
     * that the user was on before entering free scroll mode (e.g. the home screen page they
     * long-pressed on to enter the overview). Try using {@link #getDestinationPage()}
     * to get the page the user is currently scrolling over.
     */
    public int getCurrentPage() {
        return mCurrentPage;
    }

    /**
     * Returns the index of page to be shown immediately afterwards.
     */
    public int getNextPage() {
        return (mNextPage != INVALID_PAGE) ? mNextPage : mCurrentPage;
    }

    public int getPageCount() {
        return getChildCount();
    }

    public View getPageAt(int index) {
        return getChildAt(index);
    }

    protected int indexToPage(int index) {
        return index;
    }

    /**
     * Updates the scroll of the current page immediately to its final scroll position.  We use this
     * in CustomizePagedView to allow tabs to share the same PagedView while resetting the scroll of
     * the previous tab page.
     */
    protected void updateCurrentPageScroll() {
        // If the current page is invalid, just reset the scroll position to zero
        int newPosition = 0;
        if (0 <= mCurrentPage && mCurrentPage < getPageCount()) {
            newPosition = getScrollForPage(mCurrentPage);
        }
        mOrientationHandler.set(this, VIEW_SCROLL_TO, newPosition);
        mScroller.startScroll(mScroller.getCurrX(), 0, newPosition - mScroller.getCurrX(), 0);
        forceFinishScroller(true);
    }

    /**
     *  Immediately finishes any overscroll effect and jumps to the end of the scroller animation.
     */
    public void abortScrollerAnimation() {
        mEdgeGlowLeft.finish();
        mEdgeGlowRight.finish();
        abortScrollerAnimation(true);
    }

    private void abortScrollerAnimation(boolean resetNextPage) {
        mScroller.abortAnimation();
        // We need to clean up the next page here to avoid computeScrollHelper from
        // updating current page on the pass.
        if (resetNextPage) {
            mNextPage = INVALID_PAGE;
            pageEndTransition();
        }
    }

    private void forceFinishScroller(boolean resetNextPage) {
        mScroller.forceFinished(true);
        // We need to clean up the next page here to avoid computeScrollHelper from
        // updating current page on the pass.
        if (resetNextPage) {
            mNextPage = INVALID_PAGE;
            pageEndTransition();
        }
    }

    private int validateNewPage(int newPage) {
        newPage = ensureWithinScrollBounds(newPage);
        // Ensure that it is clamped by the actual set of children in all cases
        newPage = Utilities.boundToRange(newPage, 0, getPageCount() - 1);

        if (getPanelCount() > 1) {
            // Always return left panel as new page
            newPage = getLeftmostVisiblePageForIndex(newPage);
        }
        return newPage;
    }

    private int getLeftmostVisiblePageForIndex(int pageIndex) {
        int panelCount = getPanelCount();
        return (pageIndex / panelCount) * panelCount;
    }

    /**
     * Returns the number of pages that are shown at the same time.
     */
    protected int getPanelCount() {
        return 1;
    }

    /**
     * Executes the callback against each visible page
     */
    public void forEachVisiblePage(Consumer<View> callback) {
        int panelCount = getPanelCount();
        for (int i = mCurrentPage; i < mCurrentPage + panelCount; i++) {
            View page = getPageAt(i);
            if (page != null) {
                callback.accept(page);
            }
        }
    }

    /**
     * Returns true if the view is on one of the current pages, false otherwise.
     */
    public boolean isVisible(View child) {
        return getLeftmostVisiblePageForIndex(indexOfChild(child)) == mCurrentPage;
    }

    /**
     * @return The closest page to the provided page that is within mMinScrollX and mMaxScrollX.
     */
    private int ensureWithinScrollBounds(int page) {
        int dir = !mIsRtl ? 1 : - 1;
        int currScroll = getScrollForPage(page);
        int prevScroll;
        while (currScroll < mMinScroll) {
            page += dir;
            prevScroll = currScroll;
            currScroll = getScrollForPage(page);
            if (currScroll <= prevScroll) {
                Log.e(TAG, "validateNewPage: failed to find a page > mMinScrollX");
                break;
            }
        }
        while (currScroll > mMaxScroll) {
            page -= dir;
            prevScroll = currScroll;
            currScroll = getScrollForPage(page);
            if (currScroll >= prevScroll) {
                Log.e(TAG, "validateNewPage: failed to find a page < mMaxScrollX");
                break;
            }
        }
        return page;
    }

    public void setCurrentPage(int currentPage) {
        setCurrentPage(currentPage, INVALID_PAGE);
    }

    /**
     * Sets the current page.
     */
    public void setCurrentPage(int currentPage, int overridePrevPage) {
        if (!mScroller.isFinished()) {
            abortScrollerAnimation(true);
        }
        // don't introduce any checks like mCurrentPage == currentPage here-- if we change the
        // the default
        if (getChildCount() == 0) {
            return;
        }
        int prevPage = overridePrevPage != INVALID_PAGE ? overridePrevPage : mCurrentPage;
        mCurrentPage = validateNewPage(currentPage);
        updateCurrentPageScroll();
        notifyPageSwitchListener(prevPage);
        invalidate();
    }

    /**
     * Should be called whenever the page changes. In the case of a scroll, we wait until the page
     * has settled.
     */
    protected void notifyPageSwitchListener(int prevPage) {
        updatePageIndicator();
    }

    private void updatePageIndicator() {
        if (mPageIndicator != null) {
            mPageIndicator.setActiveMarker(getNextPage());
        }
    }
    protected void pageBeginTransition() {
        if (!mIsPageInTransition) {
            mIsPageInTransition = true;
            onPageBeginTransition();
        }
    }

    protected void pageEndTransition() {
        if (mIsPageInTransition && !mIsBeingDragged && mScroller.isFinished()
                && (!isShown() || (mEdgeGlowLeft.isFinished() && mEdgeGlowRight.isFinished()))) {
            mIsPageInTransition = false;
            onPageEndTransition();
        }
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        pageEndTransition();
        super.onVisibilityAggregated(isVisible);
    }

    protected boolean isPageInTransition() {
        return mIsPageInTransition;
    }

    /**
     * Called when the page starts moving as part of the scroll. Subclasses can override this
     * to provide custom behavior during animation.
     */
    protected void onPageBeginTransition() {
    }

    /**
     * Called when the page ends moving as part of the scroll. Subclasses can override this
     * to provide custom behavior during animation.
     */
    protected void onPageEndTransition() {
        AccessibilityManagerCompat.sendScrollFinishedEventToTest(getContext());
        AccessibilityManagerCompat.sendCustomAccessibilityEvent(getPageAt(mCurrentPage),
                AccessibilityEvent.TYPE_VIEW_FOCUSED, null);
        if (mOnPageTransitionEndCallback != null) {
            mOnPageTransitionEndCallback.run();
            mOnPageTransitionEndCallback = null;
        }
    }

    /**
     * Sets a callback to run once when the scrolling finishes. If there is currently
     * no page in transition, then the callback is called immediately.
     */
    public void setOnPageTransitionEndCallback(@Nullable Runnable callback) {
        if (mIsPageInTransition || callback == null) {
            mOnPageTransitionEndCallback = callback;
        } else {
            callback.run();
        }
    }

    @Override
    public void scrollTo(int x, int y) {
        x = Utilities.boundToRange(x,
                mOrientationHandler.getPrimaryValue(mMinScroll, 0), mMaxScroll);
        y = Utilities.boundToRange(y,
                mOrientationHandler.getPrimaryValue(0, mMinScroll), mMaxScroll);
        super.scrollTo(x, y);
    }

    private void sendScrollAccessibilityEvent() {
        if (isObservedEventType(getContext(), AccessibilityEvent.TYPE_VIEW_SCROLLED)) {
            if (mCurrentPage != getNextPage()) {
                AccessibilityEvent ev =
                        AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_SCROLLED);
                ev.setScrollable(true);
                ev.setScrollX(getScrollX());
                ev.setScrollY(getScrollY());
                mOrientationHandler.setMaxScroll(ev, mMaxScroll);
                sendAccessibilityEventUnchecked(ev);
            }
        }
    }

    protected void announcePageForAccessibility() {
        if (isAccessibilityEnabled(getContext())) {
            // Notify the user when the page changes
            announceForAccessibility(getCurrentPageDescription());
        }
    }

    protected boolean computeScrollHelper() {
        if (mScroller.computeScrollOffset()) {
            // Don't bother scrolling if the page does not need to be moved
            int oldPos = mOrientationHandler.getPrimaryScroll(this);
            int newPos = mScroller.getCurrX();
            if (oldPos != newPos) {
                mOrientationHandler.set(this, VIEW_SCROLL_TO, mScroller.getCurrX());
            }

            if (mAllowOverScroll) {
                if (newPos < mMinScroll && oldPos >= mMinScroll) {
                    mEdgeGlowLeft.onAbsorb((int) mScroller.getCurrVelocity());
                    mScroller.abortAnimation();
                } else if (newPos > mMaxScroll && oldPos <= mMaxScroll) {
                    mEdgeGlowRight.onAbsorb((int) mScroller.getCurrVelocity());
                    mScroller.abortAnimation();
                }
            }

            // If the scroller has scrolled to the final position and there is no edge effect, then
            // finish the scroller to skip waiting for additional settling
            int finalPos = mOrientationHandler.getPrimaryValue(mScroller.getFinalX(),
                    mScroller.getFinalY());
            if (newPos == finalPos && mEdgeGlowLeft.isFinished() && mEdgeGlowRight.isFinished()) {
                mScroller.abortAnimation();
            }

            invalidate();
            return true;
        } else if (mNextPage != INVALID_PAGE) {
            sendScrollAccessibilityEvent();
            int prevPage = mCurrentPage;
            mCurrentPage = validateNewPage(mNextPage);
            mNextPage = INVALID_PAGE;
            notifyPageSwitchListener(prevPage);

            // We don't want to trigger a page end moving unless the page has settled
            // and the user has stopped scrolling
            if (!mIsBeingDragged) {
                pageEndTransition();
            }

            if (canAnnouncePageDescription()) {
                announcePageForAccessibility();
            }
        }
        return false;
    }

    @Override
    public void computeScroll() {
        computeScrollHelper();
    }

    public int getExpectedHeight() {
        return getMeasuredHeight();
    }

    public int getNormalChildHeight() {
        return  getExpectedHeight() - getPaddingTop() - getPaddingBottom()
                - mInsets.top - mInsets.bottom;
    }

    public int getExpectedWidth() {
        return getMeasuredWidth();
    }

    public int getNormalChildWidth() {
        return  getExpectedWidth() - getPaddingLeft() - getPaddingRight()
                - mInsets.left - mInsets.right;
    }

    @Override
    public void requestLayout() {
        mIsLayoutValid = false;
        super.requestLayout();
    }

    @Override
    public void forceLayout() {
        mIsLayoutValid = false;
        super.forceLayout();
    }

    private int getPageWidthSize(int widthSize) {
        return (widthSize - mInsets.left - mInsets.right) / getPanelCount();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (getChildCount() == 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }

        // We measure the dimensions of the PagedView to be larger than the pages so that when we
        // zoom out (and scale down), the view is still contained in the parent
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);

        if (widthMode == MeasureSpec.UNSPECIFIED || heightMode == MeasureSpec.UNSPECIFIED) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }

        // Return early if we aren't given a proper dimension
        if (widthSize <= 0 || heightSize <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }

        // The children are given the same width and height as the workspace
        // unless they were set to WRAP_CONTENT
        if (DEBUG) Log.d(TAG, "PagedView.onMeasure(): " + widthSize + ", " + heightSize);

        int myWidthSpec = MeasureSpec.makeMeasureSpec(
                getPageWidthSize(widthSize), MeasureSpec.EXACTLY);
        int myHeightSpec = MeasureSpec.makeMeasureSpec(
                heightSize - mInsets.top - mInsets.bottom, MeasureSpec.EXACTLY);

        // measureChildren takes accounts for content padding, we only need to care about extra
        // space due to insets.
        measureChildren(myWidthSpec, myHeightSpec);
        setMeasuredDimension(widthSize, heightSize);
    }

    @SuppressLint("DrawAllocation")
    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        mIsLayoutValid = true;
        final int childCount = getChildCount();
        boolean pageScrollChanged = false;
        if (mPageScrolls == null || childCount != mPageScrolls.length) {
            mPageScrolls = new int[childCount];
            pageScrollChanged = true;
        }

        if (childCount == 0) {
            return;
        }

        if (DEBUG) Log.d(TAG, "PagedView.onLayout()");

        boolean isScrollChanged = getPageScrolls(mPageScrolls, true, SIMPLE_SCROLL_LOGIC);
        if (isScrollChanged) {
            pageScrollChanged = true;
        }

        final LayoutTransition transition = getLayoutTransition();
        // If the transition is running defer updating max scroll, as some empty pages could
        // still be present, and a max scroll change could cause sudden jumps in scroll.
        if (transition != null && transition.isRunning()) {
            transition.addTransitionListener(new LayoutTransition.TransitionListener() {

                @Override
                public void startTransition(LayoutTransition transition, ViewGroup container,
                        View view, int transitionType) { }

                @Override
                public void endTransition(LayoutTransition transition, ViewGroup container,
                        View view, int transitionType) {
                    // Wait until all transitions are complete.
                    if (!transition.isRunning()) {
                        transition.removeTransitionListener(this);
                        updateMinAndMaxScrollX();
                    }
                }
            });
        } else {
            updateMinAndMaxScrollX();
        }

        if (mFirstLayout && mCurrentPage >= 0 && mCurrentPage < childCount) {
            updateCurrentPageScroll();
            mFirstLayout = false;
        }

        if (mScroller.isFinished() && pageScrollChanged) {
            setCurrentPage(getNextPage());
        }
    }

    /**
     * Initializes {@code outPageScrolls} with scroll positions for view at that index. The length
     * of {@code outPageScrolls} should be same as the the childCount
     */
    protected boolean getPageScrolls(int[] outPageScrolls, boolean layoutChildren,
            ComputePageScrollsLogic scrollLogic) {
        final int childCount = getChildCount();

        final int startIndex = mIsRtl ? childCount - 1 : 0;
        final int endIndex = mIsRtl ? -1 : childCount;
        final int delta = mIsRtl ? -1 : 1;

        final int pageCenter = mOrientationHandler.getCenterForPage(this, mInsets);

        final int scrollOffsetStart = mOrientationHandler.getScrollOffsetStart(this, mInsets);
        final int scrollOffsetEnd = mOrientationHandler.getScrollOffsetEnd(this, mInsets);
        boolean pageScrollChanged = false;

        for (int i = startIndex, childStart = scrollOffsetStart; i != endIndex; i += delta) {
            final View child = getPageAt(i);
            if (scrollLogic.shouldIncludeView(child)) {
                ChildBounds bounds = mOrientationHandler.getChildBounds(child, childStart,
                    pageCenter, layoutChildren);
                final int primaryDimension = bounds.primaryDimension;
                final int childPrimaryEnd = bounds.childPrimaryEnd;

                // In case the pages are of different width, align the page to left edge for non-RTL
                // or right edge for RTL.
                final int pageScroll =
                        mIsRtl ? childPrimaryEnd - scrollOffsetEnd : childStart - scrollOffsetStart;
                if (outPageScrolls[i] != pageScroll) {
                    pageScrollChanged = true;
                    outPageScrolls[i] = pageScroll;
                }
                childStart += primaryDimension + mPageSpacing + getChildGap();
            }
        }

        int panelCount = getPanelCount();
        if (panelCount > 1) {
            for (int i = 0; i < childCount; i++) {
                // In case we have multiple panels, always use left panel's page scroll for all
                // panels on the screen.
                int adjustedScroll = outPageScrolls[getLeftmostVisiblePageForIndex(i)];
                if (outPageScrolls[i] != adjustedScroll) {
                    outPageScrolls[i] = adjustedScroll;
                    pageScrollChanged = true;
                }
            }
        }
        return pageScrollChanged;
    }

    protected int getChildGap() {
        return 0;
    }

    protected void updateMinAndMaxScrollX() {
        mMinScroll = computeMinScroll();
        mMaxScroll = computeMaxScroll();
    }

    protected int computeMinScroll() {
        return 0;
    }

    protected int computeMaxScroll() {
        int childCount = getChildCount();
        if (childCount > 0) {
            final int index = mIsRtl ? 0 : childCount - 1;
            return getScrollForPage(index);
        } else {
            return 0;
        }
    }

    public void setPageSpacing(int pageSpacing) {
        mPageSpacing = pageSpacing;
        requestLayout();
    }

    public int getPageSpacing() {
        return mPageSpacing;
    }

    private void dispatchPageCountChanged() {
        if (mPageIndicator != null) {
            mPageIndicator.setMarkersCount(getChildCount());
        }
        // This ensures that when children are added, they get the correct transforms / alphas
        // in accordance with any scroll effects.
        invalidate();
    }

    @Override
    public void onViewAdded(View child) {
        super.onViewAdded(child);
        dispatchPageCountChanged();
    }

    @Override
    public void onViewRemoved(View child) {
        super.onViewRemoved(child);
        mCurrentPage = validateNewPage(mCurrentPage);
        dispatchPageCountChanged();
    }

    protected int getChildOffset(int index) {
        if (index < 0 || index > getChildCount() - 1) return 0;
        View pageAtIndex = getPageAt(index);
        return mOrientationHandler.getChildStart(pageAtIndex);
    }

    protected int getChildVisibleSize(int index) {
        View layout = getPageAt(index);
        return mOrientationHandler.getMeasuredSize(layout);
    }

    @Override
    public boolean requestChildRectangleOnScreen(View child, Rect rectangle, boolean immediate) {
        int page = indexToPage(indexOfChild(child));
        if (page != mCurrentPage || !mScroller.isFinished()) {
            if (immediate) {
                setCurrentPage(page);
            } else {
                snapToPage(page);
            }
            return true;
        }
        return false;
    }

    @Override
    protected boolean onRequestFocusInDescendants(int direction, Rect previouslyFocusedRect) {
        int focusablePage;
        if (mNextPage != INVALID_PAGE) {
            focusablePage = mNextPage;
        } else {
            focusablePage = mCurrentPage;
        }
        View v = getPageAt(focusablePage);
        if (v != null) {
            return v.requestFocus(direction, previouslyFocusedRect);
        }
        return false;
    }

    @Override
    public boolean dispatchUnhandledMove(View focused, int direction) {
        if (super.dispatchUnhandledMove(focused, direction)) {
            return true;
        }

        if (mIsRtl) {
            if (direction == View.FOCUS_LEFT) {
                direction = View.FOCUS_RIGHT;
            } else if (direction == View.FOCUS_RIGHT) {
                direction = View.FOCUS_LEFT;
            }
        }
        if (direction == View.FOCUS_LEFT) {
            if (getCurrentPage() > 0) {
                int nextPage = validateNewPage(getCurrentPage() - 1);
                snapToPage(nextPage);
                getChildAt(nextPage).requestFocus(direction);
                return true;
            }
        } else if (direction == View.FOCUS_RIGHT) {
            if (getCurrentPage() < getPageCount() - 1) {
                int nextPage = validateNewPage(getCurrentPage() + 1);
                snapToPage(nextPage);
                getChildAt(nextPage).requestFocus(direction);
                return true;
            }
        }
        return false;
    }

    @Override
    public void addFocusables(ArrayList<View> views, int direction, int focusableMode) {
        if (getDescendantFocusability() == FOCUS_BLOCK_DESCENDANTS) {
            return;
        }

        // Add the current page's views as focusable and the next possible page's too. If the
        // last focus change action was left then the left neighbour's views will be added, and
        // if it was right then the right neighbour's views will be added.
        // Unfortunately mCurrentPage can be outdated if there were multiple control actions in a
        // short period of time, but mNextPage is up to date because it is always updated by
        // method snapToPage.
        int nextPage = getNextPage();
        // XXX-RTL: This will be fixed in a future CL
        if (nextPage >= 0 && nextPage < getPageCount()) {
            getPageAt(nextPage).addFocusables(views, direction, focusableMode);
        }
        if (direction == View.FOCUS_LEFT) {
            if (nextPage > 0) {
                nextPage = validateNewPage(nextPage - 1);
                getPageAt(nextPage).addFocusables(views, direction, focusableMode);
            }
        } else if (direction == View.FOCUS_RIGHT) {
            if (nextPage < getPageCount() - 1) {
                nextPage = validateNewPage(nextPage + 1);
                getPageAt(nextPage).addFocusables(views, direction, focusableMode);
            }
        }
    }

    /**
     * If one of our descendant views decides that it could be focused now, only
     * pass that along if it's on the current page.
     *
     * This happens when live folders requery, and if they're off page, they
     * end up calling requestFocus, which pulls it on page.
     */
    @Override
    public void focusableViewAvailable(View focused) {
        View current = getPageAt(mCurrentPage);
        View v = focused;
        while (true) {
            if (v == current) {
                super.focusableViewAvailable(focused);
                return;
            }
            if (v == this) {
                return;
            }
            ViewParent parent = v.getParent();
            if (parent instanceof View) {
                v = (View)v.getParent();
            } else {
                return;
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {
        if (disallowIntercept) {
            // We need to make sure to cancel our long press if
            // a scrollable widget takes over touch events
            cancelCurrentPageLongPress();
        }
        super.requestDisallowInterceptTouchEvent(disallowIntercept);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        /*
         * This method JUST determines whether we want to intercept the motion.
         * If we return true, onTouchEvent will be called and we do the actual
         * scrolling there.
         */

        // Skip touch handling if there are no pages to swipe
        if (getChildCount() <= 0) return false;

        acquireVelocityTrackerAndAddMovement(ev);

        /*
         * Shortcut the most recurring case: the user is in the dragging
         * state and he is moving his finger.  We want to intercept this
         * motion.
         */
        final int action = ev.getAction();
        if ((action == MotionEvent.ACTION_MOVE) && mIsBeingDragged) {
            return true;
        }

        switch (action & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_MOVE: {
                /*
                 * mIsBeingDragged == false, otherwise the shortcut would have caught it. Check
                 * whether the user has moved far enough from their original down touch.
                 */
                if (mActivePointerId != INVALID_POINTER) {
                    determineScrollingStart(ev);
                }
                // if mActivePointerId is INVALID_POINTER, then we must have missed an ACTION_DOWN
                // event. in that case, treat the first occurrence of a move event as a ACTION_DOWN
                // i.e. fall through to the next case (don't break)
                // (We sometimes miss ACTION_DOWN events in Workspace because it ignores all events
                // while it's small- this was causing a crash before we checked for INVALID_POINTER)
                break;
            }

            case MotionEvent.ACTION_DOWN: {
                final float x = ev.getX();
                final float y = ev.getY();
                // Remember location of down touch
                mDownMotionX = x;
                mDownMotionY = y;
                mDownMotionPrimary = mLastMotion = mOrientationHandler.getPrimaryDirection(ev, 0);
                mLastMotionRemainder = 0;
                mTotalMotion = 0;
                mAllowEasyFling = false;
                mActivePointerId = ev.getPointerId(0);
                updateIsBeingDraggedOnTouchDown(ev);
                break;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                resetTouchState();
                break;

            case MotionEvent.ACTION_POINTER_UP:
                onSecondaryPointerUp(ev);
                releaseVelocityTracker();
                break;
        }

        /*
         * The only time we want to intercept motion events is if we are in the
         * drag mode.
         */
        return mIsBeingDragged;
    }

    /**
     * If being flinged and user touches the screen, initiate drag; otherwise don't.
     */
    private void updateIsBeingDraggedOnTouchDown(MotionEvent ev) {
        // mScroller.isFinished should be false when being flinged.
        final int xDist = Math.abs(mScroller.getFinalX() - mScroller.getCurrX());
        final boolean finishedScrolling = (mScroller.isFinished() || xDist < mPageSlop / 3);

        if (finishedScrolling) {
            mIsBeingDragged = false;
            if (!mScroller.isFinished() && !mFreeScroll) {
                setCurrentPage(getNextPage());
                pageEndTransition();
            }
            mIsBeingDragged = !mEdgeGlowLeft.isFinished() || !mEdgeGlowRight.isFinished();
        } else {
            mIsBeingDragged = true;
        }

        // Catch the edge effect if it is active.
        float displacement = mOrientationHandler.getSecondaryValue(ev.getX(), ev.getY())
                / mOrientationHandler.getSecondaryValue(getWidth(), getHeight());
        if (!mEdgeGlowLeft.isFinished()) {
            mEdgeGlowLeft.onPullDistance(0f, 1f - displacement);
        }
        if (!mEdgeGlowRight.isFinished()) {
            mEdgeGlowRight.onPullDistance(0f, displacement);
        }
    }

    public boolean isHandlingTouch() {
        return mIsBeingDragged;
    }

    protected void determineScrollingStart(MotionEvent ev) {
        determineScrollingStart(ev, 1.0f);
    }

    /*
     * Determines if we should change the touch state to start scrolling after the
     * user moves their touch point too far.
     */
    protected void determineScrollingStart(MotionEvent ev, float touchSlopScale) {
        // Disallow scrolling if we don't have a valid pointer index
        final int pointerIndex = ev.findPointerIndex(mActivePointerId);
        if (pointerIndex == -1) return;

        final float primaryDirection = mOrientationHandler.getPrimaryDirection(ev, pointerIndex);
        final int diff = (int) Math.abs(primaryDirection - mLastMotion);
        final int touchSlop = Math.round(touchSlopScale * mTouchSlop);
        boolean moved = diff > touchSlop || ev.getAction() == ACTION_MOVE_ALLOW_EASY_FLING;

        if (moved) {
            // Scroll if the user moved far enough along the X axis
            mIsBeingDragged = true;
            mTotalMotion += Math.abs(mLastMotion - primaryDirection);
            mLastMotion = primaryDirection;
            mLastMotionRemainder = 0;
            pageBeginTransition();
            // Stop listening for things like pinches.
            requestDisallowInterceptTouchEvent(true);
        }
    }

    protected void cancelCurrentPageLongPress() {
        // Try canceling the long press. It could also have been scheduled
        // by a distant descendant, so use the mAllowLongPress flag to block
        // everything
        forEachVisiblePage(View::cancelLongPress);
    }

    protected float getScrollProgress(int screenCenter, View v, int page) {
        final int halfScreenSize = getMeasuredWidth() / 2;

        int delta = screenCenter - (getScrollForPage(page) + halfScreenSize);
        int count = getChildCount();

        final int totalDistance;

        int adjacentPage = page + 1;
        if ((delta < 0 && !mIsRtl) || (delta > 0 && mIsRtl)) {
            adjacentPage = page - 1;
        }

        if (adjacentPage < 0 || adjacentPage > count - 1) {
            totalDistance = v.getMeasuredWidth() + mPageSpacing;
        } else {
            totalDistance = Math.abs(getScrollForPage(adjacentPage) - getScrollForPage(page));
        }

        float scrollProgress = delta / (totalDistance * 1.0f);
        scrollProgress = Math.min(scrollProgress, MAX_SCROLL_PROGRESS);
        scrollProgress = Math.max(scrollProgress, - MAX_SCROLL_PROGRESS);
        return scrollProgress;
    }

    public int getScrollForPage(int index) {
        if (mPageScrolls == null || index >= mPageScrolls.length || index < 0) {
            return 0;
        } else {
            return mPageScrolls[index];
        }
    }

    // While layout transitions are occurring, a child's position may stray from its baseline
    // position. This method returns the magnitude of this stray at any given time.
    public int getLayoutTransitionOffsetForPage(int index) {
        if (mPageScrolls == null || index >= mPageScrolls.length || index < 0) {
            return 0;
        } else {
            View child = getChildAt(index);

            int scrollOffset = mIsRtl ? getPaddingRight() : getPaddingLeft();
            int baselineX = mPageScrolls[index] + scrollOffset;
            return (int) (child.getX() - baselineX);
        }
    }

    public void setEnableFreeScroll(boolean freeScroll) {
        if (mFreeScroll == freeScroll) {
            return;
        }

        boolean wasFreeScroll = mFreeScroll;
        mFreeScroll = freeScroll;

        if (mFreeScroll) {
            setCurrentPage(getNextPage());
        } else if (wasFreeScroll) {
            if (getScrollForPage(getNextPage()) != getScrollX()) {
                snapToPage(getNextPage());
            }
        }
    }

    protected void setEnableOverscroll(boolean enable) {
        mAllowOverScroll = enable;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        // Skip touch handling if there are no pages to swipe
        if (getChildCount() <= 0) return false;

        acquireVelocityTrackerAndAddMovement(ev);

        final int action = ev.getAction();

        switch (action & MotionEvent.ACTION_MASK) {
        case MotionEvent.ACTION_DOWN:
            updateIsBeingDraggedOnTouchDown(ev);

            /*
             * If being flinged and user touches, stop the fling. isFinished
             * will be false if being flinged.
             */
            if (!mScroller.isFinished()) {
                abortScrollerAnimation(false);
            }

            // Remember where the motion event started
            mDownMotionX = ev.getX();
            mDownMotionY = ev.getY();
            mDownMotionPrimary = mLastMotion = mOrientationHandler.getPrimaryDirection(ev, 0);
            mLastMotionRemainder = 0;
            mTotalMotion = 0;
            mAllowEasyFling = false;
            mActivePointerId = ev.getPointerId(0);
            if (mIsBeingDragged) {
                pageBeginTransition();
            }
            break;

        case ACTION_MOVE_ALLOW_EASY_FLING:
            // Start scrolling immediately
            determineScrollingStart(ev);
            mAllowEasyFling = true;
            break;

        case MotionEvent.ACTION_MOVE:
            if (mIsBeingDragged) {
                // Scroll to follow the motion event
                final int pointerIndex = ev.findPointerIndex(mActivePointerId);

                if (pointerIndex == -1) return true;
                float oldScroll = mOrientationHandler.getPrimaryScroll(this);
                float dx = ev.getX(pointerIndex);
                float dy = ev.getY(pointerIndex);

                float direction = mOrientationHandler.getPrimaryValue(dx, dy);
                float delta = mLastMotion + mLastMotionRemainder - direction;

                int width = getWidth();
                int height = getHeight();
                int size = mOrientationHandler.getPrimaryValue(width, height);

                final float displacement = mOrientationHandler.getSecondaryValue(dx, dy)
                        / mOrientationHandler.getSecondaryValue(width, height);
                mTotalMotion += Math.abs(delta);

                if (mAllowOverScroll) {
                    float consumed = 0;
                    if (delta < 0 && mEdgeGlowRight.getDistance() != 0f) {
                        consumed = size * mEdgeGlowRight.onPullDistance(delta / size, displacement);
                    } else if (delta > 0 && mEdgeGlowLeft.getDistance() != 0f) {
                        consumed = -size * mEdgeGlowLeft.onPullDistance(
                                -delta / size, 1 - displacement);
                    }
                    delta -= consumed;
                }

                // Only scroll and update mLastMotionX if we have moved some discrete amount.  We
                // keep the remainder because we are actually testing if we've moved from the last
                // scrolled position (which is discrete).
                mLastMotion = direction;
                int movedDelta = (int) delta;
                mLastMotionRemainder = delta - movedDelta;


                if (delta != 0) {
                    mOrientationHandler.set(this, VIEW_SCROLL_BY, movedDelta);

                    if (mAllowOverScroll) {
                        final float pulledToX = oldScroll + delta;

                        if (pulledToX < mMinScroll) {
                            mEdgeGlowLeft.onPullDistance(-delta / size, 1.f - displacement);
                            if (!mEdgeGlowRight.isFinished()) {
                                mEdgeGlowRight.onRelease();
                            }
                        } else if (pulledToX > mMaxScroll) {
                            mEdgeGlowRight.onPullDistance(delta / size, displacement);
                            if (!mEdgeGlowLeft.isFinished()) {
                                mEdgeGlowLeft.onRelease();
                            }
                        }

                        if (!mEdgeGlowLeft.isFinished() || !mEdgeGlowRight.isFinished()) {
                            postInvalidateOnAnimation();
                        }
                    }

                } else {
                    awakenScrollBars();
                }
            } else {
                determineScrollingStart(ev);
            }
            break;

        case MotionEvent.ACTION_UP:
            if (mIsBeingDragged) {
                final int activePointerId = mActivePointerId;
                final int pointerIndex = ev.findPointerIndex(activePointerId);
                if (pointerIndex == -1) return true;

                final float primaryDirection = mOrientationHandler.getPrimaryDirection(ev,
                    pointerIndex);
                final VelocityTracker velocityTracker = mVelocityTracker;
                //表示1000ms运动了多少个像素
                velocityTracker.computeCurrentVelocity(1000, mMaximumVelocity);

                int velocity = (int) mOrientationHandler.getPrimaryVelocity(velocityTracker,
                    mActivePointerId);
                int delta = (int) (primaryDirection - mDownMotionPrimary);
                int pageOrientedSize = mOrientationHandler.getMeasuredSize(getPageAt(mCurrentPage));

                boolean isSignificantMove = Math.abs(delta) > pageOrientedSize *
                    SIGNIFICANT_MOVE_THRESHOLD;

                mTotalMotion += Math.abs(mLastMotion + mLastMotionRemainder - primaryDirection);
                boolean passedSlop = mAllowEasyFling || mTotalMotion > mPageSlop;
                boolean isFling = passedSlop && shouldFlingForVelocity(velocity);
                boolean isDeltaLeft = mIsRtl ? delta > 0 : delta < 0;
                boolean isVelocityLeft = mIsRtl ? velocity > 0 : velocity < 0;
                if (DEBUG_FAILED_QUICKSWITCH && !isFling && mAllowEasyFling) {
                    Log.d("Quickswitch", "isFling=false vel=" + velocity
                            + " threshold=" + mEasyFlingThresholdVelocity);
                }

                if (!mFreeScroll) {
                    // In the case that the page is moved far to one direction and then is flung
                    // in the opposite direction, we use a threshold to determine whether we should
                    // just return to the starting page, or if we should skip one further.
                    boolean returnToOriginalPage = false;
                    if (Math.abs(delta) > pageOrientedSize * RETURN_TO_ORIGINAL_PAGE_THRESHOLD &&
                            Math.signum(velocity) != Math.signum(delta) && isFling) {
                        returnToOriginalPage = true;
                    }

                    int finalPage;
                    // We give flings precedence over large moves, which is why we short-circuit our
                    // test for a large move if a fling has been registered. That is, a large
                    // move to the left and fling to the right will register as a fling to the right.

                    if (((isSignificantMove && !isDeltaLeft && !isFling) ||
                            (isFling && !isVelocityLeft)) && mCurrentPage > 0) {
                        finalPage = returnToOriginalPage
                                ? mCurrentPage : mCurrentPage - getPanelCount();
                        snapToPageWithVelocity(finalPage, velocity);
                    } else if (((isSignificantMove && isDeltaLeft && !isFling) ||
                            (isFling && isVelocityLeft)) &&
                            mCurrentPage < getChildCount() - 1) {
                        finalPage = returnToOriginalPage
                                ? mCurrentPage : mCurrentPage + getPanelCount();
                        snapToPageWithVelocity(finalPage, velocity);
                    } else {
                        snapToDestination();
                    }
                } else {
                    if (!mScroller.isFinished()) {
                        abortScrollerAnimation(true);
                    }

                    int initialScroll = mOrientationHandler.getPrimaryScroll(this);
                    int maxScroll = mMaxScroll;
                    int minScroll = mMinScroll;

                    if (((initialScroll >= maxScroll) && (isVelocityLeft || !isFling)) ||
                        ((initialScroll <= minScroll) && (!isVelocityLeft || !isFling))) {
                        mScroller.springBack(initialScroll, 0, minScroll, maxScroll, 0, 0);
                        mNextPage = getDestinationPage();
                    } else {
                        int velocity1 = -velocity;
                        // Continue a scroll or fling in progress
                        mScroller.fling(initialScroll, 0, velocity1, 0, minScroll, maxScroll, 0, 0,
                                Math.round(getWidth() * 0.5f * OVERSCROLL_DAMP_FACTOR), 0);

                        int finalPos = mScroller.getFinalX();
                        mNextPage = getDestinationPage(finalPos);
                        onNotSnappingToPageInFreeScroll();
                    }
                    invalidate();
                }
            }

            mEdgeGlowLeft.onRelease();
            mEdgeGlowRight.onRelease();
            // End any intermediate reordering states
            resetTouchState();
            break;

        case MotionEvent.ACTION_CANCEL:
            if (mIsBeingDragged) {
                snapToDestination();
            }
            mEdgeGlowLeft.onRelease();
            mEdgeGlowRight.onRelease();
            resetTouchState();
            break;

        case MotionEvent.ACTION_POINTER_UP:
            onSecondaryPointerUp(ev);
            releaseVelocityTracker();
            break;
        }

        return true;
    }

    protected void onNotSnappingToPageInFreeScroll() { }

    protected boolean shouldFlingForVelocity(int velocity) {
        float threshold = mAllowEasyFling ? mEasyFlingThresholdVelocity : mFlingThresholdVelocity;
        return Math.abs(velocity) > threshold;
    }

    private void resetTouchState() {
        releaseVelocityTracker();
        mIsBeingDragged = false;
        mActivePointerId = INVALID_POINTER;
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if ((event.getSource() & InputDevice.SOURCE_CLASS_POINTER) != 0) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_SCROLL: {
                    // Handle mouse (or ext. device) by shifting the page depending on the scroll
                    final float vscroll;
                    final float hscroll;
                    if ((event.getMetaState() & KeyEvent.META_SHIFT_ON) != 0) {
                        vscroll = 0;
                        hscroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                    } else {
                        vscroll = -event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                        hscroll = event.getAxisValue(MotionEvent.AXIS_HSCROLL);
                    }
                    if (!canScroll(Math.abs(vscroll), Math.abs(hscroll))) {
                        return false;
                    }
                    if (hscroll != 0 || vscroll != 0) {
                        boolean isForwardScroll = mIsRtl ? (hscroll < 0 || vscroll < 0)
                                                         : (hscroll > 0 || vscroll > 0);
                        if (isForwardScroll) {
                            scrollRight();
                        } else {
                            scrollLeft();
                        }
                        return true;
                    }
                }
            }
        }
        return super.onGenericMotionEvent(event);
    }

    /**
     * Returns true if the paged view can scroll for the provided vertical and horizontal
     * scroll values
     */
    protected boolean canScroll(float absVScroll, float absHScroll) {
        ActivityContext ac = ActivityContext.lookupContext(getContext());
        return (ac == null || AbstractFloatingView.getTopOpenView(ac) == null);
    }

    private void acquireVelocityTrackerAndAddMovement(MotionEvent ev) {
        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        }
        mVelocityTracker.addMovement(ev);
    }

    private void releaseVelocityTracker() {
        if (mVelocityTracker != null) {
            mVelocityTracker.clear();
            mVelocityTracker.recycle();
            mVelocityTracker = null;
        }
    }

    private void onSecondaryPointerUp(MotionEvent ev) {
        final int pointerIndex = ev.getActionIndex();
        final int pointerId = ev.getPointerId(pointerIndex);
        if (pointerId == mActivePointerId) {
            // This was our active pointer going up. Choose a new
            // active pointer and adjust accordingly.
            // TODO: Make this decision more intelligent.
            final int newPointerIndex = pointerIndex == 0 ? 1 : 0;
            mLastMotion = mDownMotionPrimary = mOrientationHandler.getPrimaryDirection(ev,
                newPointerIndex);
            mLastMotionRemainder = 0;
            mActivePointerId = ev.getPointerId(newPointerIndex);
            if (mVelocityTracker != null) {
                mVelocityTracker.clear();
            }
        }
    }

    @Override
    public void requestChildFocus(View child, View focused) {
        super.requestChildFocus(child, focused);

        // In case the device is controlled by a controller, mCurrentPage isn't updated properly
        // which results in incorrect navigation
        int nextPage = getNextPage();
        if (nextPage != mCurrentPage) {
            setCurrentPage(nextPage);
        }

        int page = indexToPage(indexOfChild(child));
        if (page >= 0 && page != getCurrentPage() && !isInTouchMode()) {
            snapToPage(page);
        }
    }

    public int getDestinationPage() {
        return getDestinationPage(mOrientationHandler.getPrimaryScroll(this));
    }

    protected int getDestinationPage(int primaryScroll) {
        return getPageNearestToCenterOfScreen(primaryScroll);
    }

    public int getPageNearestToCenterOfScreen() {
        return getPageNearestToCenterOfScreen(mOrientationHandler.getPrimaryScroll(this));
    }

    private int getPageNearestToCenterOfScreen(int primaryScroll) {
        int screenCenter = getScreenCenter(primaryScroll);
        int minDistanceFromScreenCenter = Integer.MAX_VALUE;
        int minDistanceFromScreenCenterIndex = -1;
        final int childCount = getChildCount();
        for (int i = 0; i < childCount; ++i) {
            int distanceFromScreenCenter = Math.abs(
                    getDisplacementFromScreenCenter(i, screenCenter));
            if (distanceFromScreenCenter < minDistanceFromScreenCenter) {
                minDistanceFromScreenCenter = distanceFromScreenCenter;
                minDistanceFromScreenCenterIndex = i;
            }
        }
        return minDistanceFromScreenCenterIndex;
    }

    private int getDisplacementFromScreenCenter(int childIndex, int screenCenter) {
        int childSize = Math.round(getChildVisibleSize(childIndex));
        int halfChildSize = (childSize / 2);
        int childCenter = getChildOffset(childIndex) + halfChildSize;
        return childCenter - screenCenter;
    }

    protected int getDisplacementFromScreenCenter(int childIndex) {
        int primaryScroll = mOrientationHandler.getPrimaryScroll(this);
        int screenCenter = getScreenCenter(primaryScroll);
        return getDisplacementFromScreenCenter(childIndex, screenCenter);
    }

    private int getScreenCenter(int primaryScroll) {
        float primaryScale = mOrientationHandler.getPrimaryScale(this);
        float primaryPivot =  mOrientationHandler.getPrimaryValue(getPivotX(), getPivotY());
        int pageOrientationSize = mOrientationHandler.getMeasuredSize(this);
        return Math.round(primaryScroll + (pageOrientationSize / 2f - primaryPivot) / primaryScale
                + primaryPivot);
    }

    protected void snapToDestination() {
        snapToPage(getDestinationPage(), PAGE_SNAP_ANIMATION_DURATION);
    }

    // We want the duration of the page snap animation to be influenced by the distance that
    // the screen has to travel, however, we don't want this duration to be effected in a
    // purely linear fashion. Instead, we use this method to moderate the effect that the distance
    // of travel has on the overall snap duration.
    private float distanceInfluenceForSnapDuration(float f) {
        f -= 0.5f; // center the values about 0.
        f *= 0.3f * Math.PI / 2.0f;
        return (float) Math.sin(f);
    }

    protected boolean snapToPageWithVelocity(int whichPage, int velocity) {
        whichPage = validateNewPage(whichPage);
        int halfScreenSize = mOrientationHandler.getMeasuredSize(this) / 2;

        final int newLoc = getScrollForPage(whichPage);
        int delta = newLoc - mOrientationHandler.getPrimaryScroll(this);
        int duration = 0;

        if (Math.abs(velocity) < mMinFlingVelocity) {
            // If the velocity is low enough, then treat this more as an automatic page advance
            // as opposed to an apparent physical response to flinging
            return snapToPage(whichPage, PAGE_SNAP_ANIMATION_DURATION);
        }

        // Here we compute a "distance" that will be used in the computation of the overall
        // snap duration. This is a function of the actual distance that needs to be traveled;
        // we keep this value close to half screen size in order to reduce the variance in snap
        // duration as a function of the distance the page needs to travel.
        float distanceRatio = Math.min(1f, 1.0f * Math.abs(delta) / (2 * halfScreenSize));
        float distance = halfScreenSize + halfScreenSize *
                distanceInfluenceForSnapDuration(distanceRatio);

        velocity = Math.abs(velocity);
        velocity = Math.max(mMinSnapVelocity, velocity);

        // we want the page's snap velocity to approximately match the velocity at which the
        // user flings, so we scale the duration by a value near to the derivative of the scroll
        // interpolator at zero, ie. 5. We use 4 to make it a little slower.
        duration = 4 * Math.round(1000 * Math.abs(distance / velocity));

        return snapToPage(whichPage, delta, duration);
    }

    public boolean snapToPage(int whichPage) {
        return snapToPage(whichPage, PAGE_SNAP_ANIMATION_DURATION);
    }

    public boolean snapToPageImmediately(int whichPage) {
        return snapToPage(whichPage, PAGE_SNAP_ANIMATION_DURATION, true);
    }

    public boolean snapToPage(int whichPage, int duration) {
        return snapToPage(whichPage, duration, false);
    }

    protected boolean snapToPage(int whichPage, int duration, boolean immediate) {
        whichPage = validateNewPage(whichPage);

        int newLoc = getScrollForPage(whichPage);
        final int delta = newLoc - mOrientationHandler.getPrimaryScroll(this);
        return snapToPage(whichPage, delta, duration, immediate);
    }

    protected boolean snapToPage(int whichPage, int delta, int duration) {
        return snapToPage(whichPage, delta, duration, false);
    }

    protected boolean snapToPage(int whichPage, int delta, int duration, boolean immediate) {
        if (mFirstLayout) {
            setCurrentPage(whichPage);
            return false;
        }

        if (FeatureFlags.IS_STUDIO_BUILD) {
            duration *= Settings.Global.getFloat(getContext().getContentResolver(),
                    Settings.Global.WINDOW_ANIMATION_SCALE, 1);
        }

        whichPage = validateNewPage(whichPage);

        mNextPage = whichPage;

        awakenScrollBars(duration);
        if (immediate) {
            duration = 0;
        } else if (duration == 0) {
            duration = Math.abs(delta);
        }

        if (duration != 0) {
            pageBeginTransition();
        }

        if (!mScroller.isFinished()) {
            abortScrollerAnimation(false);
        }

        mScroller.startScroll(mOrientationHandler.getPrimaryScroll(this), 0, delta, 0, duration);
        updatePageIndicator();

        // Trigger a compute() to finish switching pages if necessary
        if (immediate) {
            computeScroll();
            pageEndTransition();
        }

        invalidate();
        return Math.abs(delta) > 0;
    }

    public boolean scrollLeft() {
        if (getNextPage() > 0) {
            snapToPage(getNextPage() - 1);
            return true;
        }
        return mAllowOverScroll;
    }

    public boolean scrollRight() {
        if (getNextPage() < getChildCount() - 1) {
            snapToPage(getNextPage() + 1);
            return true;
        }
        return mAllowOverScroll;
    }

    @Override
    public CharSequence getAccessibilityClassName() {
        // Some accessibility services have special logic for ScrollView. Since we provide same
        // accessibility info as ScrollView, inform the service to handle use the same way.
        return ScrollView.class.getName();
    }

    protected boolean isPageOrderFlipped() {
        return false;
    }

    /* Accessibility */
    @RequiresApi(api = Build.VERSION_CODES.Q)
    @SuppressWarnings("deprecation")
    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        final boolean pagesFlipped = isPageOrderFlipped();
        int offset = (mAllowOverScroll ? 0 : 1);
        info.setScrollable(getPageCount() > offset);
        if (getCurrentPage() < getPageCount() - offset) {
            info.addAction(pagesFlipped ?
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD
                : AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
            info.addAction(mIsRtl ?
                AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_LEFT
                : AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_RIGHT);
        }
        if (getCurrentPage() >= offset) {
            info.addAction(pagesFlipped ?
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD
                : AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
            info.addAction(mIsRtl ?
                AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_RIGHT
                : AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_LEFT);
        }
        // Accessibility-wise, PagedView doesn't support long click, so disabling it.
        // Besides disabling the accessibility long-click, this also prevents this view from getting
        // accessibility focus.
        info.setLongClickable(false);
        info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK);
    }

    @Override
    public void sendAccessibilityEvent(int eventType) {
        // Don't let the view send real scroll events.
        if (eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            super.sendAccessibilityEvent(eventType);
        }
    }

    @Override
    public void onInitializeAccessibilityEvent(AccessibilityEvent event) {
        super.onInitializeAccessibilityEvent(event);
        event.setScrollable(mAllowOverScroll || getPageCount() > 1);
    }

    @Override
    public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (super.performAccessibilityAction(action, arguments)) {
            return true;
        }
        final boolean pagesFlipped = isPageOrderFlipped();
        switch (action) {
            case AccessibilityNodeInfo.ACTION_SCROLL_FORWARD: {
                if (pagesFlipped ? scrollLeft() : scrollRight()) {
                    return true;
                }
            } break;
            case AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD: {
                if (pagesFlipped ? scrollRight() : scrollLeft()) {
                    return true;
                }
            } break;
            case android.R.id.accessibilityActionPageRight: {
                if (!mIsRtl) {
                  return scrollRight();
                } else {
                  return scrollLeft();
                }
            }
            case android.R.id.accessibilityActionPageLeft: {
                if (!mIsRtl) {
                  return scrollLeft();
                } else {
                  return scrollRight();
                }
            }
        }
        return false;
    }

    protected boolean canAnnouncePageDescription() {
        return true;
    }

    protected String getCurrentPageDescription() {
        return getContext().getString(R.string.default_scroll_format,
                getNextPage() + 1, getChildCount());
    }

    protected float getDownMotionX() {
        return mDownMotionX;
    }

    protected float getDownMotionY() {
        return mDownMotionY;
    }

    protected interface ComputePageScrollsLogic {

        boolean shouldIncludeView(View view);
    }

    public int[] getVisibleChildrenRange() {
        float visibleLeft = 0;
        float visibleRight = visibleLeft + getMeasuredWidth();
        float scaleX = getScaleX();
        if (scaleX < 1 && scaleX > 0) {
            float mid = getMeasuredWidth() / 2;
            visibleLeft = mid - ((mid - visibleLeft) / scaleX);
            visibleRight = mid + ((visibleRight - mid) / scaleX);
        }

        int leftChild = -1;
        int rightChild = -1;
        final int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            final View child = getPageAt(i);

            float left = child.getLeft() + child.getTranslationX() - getScrollX();
            if (left <= visibleRight && (left + child.getMeasuredWidth()) >= visibleLeft) {
                if (leftChild == -1) {
                    leftChild = i;
                }
                rightChild = i;
            }
        }
        mTmpIntPair[0] = leftChild;
        mTmpIntPair[1] = rightChild;
        return mTmpIntPair;
    }

    @Override
    public void draw(Canvas canvas) {
        super.draw(canvas);
        drawEdgeEffect(canvas);
        pageEndTransition();
    }

    protected void drawEdgeEffect(Canvas canvas) {
        if (mAllowOverScroll && (!mEdgeGlowRight.isFinished() || !mEdgeGlowLeft.isFinished())) {
            final int width = getWidth();
            final int height = getHeight();
            if (!mEdgeGlowLeft.isFinished()) {
                final int restoreCount = canvas.save();
                canvas.rotate(-90);
                canvas.translate(-height, Math.min(mMinScroll, getScrollX()));
                mEdgeGlowLeft.setSize(height, width);
                if (mEdgeGlowLeft.draw(canvas)) {
                    postInvalidateOnAnimation();
                }
                canvas.restoreToCount(restoreCount);
            }
            if (!mEdgeGlowRight.isFinished()) {
                final int restoreCount = canvas.save();
                canvas.rotate(90, width, 0);
                canvas.translate(width, -(Math.max(mMaxScroll, getScrollX())));

                mEdgeGlowRight.setSize(height, width);
                if (mEdgeGlowRight.draw(canvas)) {
                    postInvalidateOnAnimation();
                }
                canvas.restoreToCount(restoreCount);
            }
        }
    }
}