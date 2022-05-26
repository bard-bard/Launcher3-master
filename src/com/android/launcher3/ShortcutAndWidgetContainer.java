/*
 * Copyright (C) 2008 The Android Open Source Project
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

import static android.view.MotionEvent.ACTION_DOWN;

import static com.android.launcher3.CellLayout.FOLDER;
import static com.android.launcher3.CellLayout.WORKSPACE;

import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import com.android.launcher3.CellLayout.ContainerType;
import com.android.launcher3.folder.FolderIcon;
import com.android.launcher3.views.ActivityContext;
import com.android.launcher3.widget.NavigableAppWidgetHostView;

public class

ShortcutAndWidgetContainer extends ViewGroup implements FolderIcon.FolderIconParent {
    static final String TAG = "ShortcutAndWidgetContainer";

    // These are temporary variables to prevent having to allocate a new object just to
    // return an (x, y) value from helper functions. Do NOT use them to maintain other state.
    private final int[] mTmpCellXY = new int[2];

    private final Rect mTempRect = new Rect();

    @ContainerType
    private final int mContainerType;
    private final WallpaperManager mWallpaperManager;

    private int mCellWidth;
    private int mCellHeight;
    private int mBorderSpacing;

    private int mCountX;
    private int mCountY;

    private final ActivityContext mActivity;
    private boolean mInvertIfRtl = false;

    public ShortcutAndWidgetContainer(Context context, @ContainerType int containerType) {
        super(context);
        mActivity = ActivityContext.lookupContext(context);
        mWallpaperManager = WallpaperManager.getInstance(context);
        //容器类型
        mContainerType = containerType;
    }

    //设置各项参数
    public void setCellDimensions(int cellWidth, int cellHeight, int countX, int countY,
            int borderSpacing) {
        mCellWidth = cellWidth;
        mCellHeight = cellHeight;
        mCountX = countX;
        mCountY = countY;
        mBorderSpacing = borderSpacing;
    }

    //获取这个位置的view
    public View getChildAt(int cellX, int cellY) {
        final int count = getChildCount();
        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            CellLayout.LayoutParams lp = (CellLayout.LayoutParams) child.getLayoutParams();
//判断传进来的这个点是否在这个控件的区间里
            if ((lp.cellX <= cellX) && (cellX < lp.cellX + lp.cellHSpan)
                    && (lp.cellY <= cellY) && (cellY < lp.cellY + lp.cellVSpan)) {
                return child;
            }
        }
        return null;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int count = getChildCount();

        int widthSpecSize = MeasureSpec.getSize(widthMeasureSpec);
        int heightSpecSize = MeasureSpec.getSize(heightMeasureSpec);
        setMeasuredDimension(widthSpecSize, heightSpecSize);

        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() != GONE) {
                measureChild(child);
            }
        }
    }

    public void setupLp(View child) {
        CellLayout.LayoutParams lp = (CellLayout.LayoutParams) child.getLayoutParams();
        if (child instanceof NavigableAppWidgetHostView) {
            DeviceProfile profile = mActivity.getDeviceProfile();
            ((NavigableAppWidgetHostView) child).getWidgetInset(profile, mTempRect);
            lp.setup(mCellWidth, mCellHeight, invertLayoutHorizontally(), mCountX, mCountY,
                    profile.appWidgetScale.x, profile.appWidgetScale.y, mBorderSpacing, mTempRect);
        } else {
            lp.setup(mCellWidth, mCellHeight, invertLayoutHorizontally(), mCountX, mCountY,
                    mBorderSpacing, null);
        }
    }

    // Set whether or not to invert the layout horizontally if the layout is in RTL mode.
    public void setInvertIfRtl(boolean invert) {
        mInvertIfRtl = invert;
    }

    public int getCellContentHeight() {
        return Math.min(getMeasuredHeight(),
                mActivity.getDeviceProfile().getCellContentHeight(mContainerType));
    }

    public void measureChild(View child) {
        CellLayout.LayoutParams lp = (CellLayout.LayoutParams) child.getLayoutParams();
        final DeviceProfile dp = mActivity.getDeviceProfile();

        //判断是不是小部件
        if (child instanceof NavigableAppWidgetHostView) {
            ((NavigableAppWidgetHostView) child).getWidgetInset(dp, mTempRect);
            lp.setup(mCellWidth, mCellHeight, invertLayoutHorizontally(), mCountX, mCountY,
                    dp.appWidgetScale.x, dp.appWidgetScale.y, mBorderSpacing, mTempRect);
        } else {
            lp.setup(mCellWidth, mCellHeight, invertLayoutHorizontally(), mCountX, mCountY,
                    mBorderSpacing, null);
            // Center the icon/folder
            int cHeight = getCellContentHeight();
            int cellPaddingY = dp.isScalableGrid && mContainerType == WORKSPACE
                    ? dp.cellYPaddingPx
                    : (int) Math.max(0, ((lp.height - cHeight) / 2f));

            // No need to add padding when cell layout border spacing is present.
            boolean noPaddingX = (dp.cellLayoutBorderSpacingPx > 0 && mContainerType == WORKSPACE)
                    || (dp.folderCellLayoutBorderSpacingPx > 0 && mContainerType == FOLDER);
            int cellPaddingX = noPaddingX
                    ? 0
                    : mContainerType == WORKSPACE
                            ? dp.workspaceCellPaddingXPx
                            : (int) (dp.edgeMarginPx / 2f);
            //设置子view的padding，不重要
            child.setPadding(cellPaddingX, cellPaddingY, cellPaddingX, 0);
        }
        int childWidthMeasureSpec = MeasureSpec.makeMeasureSpec(lp.width, MeasureSpec.EXACTLY);
        int childheightMeasureSpec = MeasureSpec.makeMeasureSpec(lp.height, MeasureSpec.EXACTLY);
        child.measure(childWidthMeasureSpec, childheightMeasureSpec);
    }

    //判断方向，水平从右向左还是从左向右。
    public boolean invertLayoutHorizontally() {
        return mInvertIfRtl && Utilities.isRtl(getResources());
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int count = getChildCount();
        for (int i = 0; i < count; i++) {
            final View child = getChildAt(i);
            if (child.getVisibility() != GONE) {
                CellLayout.LayoutParams lp = (CellLayout.LayoutParams) child.getLayoutParams();
                layoutChild(child);
            }
        }
    }

    /**
     * Core logic to layout a child for this ViewGroup.
     */
    public void layoutChild(View child) {
        CellLayout.LayoutParams lp = (CellLayout.LayoutParams) child.getLayoutParams();
        if (child instanceof NavigableAppWidgetHostView) {
            NavigableAppWidgetHostView nahv = (NavigableAppWidgetHostView) child;

            // Scale and center the widget to fit within its cells.
            //缩放，将widget放在cell的中间
            DeviceProfile profile = mActivity.getDeviceProfile();
            float scaleX = profile.appWidgetScale.x;
            float scaleY = profile.appWidgetScale.y;

            nahv.setScaleToFit(Math.min(scaleX, scaleY));
            nahv.setTranslationForCentering(-(lp.width - (lp.width * scaleX)) / 2.0f,
                    -(lp.height - (lp.height * scaleY)) / 2.0f);
        }

        //重点这三个
        int childLeft = lp.x;
        int childTop = lp.y;
        child.layout(childLeft, childTop, childLeft + lp.width, childTop + lp.height);

        if (lp.dropped) {
            lp.dropped = false;

            final int[] cellXY = mTmpCellXY;
            getLocationOnScreen(cellXY);
            mWallpaperManager.sendWallpaperCommand(getWindowToken(),
                    WallpaperManager.COMMAND_DROP,
                    cellXY[0] + childLeft + lp.width / 2,
                    cellXY[1] + childTop + lp.height / 2, 0, null);
        }
    }


    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (ev.getAction() == ACTION_DOWN && getAlpha() == 0) {
            // Dont let children handle touch, if we are not visible.
            //如果子view状态是不可见的那么我们就拦截事件不让他能够被点击。
            return true;
        }
        return super.onInterceptTouchEvent(ev);
    }

    //此方法是告诉子view自己是否是滑动控件（一般默认是true，即父容器为滑动控件），这里定位false，只作为存放的容器不执行滑动.

    /**
     * shouldDelayChildPressedState 是ViewGroup里的一个函数，你在自定义ViewGroup的时候可以重写这个函数来告诉子View这个父容器是否是一个滑动控件，默认情况下是true，也就是说在默认情况下我们的子View都是定义在一个滑动控件里的（代码意义上的），假设这么一种场景在滑动列表控件里定义一个item，但是Android并不知道你点击的是这个item还是列表本身也就是它不知道要处理哪一个，所以在item接收到down事件的时候会将当前的状态设置为预点击，也就是在代码2处并且创建一个 CheckForTap 的任务对象,调用 postDelayed 函数在100ms后执行 CheckForTap 的run函数。
     * CheckForTap 在它的 run 函数里首先会将状态设置为点击状态然后检查是否长按，也就是说到这一步流程和普通的down流程一样的，但是这中间经历了100ms的延时，就是说如果你自定义了一个ViewGroup没有重写   shouldDelayChildPressedState 返回false的话都要经过100ms才能响应你的down事件，所以这里建议大家如果自定义ViewGroup的时候如果你自定义的不是一个滑动容器都要重写 shouldDelayChildPressedState 返回 false。
     *
     * @return
     */
    @Override
    public boolean shouldDelayChildPressedState() {
        return false;
    }

    //此方法是子view（widget）获取到焦点时，可以显示占据多大范围的矩形框
    @Override
    public void requestChildFocus(View child, View focused) {
        super.requestChildFocus(child, focused);
        if (child != null) {
            Rect r = new Rect();
            //获取View的绘制范围，即左、上、右、下边界相对于此View的左顶点的距离（偏移量），即0、0、View的宽、View的高
            child.getDrawingRect(r);
            //注意的是requestRectangleOnScreen()只是获取了相对于屏幕的x,y坐标，而并没有获取到right和bottom，这点很关键，需要自己在进行添加，即获取顶点坐标
            requestRectangleOnScreen(r);
        }
    }

    @Override
    public void cancelLongPress()




            //调用cancelLongPress()用来取消用户点下屏幕水平滑动但是手指未抬起时的长按事件。

    {
        super.cancelLongPress();

        // Cancel long press for all children
        final int count = getChildCount();
        for (int i = 0; i < count; i++) {
            final View child = getChildAt(i);
            child.cancelLongPress();
        }
    }

    //fold暂时忽略
    @Override
    public void drawFolderLeaveBehindForIcon(FolderIcon child) {
        CellLayout.LayoutParams lp = (CellLayout.LayoutParams) child.getLayoutParams();
        // While the folder is open, the position of the icon cannot change.
        lp.canReorder = false;
        if (mContainerType == CellLayout.HOTSEAT) {
            CellLayout cl = (CellLayout) getParent();
            cl.setFolderLeaveBehindCell(lp.cellX, lp.cellY);
        }
    }
    //fold暂时忽略
    @Override
    public void clearFolderLeaveBehind(FolderIcon child) {
        ((CellLayout.LayoutParams) child.getLayoutParams()).canReorder = true;
        if (mContainerType == CellLayout.HOTSEAT) {
            CellLayout cl = (CellLayout) getParent();
            cl.clearFolderLeaveBehind();
        }
    }
}
