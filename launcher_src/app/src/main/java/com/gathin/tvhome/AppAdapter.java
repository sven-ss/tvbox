package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * App tiles. The first {@link MainActivity#COLUMNS} positions are the pinned dock (favourites, fixed order),
 * then the module cards the owner chose to show (by label), the rest is alphabetical.
 */
public class AppAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {

    interface TileListener {
        void onTileFocused(int position, View card);

        /** Called for a D-pad direction key on a tile. Return true when handled (focus stays put). */
        boolean onTileVerticalKey(int position, int keyCode);
    }

    static final class Entry {
        static final int APP = 0, HOME_SETTINGS = 1, CARD = 2;

        final int kind;
        final String label;
        final ComponentName component;
        final String pkg;
        final Drawable banner;
        final Drawable icon;
        /** Text under the tile when it is focused in the grid: the label, plus the caption for a card. */
        final String name;
        /** Ambient glow colour; 0 until sampled from the banner, or the card's accent from the start. */
        int ambient;

        Entry(String label, ComponentName component, Drawable banner, Drawable icon) {
            this(APP, label, component, banner, icon, null, 0);
        }

        Entry(int kind, String label, ComponentName component, Drawable banner, Drawable icon, String caption, int accent) {
            this.kind = kind;
            this.label = label;
            this.component = component;
            this.pkg = component.getPackageName();
            this.banner = banner;
            this.icon = icon;
            this.name = caption == null ? label : label + " \u00B7 " + caption;
            this.ambient = accent;
        }
    }

    static final float FOCUS_SCALE = 1.10f;
    /** Design size of a tile (dp); tiles shrink from here when the columns don't fit, see tileWidthPx. */
    static final float DESIGN_W_DP = 180f, DESIGN_H_DP = 101f;

    /**
     * Tile width for a grid of this inner width: the cell minus room for the focus zoom and the glass
     * highlight, capped at the design size so 4 columns on a 1080p TV look exactly as before.
     */
    static int tileWidthPx(int innerWidthPx, int columns, float density) {
        int design = Math.round(DESIGN_W_DP * density);
        if (innerWidthPx <= 0 || columns <= 0) return design;
        int cell = innerWidthPx / columns;
        int fit = (int) ((cell - 12 * density) / FOCUS_SCALE);
        return Math.max(Math.round(80 * density), Math.min(design, fit));
    }

    static int tileHeightPx(int tileWidthPx) {
        return Math.round(tileWidthPx * DESIGN_H_DP / DESIGN_W_DP);
    }

    /** Height of one grid item: the tile, the name label under it and the item's own padding. */
    static int itemHeightPx(int tileHeightPx, float density) {
        return tileHeightPx + Math.round(45 * density);
    }

    private final Context ctx;
    private final TileListener listener;
    private final int dockSize;
    /** Dock order (config.xml dock_apps, or a theme's config.json). */
    private final List<String> favourites;
    /** Never shown as tiles (res/values/config.xml, hidden_apps). */
    private final Set<String> hidden;
    private final boolean animations;
    private final boolean advancedEffects;
    private final boolean showHomeSettings;
    /** Calmer motion for weak GPUs (config.xml, reduced_motion): no overshoot, shorter fades. */
    private final boolean reducedMotion;
    private final List<Entry> items = new ArrayList<>();

    AppAdapter(Context ctx, TileListener listener, int dockSize, Config cfg) {
        this.ctx = ctx;
        this.listener = listener;
        this.dockSize = dockSize;
        this.favourites = cfg.dockApps;
        this.hidden = new HashSet<>(cfg.hiddenApps);
        this.animations = cfg.animations;
        this.advancedEffects = cfg.animations && cfg.advancedEffects;
        this.showHomeSettings = cfg.showHomeSettingsShortcut;
        this.reducedMotion = cfg.reducedMotion;
        // Tiles keep their identity across reload(), so a focused tile stays on its app instead of
        // its view being rebound to whatever app now sits in that slot.
        setHasStableIds(true);
    }

    /**
     * Rebuilds the tiles. A shown module card stands in for every tile of its package (same component,
     * so focus follows it); hidden_apps wins over a card, and a card's package in dock_apps takes that slot.
     */
    void reload(List<Cards.Card> cards) {
        PackageManager pm = ctx.getPackageManager();
        Intent leanbackProbe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        Intent normalProbe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> found = new ArrayList<>(pm.queryIntentActivities(leanbackProbe, 0));
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo != null) seen.add(ri.activityInfo.packageName);
        }
        for (ResolveInfo ri : pm.queryIntentActivities(normalProbe, 0)) {
            if (ri.activityInfo != null && seen.add(ri.activityInfo.packageName)) {
                found.add(ri);
            }
        }
        Map<String, Cards.Card> cardByPkg = new HashMap<>();
        for (Cards.Card c : cards) cardByPkg.put(c.pkg, c);
        List<Entry> fresh = new ArrayList<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || hidden.contains(pkg)) continue;

            // 严格过滤系统预装组件，只展示用户自己安装的应用
            android.content.pm.ApplicationInfo appInfo = ri.activityInfo.applicationInfo;
            if (appInfo != null && (appInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                    && (appInfo.flags & android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) {
                continue;
            }
            ComponentName component = new ComponentName(pkg, ri.activityInfo.name);
            Cards.Card card = cardByPkg.get(pkg);
            if (card != null && !card.component.equals(component)) continue;   // the card stands in for this tile
            Drawable banner = loadBanner(pm, ri);
            Drawable icon = ri.loadIcon(pm);
            if (card != null) {
                fresh.add(new Entry(Entry.CARD, card.label, component, banner, icon, card.caption, card.accent));
            } else {
                // Same cleaner as the card prompt: an app label with a bidi override or line separator must not
                // redraw the tile name as something else (#117).
                fresh.add(new Entry(Cards.cleanLabel(String.valueOf(ri.loadLabel(pm)), pkg), component, banner, icon));
            }
        }
        Collections.sort(fresh, (a, b) -> {
            int fa = favourites.indexOf(a.pkg), fb = favourites.indexOf(b.pkg);
            if (fa >= 0 || fb >= 0) {
                if (fa < 0) return 1;
                if (fb < 0) return -1;
                return Integer.compare(fa, fb);
            }
            // Cards come right after the configured dock apps, before the alphabetical grid.
            boolean ca = a.kind == Entry.CARD, cb = b.kind == Entry.CARD;
            if (ca != cb) return ca ? -1 : 1;
            return a.label.compareToIgnoreCase(b.label);
        });
        // Retain the optional Default-home tile until dependent tile-ordering work is merged.
        if (showHomeSettings
                && pm.resolveActivity(new Intent(Settings.ACTION_HOME_SETTINGS), 0) != null) {
            fresh.add(new Entry(Entry.HOME_SETTINGS, ctx.getString(R.string.home_settings),
                    new ComponentName(ctx, HomeSettingsActivity.class), null,
                    ctx.getDrawable(R.drawable.ic_home_settings), null, 0));
        }
        items.clear();
        items.addAll(fresh);
        notifyDataSetChanged();
    }

    private Drawable loadBanner(PackageManager pm, ResolveInfo ri) {
        if (ri.activityInfo != null) {
            String pkg = ri.activityInfo.packageName;
            // 仅用户明确指定的 3 个核心 App 走定制高清图标，其余一律走官方原生图标
            if ("org.smarttube.stable".equals(pkg) || "com.tvbox.app".equals(pkg) || "com.quark.yun.tv".equals(pkg)) {
                try {
                    java.io.File ext = ctx.getExternalFilesDir(null);
                    if (ext != null && ext.getParentFile() != null) {
                        java.io.File customIcon = new java.io.File(ext.getParentFile(), "icons/" + pkg + ".png");
                        if (customIcon.exists()) {
                            Drawable d = Drawable.createFromPath(customIcon.getAbsolutePath());
                            if (d != null) return d;
                        }
                    }
                } catch (Exception ignored) {}
                try {
                    java.io.InputStream is = ctx.getAssets().open("icons/" + pkg + ".png");
                    Drawable d = Drawable.createFromStream(is, null);
                    if (d != null) return d;
                } catch (Exception ignored) {}
            }
        }
        int id = ri.activityInfo.banner;
        if (id == 0) id = ri.activityInfo.applicationInfo.banner;
        if (id != 0) {
            try {
                Resources resources = pm.getResourcesForApplication(ri.activityInfo.applicationInfo);
                // Keep at least xhdpi detail rather than downsampling artwork before the focus zoom.
                int density = Math.max(DisplayMetrics.DENSITY_XHIGH, ctx.getResources().getDisplayMetrics().densityDpi);
                return resources.getDrawableForDensity(id, density, null);
            } catch (PackageManager.NameNotFoundException | Resources.NotFoundException ignored) {
            }
        }
        return ri.activityInfo.loadBanner(pm);
    }

    /**
     * 将第三方没有横屏 Banner 的普通应用图标包装成 Apple tvOS 规范的高质感毛玻璃圆角卡片
     */
    private Drawable wrapWithAppleTvCard(Drawable icon) {
        float density = ctx.getResources().getDisplayMetrics().density;
        int w = (int) (180 * density), h = (int) (101 * density);
        float radius = 18f * density;

        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);

        // 1. 深色半透明优雅黑曜石渐变卡片底座
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF242730, 0xFF121418}
        );
        bg.setCornerRadius(radius);
        bg.setBounds(0, 0, w, h);
        bg.draw(canvas);

        // 2. 0.8dp 极细微光边缘
        Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(1f * density);
        rim.setColor(0x22FFFFFF);
        canvas.drawRoundRect(new RectF(0.5f, 0.5f, w - 0.5f, h - 0.5f), radius, radius, rim);

        // 3. 将第三方图标优雅居中呈现（52dp 尺寸，黄金比例留白，绝不拉伸变形）
        int iconSize = (int) (52 * density);
        int l = (w - iconSize) / 2, t = (h - iconSize) / 2;
        icon.setBounds(l, t, l + iconSize, t + iconSize);
        icon.draw(canvas);

        return new android.graphics.drawable.BitmapDrawable(ctx.getResources(), bmp);
    }


    private Drawable createAppleTvBanner(String pkg) {
        float density = ctx.getResources().getDisplayMetrics().density;
        int w = (int) (180 * density), h = (int) (101 * density);
        float radius = 18f * density;
        int topColor = 0xFF2A2D35, bottomColor = 0xFF14161C;
        String iconType = "generic";

        // 核心影音
        if ("org.smarttube.stable".equals(pkg)) {
            topColor = 0xFFEE384E; bottomColor = 0xFFC2182B; iconType = "smarttube";
        } else if ("com.tvbox.app".equals(pkg)) {
            topColor = 0xFF2A2D34; bottomColor = 0xFF121418; iconType = "tvbox";
        
        } else if ("com.quark.yun.tv".equals(pkg)) {
            topColor = 0xFF262A33; bottomColor = 0xFF14161B; iconType = "quark";
        } else if ("io.nekohasekai.sfa".equals(pkg)) {
            topColor = 0xFF3A3F4D; bottomColor = 0xFF1E2129; iconType = "box";
        // 投屏与系统工具（彻底美化第二行的丑图标）
        } else if (pkg != null && (pkg.contains("miracast") || pkg.contains("screencast") || pkg.contains("airplay"))) {
            topColor = 0xFF1B9CFC; bottomColor = 0xFF182C61; iconType = "cast";
        } else if (pkg != null && (pkg.contains("setting") || pkg.contains("Settings"))) {
            topColor = 0xFF4B4B4B; bottomColor = 0xFF222222; iconType = "gear";
        } else if (pkg != null && (pkg.contains("tracing") || pkg.contains("trace"))) {
            topColor = 0xFF5856D6; bottomColor = 0xFF2C2A7A; iconType = "trace";
        } else if (pkg != null && (pkg.contains("dangbei") || pkg.contains("haqu") || pkg.contains("etna"))) {
            topColor = 0xFF2F3542; bottomColor = 0xFF1E222A; iconType = "cube";
        } else {
            return null; // 未匹配的走高质量通用自适应包装
        }

        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);

        // 1. 绘制极简纯净大圆角渐变背景
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{topColor, bottomColor});
        bg.setCornerRadius(radius);
        bg.setBounds(0, 0, w, h);
        bg.draw(canvas);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0xFFFFFFFF);
        paint.setStyle(Paint.Style.FILL);

        float cx = w / 2f, cy = h / 2f;
        if ("smarttube".equals(iconType)) {
            float bw = 24f * density, bh = 18f * density;
            RectF r = new RectF(cx - bw, cy - bh, cx + bw, cy + bh);
            canvas.drawRoundRect(r, 6f * density, 6f * density, paint);
            Paint redP = new Paint(Paint.ANTI_ALIAS_FLAG);
            redP.setColor(bottomColor);
            Path tri = new Path();
            tri.moveTo(cx - 5f * density, cy - 7f * density);
            tri.lineTo(cx + 8f * density, cy);
            tri.lineTo(cx - 5f * density, cy + 7f * density);
            tri.close();
            canvas.drawPath(tri, redP);
        } else if ("play".equals(iconType)) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3.5f * density);
            canvas.drawCircle(cx, cy, 18f * density, paint);
            paint.setStyle(Paint.Style.FILL);
            Path tri = new Path();
            tri.moveTo(cx - 5f * density, cy - 9f * density);
            tri.lineTo(cx + 10f * density, cy);
            tri.lineTo(cx - 5f * density, cy + 9f * density);
            tri.close();
            canvas.drawPath(tri, paint);
        } else if ("bilibili".equals(iconType)) {
            float bw = 22f * density, bh = 15f * density;
            RectF r = new RectF(cx - bw, cy - bh + 2f * density, cx + bw, cy + bh + 2f * density);
            canvas.drawRoundRect(r, 5f * density, 5f * density, paint);
            paint.setStrokeWidth(2.5f * density);
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawLine(cx - 10f * density, cy - bh + 2f * density, cx - 18f * density, cy - bh - 6f * density, paint);
            canvas.drawLine(cx + 10f * density, cy - bh + 2f * density, cx + 18f * density, cy - bh - 6f * density, paint);
            Paint dotP = new Paint(Paint.ANTI_ALIAS_FLAG);
            dotP.setColor(bottomColor);
            dotP.setStyle(Paint.Style.FILL);
            canvas.drawCircle(cx - 9f * density, cy + 1f * density, 2.5f * density, dotP);
            canvas.drawCircle(cx + 9f * density, cy + 1f * density, 2.5f * density, dotP);
        } else if ("tvbox".equals(iconType)) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3f * density);
            RectF r = new RectF(cx - 22f * density, cy - 14f * density, cx + 22f * density, cy + 14f * density);
            canvas.drawRoundRect(r, 5f * density, 5f * density, paint);
            paint.setStyle(Paint.Style.FILL);
            Path tri = new Path();
            tri.moveTo(cx - 4f * density, cy - 6f * density);
            tri.lineTo(cx + 6f * density, cy);
            tri.lineTo(cx - 4f * density, cy + 6f * density);
            tri.close();
            canvas.drawPath(tri, paint);
        } else if ("quark".equals(iconType)) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(4f * density);
            paint.setColor(0xFF00D2D3);
            canvas.drawCircle(cx, cy, 17f * density, paint);
            paint.setStrokeWidth(3f * density);
            paint.setColor(0xFFFFFFFF);
            canvas.drawCircle(cx, cy, 10f * density, paint);
        } else if ("cast".equals(iconType)) {
            // Apple AirPlay 风格极简投屏
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3f * density);
            RectF r = new RectF(cx - 20f * density, cy - 14f * density, cx + 20f * density, cy + 14f * density);
            canvas.drawRoundRect(r, 4f * density, 4f * density, paint);
            paint.setStyle(Paint.Style.FILL);
            Path tri = new Path();
            tri.moveTo(cx - 7f * density, cy + 12f * density);
            tri.lineTo(cx + 7f * density, cy + 12f * density);
            tri.lineTo(cx, cy + 3f * density);
            tri.close();
            canvas.drawPath(tri, paint);
        } else if ("gear".equals(iconType)) {
            // Apple TV 精密设置齿轮
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3.5f * density);
            canvas.drawCircle(cx, cy, 14f * density, paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(cx, cy, 5f * density, paint);
        } else if ("cube".equals(iconType) || "box".equals(iconType)) {
            // 极简现代立体方块
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3f * density);
            RectF r = new RectF(cx - 15f * density, cy - 15f * density, cx + 15f * density, cy + 15f * density);
            canvas.drawRoundRect(r, 5f * density, 5f * density, paint);
            canvas.drawLine(cx, cy - 15f * density, cx, cy + 15f * density, paint);
            canvas.drawLine(cx - 15f * density, cy, cx + 15f * density, cy, paint);
        } else if ("trace".equals(iconType)) {
            // 极简仪表
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3f * density);
            canvas.drawCircle(cx, cy, 15f * density, paint);
            canvas.drawLine(cx, cy, cx + 8f * density, cy - 8f * density, paint);
        }

        return new android.graphics.drawable.BitmapDrawable(ctx.getResources(), bmp);
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        float density = parent.getResources().getDisplayMetrics().density;
        int width = parent.getWidth() > 0 ? parent.getWidth() : parent.getMeasuredWidth();
        int inner = width - parent.getPaddingLeft() - parent.getPaddingRight();
        int w = tileWidthPx(inner, dockSize, density), h = tileHeightPx(w);
        View card = v.findViewById(R.id.card);
        ViewGroup.LayoutParams cardLp = card.getLayoutParams();
        cardLp.width = w;
        cardLp.height = h;
        card.setLayoutParams(cardLp);
        View name = v.findViewById(R.id.name);
        ViewGroup.MarginLayoutParams nameLp = (ViewGroup.MarginLayoutParams) name.getLayoutParams();
        nameLp.width = w;
        nameLp.topMargin = h + Math.round(7 * density);
        name.setLayoutParams(nameLp);
        return new Holder(v, listener, animations, advancedEffects, reducedMotion);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Entry e = items.get(position);
        h.name.setText(e.name);
        h.name.setVisibility(position < dockSize ? View.INVISIBLE : View.VISIBLE);   // the dock has no labels
        boolean focused = h.card.isFocused();   // a rebind after reload() can hit the focused tile
        h.name.setAlpha(focused ? 1f : 0f);
        h.card.setPivotX(w / 2f);
        h.card.setPivotY(h / 2f);
        h.card.setScaleX(focused && animations ? 1.14f : 1f);
        h.card.setScaleY(focused && animations ? 1.14f : 1f);
        h.card.setTranslationY(0f); // 绝不偏心上移
        h.card.setTranslationZ(focused ? 16f * density : 0f);
        h.banner.setScaleX(focused && advancedEffects ? 1.035f : 1f);
        h.banner.setScaleY(focused && advancedEffects ? 1.035f : 1f);
        if (e.banner != null) {
            h.banner.setImageDrawable(e.banner);
            h.banner.setVisibility(View.VISIBLE);
            h.icon.setVisibility(View.GONE);
            h.label.setVisibility(View.GONE);
        } else {
            h.banner.setImageDrawable(null);
            h.banner.setVisibility(View.GONE);
            h.icon.setImageDrawable(e.icon);
            h.icon.setVisibility(View.VISIBLE);
            h.label.setText(e.label);
            h.label.setVisibility(View.VISIBLE);
        }
        h.card.setOnClickListener(v -> launch(e));
        h.card.setOnLongClickListener(v -> {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + e.pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                ctx.startActivity(i);
            } catch (ActivityNotFoundException ignored) {
            }
            return true;
        });
    }

    /** Opens the tile's activity by explicit component, with no extras: a card can name nothing else. */
    private void launch(Entry e) {
        if (e.kind == Entry.HOME_SETTINGS) {
            ctx.startActivity(new Intent(ctx, HomeSettingsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(e.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            ctx.startActivity(i);
            if (advancedEffects && ctx instanceof Activity) {
                ((Activity) ctx).overridePendingTransition(R.anim.app_open_enter, R.anim.app_open_exit);
            }
        } catch (Exception ex) {
            Toast.makeText(ctx, ctx.getString(R.string.cant_open, e.label), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).component.flattenToShortString().hashCode();
    }

    /** Stable key for the app at a position (its component), or null. */
    String keyAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).component.flattenToShortString() : null;
    }

    /** Position of the app with this key, or -1 when it is gone. */
    int positionOf(String key) {
        if (key == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).component.flattenToShortString().equals(key)) return i;
        }
        return -1;
    }

    String labelAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).label : "";
    }

    float focusScale() {
        return !animations ? 1f : advancedEffects ? 1.12f : 1.10f;
    }

    /** A vivid colour sampled from the app's banner (or icon), cached per entry; a card's accent when it set one. */
    int ambientColorAt(int position) {
        if (position < 0 || position >= items.size()) return 0xFF444444;
        Entry e = items.get(position);
        if (e.ambient == 0) e.ambient = vividColor(e.banner != null ? e.banner : e.icon);
        return e.ambient;
    }

    private static int vividColor(Drawable live) {
        if (live == null) return 0xFF444444;
        // Draw a private copy: changing the bounds of the drawable an ImageView shows would blank the tile.
        Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
        Rect saved = new Rect(d.getBounds());
        int n = 12;
        Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        d.setBounds(0, 0, n, n);
        d.draw(c);
        d.setBounds(saved);
        float[] hsv = new float[3];
        int best = 0xFF444444;
        float bestScore = -1f;
        long r = 0, g = 0, b = 0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int p = bmp.getPixel(x, y);
                r += Color.red(p);
                g += Color.green(p);
                b += Color.blue(p);
                Color.colorToHSV(p, hsv);
                float score = hsv[1] * Math.min(1f, hsv[2] * 1.4f);   // saturated and not too dark
                if (score > bestScore) {
                    bestScore = score;
                    best = p;
                }
            }
        }
        bmp.recycle();
        if (bestScore < 0.25f) {   // nearly monochrome banner (e.g. white): use its average, lifted
            int avg = Color.rgb((int) (r / (n * n)), (int) (g / (n * n)), (int) (b / (n * n)));
            Color.colorToHSV(avg, hsv);
            hsv[2] = Math.max(hsv[2], 0.55f);
            return Color.HSVToColor(hsv);
        }
        Color.colorToHSV(best, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.1f);
        hsv[2] = Math.max(0.5f, hsv[2]);
        return Color.HSVToColor(hsv);
    }

    /** Corner radius for a tile of this width: 16dp at the design width, proportionally less when smaller. */
    static float cornerRadius(int tileWidthPx, float density) {
        return 16f * density * Math.min(1f, tileWidthPx / (DESIGN_W_DP * density));
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView banner;
        final ImageView icon;
        final TextView label;
        final TextView name;
        final boolean animations;
        final boolean advancedEffects;
        final boolean reducedMotion;

        Holder(View v, TileListener listener, boolean animations, boolean advancedEffects,
               boolean reducedMotion) {
            super(v);
            card = v.findViewById(R.id.card);
            banner = v.findViewById(R.id.banner);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
            name = v.findViewById(R.id.name);
            this.animations = animations;
            this.advancedEffects = advancedEffects;
            this.reducedMotion = reducedMotion;

            final float density = v.getResources().getDisplayMetrics().density;
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    // 16dp at the design width, scaled down with the tile so small tiles keep the same shape.
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerRadius(view.getWidth(), density));
                }
            });
            card.setClipToOutline(true);

            card.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN
                        && keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                int pos = getBindingAdapterPosition();
                return listener != null && pos != RecyclerView.NO_POSITION && listener.onTileVerticalKey(pos, keyCode);
            });

            card.setOnFocusChangeListener((view, focused) -> {
                view.animate().cancel();
                banner.animate().cancel();
                name.animate().cancel();
                float scale = !animations ? 1f : advancedEffects ? 1.12f : 1.10f;
                float s = focused ? scale : 1f;
                if (!animations) {
                    view.setScaleX(1f);
                    view.setScaleY(1f);
                    view.setTranslationY(0f);
                    view.setTranslationZ(0f);
                    banner.setScaleX(1f);
                    banner.setScaleY(1f);
                    name.setAlpha(focused ? 1f : 0f);
                    name.setTranslationY(0f);
                } else if (reducedMotion) {
                    view.setPivotX(view.getWidth() / 2f);
                    view.setPivotY(view.getHeight() / 2f);
                    view.animate().scaleX(s).scaleY(s)
                            .translationY(0f)
                            .translationZ(focused ? 16f * density : 0f)
                            .setDuration(focused ? 120 : 80)
                            .setInterpolator(new DecelerateInterpolator())
                            .withLayer().start();
                    name.animate().alpha(focused ? 1f : 0f).setDuration(100).start();
                } else {
                    // 严格锁定缩放原点为应用卡片的绝对几何中心，从中心等比对称放大
                    view.setPivotX(view.getWidth() / 2f);
                    view.setPivotY(view.getHeight() / 2f);
                    view.animate().scaleX(s).scaleY(s)
                            .translationY(0f) // 保持中心原点，不产生 Y 轴位移
                            .translationZ(focused ? 16f * density : 0f)
                            .setDuration(focused ? 220 : 180)
                            .setInterpolator(new OvershootInterpolator(focused ? 0.6f : 0f))
                            .withLayer().start();
                    if (advancedEffects) {
                        banner.animate().scaleX(focused ? 1.035f : 1f).scaleY(focused ? 1.035f : 1f)
                                .setDuration(280).start();
                    }
                    name.animate().alpha(focused ? 1f : 0f)
                            .translationY(focused ? density * 4f : -density * 6f)
                            .setDuration(220).start();
                }
                int pos = getBindingAdapterPosition();
                if (focused && listener != null && pos != RecyclerView.NO_POSITION) {
                    listener.onTileFocused(pos, view);
                }
            });
        }
    }
}
