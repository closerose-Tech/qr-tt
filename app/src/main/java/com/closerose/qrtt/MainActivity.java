package com.closerose.qrtt;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.TranslateAnimation;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 二维码模拟测试工具
 *
 * 生成以 HTTPS://Y2WM.CN/99.1000.1/AC2007999003QG4D59CTWOESG990C0001 为模板的二维码，
 * 每次将末尾 N 位（默认 24 位）替换为不重复的随机字母数字串。
 * 支持纠错级别、尺寸、颜色、环境色、方向、滑入动画、时序、次数/时长限制、多码同屏与常亮。
 */
public class MainActivity extends Activity {

    private static final String DEFAULT_TEMPLATE =
            "HTTPS://Y2WM.CN/99.1000.1/AC2007999003QG4D59CTWOESG990C0001";
    private static final String ALPHANUM = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String PREFS = "qrtt_config";

    private enum State { IDLE, RUNNING, PAUSED, STOPPED }

    private static class Config {
        String template = DEFAULT_TEMPLATE;
        int randomLen = 24;
        ErrorCorrectionLevel ecl = ErrorCorrectionLevel.M;
        int qrSizeDp = 220;
        int qrColor = Color.rgb(16, 16, 16);
        int qrBgColor = Color.rgb(255, 255, 255);
        int envColor = Color.rgb(232, 238, 246);
        boolean envRandom = false;
        int rotation = 0;
        int slide = 0;              // 0 静止, 1 左->右, 2 右->左
        int animMs = 400;
        long holdMs = 500;
        long gapMs = 200;
        int maxCount = 100;
        long limitMs = 0;           // 0 表示不限时
        int qrCount = 1;
        boolean sameData = false;
        boolean keepAwake = true;
    }

    private final Config cfg = new Config();
    private final Random random = new Random();
    private final Set<String> usedSuffix = new HashSet<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private State state = State.IDLE;
    private int generated = 0;
    private long accumulatedMs = 0;
    private long activeStart = 0;
    private String phase = "";
    private long phaseRemainMs = 0;
    private long phaseEndAt = 0;
    private int currentQrCount = 1;

    private PowerManager.WakeLock wakeLock;

    // UI
    private LinearLayout stage;
    private TextView tvStatus;
    private TextView tvCurrent;
    private Button btnStart, btnPause, btnStop, btnReset;
    private EditText etTemplate, etRandomLen, etQrSize, etHold, etGap, etMaxCount,
            etLimitMinutes, etAnimMs, etQrColor, etQrBgColor, etEnvColor;
    private Spinner spEcl, spRotation, spSlide, spHoldUnit, spGapUnit, spDataMode, spCount,
            spEnvMode, spKeepAwake;
    private Button btnQrColor, btnQrBgColor, btnEnvColor;
    private final List<FrameLayout> cells = new ArrayList<>();
    private final List<ImageView> images = new ArrayList<>();

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (state != State.RUNNING) return;
            if ("hold".equals(phase)) {
                clearImages();
                enterPhase("gap", cfg.gapMs);
            } else {
                nextRound();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "qrtt:keep_awake");
        }
        setContentView(buildUi());
        loadConfig();
        applyConfigToUi();
        buildCells(cfg.qrCount);
        updateStatus("已就绪，点击「启动」开始生成");
    }

    @Override
    protected void onPause() {
        saveConfig();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyWakeSetting();
    }

    /** 按设置开启/关闭常亮，运行中才持有 WakeLock。 */
    private void applyWakeSetting() {
        if (cfg.keepAwake) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (wakeLock == null) return;
        boolean need = cfg.keepAwake && state == State.RUNNING;
        if (need && !wakeLock.isHeld()) wakeLock.acquire();
        if (!need && wakeLock.isHeld()) wakeLock.release();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(tick);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    // ---------------- UI 构建 ----------------

    private View buildUi() {
        LinearLayout root = linear(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(244, 246, 250));
        int pad = dp(12);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("二维码模拟测试工具");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTextColor(Color.rgb(32, 33, 36));
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(MATCH, WRAP));

        stage = linear(LinearLayout.VERTICAL);
        stage.setBackgroundColor(cfg.envColor);
        stage.setGravity(Gravity.CENTER);
        stage.setPadding(dp(8), dp(8), dp(8), dp(8));
        LinearLayout.LayoutParams stageLp = new LinearLayout.LayoutParams(MATCH, 0, 1f);
        stageLp.setMargins(0, dp(10), 0, dp(8));
        root.addView(stage, stageLp);

        tvCurrent = new TextView(this);
        tvCurrent.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tvCurrent.setTextColor(Color.rgb(90, 96, 106));
        tvCurrent.setGravity(Gravity.CENTER);
        tvCurrent.setSingleLine(true);
        tvCurrent.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        root.addView(tvCurrent, new LinearLayout.LayoutParams(MATCH, WRAP));

        tvStatus = new TextView(this);
        tvStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvStatus.setTextColor(Color.rgb(41, 98, 255));
        tvStatus.setGravity(Gravity.CENTER);
        root.addView(tvStatus, new LinearLayout.LayoutParams(MATCH, WRAP));

        LinearLayout btns = linear(LinearLayout.HORIZONTAL);
        btnStart = button("启动");
        btnPause = button("暂停");
        btnStop = button("停止");
        btnReset = button("复位");
        btnStart.setOnClickListener(v -> onStartClick());
        btnPause.setOnClickListener(v -> onPauseClick());
        btnStop.setOnClickListener(v -> onStopClick());
        btnReset.setOnClickListener(v -> onResetClick());
        for (Button b : new Button[]{btnStart, btnPause, btnStop, btnReset}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, WRAP, 1f);
            lp.setMargins(dp(3), dp(8), dp(3), 0);
            btns.addView(b, lp);
        }
        root.addView(btns, new LinearLayout.LayoutParams(MATCH, WRAP));

        ScrollView scroller = new ScrollView(this);
        LinearLayout form = linear(LinearLayout.VERTICAL);
        form.setPadding(0, dp(8), 0, dp(16));
        scroller.addView(form, new ScrollView.LayoutParams(MATCH, WRAP));
        root.addView(scroller, new LinearLayout.LayoutParams(MATCH, 0, 1.25f));

        section(form, "数据规则");
        etTemplate = inputRow(form, "基础数据", DEFAULT_TEMPLATE, InputType.TYPE_CLASS_TEXT);
        etTemplate.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        etRandomLen = inputRow(form, "随机尾码位数", "24", InputType.TYPE_CLASS_NUMBER);
        spCount = spinnerRow(form, "二维码数量", new String[]{"1", "2", "3", "4", "5", "6"});
        spDataMode = spinnerRow(form, "多码数据", new String[]{"各码不同", "全部相同"});

        section(form, "二维码外观");
        spEcl = spinnerRow(form, "纠错级别", new String[]{"L (低)", "M (中)", "Q (较高)", "H (最高)"});
        etQrSize = inputRow(form, "显示尺寸(dp)", "220", InputType.TYPE_CLASS_NUMBER);
        btnQrColor = colorRow(form, "本体颜色", cfg.qrColor);
        btnQrBgColor = colorRow(form, "背景颜色", cfg.qrBgColor);
        btnEnvColor = colorRow(form, "环境颜色", cfg.envColor);
        spEnvMode = spinnerRow(form, "环境色变化", new String[]{"固定颜色", "随数据随机"});
        spRotation = spinnerRow(form, "二维码方向", new String[]{"0°", "90°", "180°", "270°"});
        spSlide = spinnerRow(form, "出现方式", new String[]{"静止", "从左向右", "从右向左"});
        etAnimMs = inputRow(form, "滑入时长(ms)", "400", InputType.TYPE_CLASS_NUMBER);

        section(form, "变化时序与停止条件");
        etHold = inputRow(form, "数据保持时间", "500", InputType.TYPE_CLASS_NUMBER);
        spHoldUnit = unitSpinner(form);
        etGap = inputRow(form, "变化间隔时间", "200", InputType.TYPE_CLASS_NUMBER);
        spGapUnit = unitSpinner(form);
        etMaxCount = inputRow(form, "变化次数(0=不限)", "100", InputType.TYPE_CLASS_NUMBER);
        etLimitMinutes = inputRow(form, "限时(分钟,0=不限)", "0",
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        spKeepAwake = spinnerRow(form, "屏幕常亮", new String[]{"开启", "关闭"});

        TextView hint = new TextView(this);
        hint.setText("说明：保持时间 = 二维码展示时长；变化间隔 = 两张码之间的空白时长；两者均可在 1ms ~ 10s 内设置。");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        hint.setTextColor(Color.rgb(110, 116, 126));
        hint.setPadding(0, dp(10), 0, 0);
        form.addView(hint);

        return root;
    }

    private Spinner unitSpinner(LinearLayout form) {
        LinearLayout row = linear(LinearLayout.HORIZONTAL);
        TextView label = new TextView(this);
        label.setText("  单位");
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        row.addView(label, new LinearLayout.LayoutParams(dp(150), WRAP));
        Spinner sp = new Spinner(this);
        sp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"毫秒", "秒"}));
        row.addView(sp, new LinearLayout.LayoutParams(0, WRAP, 1f));
        form.addView(row);
        return sp;
    }

    private void section(LinearLayout form, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setTextColor(Color.rgb(41, 98, 255));
        tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        tv.setPadding(0, dp(12), 0, dp(4));
        form.addView(tv, new LinearLayout.LayoutParams(MATCH, WRAP));
    }

    private EditText inputRow(LinearLayout form, String label, String value, int inputType) {
        LinearLayout row = linear(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTextColor(Color.rgb(60, 64, 72));
        row.addView(tv, new LinearLayout.LayoutParams(dp(150), WRAP));
        final EditText et = new EditText(this);
        et.setText(value);
        et.setInputType(inputType);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        et.setSingleLine(true);
        et.setPadding(dp(8), dp(6), dp(8), dp(6));
        row.addView(et, new LinearLayout.LayoutParams(0, WRAP, 1f));
        form.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
        return et;
    }

    private Spinner spinnerRow(LinearLayout form, String label, String[] items) {
        LinearLayout row = linear(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTextColor(Color.rgb(60, 64, 72));
        row.addView(tv, new LinearLayout.LayoutParams(dp(150), WRAP));
        Spinner sp = new Spinner(this);
        sp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, items));
        row.addView(sp, new LinearLayout.LayoutParams(0, WRAP, 1f));
        form.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
        return sp;
    }

    private Button colorRow(LinearLayout form, String label, int color) {
        LinearLayout row = linear(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTextColor(Color.rgb(60, 64, 72));
        row.addView(tv, new LinearLayout.LayoutParams(dp(150), WRAP));
        Button b = new Button(this);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        b.setAllCaps(false);
        row.addView(b, new LinearLayout.LayoutParams(0, WRAP, 1f));
        form.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
        setColorButton(b, color);
        return b;
    }

    private void setColorButton(Button b, int color) {
        b.setText(hexColor(color));
        b.setBackgroundColor(color);
        b.setTextColor(isDark(color) ? Color.WHITE : Color.BLACK);
    }

    private boolean isDark(int c) {
        return 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c) < 150;
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setAllCaps(false);
        return b;
    }

    private LinearLayout linear(int orientation) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(orientation);
        return l;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    // ---------------- 颜色选择 ----------------

    private void pickColor(final int which) {
        final Dialog d = new Dialog(this);
        d.setTitle(which == 0 ? "二维码本体颜色" : which == 1 ? "二维码背景颜色" : "环境颜色");
        LinearLayout root = linear(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        final View preview = new View(this);
        preview.setLayoutParams(new LinearLayout.LayoutParams(MATCH, dp(48)));
        int initial = which == 0 ? cfg.qrColor : which == 1 ? cfg.qrBgColor : cfg.envColor;
        preview.setBackgroundColor(initial);
        root.addView(preview);

        final EditText hex = new EditText(this);
        hex.setText(hexColor(initial));
        hex.setSingleLine(true);
        hex.setHint("#RRGGBB");
        root.addView(hex, new LinearLayout.LayoutParams(MATCH, WRAP));
        hex.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(android.text.Editable s) {
                try {
                    preview.setBackgroundColor(Color.parseColor(s.toString().trim()));
                } catch (Exception ignored) { }
            }
        });

        int[] presets = {
                Color.rgb(0, 0, 0), Color.rgb(255, 255, 255), Color.rgb(41, 98, 255),
                Color.rgb(0, 150, 136), Color.rgb(244, 67, 54), Color.rgb(156, 39, 176),
                Color.rgb(255, 152, 0), Color.rgb(76, 175, 80), Color.rgb(33, 33, 33),
                Color.rgb(232, 238, 246), Color.rgb(255, 235, 59), Color.rgb(3, 169, 244)
        };
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(4);
        for (final int c : presets) {
            Button swatch = new Button(this);
            swatch.setBackgroundColor(c);
            swatch.setLayoutParams(new GridLayout.LayoutParams());
            swatch.setOnClickListener(v -> hex.setText(hexColor(c)));
            grid.addView(swatch, dp(56), dp(40));
        }
        root.addView(grid, new LinearLayout.LayoutParams(MATCH, WRAP));

        LinearLayout actions = linear(LinearLayout.HORIZONTAL);
        Button cancel = button("取消");
        Button ok = button("确定");
        cancel.setOnClickListener(v -> d.dismiss());
        ok.setOnClickListener(v -> {
            int c;
            try {
                c = Color.parseColor(hex.getText().toString().trim());
            } catch (Exception e) {
                Toast.makeText(MainActivity.this, "颜色格式应为 #RRGGBB", Toast.LENGTH_SHORT).show();
                return;
            }
            if (which == 0) { cfg.qrColor = c; setColorButton(btnQrColor, c); }
            else if (which == 1) { cfg.qrBgColor = c; setColorButton(btnQrBgColor, c); }
            else { cfg.envColor = c; setColorButton(btnEnvColor, c); stage.setBackgroundColor(c); }
            d.dismiss();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, WRAP, 1f);
        actions.addView(cancel, lp);
        actions.addView(ok, new LinearLayout.LayoutParams(0, WRAP, 1f));
        root.addView(actions, new LinearLayout.LayoutParams(MATCH, WRAP));

        d.setContentView(root);
        d.show();
    }

    private static String hexColor(int c) {
        return String.format(Locale.US, "#%06X", 0xFFFFFF & c);
    }

    // ---------------- 配置读写 ----------------

    private void applyConfigToUi() {
        etTemplate.setText(cfg.template);
        etRandomLen.setText(String.valueOf(cfg.randomLen));
        etQrSize.setText(String.valueOf(cfg.qrSizeDp));
        etHold.setText(String.valueOf(cfg.holdMs >= 1000 && cfg.holdMs % 1000 == 0 ? cfg.holdMs / 1000 : cfg.holdMs));
        spHoldUnit.setSelection(cfg.holdMs >= 1000 && cfg.holdMs % 1000 == 0 ? 1 : 0);
        etGap.setText(String.valueOf(cfg.gapMs >= 1000 && cfg.gapMs % 1000 == 0 ? cfg.gapMs / 1000 : cfg.gapMs));
        spGapUnit.setSelection(cfg.gapMs >= 1000 && cfg.gapMs % 1000 == 0 ? 1 : 0);
        etMaxCount.setText(String.valueOf(cfg.maxCount));
        etLimitMinutes.setText(String.valueOf(cfg.limitMs / 60000.0));
        etAnimMs.setText(String.valueOf(cfg.animMs));
        spEcl.setSelection(eclIndex(cfg.ecl));
        spRotation.setSelection(cfg.rotation / 90);
        spSlide.setSelection(cfg.slide);
        spCount.setSelection(cfg.qrCount - 1);
        spDataMode.setSelection(cfg.sameData ? 1 : 0);
        spEnvMode.setSelection(cfg.envRandom ? 1 : 0);
        spKeepAwake.setSelection(cfg.keepAwake ? 0 : 1);
        setColorButton(btnQrColor, cfg.qrColor);
        setColorButton(btnQrBgColor, cfg.qrBgColor);
        setColorButton(btnEnvColor, cfg.envColor);
        btnQrColor.setOnClickListener(v -> pickColor(0));
        btnQrBgColor.setOnClickListener(v -> pickColor(1));
        btnEnvColor.setOnClickListener(v -> pickColor(2));
        stage.setBackgroundColor(cfg.envColor);
        applyWakeSetting();
    }

    private static int eclIndex(ErrorCorrectionLevel ecl) {
        switch (ecl) {
            case L: return 0;
            case Q: return 2;
            case H: return 3;
            default: return 1;
        }
    }

    private void readConfig() {
        cfg.template = text(etTemplate, DEFAULT_TEMPLATE).trim();
        if (cfg.template.isEmpty()) cfg.template = DEFAULT_TEMPLATE;
        cfg.randomLen = clamp(intOf(etRandomLen, 24), 1, 64);
        cfg.qrSizeDp = clamp(intOf(etQrSize, 220), 80, 600);
        cfg.holdMs = timeMs(etHold, spHoldUnit, 500);
        cfg.gapMs = timeMs(etGap, spGapUnit, 200);
        cfg.maxCount = clamp(intOf(etMaxCount, 100), 0, 1000000);
        double minutes = doubleOf(etLimitMinutes, 0);
        cfg.limitMs = minutes <= 0 ? 0 : (long) Math.min(minutes * 60000, 24L * 3600 * 1000);
        cfg.animMs = clamp(intOf(etAnimMs, 400), 0, 5000);
        cfg.ecl = spEcl.getSelectedItemPosition() == 0 ? ErrorCorrectionLevel.L
                : spEcl.getSelectedItemPosition() == 2 ? ErrorCorrectionLevel.Q
                : spEcl.getSelectedItemPosition() == 3 ? ErrorCorrectionLevel.H
                : ErrorCorrectionLevel.M;
        cfg.rotation = spRotation.getSelectedItemPosition() * 90;
        cfg.slide = spSlide.getSelectedItemPosition();
        cfg.qrCount = clamp(spCount.getSelectedItemPosition() + 1, 1, 6);
        cfg.sameData = spDataMode.getSelectedItemPosition() == 1;
        cfg.envRandom = spEnvMode.getSelectedItemPosition() == 1;
        cfg.keepAwake = spKeepAwake.getSelectedItemPosition() == 0;
    }

    private long timeMs(EditText et, Spinner unit, long def) {
        long v = longOf(et, def);
        if (unit.getSelectedItemPosition() == 1) v *= 1000L;
        return Math.max(1, Math.min(v, 10000L));
    }

    private String text(EditText et, String def) {
        String s = et.getText().toString();
        return s.isEmpty() ? def : s;
    }

    private int intOf(EditText et, int def) {
        try { return Integer.parseInt(et.getText().toString().trim()); }
        catch (Exception e) { return def; }
    }

    private long longOf(EditText et, long def) {
        try { return Long.parseLong(et.getText().toString().trim()); }
        catch (Exception e) { return def; }
    }

    private double doubleOf(EditText et, double def) {
        try { return Double.parseDouble(et.getText().toString().trim()); }
        catch (Exception e) { return def; }
    }

    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(v, max)); }

    private void saveConfig() {
        readConfig();
        android.content.SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        android.content.SharedPreferences.Editor e = sp.edit();
        e.putString("template", cfg.template);
        e.putInt("randomLen", cfg.randomLen);
        e.putInt("qrSize", cfg.qrSizeDp);
        e.putLong("hold", cfg.holdMs);
        e.putLong("gap", cfg.gapMs);
        e.putInt("maxCount", cfg.maxCount);
        e.putLong("limit", cfg.limitMs);
        e.putInt("anim", cfg.animMs);
        e.putInt("ecl", eclIndex(cfg.ecl));
        e.putInt("rotation", cfg.rotation);
        e.putInt("slide", cfg.slide);
        e.putInt("count", cfg.qrCount);
        e.putBoolean("sameData", cfg.sameData);
        e.putBoolean("envRandom", cfg.envRandom);
        e.putBoolean("keepAwake", cfg.keepAwake);
        e.putInt("qrColor", cfg.qrColor);
        e.putInt("qrBg", cfg.qrBgColor);
        e.putInt("envColor", cfg.envColor);
        e.apply();
    }

    private void loadConfig() {
        android.content.SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        cfg.template = sp.getString("template", cfg.template);
        cfg.randomLen = sp.getInt("randomLen", cfg.randomLen);
        cfg.qrSizeDp = sp.getInt("qrSize", cfg.qrSizeDp);
        cfg.holdMs = sp.getLong("hold", cfg.holdMs);
        cfg.gapMs = sp.getLong("gap", cfg.gapMs);
        cfg.maxCount = sp.getInt("maxCount", cfg.maxCount);
        cfg.limitMs = sp.getLong("limit", cfg.limitMs);
        cfg.animMs = sp.getInt("anim", cfg.animMs);
        int e = sp.getInt("ecl", 1);
        cfg.ecl = e == 0 ? ErrorCorrectionLevel.L : e == 2 ? ErrorCorrectionLevel.Q
                : e == 3 ? ErrorCorrectionLevel.H : ErrorCorrectionLevel.M;
        cfg.rotation = sp.getInt("rotation", 0);
        cfg.slide = sp.getInt("slide", 0);
        cfg.qrCount = sp.getInt("count", 1);
        cfg.sameData = sp.getBoolean("sameData", false);
        cfg.envRandom = sp.getBoolean("envRandom", false);
        cfg.keepAwake = sp.getBoolean("keepAwake", true);
        cfg.qrColor = sp.getInt("qrColor", cfg.qrColor);
        cfg.qrBgColor = sp.getInt("qrBg", cfg.qrBgColor);
        cfg.envColor = sp.getInt("envColor", cfg.envColor);
    }

    // ---------------- 二维码生成 ----------------

    private String randomSuffix(int len) {
        while (true) {
            StringBuilder sb = new StringBuilder(len);
            for (int i = 0; i < len; i++) sb.append(ALPHANUM.charAt(random.nextInt(ALPHANUM.length())));
            String s = sb.toString();
            if (usedSuffix.add(s)) return s;   // 保证每次生成都不一样
        }
    }

    private String nextData() {
        String tpl = cfg.template;
        int prefixLen = Math.max(0, tpl.length() - cfg.randomLen);
        return tpl.substring(0, prefixLen) + randomSuffix(cfg.randomLen);
    }

    private Bitmap qrBitmap(String data, int sizePx) {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.ERROR_CORRECTION, cfg.ecl);
        hints.put(EncodeHintType.MARGIN, 1);
        try {
            BitMatrix matrix = new QRCodeWriter().encode(data, BarcodeFormat.QR_CODE,
                    sizePx, sizePx, hints);
            Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
            int dark = cfg.qrColor, light = cfg.qrBgColor;
            int[] pixels = new int[sizePx * sizePx];
            for (int y = 0; y < sizePx; y++) {
                int row = y * sizePx;
                for (int x = 0; x < sizePx; x++) {
                    pixels[row + x] = matrix.get(x, y) ? dark : light;
                }
            }
            bmp.setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx);
            if (cfg.rotation != 0) {
                Matrix m = new Matrix();
                m.setRotate(cfg.rotation, sizePx / 2f, sizePx / 2f);
                bmp = Bitmap.createBitmap(bmp, 0, 0, sizePx, sizePx, m, true);
            }
            return bmp;
        } catch (WriterException e) {
            return Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        }
    }

    private void buildCells(int count) {
        stage.removeAllViews();
        cells.clear();
        images.clear();
        int rows = (count + 1) / 2;
        for (int r = 0; r < rows; r++) {
            LinearLayout row = linear(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            int inRow = Math.min(2, count - r * 2);
            for (int c = 0; c < inRow; c++) {
                FrameLayout cell = new FrameLayout(this);
                cell.setBackgroundColor(Color.TRANSPARENT);
                ImageView iv = new ImageView(this);
                iv.setAdjustViewBounds(true);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                cell.addView(iv, new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, WRAP, 1f);
                lp.setMargins(dp(4), dp(4), dp(4), dp(4));
                row.addView(cell, lp);
                cells.add(cell);
                images.add(iv);
            }
            stage.addView(row, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        }
        currentQrCount = count;
    }

    private void clearImages() {
        for (ImageView iv : images) iv.setImageBitmap(null);
    }

    // ---------------- 运行状态机 ----------------

    private void onStartClick() {
        readConfig();
        if (cfg.qrCount != currentQrCount) buildCells(cfg.qrCount);
        if (state == State.PAUSED) {
            state = State.RUNNING;
            activeStart = SystemClock.uptimeMillis();
            enterPhase(phase.isEmpty() ? "gap" : phase, Math.max(1, phaseRemainMs));
            updateStatus("已继续");
            return;
        }
        generated = 0;
        accumulatedMs = 0;
        usedSuffix.clear();
        clearImages();
        state = State.RUNNING;
        activeStart = SystemClock.uptimeMillis();
        phase = "";
        applyWakeSetting();
        nextRound();
    }

    private void onPauseClick() {
        if (state != State.RUNNING) return;
        long now = SystemClock.uptimeMillis();
        accumulatedMs += now - activeStart;
        phaseRemainMs = Math.max(0, phaseEndAt - now);
        handler.removeCallbacks(tick);
        state = State.PAUSED;
        updateStatus("已暂停");
    }

    private void onStopClick() {
        handler.removeCallbacks(tick);
        state = State.STOPPED;
        phase = "";
        clearImages();
        tvCurrent.setText("");
        updateStatus("已停止（再次点击启动将重新开始）");
    }

    private void onResetClick() {
        handler.removeCallbacks(tick);
        state = State.IDLE;
        generated = 0;
        accumulatedMs = 0;
        phaseRemainMs = 0;
        phase = "";
        usedSuffix.clear();
        clearImages();
        tvCurrent.setText("");
        updateStatus("已复位，数据计数清零，可以重新开始");
    }

    private void nextRound() {
        readConfig();
        if (cfg.qrCount != currentQrCount) buildCells(cfg.qrCount);
        if (cfg.maxCount > 0 && generated >= cfg.maxCount) {
            finish("已完成 " + cfg.maxCount + " 次变化，自动停止");
            return;
        }
        if (cfg.limitMs > 0 && elapsedMs() >= cfg.limitMs) {
            finish("已到设定时长，自动停止");
            return;
        }
        generated++;

        int env = cfg.envRandom ? randomColor() : cfg.envColor;
        stage.setBackgroundColor(env);

        String common = cfg.sameData ? nextData() : null;
        int columns = currentQrCount == 1 ? 1 : 2;
        int rows = (currentQrCount + 1) / 2;
        float density = getResources().getDisplayMetrics().density;
        int screenDp = (int) (getResources().getDisplayMetrics().widthPixels / density);
        int stageDp = stage.getHeight() > 0 ? (int) (stage.getHeight() / density) : 300;
        int sizeDp = Math.min(cfg.qrSizeDp, screenDp / columns - 32);
        sizeDp = Math.min(sizeDp, stageDp / rows - 12);
        int sizePx = dp(Math.max(60, sizeDp));

        StringBuilder shown = new StringBuilder();
        for (int i = 0; i < images.size(); i++) {
            String data = common != null ? common : nextData();
            Bitmap bmp = qrBitmap(data, sizePx);
            ImageView iv = images.get(i);
            iv.setImageBitmap(bmp);
            iv.setLayoutParams(new FrameLayout.LayoutParams(sizePx, sizePx, Gravity.CENTER));
            if (cfg.slide != 0) {
                float from = cfg.slide == 1 ? -sizePx * 1.2f : sizePx * 1.2f;
                TranslateAnimation anim = new TranslateAnimation(from, 0, 0, 0);
                anim.setDuration(cfg.animMs);
                anim.setFillAfter(false);
                iv.startAnimation(anim);
            }
            if (i == 0) shown.append(data);
        }
        tvCurrent.setText("当前数据：" + (shown.length() > 70 ? shown.substring(0, 70) + "…" : shown));
        updateStatus(null);
        enterPhase("hold", cfg.holdMs);
    }

    private int randomColor() {
        return Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256));
    }

    private void enterPhase(String p, long ms) {
        phase = p;
        phaseRemainMs = ms;
        phaseEndAt = SystemClock.uptimeMillis() + ms;
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, Math.max(1, ms));
    }

    private long elapsedMs() {
        long e = accumulatedMs;
        if (state == State.RUNNING) e += SystemClock.uptimeMillis() - activeStart;
        return e;
    }

    private String stateName() {
        switch (state) {
            case RUNNING: return "运行中";
            case PAUSED: return "已暂停";
            case STOPPED: return "已停止";
            case IDLE: return "就绪";
            default: return "";
        }
    }

    private void updateStatus(String extra) {
        applyWakeSetting();
        if (extra != null) {
            tvStatus.setText(extra);
            return;
        }
        long sec = elapsedMs() / 1000;
        String limit = cfg.limitMs > 0 ? " / " + (cfg.limitMs / 1000) + "s" : "";
        String count = cfg.maxCount > 0 ? " / " + cfg.maxCount : "";
        tvStatus.setText(String.format(Locale.US, "%s | 次数 %d%s | 用时 %02d:%02d%s",
                stateName(), generated, count, sec / 60, sec % 60, limit));
    }

    private void finish(String message) {
        handler.removeCallbacks(tick);
        state = State.STOPPED;
        phase = "";
        updateStatus(message);
    }
}
