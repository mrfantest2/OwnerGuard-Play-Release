package com.fantest.ownerguard;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/** Samsung-compatible secure player using MediaPlayer + SurfaceView and a private file path. */
public class VideoPlaybackActivity extends SecureActivity implements SurfaceHolder.Callback {
    private final Handler main = new Handler(Looper.getMainLooper());
    private SurfaceView surface;
    private MediaPlayer player;
    private File videoFile;
    private TextView status;
    private TextView time;
    private SeekBar seek;
    private boolean surfaceReady;
    private boolean preparing;
    private final Runnable progress = new Runnable() {
        @Override public void run() {
            try {
                if (player != null && player.getDuration() > 0) {
                    seek.setMax(player.getDuration());
                    seek.setProgress(player.getCurrentPosition());
                    time.setText(format(player.getCurrentPosition()) + " / " + format(player.getDuration()));
                }
            } catch (Exception ignored) {}
            main.postDelayed(this, 150L);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (!AuthSession.isUnlocked()) { routeToUnlock(); return; }
        String raw = getIntent().getStringExtra("video_path");
        if (raw == null) { finish(); return; }
        try {
            File exports = new File(getCacheDir(), "exports").getCanonicalFile();
            videoFile = new File(raw).getCanonicalFile();
            if (!videoFile.getPath().startsWith(exports.getPath() + File.separator)
                    || !videoFile.isFile() || videoFile.length() < 2048) {
                throw new SecurityException("Invalid secure playback file");
            }
        } catch (Exception e) {
            Toast.makeText(this, "Secure video file is unavailable", Toast.LENGTH_LONG).show();
            finish(); return;
        }
        build();
    }

    private void build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        root.setBackgroundColor(Color.parseColor("#070D18"));

        TextView title = text("OwnerGuard secure video", 23, Color.WHITE);
        title.setPadding(0, 0, 0, dp(12)); root.addView(title);

        surface = new SurfaceView(this);
        surface.setKeepScreenOn(true);
        surface.getHolder().addCallback(this);
        root.addView(surface, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        status = text("Preparing private in-app playback…", 14, Color.parseColor("#B8C5D6"));
        status.setPadding(0, dp(12), 0, dp(6)); root.addView(status);
        time = text("00:00 / 00:00", 13, Color.parseColor("#B8C5D6")); root.addView(time);

        seek = new SeekBar(this); root.addView(seek, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser && player != null) try { player.seekTo(p); } catch (Exception ignored) {}
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        LinearLayout actions = new LinearLayout(this); actions.setOrientation(LinearLayout.HORIZONTAL);
        Button playPause = button("Play / pause");
        playPause.setOnClickListener(v -> togglePlayback());
        Button replay = button("Replay"); replay.setOnClickListener(v -> replay());
        Button external = button("Other player"); external.setOnClickListener(v -> openExternal());
        actions.addView(playPause, weighted()); actions.addView(replay, weighted()); actions.addView(external, weighted());
        root.addView(actions);
        Button close = button("Close"); close.setOnClickListener(v -> finish()); root.addView(close, top(10));
        setContentView(root);
        main.post(progress);
    }

    private void preparePlayer() {
        if (!surfaceReady || preparing || player != null) return;
        preparing = true;
        try {
            player = new MediaPlayer();
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            player.setDisplay(surface.getHolder());
            player.setDataSource(videoFile.getAbsolutePath());
            player.setOnPreparedListener(mp -> {
                preparing = false;
                status.setText("Playing 5-second silent video inside OwnerGuard");
                seek.setMax(Math.max(1, mp.getDuration()));
                fitSurface(mp.getVideoWidth(), mp.getVideoHeight());
                mp.start();
            });
            player.setOnVideoSizeChangedListener((mp, w, h) -> fitSurface(w, h));
            player.setOnCompletionListener(mp -> status.setText("Playback completed. Tap Replay to view again."));
            player.setOnErrorListener((mp, what, extra) -> {
                preparing = false;
                status.setText("Built-in playback failed (" + what + "/" + extra + "). Use Other player as fallback.");
                return true;
            });
            player.prepareAsync();
        } catch (Exception e) {
            preparing = false;
            status.setText("Built-in playback could not start: " + e.getClass().getSimpleName());
            releasePlayer();
        }
    }

    private void fitSurface(int videoWidth, int videoHeight) {
        if (videoWidth <= 0 || videoHeight <= 0 || surface == null) return;
        int availableWidth = Math.max(1, surface.getWidth());
        int availableHeight = Math.max(1, surface.getHeight());
        float scale = Math.min((float) availableWidth / videoWidth, (float) availableHeight / videoHeight);
        int width = Math.max(1, Math.round(videoWidth * scale));
        int height = Math.max(1, Math.round(videoHeight * scale));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width, height, 1f);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        surface.setLayoutParams(lp);
    }

    private void togglePlayback() {
        try {
            if (player == null) { preparePlayer(); return; }
            if (player.isPlaying()) { player.pause(); status.setText("Paused"); }
            else { player.start(); status.setText("Playing inside OwnerGuard"); }
        } catch (Exception e) { status.setText("Playback control failed"); }
    }

    private void replay() {
        try {
            if (player == null) { preparePlayer(); return; }
            player.seekTo(0); player.start(); status.setText("Replaying inside OwnerGuard");
        } catch (Exception e) { status.setText("Could not replay video"); }
    }

    private void openExternal() {
        try {
            Uri uri = SecureShareProvider.uriForFile(this, videoFile);
            Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Open OwnerGuard video"));
        } catch (Exception e) {
            Toast.makeText(this, "No compatible video player was found", Toast.LENGTH_LONG).show();
        }
    }

    private void routeToUnlock() {
        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
        finish();
    }

    @Override public void surfaceCreated(SurfaceHolder holder) { surfaceReady = true; preparePlayer(); }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}
    @Override public void surfaceDestroyed(SurfaceHolder holder) { surfaceReady = false; releasePlayer(); }

    @Override protected void onResume() {
        super.onResume();
        if (!AuthSession.isUnlocked()) { routeToUnlock(); return; }
        if (surfaceReady && player == null) preparePlayer();
    }

    @Override protected void onPause() {
        try { if (player != null && player.isPlaying()) player.pause(); } catch (Exception ignored) {}
        super.onPause();
    }

    @Override protected void onDestroy() {
        main.removeCallbacks(progress);
        releasePlayer();
        super.onDestroy();
    }

    private void releasePlayer() {
        try { if (player != null) player.reset(); } catch (Exception ignored) {}
        try { if (player != null) player.release(); } catch (Exception ignored) {}
        player = null;
    }

    private TextView text(String s, int sp, int color) { TextView v = new TextView(this); v.setText(s); v.setTextSize(sp); v.setTextColor(color); return v; }
    private Button button(String s) { Button b = new Button(this); b.setText(s); b.setAllCaps(false); b.setTextColor(Color.WHITE); b.setBackgroundColor(Color.parseColor("#123D52")); b.setMinHeight(dp(50)); return b; }
    private LinearLayout.LayoutParams weighted(){ LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); p.setMargins(dp(4),0,dp(4),0); return p; }
    private LinearLayout.LayoutParams top(int n){ LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT); p.setMargins(0,dp(n),0,0); return p; }
    private int dp(int v){ return Math.round(v * getResources().getDisplayMetrics().density); }
    private static String format(int millis){ int total=Math.max(0,millis/1000); return String.format(java.util.Locale.US,"%02d:%02d",total/60,total%60); }
}
