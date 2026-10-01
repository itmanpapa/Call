package dummydomain.yetanothercallblocker;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.EdgeToEdge;
import androidx.annotation.IdRes;
import androidx.annotation.LayoutRes;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NavUtils;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.bottomnavigation.BottomNavigationView;

/**
 * Common screen frame: edge-to-edge window, Material 3 top app bar
 * and (for top-level screens) the bottom navigation bar.
 */
public abstract class BaseActivity extends AppCompatActivity {

    private BottomNavigationView bottomNavigation;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
    }

    /**
     * @return the bottom navigation item of this screen
     * or 0 if this is not a top-level screen
     */
    @IdRes
    protected int getNavigationItemId() {
        return 0;
    }

    protected boolean isTopLevel() {
        return getNavigationItemId() != 0;
    }

    @Override
    public void setContentView(@LayoutRes int layoutResID) {
        super.setContentView(R.layout.activity_base);

        ViewGroup content = findViewById(R.id.base_content);
        LayoutInflater.from(this).inflate(layoutResID, content, true);

        setUpFrame();
    }

    @Override
    public void setContentView(View view) {
        super.setContentView(R.layout.activity_base);

        ViewGroup content = findViewById(R.id.base_content);
        content.addView(view);

        setUpFrame();
    }

    private void setUpFrame() {
        setSupportActionBar(findViewById(R.id.base_toolbar));

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(!isTopLevel()
                    && NavUtils.getParentActivityName(this) != null);
        }

        AppBarLayout appBar = findViewById(R.id.base_app_bar);
        View content = findViewById(R.id.base_content);
        bottomNavigation = findViewById(R.id.base_bottom_navigation);

        if (isTopLevel()) {
            bottomNavigation.setVisibility(View.VISIBLE);
            bottomNavigation.setSelectedItemId(getNavigationItemId());
            bottomNavigation.setOnItemSelectedListener(item -> {
                if (item.getItemId() != getNavigationItemId()) {
                    navigateTo(item.getItemId());
                }
                return false; // the selection is updated by the target screen
            });
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.base_root), (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            Insets ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime());

            appBar.setPadding(bars.left, bars.top, bars.right, 0);

            int bottom = isTopLevel() && !windowInsets.isVisible(WindowInsetsCompat.Type.ime())
                    ? 0 : Math.max(bars.bottom, ime.bottom);
            content.setPadding(bars.left, 0, bars.right, bottom);

            if (isTopLevel()) {
                bottomNavigation.setVisibility(
                        windowInsets.isVisible(WindowInsetsCompat.Type.ime())
                                ? View.GONE : View.VISIBLE);
            }

            // the bottom navigation applies the bottom inset itself
            return windowInsets;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (bottomNavigation != null && isTopLevel()) {
            bottomNavigation.getMenu().findItem(getNavigationItemId()).setChecked(true);
        }
    }

    private void navigateTo(@IdRes int itemId) {
        Class<?> target;
        if (itemId == R.id.nav_call_log) {
            target = MainActivity.class;
        } else if (itemId == R.id.nav_lookup) {
            target = LookupNumberActivity.class;
        } else if (itemId == R.id.nav_blacklist) {
            target = BlacklistActivity.class;
        } else if (itemId == R.id.nav_settings) {
            target = SettingsActivity.class;
        } else {
            return;
        }

        Intent intent = new Intent(this, target)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        startActivity(intent);
        overridePendingTransition(0, 0);
    }

    @Override
    public boolean onSupportNavigateUp() {
        if (getSupportFragmentManager().getBackStackEntryCount() > 0) {
            getSupportFragmentManager().popBackStack();
            return true;
        }
        return super.onSupportNavigateUp();
    }

}
