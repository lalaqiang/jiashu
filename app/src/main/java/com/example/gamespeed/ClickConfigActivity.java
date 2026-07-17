package com.example.gamespeed;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * 连点器配置界面
 *
 * 功能：
 *  - 添加/编辑/删除点击点（x, y, 按下时长）
 *  - 调整点击间隔（毫秒）
 *  - 检测并引导开启无障碍权限
 *  - 一键启动/停止连点
 *
 * 点击点获取方式：
 *  - 手动输入坐标
 *  - 后续可加：屏幕录制点选 / 拖拽悬浮十字光标
 */
public class ClickConfigActivity extends AppCompatActivity {

    private ListView lvPoints;
    private PointsAdapter adapter;
    private TextView tvInterval;
    private SeekBar sbInterval;
    private Button btnStart;
    private Button btnStop;
    private Button btnAccessibility;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_click_config);

        ClickConfig.init(this);

        lvPoints = findViewById(R.id.lvPoints);
        tvInterval = findViewById(R.id.tvInterval);
        sbInterval = findViewById(R.id.sbInterval);
        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);
        btnAccessibility = findViewById(R.id.btnAccessibility);
        Button btnAdd = findViewById(R.id.btnAdd);
        Button btnClear = findViewById(R.id.btnClear);

        adapter = new PointsAdapter(this);
        lvPoints.setAdapter(adapter);

        // 间隔 SeekBar：10~2000ms
        sbInterval.setMax(1990);
        int cur = ClickConfig.getInterval();
        sbInterval.setProgress(cur - 10);
        tvInterval.setText(cur + " ms");
        sbInterval.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                int ms = progress + 10;
                tvInterval.setText(ms + " ms");
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {}

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                int ms = bar.getProgress() + 10;
                ClickConfig.setInterval(ClickConfigActivity.this, ms);
            }
        });

        btnAdd.setOnClickListener(v -> showAddDialog());
        btnClear.setOnClickListener(v -> {
            if (ClickConfig.getPoints().isEmpty()) return;
            new AlertDialog.Builder(this)
                    .setTitle("清空")
                    .setMessage("确定清空所有点击点？")
                    .setPositiveButton("确定", (d, w) -> {
                        ClickConfig.clearPoints(this);
                        adapter.notifyDataSetChanged();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });

        btnStart.setOnClickListener(v -> {
            if (!isAccessibilityEnabled()) {
                Toast.makeText(this, R.string.toast_enable_accessibility, Toast.LENGTH_LONG).show();
                return;
            }
            if (ClickConfig.getPoints().isEmpty()) {
                Toast.makeText(this, R.string.toast_add_point_first, Toast.LENGTH_SHORT).show();
                return;
            }
            Intent it = new Intent(this, ClickService.class);
            it.setAction(ClickService.ACTION_START);
            startService(it);
            Toast.makeText(this, R.string.toast_click_started, Toast.LENGTH_SHORT).show();
        });

        btnStop.setOnClickListener(v -> {
            Intent it = new Intent(this, ClickService.class);
            it.setAction(ClickService.ACTION_STOP);
            startService(it);
            Toast.makeText(this, R.string.toast_click_stopped, Toast.LENGTH_SHORT).show();
        });

        btnAccessibility.setOnClickListener(v -> {
            Intent it = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(it);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateAccessibilityButton();
        adapter.notifyDataSetChanged();
    }

    private void updateAccessibilityButton() {
        if (isAccessibilityEnabled()) {
            btnAccessibility.setText(R.string.btn_accessibility_on);
            btnAccessibility.setEnabled(false);
        } else {
            btnAccessibility.setText(R.string.btn_accessibility_off);
            btnAccessibility.setEnabled(true);
        }
    }

    /** 判断本 APP 的无障碍服务是否已启用 */
    private boolean isAccessibilityEnabled() {
        ComponentName expected = new ComponentName(this, ClickService.class);
        String flat = expected.flattenToString();
        String enabled = Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) return false;
        for (String item : enabled.split(":")) {
            if (item.equals(flat)) return true;
        }
        return false;
    }

    private void showAddDialog() {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_point, null);
        EditText etX = v.findViewById(R.id.etX);
        EditText etY = v.findViewById(R.id.etY);
        EditText etDur = v.findViewById(R.id.etDuration);
        etDur.setText("50");

        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_add_point)
                .setView(v)
                .setPositiveButton("确定", (d, w) -> {
                    try {
                        int x = Integer.parseInt(etX.getText().toString().trim());
                        int y = Integer.parseInt(etY.getText().toString().trim());
                        int dur = Integer.parseInt(etDur.getText().toString().trim());
                        if (dur < 10) dur = 10;
                        ClickConfig.addPoint(this, x, y, dur);
                        adapter.notifyDataSetChanged();
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, R.string.toast_invalid_input, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 点位列表适配器 */
    private static class PointsAdapter extends BaseAdapter {
        private final Context ctx;
        private final List<ClickConfig.ClickPoint> snapshot = new ArrayList<>();

        PointsAdapter(Context ctx) { this.ctx = ctx; }

        @Override
        public void notifyDataSetChanged() {
            snapshot.clear();
            snapshot.addAll(ClickConfig.getPoints());
            super.notifyDataSetChanged();
        }

        @Override public int getCount() { return snapshot.size(); }
        @Override public Object getItem(int i) { return snapshot.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(ctx).inflate(R.layout.item_point, parent, false);
            }
            ClickConfig.ClickPoint p = snapshot.get(i);
            TextView tv = convertView.findViewById(R.id.tvPointInfo);
            tv.setText(String.format("#%d  (%d, %d)  %dms", i + 1, p.x, p.y, p.durationMs));

            convertView.findViewById(R.id.btnDelete).setOnClickListener(v -> {
                ClickConfig.removePoint(ctx, i);
                notifyDataSetChanged();
            });
            return convertView;
        }
    }
}
