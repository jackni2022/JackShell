package com.fongmi.android.tv.ui.custom;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.fongmi.android.tv.App;
import com.github.catvod.crawler.DebugLogStore;

import java.util.List;

public class FloatLog {

    private static final int COLOR_PRIMARY = 0xFF1675D2;

    private static TextView anchorBtn = null;
    private static int anchorIndex = -1;
    private static FrameLayout bubble = null;
    private static Activity bubbleHost = null;
    private static WindowManager.LayoutParams bubbleParams = null;
    private static boolean bubblePosSaved = false;
    private static boolean bubbleShowing = false;
    private static int bubbleX = 0;
    private static int bubbleY = 0;
    private static String filter = "";
    private static TextView logView = null;
    private static Handler mainHandler = null;
    private static TextView newLogTip = null;
    private static LinearLayout panel = null;
    private static Activity panelHost = null;
    private static WindowManager.LayoutParams panelParams = null;
    private static boolean panelPosSaved = false;
    private static boolean panelShowing = false;
    private static int panelX = 0;
    private static int panelY = 0;
    private static TextView pauseBtn = null;
    private static boolean paused = false;
    private static Runnable refresher = null;
    private static boolean scrollTopOnRefresh = false;
    private static ScrollView scrollView = null;
    private static int shownAnchor = 0;
    private static int shownCount = 0;
    private static boolean shownPlaceholder = false;
    private static TextView shownView = null;
    private static TextView titleView = null;
    private static boolean userPaused = false;
    private static int watchLastSize = 0;
    private static int watchPending = -1;
    private static int watchSettled;
    private static int watchStable;

    static class Auto implements Runnable {
        @Override
        public void run() {
            try {
                if (!isPaused() && !isUserPaused()) {
                    refreshContent();
                }
                scheduleAuto(new Auto());
            } catch (Throwable ignored) {
            }
        }
    }

    static class Btn implements View.OnClickListener {
        private final int code;

        Btn(int code) {
            this.code = code;
        }

        @Override
        public void onClick(View view) {
            try {
                int i = this.code;
                if (i == 0) {
                    minimize();
                } else if (i == 1) {
                    if (isUserPaused()) togglePause();
                    doRefresh();
                } else if (i == 2) {
                    closeAll();
                } else if (i == 3) {
                    copyLog();
                } else if (i == 4) {
                    clearLog();
                } else if (i == 5) {
                    togglePause();
                } else if (i == 6) {
                    scrollToBottom();
                    hideNewLogTip();
                } else {
                    toggleAnchor();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    static class Opener implements Runnable {
        @Override
        public void run() {
            openPanelMain();
        }
    }

    static class PanelTouch implements View.OnTouchListener {
        private float downX;
        private float downY;
        private boolean hasDown;
        private int startX;
        private int startY;

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            try {
                int action = event.getAction();
                if (action == MotionEvent.ACTION_DOWN) {
                    this.downX = event.getRawX();
                    this.downY = event.getRawY();
                    this.hasDown = true;
                    this.startX = panelParams.x;
                    this.startY = panelParams.y;
                    return true;
                }
                if (action != MotionEvent.ACTION_MOVE) {
                    this.hasDown = false;
                    return false;
                }
                if (this.hasDown) {
                    panelParams.x = (int) (event.getRawX() - this.downX) + this.startX;
                    panelParams.y = (int) (event.getRawY() - this.downY) + this.startY;
                    savePanelPos(panelParams.x, panelParams.y);
                    updatePanel();
                    return true;
                }
                this.downX = event.getRawX();
                this.downY = event.getRawY();
                this.hasDown = true;
                this.startX = panelParams.x;
                this.startY = panelParams.y;
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    public static class Refresh implements Runnable {
        @Override
        public void run() {
            refreshMain();
        }
    }

    static class ScrollTo implements Runnable {
        private final int y;

        ScrollTo(int y) {
            this.y = y;
        }

        @Override
        public void run() {
            try {
                ScrollView sv = scrollView;
                if (sv != null) {
                    if (this.y == -1) sv.fullScroll(View.FOCUS_DOWN);
                    else sv.scrollTo(0, this.y);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    static class ScrollTouch implements View.OnTouchListener {
        @Override
        public boolean onTouch(View view, MotionEvent event) {
            try {
                int action = event.getAction();
                if (action == MotionEvent.ACTION_DOWN) {
                    setPaused(true);
                    return false;
                }
                if (action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL) return false;
                setPaused(false);
                return false;
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    static class Touch implements View.OnTouchListener {
        private float downX;
        private float downY;
        private boolean hasDown;
        private boolean moved;
        private int startX;
        private int startY;

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            try {
                int action = event.getAction();
                if (action == MotionEvent.ACTION_DOWN) {
                    this.downX = event.getRawX();
                    this.downY = event.getRawY();
                    this.moved = false;
                    this.hasDown = true;
                    this.startX = bubbleParams.x;
                    this.startY = bubbleParams.y;
                    return true;
                }
                if (action == MotionEvent.ACTION_MOVE && (Math.abs(event.getRawX() - this.downX) > 8.0f || Math.abs(event.getRawY() - this.downY) > 8.0f)) {
                    this.moved = true;
                    bubbleParams.x = (int) (event.getRawX() - this.downX) + this.startX;
                    bubbleParams.y = (int) (event.getRawY() - this.downY) + this.startY;
                    saveBubblePos(bubbleParams.x, bubbleParams.y);
                    updateBubble();
                    return true;
                }
                if (this.hasDown) {
                    if (action != MotionEvent.ACTION_UP) return false;
                    if (this.moved) return true;
                    openPanel();
                    return true;
                }
                this.downX = event.getRawX();
                this.downY = event.getRawY();
                this.hasDown = true;
                this.startX = bubbleParams.x;
                this.startY = bubbleParams.y;
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    static class Watcher implements TextWatcher {
        @Override
        public void afterTextChanged(Editable editable) {
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            try {
                setFilter(s.toString());
                refreshContent();
            } catch (Throwable ignored) {
            }
        }
    }

    static void scheduleAuto(Runnable runnable) {
        try {
            refresher = runnable;
            mainHandler().postDelayed(runnable, 1000L);
        } catch (Throwable ignored) {
        }
    }

    static void updateBubble() {
        try {
            FrameLayout layout = bubble;
            Activity activity = bubbleHost;
            if (layout == null || activity == null) return;
            ((WindowManager) activity.getSystemService(Context.WINDOW_SERVICE)).updateViewLayout(layout, bubbleParams);
        } catch (Throwable ignored) {
        }
    }

    static void updatePanel() {
        try {
            LinearLayout layout = panel;
            Activity activity = panelHost;
            if (layout == null || activity == null) return;
            ((WindowManager) activity.getSystemService(Context.WINDOW_SERVICE)).updateViewLayout(layout, panelParams);
        } catch (Throwable ignored) {
        }
    }

    private static TextView bottomButton(Context context, String text, int code) {
        TextView textView = new TextView(context);
        textView.setText(text);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.0f);
        textView.setTextColor(COLOR_PRIMARY);
        textView.setGravity(Gravity.CENTER);
        int horizontal = dp(10.0f);
        int vertical = dp(8.0f);
        textView.setPadding(horizontal, vertical, horizontal, vertical);
        textView.setOnClickListener(new Btn(code));
        return textView;
    }

    static void clearLog() {
        try {
            DebugLogStore.clear();
            anchorIndex = -1;
            watchPending = -1;
            watchSettled = 0;
            watchLastSize = 0;
            watchStable = 0;
            TextView btn = anchorBtn;
            if (btn != null) btn.setText("只看新增");
            doRefresh();
        } catch (Throwable ignored) {
        }
    }

    public static void closeAll() {
        stopAuto();
        hidePanel();
        hideBubble();
    }

    static void copyLog() {
        try {
            TextView view = logView;
            if (view != null) {
                ((ClipboardManager) App.get().getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("log", view.getText().toString()));
            }
        } catch (Throwable ignored) {
        }
    }

    private static Activity curActivity() {
        try {
            Activity activity = App.activity();
            if (activity == null) return null;
            if (activity.isFinishing()) return null;
            return activity;
        } catch (Throwable ignored) {
            return null;
        }
    }

    static void doRefresh() {
        try {
            TextView textView = logView;
            if (textView == null) return;
            ScrollView sv = scrollView;
            int scrollY = 0;
            boolean atBottom = false;
            if (sv != null) {
                scrollY = sv.getScrollY();
                atBottom = isAtBottom();
            }
            List<String> snapshot = DebugLogStore.snapshot();
            int size = snapshot.size();
            int anchor = anchorIndex;
            if (anchor < 0) anchor = 0;
            if (anchor > size) anchor = size;
            boolean rebuild = !(shownView == textView && shownAnchor == anchor && shownCount >= anchor && shownCount <= size && !shownPlaceholder);
            if (rebuild) {
                StringBuilder sb = new StringBuilder();
                for (int i = anchor; i < size; i++) {
                    sb.append(snapshot.get(i));
                    sb.append('\n');
                }
                String text = sb.toString();
                boolean empty = text.isEmpty();
                shownPlaceholder = empty;
                if (empty) {
                    text = DebugLogStore.isEnabled() ? "暂无日志\n\n日志已开启，但当前分类没有产生新日志。下拉刷新页面或切到没打开过的分类，spider 运行时会记录请求" : "日志记录未开启\n\n面板已尝试开启，若仍无日志请检查设置里的调试日志开关";
                }
                textView.setText(text);
                if (sv != null) {
                    if (scrollTopOnRefresh) {
                        scrollTopOnRefresh = false;
                        sv.post(new ScrollTo(0));
                    } else if (atBottom) {
                        scrollToBottom();
                    } else {
                        sv.post(new ScrollTo(scrollY));
                    }
                }
            } else if (shownCount < size) {
                StringBuilder sb = new StringBuilder();
                int from = shownCount;
                while (from < size) {
                    sb.append(snapshot.get(from));
                    sb.append('\n');
                    from++;
                }
                textView.append(sb.toString());
                if (sv != null) {
                    if (scrollTopOnRefresh) {
                        scrollTopOnRefresh = false;
                        sv.post(new ScrollTo(0));
                    } else if (atBottom) {
                        scrollToBottom();
                    }
                }
            }
            if (sv != null) {
                TextView tip = newLogTip;
                if (tip != null) tip.setVisibility(atBottom ? View.GONE : View.VISIBLE);
            }
            shownView = textView;
            shownAnchor = anchor;
            shownCount = size;
            TextView title = titleView;
            if (title != null) {
                int count = size - anchor;
                StringBuilder sb = new StringBuilder();
                sb.append(anchorIndex < 0 ? (userPaused ? "运行日志（已暂停，" : "运行日志（") : "运行日志（当前页，");
                sb.append(count);
                sb.append("条）");
                title.setText(sb.toString());
            }
        } catch (Throwable ignored) {
        }
    }

    private static int dp(float value) {
        try {
            return (int) (value * App.get().getResources().getDisplayMetrics().density + 0.5f);
        } catch (Throwable ignored) {
            return (int) value;
        }
    }

    static String getFilter() {
        return filter;
    }

    private static void hideAll() {
        hidePanel();
        hideBubble();
    }

    private static void hideBubble() {
        try {
            if (bubbleShowing) {
                bubbleShowing = false;
                Activity activity = bubbleHost;
                FrameLayout layout = bubble;
                if (activity != null && layout != null) {
                    ((WindowManager) activity.getSystemService(Context.WINDOW_SERVICE)).removeView(layout);
                }
                bubble = null;
                bubbleHost = null;
                bubbleParams = null;
            }
        } catch (Throwable ignored) {
        }
    }

    static void hideNewLogTip() {
        try {
            TextView tip = newLogTip;
            if (tip != null) tip.setVisibility(View.GONE);
        } catch (Throwable ignored) {
        }
    }

    private static void hidePanel() {
        try {
            stopAuto();
            if (panelShowing) {
                panelShowing = false;
                Activity activity = panelHost;
                LinearLayout layout = panel;
                if (activity == null || layout == null) return;
                ((WindowManager) activity.getSystemService(Context.WINDOW_SERVICE)).removeView(layout);
            }
        } catch (Throwable ignored) {
        }
    }

    static boolean isAtBottom() {
        try {
            ScrollView sv = scrollView;
            TextView view = logView;
            if (sv == null || view == null) return true;
            return sv.getScrollY() + sv.getHeight() >= view.getHeight() - 50;
        } catch (Throwable ignored) {
            return true;
        }
    }

    static boolean isPaused() {
        return paused;
    }

    static boolean isUserPaused() {
        return userPaused;
    }

    private static Handler mainHandler() {
        if (mainHandler == null) mainHandler = new Handler(Looper.getMainLooper());
        return mainHandler;
    }

    public static void minimize() {
        try {
            hidePanel();
            showBubble();
        } catch (Throwable ignored) {
        }
    }

    public static void openPanel() {
        try {
            mainHandler().post(new Opener());
        } catch (Throwable ignored) {
        }
    }

    static void openPanelMain() {
        try {
            DebugLogStore.setEnabled(true);
            Activity activity = curActivity();
            if (activity == null) return;
            if (panelShowing && panelHost == activity) return;
            hideBubble();
            hidePanel();
            showPanelOn(activity);
        } catch (Throwable ignored) {
        }
    }

    public static void refresh() {
        try {
            mainHandler().post(new Refresh());
        } catch (Throwable ignored) {
        }
    }

    static void refreshContent() {
        try {
            if (panel == null || paused || userPaused) return;
            int size = DebugLogStore.snapshot().size();
            if (size != watchLastSize) {
                watchLastSize = size;
                watchStable = 0;
                int settled = watchSettled;
                if (size < settled) {
                    watchSettled = size;
                    watchPending = -1;
                    return;
                }
                if (size <= settled || watchPending >= 0) return;
                watchPending = settled;
                return;
            }
            if (watchPending < 0) return;
            int stable = watchStable + 1;
            watchStable = stable;
            if (stable < 2) {
                anchorIndex = watchPending;
                watchPending = -1;
                watchStable = 0;
                watchSettled = size;
                scrollTopOnRefresh = true;
                TextView btn = anchorBtn;
                if (btn != null) btn.setText("看全部");
                doRefresh();
            }
        } catch (Throwable ignored) {
        }
    }

    static void refreshMain() {
        try {
            if (!DebugLogStore.isEnabled()) {
                closeAll();
                return;
            }
            Activity activity = curActivity();
            if (activity == null) return;
            if (panelShowing) {
                if (panelHost == activity) return;
                hidePanel();
                showPanelOn(activity);
                return;
            }
            if (bubbleShowing && bubbleHost != activity) {
                hideBubble();
                showBubbleOn(activity);
            }
        } catch (Throwable ignored) {
        }
    }

    static void saveBubblePos(int x, int y) {
        bubbleX = x;
        bubbleY = y;
        bubblePosSaved = true;
    }

    static void savePanelPos(int x, int y) {
        panelX = x;
        panelY = y;
        panelPosSaved = true;
    }

    static void scrollToBottom() {
        try {
            ScrollView sv = scrollView;
            if (sv != null) sv.post(new ScrollTo(-1));
        } catch (Throwable ignored) {
        }
    }

    static void setFilter(String value) {
        filter = value == null ? "" : value;
    }

    static void setPaused(boolean value) {
        paused = value;
    }

    private static void showBubble() {
        try {
            Activity activity = curActivity();
            if (activity != null) showBubbleOn(activity);
        } catch (Throwable ignored) {
        }
    }

    static void showBubbleOn(Activity activity) {
        try {
            if (bubbleShowing) return;
            App app = App.get();
            int size = dp(56.0f);
            FrameLayout layout = new FrameLayout(app);
            GradientDrawable background = new GradientDrawable();
            background.setShape(GradientDrawable.OVAL);
            background.setColor(COLOR_PRIMARY);
            layout.setBackground(background);
            TextView textView = new TextView(app);
            textView.setText("日志");
            textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.0f);
            textView.setTextColor(0xFFFFFFFF);
            textView.setGravity(Gravity.CENTER);
            layout.addView(textView);
            layout.setOnTouchListener(new Touch());
            WindowManager.LayoutParams params = new WindowManager.LayoutParams();
            params.type = WindowManager.LayoutParams.TYPE_APPLICATION_PANEL;
            params.format = PixelFormat.TRANSLUCENT;
            params.flags = 520;
            params.width = size;
            params.height = size;
            params.gravity = Gravity.TOP | Gravity.LEFT;
            int x;
            int y;
            if (bubblePosSaved) {
                x = bubbleX;
                y = bubbleY;
            } else {
                DisplayMetrics metrics = app.getResources().getDisplayMetrics();
                x = metrics.widthPixels - dp(56.0f) - dp(16.0f);
                y = metrics.heightPixels / 2 + dp(100.0f) - dp(28.0f);
            }
            params.x = x;
            params.y = y;
            params.token = activity.getWindow().getDecorView().getWindowToken();
            ((WindowManager) app.getSystemService(Context.WINDOW_SERVICE)).addView(layout, params);
            bubble = layout;
            bubbleParams = params;
            bubbleHost = activity;
            bubbleShowing = true;
        } catch (Throwable ignored) {
        }
    }

    static void showPanelOn(Activity activity) {
        try {
            App app = App.get();
            if (app == null) return;
            DisplayMetrics metrics = app.getResources().getDisplayMetrics();
            int width = (int) (metrics.widthPixels * 0.85f);
            int height = (int) (metrics.heightPixels * 0.55f);
            LinearLayout layout = new LinearLayout(app);
            layout.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable background = new GradientDrawable();
            background.setCornerRadius(dp(10.0f));
            background.setColor(0xFFFFFFFF);
            layout.setBackground(background);
            layout.setElevation(dp(8.0f));
            LinearLayout titleBar = new LinearLayout(app);
            titleBar.setOrientation(LinearLayout.HORIZONTAL);
            titleBar.setGravity(Gravity.CENTER_VERTICAL);
            titleBar.setBackgroundColor(COLOR_PRIMARY);
            int titleH = dp(12.0f);
            int titleV = dp(8.0f);
            titleBar.setPadding(titleH, titleV, titleH, titleV);
            TextView title = new TextView(app);
            title.setText("运行日志");
            titleView = title;
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.0f);
            title.setTextColor(0xFFFFFFFF);
            titleBar.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
            titleBar.addView(titleButton(app, "—", 0));
            titleBar.addView(titleButton(app, "✕", 2));
            titleBar.setOnTouchListener(new PanelTouch());
            layout.addView(titleBar);
            ScrollView sv = new ScrollView(app);
            sv.setFillViewport(true);
            sv.setOnTouchListener(new ScrollTouch());
            TextView textView = new TextView(app);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.0f);
            textView.setTypeface(Typeface.MONOSPACE);
            int textPad = dp(8.0f);
            textView.setPadding(textPad, textPad, textPad, textPad);
            textView.setTextIsSelectable(true);
            sv.addView(textView);
            layout.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));
            logView = textView;
            scrollView = sv;
            TextView tip = new TextView(app);
            tip.setText("↓ 有新日志，点击查看最新");
            tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.0f);
            tip.setTextColor(0xFFFFFFFF);
            tip.setGravity(Gravity.CENTER);
            tip.setBackgroundColor(COLOR_PRIMARY);
            int tipPad = dp(8.0f);
            tip.setPadding(tipPad, tipPad, tipPad, tipPad);
            tip.setVisibility(View.GONE);
            tip.setOnClickListener(new Btn(6));
            newLogTip = tip;
            layout.addView(tip);
            LinearLayout buttonBar = new LinearLayout(app);
            buttonBar.setOrientation(LinearLayout.HORIZONTAL);
            buttonBar.setGravity(Gravity.CENTER);
            int barPad = dp(8.0f);
            buttonBar.setPadding(barPad, barPad, barPad, barPad);
            TextView pause = bottomButton(app, "暂停", 5);
            pauseBtn = pause;
            buttonBar.addView(pause, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
            userPaused = false;
            buttonBar.addView(bottomButton(app, "刷新", 1), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
            buttonBar.addView(bottomButton(app, "复制", 3), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
            buttonBar.addView(bottomButton(app, "清空", 4), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
            TextView anchor = bottomButton(app, "只看新增", 7);
            anchorBtn = anchor;
            buttonBar.addView(anchor, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
            layout.addView(buttonBar);
            WindowManager.LayoutParams params = new WindowManager.LayoutParams();
            params.type = WindowManager.LayoutParams.TYPE_APPLICATION_PANEL;
            params.format = PixelFormat.TRANSLUCENT;
            params.flags = 520;
            params.width = width;
            params.height = height;
            params.gravity = Gravity.TOP | Gravity.LEFT;
            if (panelPosSaved) {
                params.x = panelX;
                params.y = panelY;
            } else {
                params.x = (metrics.widthPixels - width) / 2;
                params.y = (metrics.heightPixels - height) / 2;
            }
            params.token = activity.getWindow().getDecorView().getWindowToken();
            ((WindowManager) app.getSystemService(Context.WINDOW_SERVICE)).addView(layout, params);
            panel = layout;
            panelParams = params;
            panelHost = activity;
            panelShowing = true;
            int size = DebugLogStore.snapshot().size();
            watchLastSize = size;
            watchSettled = size;
            watchPending = -1;
            watchStable = 0;
            doRefresh();
            startAuto();
        } catch (Throwable ignored) {
        }
    }

    private static void startAuto() {
        try {
            stopAuto();
            Auto auto = new Auto();
            refresher = auto;
            mainHandler().postDelayed(auto, 1000L);
        } catch (Throwable ignored) {
        }
    }

    private static void stopAuto() {
        try {
            Runnable runnable = refresher;
            if (runnable != null) {
                mainHandler().removeCallbacks(runnable);
                refresher = null;
            }
        } catch (Throwable ignored) {
        }
    }

    private static TextView titleButton(Context context, String text, int code) {
        TextView textView = new TextView(context);
        textView.setText(text);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.0f);
        textView.setTextColor(0xFFFFFFFF);
        textView.setGravity(Gravity.CENTER);
        int horizontal = dp(8.0f);
        int vertical = dp(6.0f);
        textView.setPadding(horizontal, vertical, horizontal, vertical);
        textView.setOnClickListener(new Btn(code));
        return textView;
    }

    static void toggleAnchor() {
        try {
            TextView btn = anchorBtn;
            if (anchorIndex < 0) {
                anchorIndex = DebugLogStore.snapshot().size();
                if (btn != null) btn.setText("看全部");
            } else {
                anchorIndex = -1;
                if (btn != null) btn.setText("只看新增");
            }
            doRefresh();
        } catch (Throwable ignored) {
        }
    }

    static void togglePause() {
        try {
            if (userPaused) {
                userPaused = false;
                TextView btn = pauseBtn;
                if (btn != null) btn.setText("暂停");
                TextView title = titleView;
                if (title != null) title.setText("运行日志");
            } else {
                userPaused = true;
                TextView btn = pauseBtn;
                if (btn != null) btn.setText("继续");
                TextView title = titleView;
                if (title != null) title.setText("运行日志（已暂停）");
            }
            doRefresh();
        } catch (Throwable ignored) {
        }
    }
}
