package dummydomain.yetanothercallblocker;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.google.android.material.materialswitch.MaterialSwitch;

import dummydomain.yetanothercallblocker.data.sources.PhoneBlockReportQueue;

/**
 * The "send my marks to PhoneBlock" switch and the counter of sent reports in the
 * PhoneBlock section of {@link SourceDetailsActivity}.
 */
final class PhoneBlockReportSection {

    /** Counters, loaded in the background. */
    static final class Counts {
        int sent;
        int pending;
        String lastError;
    }

    private final Context context;
    private final MaterialSwitch enabledSwitch;
    private final TextView counterView;
    private boolean binding;

    /**
     * Adds the views to the section, before its last child (the PhoneBlock terms).
     */
    PhoneBlockReportSection(Context context, ViewGroup section) {
        this.context = context;
        View view = LayoutInflater.from(context)
                .inflate(R.layout.phoneblock_report_section, section, false);
        section.addView(view, Math.max(0, section.getChildCount() - 1));

        enabledSwitch = view.findViewById(R.id.pbreport_enabled);
        counterView = view.findViewById(R.id.pbreport_counter);

        enabledSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (binding) return;
            PhoneBlockReports.setEnabled(context, isChecked);
        });
    }

    /** Runs in the background. */
    static Counts load() {
        Counts counts = new Counts();
        PhoneBlockReportQueue queue = PhoneBlockReports.getQueue();
        counts.sent = queue.getSentCount();
        counts.pending = queue.getPendingCount();
        counts.lastError = PhoneBlockReports.getLastError();
        return counts;
    }

    void bind(Counts counts, boolean hasToken) {
        binding = true;
        try {
            enabledSwitch.setChecked(PhoneBlockReports.isEnabled());
            // reporting needs the key
            enabledSwitch.setEnabled(hasToken);
        } finally {
            binding = false;
        }

        if (counts == null) {
            counterView.setVisibility(View.GONE);
            return;
        }
        StringBuilder text = new StringBuilder(context.getResources().getQuantityString(
                R.plurals.pbreport_sent_count, counts.sent, counts.sent));
        if (counts.pending > 0) {
            text.append('\n').append(context.getResources().getQuantityString(
                    R.plurals.pbreport_pending_count, counts.pending, counts.pending));
            if (counts.lastError != null) {
                text.append('\n').append(context.getString(R.string.pbreport_last_error,
                        counts.lastError));
            }
        }
        counterView.setText(text);
        counterView.setVisibility(View.VISIBLE);
    }

}
