package com.codehub.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/** Pantalla de presentación (splash) al abrir la app desde el launcher.
 *  Muestra el nombre/logo unos instantes y entrega el control a
 *  MainActivity. Los deep links y atajos van directo a MainActivity. */
public class SplashActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int d = (int) getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xFF080810);

        TextView logo = new TextView(this);
        logo.setText("🚀");
        logo.setTextSize(56);
        root.addView(logo, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView name = new TextView(this);
        name.setText("CodeHub");
        name.setTextColor(0xFFFFFFFF);
        name.setTypeface(name.getTypeface(), Typeface.BOLD);
        name.setTextSize(30);
        name.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameLp.topMargin = (int) (10 * d);
        root.addView(name, nameLp);

        TextView tagline = new TextView(this);
        tagline.setText("Todos tus servicios en un solo lugar");
        tagline.setTextColor(0xFF9AA0B8);
        tagline.setTextSize(14);
        tagline.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tagLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tagLp.topMargin = (int) (6 * d);
        root.addView(tagline, tagLp);

        ProgressBar spinner = new ProgressBar(this);
        LinearLayout.LayoutParams spinLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        spinLp.topMargin = (int) (28 * d);
        root.addView(spinner, spinLp);

        setContentView(root);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (isFinishing() || isDestroyed()) return;
            Intent i = new Intent(SplashActivity.this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(i);
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            finish();
        }, 1200);
    }
}