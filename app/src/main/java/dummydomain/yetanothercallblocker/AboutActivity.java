package dummydomain.yetanothercallblocker;

import android.os.Bundle;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.widget.TextView;

import androidx.annotation.IdRes;

/**
 * "About": app name, version, license and the links to the fork and the original
 * project. The status of the databases is shown on the "Databases" screen.
 */
public class AboutActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        ((TextView) findViewById(R.id.about_version)).setText(
                getString(R.string.version_string, BuildConfig.VERSION_NAME));

        setLink(R.id.about_based_on, getString(R.string.about_based_on,
                link(getString(R.string.url_repo), getString(R.string.about_original_name))));
        setLink(R.id.about_fork, link(getString(R.string.url_fork_repo),
                getString(R.string.about_fork_link)));
    }

    private static String link(String url, String text) {
        return "<a href=\"" + url + "\">" + Html.escapeHtml(text) + "</a>";
    }

    private void setLink(@IdRes int textViewId, String html) {
        TextView textView = findViewById(textViewId);
        textView.setMovementMethod(LinkMovementMethod.getInstance());
        textView.setText(Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY));
    }

}
