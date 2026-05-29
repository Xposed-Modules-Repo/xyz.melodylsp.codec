package xyz.melodylsp.codec.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import xyz.melodylsp.codec.BuildConfig;
import xyz.melodylsp.codec.R;
import xyz.melodylsp.codec.diag.DiagnosticEvents;
import xyz.melodylsp.codec.diag.FeedbackCollector;

public final class MasterSwitchActivity extends Activity {

    private static final String PREFS_NAME = "module_prefs";
    private static final String KEY_ENABLED = "enabled";

    private SharedPreferences modulePrefs;
    private SharedPreferences diagPrefs;
    private LinearLayout statusList;
    private LinearLayout packageList;
    private TextView recentEvents;
    private TextView enabledStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        modulePrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        diagPrefs = getSharedPreferences(DiagnosticEvents.PREFS, Context.MODE_PRIVATE);

        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(Color.rgb(245, 248, 251));
            getWindow().setNavigationBarColor(Color.rgb(245, 248, 251));
        }
        setContentView(buildContent());
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(245, 248, 251));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(22), dp(20), dp(28));
        scroll.addView(root, matchWrap());

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(header, matchWrap());

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.mipmap.ic_launcher);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(58), dp(58));
        header.addView(icon, iconLp);

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleLp.leftMargin = dp(14);
        header.addView(titleBox, titleLp);

        TextView title = text("耳机音质助手", 24, Color.rgb(20, 28, 38), true);
        titleBox.addView(title, matchWrap());
        TextView subtitle = text("音质控制、LE Audio 和 Hook 诊断都在这里", 14,
                Color.rgb(90, 103, 118), false);
        subtitle.setPadding(0, dp(4), 0, 0);
        titleBox.addView(subtitle, matchWrap());

        root.addView(switchCard(), topMargin(matchWrap(), dp(22)));
        packageList = section(root, "环境信息");
        statusList = section(root, "Hook 状态");
        root.addView(actionCard(), topMargin(matchWrap(), dp(14)));

        LinearLayout eventCard = card();
        TextView eventTitle = text("最近事件", 17, Color.rgb(24, 33, 45), true);
        eventCard.addView(eventTitle, matchWrap());
        recentEvents = text("", 12, Color.rgb(87, 99, 113), false);
        recentEvents.setTypeface(Typeface.MONOSPACE);
        recentEvents.setPadding(0, dp(10), 0, 0);
        eventCard.addView(recentEvents, matchWrap());
        root.addView(eventCard, topMargin(matchWrap(), dp(14)));

        return scroll;
    }

    private View switchCard() {
        LinearLayout card = card();
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setOrientation(LinearLayout.HORIZONTAL);
        card.addView(row, matchWrap());

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        row.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        texts.addView(text("模块总开关", 18, Color.rgb(24, 33, 45), true), matchWrap());
        enabledStatus = text("", 13, Color.rgb(98, 110, 125), false);
        enabledStatus.setPadding(0, dp(6), 0, 0);
        texts.addView(enabledStatus, matchWrap());

        Switch sw = new Switch(this);
        sw.setChecked(modulePrefs.getBoolean(KEY_ENABLED, true));
        sw.setOnCheckedChangeListener(this::onModuleSwitchChanged);
        row.addView(sw, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return card;
    }

    private View actionCard() {
        LinearLayout card = card();
        TextView title = text("反馈工具", 17, Color.rgb(24, 33, 45), true);
        card.addView(title, matchWrap());
        TextView desc = text("一键打包模块状态、包版本、诊断事件和 MelodyCodecLsp 日志。", 13,
                Color.rgb(98, 110, 125), false);
        desc.setPadding(0, dp(6), 0, dp(14));
        card.addView(desc, matchWrap());

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        card.addView(row, matchWrap());

        Button refresh = button("刷新状态", false);
        refresh.setOnClickListener(v -> refresh());
        row.addView(refresh, new LinearLayout.LayoutParams(0, dp(46), 1f));

        Button collect = button("一键收集反馈包", true);
        collect.setOnClickListener(v -> collectFeedback(collect));
        LinearLayout.LayoutParams collectLp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        collectLp.leftMargin = dp(10);
        row.addView(collect, collectLp);
        return card;
    }

    private LinearLayout section(LinearLayout root, String title) {
        LinearLayout box = card();
        TextView tv = text(title, 17, Color.rgb(24, 33, 45), true);
        box.addView(tv, matchWrap());
        root.addView(box, topMargin(matchWrap(), dp(14)));
        return box;
    }

    private void refresh() {
        boolean enabled = modulePrefs.getBoolean(KEY_ENABLED, true);
        enabledStatus.setText(enabled
                ? "已启用。重启 Melody 后会注入音质控制项。"
                : "已禁用。重启 Melody 后宿主页面恢复原状。");

        clearDynamicRows(packageList);
        addInfoRow(packageList, "模块版本",
                BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")");
        addInfoRow(packageList, "系统",
                Build.MANUFACTURER + " " + Build.MODEL + " / Android " + Build.VERSION.RELEASE);
        addInfoRow(packageList, "Melody", packageVersion("com.oplus.melody"));
        addInfoRow(packageList, "Bluetooth", packageVersion("com.android.bluetooth"));
        addInfoRow(packageList, "WirelessSettings", packageVersion("com.oplus.wirelesssettings"));

        clearDynamicRows(statusList);
        addStatusRow("Melody 作用域", "scope.host");
        addStatusRow("Host 控制器", "host.controller");
        addStatusRow("页面 Hook", "hook.host");
        addStatusRow("DetailMain 注入", "inject.detail");
        addStatusRow("OneSpace 注入", "inject.onespace");
        addStatusRow("蓝牙作用域", "scope.bluetooth");
        addStatusRow("A2DP Bridge", "bridge.codec");
        addStatusRow("LE Audio 蓝牙桥", "bridge.le.bt");
        addStatusRow("无线设置作用域", "scope.wirelesssettings");
        addStatusRow("LE Audio 无线设置桥", "bridge.le.ws");
        addStatusRow("最近警告", "last.warning");
        addStatusRow("最近错误", "last.error");

        String events = diagPrefs.getString(DiagnosticEvents.KEY_EVENTS, "");
        recentEvents.setText(tailLines(events, 10));
    }

    private void onModuleSwitchChanged(CompoundButton buttonView, boolean isChecked) {
        modulePrefs.edit().putBoolean(KEY_ENABLED, isChecked).apply();
        refresh();
    }

    private static void clearDynamicRows(LinearLayout parent) {
        int count = parent.getChildCount();
        if (count > 1) {
            parent.removeViews(1, count - 1);
        }
    }

    private void collectFeedback(Button button) {
        button.setEnabled(false);
        button.setText("正在打包...");
        new Thread(() -> {
            String path;
            Throwable error = null;
            try {
                path = FeedbackCollector.collect(this);
            } catch (Throwable t) {
                path = null;
                error = t;
            }
            String finalPath = path;
            Throwable finalError = error;
            runOnUiThread(() -> {
                button.setEnabled(true);
                button.setText("一键收集反馈包");
                if (finalError == null) {
                    Toast.makeText(this, "反馈包已保存：" + finalPath, Toast.LENGTH_LONG).show();
                    refresh();
                } else {
                    Toast.makeText(this, "打包失败：" + finalError.getMessage(),
                            Toast.LENGTH_LONG).show();
                }
            });
        }, "HeadsetAudioHelper-feedback").start();
    }

    private void addStatusRow(String label, String key) {
        String status = DiagnosticEvents.status(diagPrefs, key);
        String time = DiagnosticEvents.formatTime(DiagnosticEvents.time(diagPrefs, key));
        String detail = DiagnosticEvents.detail(diagPrefs, key);
        addInfoRow(statusList, label, status + "  " + time
                + (detail == null || detail.isEmpty() ? "" : "\n" + detail));
    }

    private void addInfoRow(LinearLayout parent, String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(12), 0, 0);
        parent.addView(row, matchWrap());
        row.addView(text(label, 13, Color.rgb(102, 114, 129), false), matchWrap());
        TextView v = text(value, 14, Color.rgb(25, 34, 46), false);
        v.setPadding(0, dp(4), 0, 0);
        row.addView(v, matchWrap());
    }

    private String packageVersion(String pkg) {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(pkg, 0);
            long code = Build.VERSION.SDK_INT >= 28
                    ? info.getLongVersionCode() : info.versionCode;
            return String.valueOf(info.versionName) + " (" + code + ")";
        } catch (Throwable t) {
            return "未安装 / 不可读取";
        }
    }

    private static String tailLines(String text, int maxLines) {
        if (text == null || text.trim().isEmpty()) return "还没有收到 Hook 事件。";
        String[] lines = text.split("\\n");
        int start = Math.max(0, lines.length - maxLines);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            sb.append(lines[i]).append('\n');
        }
        return sb.toString().trim();
    }

    private LinearLayout card() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(18));
        bg.setStroke(1, Color.rgb(229, 235, 242));
        box.setBackground(bg);
        return box;
    }

    private Button button(String label, boolean primary) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(label);
        b.setTextSize(14);
        b.setTextColor(primary ? Color.WHITE : Color.rgb(0, 105, 255));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(primary ? Color.rgb(0, 105, 255) : Color.rgb(235, 244, 255));
        b.setBackground(bg);
        return b;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        tv.setLineSpacing(dp(2), 1.0f);
        if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static ViewGroup.LayoutParams matchWrap() {
        return new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static ViewGroup.MarginLayoutParams topMargin(
            ViewGroup.LayoutParams base, int top) {
        ViewGroup.MarginLayoutParams lp = new ViewGroup.MarginLayoutParams(base);
        lp.topMargin = top;
        return lp;
    }
}
