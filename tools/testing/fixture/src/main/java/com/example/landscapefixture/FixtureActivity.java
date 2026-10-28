package com.example.landscapefixture;
import android.app.Activity;
import android.os.*;
import android.view.*;
import android.widget.*;
import android.graphics.Color;
import android.graphics.*;

public class FixtureActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout prompt;
    private TextView content;
    private float downY;
    private long readyAt;
    private int count;
    private boolean finished;
    private View drawnPrompt;
    private boolean keep;
    private String mode;
    private TextView adBadge;
    private boolean loopMode;
    private int successes;
    private long lastUp;
    private long minStartGap = Long.MAX_VALUE;
    private int dp(int x) { return Math.round(x * getResources().getDisplayMetrics().density); }
    private TextView label(String text) {
        TextView v = new TextView(this); v.setText(text); v.setTextSize(20); v.setTextColor(Color.WHITE);
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES); return v;
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if(getIntent().getBooleanExtra("portrait",false)) setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        getWindow().getDecorView().setSystemUiVisibility(5894);
        mode = getIntent().getStringExtra("mode");
        if(mode == null) mode = "split";
        keep = getIntent().getBooleanExtra("keep", false);
        if(mode.equals("secure")) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        count = 0; finished = false;
        getSharedPreferences("result",0).edit().clear().putInt("gestures",0).putString("mode",mode).apply();
        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(Color.DKGRAY);
        content = label("测试广告内容 A / 模拟页面");
        FrameLayout.LayoutParams center = new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER);
        root.addView(content,center);
        prompt = new LinearLayout(this); prompt.setOrientation(LinearLayout.HORIZONTAL);
        prompt.setPadding(dp(10),dp(10),dp(10),dp(10)); prompt.setBackgroundColor(Color.BLACK);
        FrameLayout.LayoutParams top = new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.RIGHT);
        top.topMargin=dp(16); top.rightMargin=dp(20); root.addView(prompt,top);
        setContentView(root);
        loopMode = mode.startsWith("loop_") || mode.equals("normal_video");
        if(loopMode) {
            prompt.setVisibility(View.GONE);
            adBadge = label(mode.equals("loop_substring") ? "广告服务" : "广告");
            if(mode.equals("loop_description")) {
                adBadge.setText(""); adBadge.setContentDescription("广告");
                adBadge.setMinimumWidth(dp(80)); adBadge.setMinimumHeight(dp(40));
            }
            FrameLayout.LayoutParams badgePos = new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.RIGHT);
            badgePos.topMargin=dp(16); badgePos.rightMargin=dp(20);
            root.addView(adBadge,badgePos);
            if(mode.equals("normal_video")) adBadge.setVisibility(View.GONE);
            if(mode.equals("loop_ready") || mode.equals("loop_description") || mode.equals("loop_blank_after_success")) ready();
            else if(!mode.equals("loop_persistent") && !mode.equals("loop_hidden") && !mode.equals("loop_substring") && !mode.equals("normal_video")) {
                content.setText("5秒后上滑继续观看剧集");
                handler.postDelayed(() -> { ready(); content.setText("模拟广告倒计时结束，播放器现在允许上滑"); },5000);
            }
            if(mode.equals("loop_hidden")) { adBadge.setVisibility(View.GONE); ready(); }
            if(mode.equals("loop_temporary")) handler.postDelayed(() -> {
                adBadge.setVisibility(View.GONE);
                handler.postDelayed(() -> adBadge.setVisibility(View.VISIBLE),1500);
            },2300);
            if(mode.equals("loop_blank")) handler.postDelayed(() -> {
                adBadge.setVisibility(View.GONE); content.setVisibility(View.GONE);
                handler.postDelayed(() -> { adBadge.setVisibility(View.VISIBLE); content.setVisibility(View.VISIBLE); },3500);
            },2300);
            if(mode.equals("loop_next_ad")) ready();
        }
        else if(mode.equals("countdown")) { timer(4); }
        else if(mode.equals("single")) { prompt.addView(label("上滑继续观看剧集")); ready(); }
        else { split(); ready(); }
    }
    private void add(String s) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2,-2); p.leftMargin=dp(3);
        prompt.addView(label(s),p);
    }
    private void split() { prompt.removeAllViews(); add("上滑"); add("继续观看剧集"); }
    private void ready() {
        readyAt = SystemClock.elapsedRealtime();
        getSharedPreferences("result",0).edit().putLong("ready_at",readyAt).apply();
    }
    private void timer(int seconds) {
        prompt.removeAllViews(); add(seconds + "秒后"); add("上滑继续观看剧集");
        handler.postDelayed(() -> { if(seconds > 1) timer(seconds-1); else { split(); ready(); } },1000);
    }
    @Override public boolean dispatchTouchEvent(android.view.MotionEvent e) {
        if(e.getAction()==MotionEvent.ACTION_DOWN) {
            downY=e.getY();
            if(lastUp>0) minStartGap=Math.min(minStartGap,SystemClock.elapsedRealtime()-lastUp);
        }
        if(e.getAction()==MotionEvent.ACTION_UP && downY-e.getY()>dp(50)) {
            count++;
            long at=SystemClock.elapsedRealtime();
            if(count==1) getSharedPreferences("result",0).edit().putLong("first_gesture_at",at).apply();
            lastUp=at;
            if(loopMode) {
                boolean canLeave=readyAt>0 && at>=readyAt && !mode.equals("loop_persistent");
                if(canLeave && !finished) {
                    finished=true; successes++;
                    adBadge.setVisibility(View.GONE); content.setText("正常剧集 B");
                    if(mode.equals("loop_blank_after_success")) content.setVisibility(View.GONE);
                    getSharedPreferences("result",0).edit().putInt("gestures_at_clear",count).putLong("cleared_at",at).apply();
                    if(mode.equals("loop_next_ad") && successes==1) handler.postDelayed(() -> {
                        finished=false; ready(); adBadge.setVisibility(View.VISIBLE); content.setText("第二条广告");
                    },6000);
                }
                getSharedPreferences("result",0).edit().putInt("gestures",count).putLong("gesture_at",at)
                    .putInt("successes",successes).putLong("min_start_gap_ms",minStartGap).apply();
                android.util.Log.i("LandscapeFixture","mode="+mode+" attempt="+count+" successes="+successes+" minGap="+minStartGap);
                return true;
            }
            getSharedPreferences("result",0).edit().putInt("gestures",count).putLong("gesture_at",at)
                .putBoolean("early",readyAt==0 || at<readyAt).apply();
            android.util.Log.i("LandscapeFixture","gesture="+count+" early="+(readyAt==0 || at<readyAt));
            if(!finished && !keep) { finished=true; prompt.setVisibility(View.GONE); if(drawnPrompt!=null) drawnPrompt.setVisibility(View.GONE); content.setText("已进入剧集 B / 内容已切换"); }
            return true;
        }
        super.dispatchTouchEvent(e); return true;
    }
    @Override public void onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy(); }
}
