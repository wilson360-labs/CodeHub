package com.codehub.app.codesu;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.animation.LinearInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.navigation.NavController;
import androidx.navigation.NavDestination;
import androidx.navigation.fragment.NavHostFragment;

import com.codehub.app.codesu.databinding.CodesuActivityMainBinding;

public class MainActivity extends AppCompatActivity {

    private CodesuActivityMainBinding binding;
    private ObjectAnimator logoAnimator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        WindowCompat.setDecorFitsSystemWindows(window, false);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        WindowCompat.getInsetsController(window, window.getDecorView())
                .setAppearanceLightNavigationBars(false);

        binding = CodesuActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        App.setAppThemeMode();
        NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager()
                .findFragmentById(R.id.nav_host_fragment_activity_main);
        NavController navController = navHostFragment.getNavController();

        ViewCompat.setOnApplyWindowInsetsListener(binding.codesuToolbar, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(
                    v.getPaddingLeft(),
                    insets.top,
                    v.getPaddingRight(),
                    v.getPaddingBottom()
            );
            return windowInsets;
        });

        // Keep CodeSu's tab bar above Android's gesture/three-button navigation area.
        ViewCompat.setOnApplyWindowInsetsListener(binding.navView, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars());
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), insets.bottom);
            return windowInsets;
        });

        logoAnimator = ObjectAnimator.ofFloat(binding.logo, "rotation", 0f, 360f);
        logoAnimator.setDuration(6000);
        logoAnimator.setRepeatCount(ValueAnimator.INFINITE);
        logoAnimator.setInterpolator(new LinearInterpolator());

        binding.searchEditText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
                v.clearFocus();
                return true;
            }
            return false;
        });

        binding.searchLayout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                binding.searchEditText.requestFocus();
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null)
                    imm.showSoftInput(binding.searchEditText, InputMethodManager.SHOW_IMPLICIT);
            }
        });

        navController.addOnDestinationChangedListener((controller, destination, arguments) ->
                updateSelectedTab(destination));
        updateSelectedTab(navController.getCurrentDestination());

        // Navegación manual para mantener el tab bar ligero y sin el tema
        // Material que causaba el crash anterior de BottomNavigationView.
        View.OnClickListener navListener = v -> {
            int id = v.getId();
            int destinationId = id == R.id.nav_home ? R.id.navigation_home
                    : id == R.id.nav_excluded ? R.id.navigation_excluded
                    : id == R.id.nav_settings ? R.id.navigation_settings
                    : id == R.id.nav_cache ? R.id.navigation_cache : 0;
            if (destinationId == 0 || navController.getCurrentDestination() == null
                    || navController.getCurrentDestination().getId() == destinationId)
                return;
            if (!navController.popBackStack(destinationId, false))
                navController.navigate(destinationId);
        };
        binding.navHome.setOnClickListener(navListener);
        binding.navExcluded.setOnClickListener(navListener);
        binding.navSettings.setOnClickListener(navListener);
        binding.navCache.setOnClickListener(navListener);
    }

    private void updateSelectedTab(NavDestination destination) {
        if (destination == null || binding == null) return;
        setTabSelected(binding.navHome, destination.getId() == R.id.navigation_home);
        setTabSelected(binding.navExcluded, destination.getId() == R.id.navigation_excluded);
        setTabSelected(binding.navSettings, destination.getId() == R.id.navigation_settings);
        setTabSelected(binding.navCache, destination.getId() == R.id.navigation_cache);
    }

    private void setTabSelected(View tab, boolean selected) {
        tab.setSelected(selected);
        tab.setAlpha(selected ? 1f : 0.72f);
        if (tab instanceof android.widget.TextView)
            ((android.widget.TextView) tab).setTypeface(null,
                    selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
    }

    @Override
    protected void onStart() {
        super.onStart();
        logoAnimator.start();
    }

    @Override
    protected void onStop() {
        super.onStop();
        logoAnimator.cancel();
    }

    public CardView getSearchLayout() {
        return binding.searchLayout;
    }

    public EditText getSearchEditText() {
        return binding.searchEditText;
    }
}
