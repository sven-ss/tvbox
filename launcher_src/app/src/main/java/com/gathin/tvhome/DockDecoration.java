package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Draws the authentic Apple tvOS style frosted glass shelf behind the first (dock) row.
 * Supports both Dark Frosted Glass and Light/White Frosted Glass materials.
 */
public class DockDecoration extends RecyclerView.ItemDecoration {

    private final int dockSize;
    private final float density;
    private final boolean isWhiteShelf;
    private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint specular = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RectF specularRect = new RectF();
    private float lastTop = -1, lastBottom = -1;

    DockDecoration(int dockSize, float density, String glassShelfStyle) {
        this.dockSize = dockSize;
        this.density = density;
        this.isWhiteShelf = "white".equalsIgnoreCase(glassShelfStyle) || "light".equalsIgnoreCase(glassShelfStyle);

        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(1.2f * density);

        shadow.setStyle(Paint.Style.STROKE);
        shadow.setColor(0x22000000);
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent,
                               @NonNull RecyclerView.State state) {
        int pos = parent.getChildAdapterPosition(view);
        if (pos >= 0 && pos < dockSize) {
            outRect.top = (int) (6 * density);
            outRect.bottom = (int) (14 * density);
        }
    }

    @Override
    public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        boolean any = false;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            int pos = parent.getChildAdapterPosition(child);
            if (pos < 0 || pos >= dockSize) continue;
            View card = child.findViewById(R.id.card);
            if (card == null) continue;
            float l = child.getLeft() + card.getLeft();
            float t = child.getTop() + card.getTop() + child.getTranslationY();
            float r = l + card.getWidth();
            float b = t + card.getHeight();
            if (!any) {
                rect.set(l, t, r, b);
                any = true;
            } else {
                rect.union(l, t, r, b);
            }
        }
        if (!any) return;

        float padH = 14 * density;
        float padV = 12 * density;
        rect.inset(-padH, -padV);
        float radius = 28 * density;

        // 仅在尺寸或位置变化时更新着色器（避免每帧创建对象）
        if (rect.top != lastTop || rect.bottom != lastBottom) {
            lastTop = rect.top;
            lastBottom = rect.bottom;
            float h = rect.height();

            if (isWhiteShelf) {
                // Apple tvOS 标志性的白色/浅色毛玻璃玉脂托盘（White Frosted Glass）
                band.setShader(new LinearGradient(
                        0, rect.top, 0, rect.bottom,
                        0x73FFFFFF, // 顶部 45% 透白
                        0x33FFFFFF, // 底部 20% 朦胧白
                        Shader.TileMode.CLAMP));
                specular.setShader(new LinearGradient(
                        0, rect.top, 0, rect.top + h * 0.45f,
                        0x59FFFFFF, // 顶部高光
                        0x00FFFFFF,
                        Shader.TileMode.CLAMP));
                rim.setColor(0x8CFFFFFF); // 55% 白色质感边缘
            } else {
                // Apple tvOS 标志性的深色黑曜石半透明玻璃托盘（Dark Obsidian Glass）
                band.setShader(new LinearGradient(
                        0, rect.top, 0, rect.bottom,
                        0x403A3A40, // 顶部透光灰
                        0x66121216, // 底部深邃半透黑
                        Shader.TileMode.CLAMP));
                specular.setShader(new LinearGradient(
                        0, rect.top, 0, rect.top + h * 0.45f,
                        0x38FFFFFF, // 顶部玻璃反光
                        0x00FFFFFF,
                        Shader.TileMode.CLAMP));
                rim.setColor(0x38FFFFFF); // 22% 细致微光边框
            }
        }

        // 1. 底部柔和阴影扩散
        shadow.setStrokeWidth(3f * density);
        c.drawRoundRect(rect.left - 1.5f * density, rect.top + 2f * density,
                rect.right + 1.5f * density, rect.bottom + 4f * density, radius + 2f * density, radius + 2f * density, shadow);

        // 2. 玻璃托盘本体半透明渐变
        c.drawRoundRect(rect, radius, radius, band);

        // 3. 上半部分 45% 入射光折射高光（Specular Highlight）
        c.save();
        specularRect.set(rect.left, rect.top, rect.right, rect.top + rect.height() * 0.45f);
        c.clipRect(specularRect);
        c.drawRoundRect(rect, radius, radius, specular);
        c.restore();

        // 4. 高精度玻璃反光细边框
        c.drawRoundRect(rect, radius, radius, rim);
    }
}
