package dummydomain.yetanothercallblocker;

import android.annotation.SuppressLint;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputLayout;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dummydomain.yetanothercallblocker.data.YacbHolder;
import dummydomain.yetanothercallblocker.data.rules.CallRule;
import dummydomain.yetanothercallblocker.data.rules.NumberPatterns;
import dummydomain.yetanothercallblocker.data.rules.RuleAction;
import dummydomain.yetanothercallblocker.data.rules.RulePresets;
import dummydomain.yetanothercallblocker.data.rules.RuleType;
import dummydomain.yetanothercallblocker.data.rules.RulesManager;

/**
 * "Rules" screen: the call rules in priority order (the first matching rule decides)
 * with enable switches, one-tap presets, adding/editing in a dialog, swipe to delete
 * (with undo) and long-press drag to reorder.
 */
public class RulesActivity extends BaseActivity {

    /** The types offered by the "Add rule" chooser, in this order. */
    private static final RuleType[] ADDABLE_TYPES = {
            RuleType.NUMBER_PATTERN, RuleType.HIDDEN_NUMBER, RuleType.FOREIGN_NUMBER,
            RuleType.PREMIUM_DE, RuleType.REPEATED_CALLER
    };

    private static final Logger LOG = LoggerFactory.getLogger(RulesActivity.class);

    private final RulesManager rulesManager = YacbHolder.getRulesManager();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private RulesAdapter adapter;
    private TextView emptyView;
    private View rootView;

    private Chip presetHidden;
    private Chip presetPremium;
    private Chip presetRepeated;
    private Chip presetForeign;

    public static Intent getIntent(Context context) {
        return new Intent(context, RulesActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rules);

        rootView = findViewById(R.id.rules_root);
        emptyView = findViewById(R.id.rules_empty);

        presetHidden = findViewById(R.id.rules_preset_hidden);
        presetPremium = findViewById(R.id.rules_preset_premium);
        presetRepeated = findViewById(R.id.rules_preset_repeated);
        presetForeign = findViewById(R.id.rules_preset_foreign);
        presetHidden.setOnClickListener(v -> addPreset(RulePresets.Preset.HIDDEN_NUMBERS));
        presetPremium.setOnClickListener(v -> addPreset(RulePresets.Preset.GERMAN_PREMIUM));
        presetRepeated.setOnClickListener(v -> addPreset(RulePresets.Preset.REPEATED_CALLER));
        presetForeign.setOnClickListener(v -> addPreset(RulePresets.Preset.FOREIGN_NUMBERS));

        findViewById(R.id.rules_add_fab).setOnClickListener(v -> showTypeChooser());

        adapter = new RulesAdapter();
        RecyclerView recyclerView = findViewById(R.id.rules_list);
        recyclerView.setAdapter(adapter);
        new ItemTouchHelper(new TouchCallback()).attachToRecyclerView(recyclerView);

        reload();
    }

    @Override
    protected void onDestroy() {
        // a running change is allowed to finish, its result is just not shown
        executor.shutdown();
        super.onDestroy();
    }

    @Override
    public boolean onSupportNavigateUp() {
        // always started from the settings screen, which is still in the back stack
        finish();
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        PermissionHelper.handlePermissionsResult(this, requestCode, permissions, grantResults,
                false, true, false);
    }

    // background work

    private interface BackgroundAction {
        void run() throws Exception;
    }

    /**
     * Runs the action (may be null) on the background thread, then shows the rules.
     */
    private void runInBackground(BackgroundAction action) {
        if (executor.isShutdown()) return;
        executor.execute(() -> {
            boolean failed = false;
            try {
                if (action != null) action.run();
            } catch (Exception e) {
                LOG.warn("runInBackground() action failed", e);
                failed = true;
            }

            List<CallRule> rules;
            try {
                rules = rulesManager.getRules();
            } catch (Exception e) {
                LOG.error("runInBackground() failed to load rules", e);
                rules = Collections.emptyList();
            }

            List<CallRule> result = rules;
            boolean showError = failed;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (showError) {
                    Toast.makeText(this, R.string.rules_save_failed, Toast.LENGTH_LONG).show();
                }
                showRules(result);
            });
        });
    }

    private void reload() {
        runInBackground(null);
    }

    private void showRules(List<CallRule> rules) {
        adapter.setItems(rules);
        emptyView.setVisibility(rules.isEmpty() ? View.VISIBLE : View.GONE);

        presetHidden.setEnabled(!RulePresets.isPresent(rules, RulePresets.Preset.HIDDEN_NUMBERS));
        presetPremium.setEnabled(!RulePresets.isPresent(rules, RulePresets.Preset.GERMAN_PREMIUM));
        presetRepeated.setEnabled(!RulePresets.isPresent(rules, RulePresets.Preset.REPEATED_CALLER));
        presetForeign.setEnabled(!RulePresets.isPresent(rules, RulePresets.Preset.FOREIGN_NUMBERS));
    }

    // actions

    private void addPreset(RulePresets.Preset preset) {
        addRule(RulePresets.create(preset));
    }

    private void addRule(CallRule rule) {
        runInBackground(() -> rulesManager.add(rule));
        onBlockingRuleMaybeEnabled(rule);
    }

    private void saveRule(CallRule rule) {
        if (rule.getId() == 0) {
            addRule(rule);
        } else {
            runInBackground(() -> rulesManager.update(rule));
            onBlockingRuleMaybeEnabled(rule);
        }
    }

    private void onEnabledChanged(CallRule rule, boolean enabled) {
        runInBackground(() -> rulesManager.setEnabled(rule.getId(), enabled));
        if (enabled) onBlockingRuleMaybeEnabled(rule);
    }

    private void onBlockingRuleMaybeEnabled(CallRule rule) {
        if (rule.isEnabled() && rule.isBlocking()) {
            PermissionHelper.checkPermissions(this, false, true, false);
        }
    }

    private void deleteRule(int position) {
        CallRule rule = adapter.getItem(position);
        if (rule == null) return;

        runInBackground(() -> rulesManager.delete(rule.getId()));

        Snackbar.make(rootView, getString(R.string.rules_deleted,
                RuleTexts.getTitle(this, rule)), Snackbar.LENGTH_LONG)
                .setAction(R.string.rules_undo,
                        v -> runInBackground(() -> rulesManager.insert(position, rule)))
                .show();
    }

    private void saveOrder(List<CallRule> rules) {
        List<Long> ids = new ArrayList<>(rules.size());
        for (CallRule rule : rules) ids.add(rule.getId());
        runInBackground(() -> rulesManager.reorder(ids));
    }

    // dialogs

    private static int getTypeNameRes(RuleType type) {
        switch (type) {
            case HIDDEN_NUMBER:
                return R.string.rules_type_hidden;
            case FOREIGN_NUMBER:
                return R.string.rules_type_foreign;
            case PREMIUM_DE:
                return R.string.rules_type_premium_de;
            case REPEATED_CALLER:
                return R.string.rules_type_repeated;
            case NUMBER_PATTERN:
            default:
                return R.string.rules_type_pattern;
        }
    }

    private void showTypeChooser() {
        CharSequence[] items = new CharSequence[ADDABLE_TYPES.length];
        for (int i = 0; i < ADDABLE_TYPES.length; i++) {
            items[i] = getString(getTypeNameRes(ADDABLE_TYPES[i]));
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.rules_add)
                .setItems(items, (d, which) -> showEditDialog(
                        CallRule.builder(ADDABLE_TYPES[which]).build()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Shows the add/edit dialog; a rule with id 0 is added, otherwise updated.
     */
    private void showEditDialog(CallRule rule) {
        RuleType type = rule.getType();

        View view = LayoutInflater.from(this).inflate(R.layout.dialog_rule, null);
        TextView description = view.findViewById(R.id.rule_type_description);
        TextInputLayout patternsLayout = view.findViewById(R.id.rule_patterns_layout);
        EditText patternsEdit = view.findViewById(R.id.rule_patterns);
        TextInputLayout windowLayout = view.findViewById(R.id.rule_window_layout);
        EditText windowEdit = view.findViewById(R.id.rule_window);
        View actionGroupTitle = view.findViewById(R.id.rule_action_title);
        MaterialButtonToggleGroup actionGroup = view.findViewById(R.id.rule_action_group);
        CheckBox exceptContacts = view.findViewById(R.id.rule_except_contacts);
        CheckBox scheduleEnabled = view.findViewById(R.id.rule_schedule_enabled);
        View scheduleTimes = view.findViewById(R.id.rule_schedule_times);
        Button scheduleStart = view.findViewById(R.id.rule_schedule_start);
        Button scheduleEnd = view.findViewById(R.id.rule_schedule_end);
        EditText labelEdit = view.findViewById(R.id.rule_label);

        description.setText(getTypeDescription(type));

        boolean patternType = type == RuleType.NUMBER_PATTERN;
        patternsLayout.setVisibility(patternType ? View.VISIBLE : View.GONE);
        patternsEdit.setText(rule.getPatterns());

        boolean repeatedType = type == RuleType.REPEATED_CALLER;
        windowLayout.setVisibility(repeatedType ? View.VISIBLE : View.GONE);
        windowEdit.setText(String.valueOf(rule.getRepeatWindowMinutes()));

        // a repeated-caller rule always lets the call through
        actionGroupTitle.setVisibility(repeatedType ? View.GONE : View.VISIBLE);
        actionGroup.setVisibility(repeatedType ? View.GONE : View.VISIBLE);
        actionGroup.check(rule.getAction() == RuleAction.ALLOW
                ? R.id.rule_action_allow : R.id.rule_action_block);

        // contacts have no hidden numbers; ALLOW rules don't need the option
        boolean contactsOptionApplies = type != RuleType.HIDDEN_NUMBER && !repeatedType;
        exceptContacts.setChecked(rule.isExceptContacts());
        Runnable updateContactsOption = () -> exceptContacts.setVisibility(contactsOptionApplies
                && actionGroup.getCheckedButtonId() == R.id.rule_action_block
                ? View.VISIBLE : View.GONE);
        updateContactsOption.run();
        actionGroup.addOnButtonCheckedListener((group, checkedId, isChecked) ->
                updateContactsOption.run());

        int[] times = {rule.getScheduleStart(), rule.getScheduleEnd()};
        Runnable updateTimes = () -> {
            scheduleStart.setText(getString(R.string.rules_schedule_from,
                    RuleTexts.formatTime(this, times[0])));
            scheduleEnd.setText(getString(R.string.rules_schedule_to,
                    RuleTexts.formatTime(this, times[1])));
        };
        updateTimes.run();
        scheduleEnabled.setChecked(rule.isScheduleEnabled());
        scheduleTimes.setVisibility(rule.isScheduleEnabled() ? View.VISIBLE : View.GONE);
        scheduleEnabled.setOnCheckedChangeListener((buttonView, isChecked) ->
                scheduleTimes.setVisibility(isChecked ? View.VISIBLE : View.GONE));
        scheduleStart.setOnClickListener(v -> pickTime(times[0], minute -> {
            times[0] = minute;
            updateTimes.run();
        }));
        scheduleEnd.setOnClickListener(v -> pickTime(times[1], minute -> {
            times[1] = minute;
            updateTimes.run();
        }));

        labelEdit.setText(rule.getLabel());

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(rule.getId() == 0 ? R.string.rules_add : R.string.rules_edit)
                .setView(view)
                .setPositiveButton(R.string.rules_save, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        // validate before closing the dialog
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    CallRule.Builder builder = rule.toBuilder();

                    if (patternType) {
                        String patterns = patternsEdit.getText().toString();
                        if (!NumberPatterns.isValidList(patterns)) {
                            patternsLayout.setError(getString(R.string.rules_patterns_invalid));
                            return;
                        }
                        patternsLayout.setError(null);
                        builder.patterns(patterns);
                    }

                    if (repeatedType) {
                        int window;
                        try {
                            window = Integer.parseInt(windowEdit.getText().toString().trim());
                        } catch (NumberFormatException e) {
                            window = -1;
                        }
                        if (window < CallRule.MIN_REPEAT_WINDOW_MINUTES
                                || window > CallRule.MAX_REPEAT_WINDOW_MINUTES) {
                            windowLayout.setError(getString(R.string.rules_window_invalid,
                                    CallRule.MIN_REPEAT_WINDOW_MINUTES,
                                    CallRule.MAX_REPEAT_WINDOW_MINUTES));
                            return;
                        }
                        windowLayout.setError(null);
                        builder.repeatWindowMinutes(window);
                    } else {
                        builder.action(actionGroup.getCheckedButtonId() == R.id.rule_action_allow
                                ? RuleAction.ALLOW : RuleAction.BLOCK);
                    }

                    builder.exceptContacts(exceptContacts.isChecked())
                            .schedule(scheduleEnabled.isChecked(), times[0], times[1])
                            .label(labelEdit.getText().toString());

                    dialog.dismiss();
                    saveRule(builder.build());
                }));
        dialog.show();
    }

    private String getTypeDescription(RuleType type) {
        switch (type) {
            case HIDDEN_NUMBER:
                return getString(R.string.rules_type_hidden_description);
            case FOREIGN_NUMBER:
                return getString(R.string.rules_type_foreign_description);
            case PREMIUM_DE:
                return getString(R.string.rules_type_premium_de_description);
            case REPEATED_CALLER:
                return getString(R.string.rules_type_repeated_description);
            case NUMBER_PATTERN:
            default:
                return getString(R.string.rules_type_pattern_description);
        }
    }

    private interface TimeCallback {
        void onTime(int minuteOfDay);
    }

    private void pickTime(int minuteOfDay, TimeCallback callback) {
        new TimePickerDialog(this,
                (picker, hourOfDay, minute) -> callback.onTime(hourOfDay * 60 + minute),
                minuteOfDay / 60, minuteOfDay % 60,
                android.text.format.DateFormat.is24HourFormat(this))
                .show();
    }

    // list

    /** Swipe (either side) to delete, long-press drag to reorder. */
    private class TouchCallback extends ItemTouchHelper.SimpleCallback {

        private boolean moved;

        TouchCallback() {
            super(ItemTouchHelper.UP | ItemTouchHelper.DOWN,
                    ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT);
        }

        @Override
        public boolean onMove(@NonNull RecyclerView recyclerView,
                              @NonNull RecyclerView.ViewHolder viewHolder,
                              @NonNull RecyclerView.ViewHolder target) {
            int from = viewHolder.getBindingAdapterPosition();
            int to = target.getBindingAdapterPosition();
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false;
            adapter.move(from, to);
            moved = true;
            return true;
        }

        @Override
        public void clearView(@NonNull RecyclerView recyclerView,
                              @NonNull RecyclerView.ViewHolder viewHolder) {
            super.clearView(recyclerView, viewHolder);
            if (moved) {
                moved = false;
                saveOrder(adapter.getItems());
            }
        }

        @Override
        public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
            int position = viewHolder.getBindingAdapterPosition();
            if (position == RecyclerView.NO_POSITION) return;
            deleteRule(position);
            adapter.remove(position);
        }
    }

    private class RulesAdapter extends RecyclerView.Adapter<RulesAdapter.ViewHolder> {

        private List<CallRule> items = new ArrayList<>();

        @SuppressLint("NotifyDataSetChanged") // the list is tiny
        void setItems(List<CallRule> items) {
            this.items = new ArrayList<>(items);
            notifyDataSetChanged();
        }

        List<CallRule> getItems() {
            return new ArrayList<>(items);
        }

        CallRule getItem(int position) {
            return position >= 0 && position < items.size() ? items.get(position) : null;
        }

        void move(int from, int to) {
            items.add(to, items.remove(from));
            notifyItemMoved(from, to);
        }

        void remove(int position) {
            items.remove(position);
            notifyItemRemoved(position);
            emptyView.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.rule_item, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            holder.bind(items.get(position));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {

            final TextView title;
            final TextView summary;
            final MaterialSwitch enabled;

            ViewHolder(View view) {
                super(view);
                title = view.findViewById(R.id.rule_title);
                summary = view.findViewById(R.id.rule_summary);
                enabled = view.findViewById(R.id.rule_enabled);
            }

            void bind(CallRule rule) {
                Context context = itemView.getContext();
                itemView.setOnClickListener(v -> showEditDialog(rule));

                title.setText(RuleTexts.getTitle(context, rule));
                String summaryText = RuleTexts.getSummary(context, rule);
                summary.setText(summaryText);
                summary.setVisibility(TextUtils.isEmpty(summaryText) ? View.GONE : View.VISIBLE);

                enabled.setOnCheckedChangeListener(null);
                enabled.setChecked(rule.isEnabled());
                enabled.setOnCheckedChangeListener((buttonView, isChecked) ->
                        onEnabledChanged(rule, isChecked));
            }
        }
    }

}
