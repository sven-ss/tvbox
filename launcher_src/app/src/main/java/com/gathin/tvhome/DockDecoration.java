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
    private final boolean isShelfHidden;
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
        this.isShelfHidden = "none".equalsIgnoreCase(glassShelfStyle) || "hide".equalsIgnoreCase(glassShelfStyle) || "false".equalsIgnoreCase(glassShelfStyle);
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
        // 彻底移除底部长条
        return;
    }
}
