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
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import dummydomain.yetanothercallblocker.data.donation.BitcoinAddress;
import dummydomain.yetanothercallblocker.data.donation.EvmAddress;
import dummydomain.yetanothercallblocker.data.donation.TronAddress;

/**
 * "About": app name, version, the update check (not in the F-Droid build), license, the
 * links to the fork and the original project and the optional donation buttons (Ko-fi,
 * cryptocurrency). The
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

        // shown only when at least one valid cryptocurrency address is configured
        // (res/values/donation.xml)
        List<Wallet> wallets = getWallets();
        View donateCrypto = findViewById(R.id.about_donate_crypto);
        if (wallets.isEmpty()) {
            donateCrypto.setVisibility(View.GONE);
        } else {
            donateCrypto.setVisibility(View.VISIBLE);
            donateCrypto.setOnClickListener(v -> showCryptoDialog(wallets));
        }
    }

    /** A donation address on one network. */
    private static final class Wallet {
        final @IdRes int buttonId;
        final @StringRes int hint;
        final String address;
        final String qrContent;
        /** for "Open in wallet", {@code null} if there is no common URI scheme */
        final String uri;

        Wallet(@IdRes int buttonId, @StringRes int hint, String address, String qrContent,
               String uri) {
            this.buttonId = buttonId;
            this.hint = hint;
            this.address = address;
            this.qrContent = qrContent;
            this.uri = uri;
        }
    }

    private List<Wallet> getWallets() {
        List<Wallet> wallets = new ArrayList<>();
        String btc = BitcoinAddress.normalize(getString(R.string.donation_btc_address));
        if (btc != null) {
            // upper case lets the QR code use the compact alphanumeric mode
            // (BIP 21 allows an upper-case "BITCOIN:" scheme and bech32 address)
            wallets.add(new Wallet(R.id.donation_crypto_btc, R.string.donation_btc_hint, btc,
                    BitcoinAddress.toUri(btc).toUpperCase(Locale.ROOT),
                    BitcoinAddress.toUri(btc)));
        }
        String trc20 = TronAddress.normalize(getString(R.string.donation_trc20_address));
        if (trc20 != null) {
            wallets.add(new Wallet(R.id.donation_crypto_trc20, R.string.donation_trc20_hint,
                    trc20, trc20, null));
        }
        String bep20 = EvmAddress.normalize(getString(R.string.donation_bep20_address));
        if (bep20 != null) {
            wallets.add(new Wallet(R.id.donation_crypto_bep20, R.string.donation_bep20_hint,
                    bep20, bep20, null));
        }
        return wallets;
    }

    /** Address with a QR code, "Copy" and (for Bitcoin) "Open in wallet". */
    @SuppressLint("InflateParams") // dialog view, there is no parent
    private void showCryptoDialog(List<Wallet> wallets) {
        View view = getLayoutInflater().inflate(R.layout.dialog_donation_crypto, null);
        MaterialButtonToggleGroup networks = view.findViewById(R.id.donation_crypto_networks);
        TextView hint = view.findViewById(R.id.donation_crypto_hint);
        ImageView qr = view.findViewById(R.id.donation_crypto_qr);
        TextView addressView = view.findViewById(R.id.donation_crypto_address);

        for (int id : new int[] {R.id.donation_crypto_btc, R.id.donation_crypto_trc20,
                R.id.donation_crypto_bep20}) {
            view.findViewById(id).setVisibility(View.GONE);
        }
        for (Wallet wallet : wallets) {
            view.findViewById(wallet.buttonId).setVisibility(View.VISIBLE);
        }
        if (wallets.size() < 2) networks.setVisibility(View.GONE);

        Wallet[] selected = {wallets.get(0)};

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.donation_crypto_title)
                .setView(view)
                // the listeners are set below, so that the buttons do not close the dialog
                .setPositiveButton(R.string.donation_btc_copy, null)
                .setNeutralButton(R.string.donation_btc_open_wallet, null)
                .setNegativeButton(R.string.donation_btc_close, null)
                .create();

        Runnable show = () -> {
            Wallet wallet = selected[0];
            hint.setText(wallet.hint);
            addressView.setText(wallet.address);
            Bitmap bitmap = qrCode(wallet.qrContent);
            qr.setImageBitmap(bitmap);
            qr.setVisibility(bitmap != null ? View.VISIBLE : View.GONE);
            View openWallet = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            if (openWallet != null) {
                openWallet.setVisibility(wallet.uri != null ? View.VISIBLE : View.GONE);
            }
        };

        networks.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            for (Wallet wallet : wallets) {
                if (wallet.buttonId == checkedId) selected[0] = wallet;
            }
            show.run();
        });

        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(
                    v -> copyAddress(selected[0].address));
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(selected[0].uri));
                if (!IntentHelper.startActivity(this, intent)) {
                    Toast.makeText(this, R.string.donation_btc_no_wallet,
                            Toast.LENGTH_LONG).show();
                }
            });
            show.run();
        });
        networks.check(selected[0].buttonId);
        dialog.show();
    }

    private void copyAddress(String address) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.donation_crypto_title),
                address));
        // Android 13+ confirms copying itself
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, R.string.donation_btc_copied, Toast.LENGTH_SHORT).show();
        }
    }

    /** Renders a QR code, {@code null} on error. */
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
