package com.example.gamespeed;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 主控台
 *
 * 三个入口：
 *  1. 变速面板（SeekBar 调倍率，写入 SharedPreferences 供 Hook 进程读取）
 *  2. 悬浮窗开关（启动 FloatingService，游戏内可调倍率）
 *  3. 连点器配置（跳转 ClickConfigActivity）
 */
public class MainActivity extends AppCompatActivity {

    private static final String PREF_NAME = "speed";
    private static final String KEY_SPEED = "speed";

    private SeekBar seekBar;
    private TextView tvSpeed;
    private TextView tvHint;
    private SharedPreferences sp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        seekBar = findViewById(R.id.seekBar);
        tvSpeed = findViewById(R.id.tvSpeed);
        tvHint = findViewById(R.id.tvHint);
        Button btnFloating = findViewById(R.id.btnFloating);
        Button btnClick = findViewById(R.id.btnClick);

        // ============ 1. 变速 SeekBar ============
        try {
            sp = getSharedPreferences(PREF_NAME, MODE_WORLD_READABLE);
        } catch (Throwable e) {
            sp = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
            tvHint.setText(getString(R.string.hint_no_world_readable));
        }

        float saved = sp.getFloat(KEY_SPEED, 1.0f);
        seekBar.setMax(200);
        seekBar.setProgress((int) (saved * 10));
        updateSpeedText(saved);

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                float speed = Math.max(0.1f, progress / 10.0f);
                updateSpeedText(speed);
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {}

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                float speed = Math.max(0.1f, bar.getProgress() / 10.0f);
                sp.edit().putFloat(KEY_SPEED, speed).apply();
                Toast.makeText(MainActivity.this,
                        getString(R.string.toast_speed_set, speed), Toast.LENGTH_SHORT).show();
            }
        });

        // ============ 2. 悬浮窗开关 ============
        btnFloating.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && !Settings.canDrawOverlays(this)) {
                // 没悬浮窗权限，去授权
                Intent it = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(it);
                Toast.makeText(this, R.string.toast_enable_overlay, Toast.LENGTH_LONG).show();
                return;
            }
            Intent it = new Intent(this, FloatingService.class);
            startService(it);
            finish(); // 关掉主界面，悬浮窗留在桌面
        });

        // ============ 3. 连点器配置 ============
        btnClick.setOnClickListener(v -> {
            startActivity(new Intent(this, ClickConfigActivity.class));
        });
    }

    private void updateSpeedText(float speed) {
        tvSpeed.setText(String.format("%.1f x", speed));
    }
}
