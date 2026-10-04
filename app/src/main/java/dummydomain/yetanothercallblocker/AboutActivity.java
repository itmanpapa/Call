package dummydomain.yetanothercallblocker;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Html;
import android.text.format.DateFormat;
import android.text.method.LinkMovementMethod;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.IdRes;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Date;
import java.util.Locale;

import dummydomain.yetanothercallblocker.data.donation.BitcoinAddress;

/**
 * "About": app name, version, the update check (not in the F-Droid build), license, the
 * links to the fork and the original project and the optional donation buttons (Ko-fi,
 * Bitcoin). The
 * status of the databases is shown on the "Databases" screen.
 */
public class AboutActivity extends BaseActivity {

    private static final Logger LOG = LoggerFactory.getLogger(AboutActivity.class);

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

        View checkUpdates = findViewById(R.id.about_check_updates);
        if (AppUpdateManager.isSelfUpdateEnabled()) {
            checkUpdates.setOnClickListener(v -> startActivity(UpdateActivity.getIntent(this)));
        } else {
            // the F-Droid build is updated by its app store
            checkUpdates.setVisibility(View.GONE);
        }

        // shown only when a donation link is configured (res/values/donation.xml)
        String donationUrl = getString(R.string.donation_url).trim();
        View donate = findViewById(R.id.about_donate);
        if (donationUrl.isEmpty()) {
            donate.setVisibility(View.GONE);
        } else {
            donate.setVisibility(View.VISIBLE);
            donate.setOnClickListener(v -> openUrl(donationUrl));
        }

        // shown only when a valid Bitcoin address is configured (res/values/donation.xml)
        String btcAddress = BitcoinAddress.normalize(getString(R.string.donation_btc_address));
        View donateBtc = findViewById(R.id.about_donate_btc);
        if (btcAddress == null) {
            donateBtc.setVisibility(View.GONE);
        } else {
            donateBtc.setVisibility(View.VISIBLE);
            donateBtc.setOnClickListener(v -> showBitcoinDialog(btcAddress));
        }
    }

    /** Address with a QR code, "Copy" and "Open in wallet" (bitcoin: URI). */
    @SuppressLint("InflateParams") // dialog view, there is no parent
    private void showBitcoinDialog(String address) {
        View view = getLayoutInflater().inflate(R.layout.dialog_donation_btc, null);
        ((TextView) view.findViewById(R.id.donation_btc_address)).setText(address);

        ImageView qr = view.findViewById(R.id.donation_btc_qr);
        Bitmap bitmap = qrCode(BitcoinAddress.toUri(address).toUpperCase(Locale.ROOT));
        if (bitmap != null) {
            qr.setImageBitmap(bitmap);
        } else {
            qr.setVisibility(View.GONE);
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.donation_btc_title)
                .setView(view)
                .setPositiveButton(R.string.donation_btc_copy, (d, w) -> copyAddress(address))
                .setNeutralButton(R.string.donation_btc_open_wallet, (d, w) -> {
                    Intent intent = new Intent(Intent.ACTION_VIEW,
                            Uri.parse(BitcoinAddress.toUri(address)));
                    if (!IntentHelper.startActivity(this, intent)) {
                        Toast.makeText(this, R.string.donation_btc_no_wallet,
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(R.string.donation_btc_close, null)
                .show();
    }

    private void copyAddress(String address) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText("Bitcoin", address));
        // Android 13+ confirms copying itself
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, R.string.donation_btc_copied, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Renders a QR code; upper case lets it use the compact alphanumeric mode
     * (BIP 21 allows an upper-case "BITCOIN:" scheme and bech32 address).
     */
    private static Bitmap qrCode(String content) {
        try {
            int size = 512;
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE,
                    size, size, Collections.singletonMap(EncodeHintType.MARGIN, 1));
            int width = matrix.getWidth();
            int height = matrix.getHeight();
            int[] pixels = new int[width * height];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    pixels[y * width + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }
            return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
        } catch (WriterException | IllegalArgumentException e) {
            LOG.warn("qrCode()", e);
            return null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateUpdateStatus();
    }

    /** Shows the result of the last update check, if there was one. */
    private void updateUpdateStatus() {
        TextView status = findViewById(R.id.about_update_status);
        if (!AppUpdateManager.isSelfUpdateEnabled()) {
            status.setVisibility(View.GONE);
            return;
        }
        AppUpdateManager manager = AppUpdateManager.get(this);

        String update = manager.getKnownUpdate();
        long lastCheck = manager.getLastCheckTime();
        if (update != null) {
            status.setText(getString(R.string.update_available_title, update));
        } else if (lastCheck > 0) {
            status.setText(getString(R.string.update_last_check,
                    DateFormat.getDateFormat(this).format(new Date(lastCheck))));
        } else {
            status.setVisibility(View.GONE);
            return;
        }
        status.setVisibility(View.VISIBLE);
    }

    private void openUrl(String url) {
        if (!IntentHelper.startActivity(this, new Intent(Intent.ACTION_VIEW, Uri.parse(url)))) {
            Toast.makeText(this, R.string.update_no_browser, Toast.LENGTH_SHORT).show();
        }
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
