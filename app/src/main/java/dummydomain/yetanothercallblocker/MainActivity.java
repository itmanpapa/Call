package dummydomain.yetanothercallblocker;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcelable;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.arch.core.util.Function;
import androidx.lifecycle.LiveData;
import androidx.paging.LivePagedListBuilder;
import androidx.paging.PagedList;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.List;
import java.util.Objects;

import dummydomain.yetanothercallblocker.data.CallLogDataSource;
import dummydomain.yetanothercallblocker.data.CallLogItem;
import dummydomain.yetanothercallblocker.data.CallLogItemGroup;
import dummydomain.yetanothercallblocker.data.NumberInfo;
import dummydomain.yetanothercallblocker.data.UserMark;
import dummydomain.yetanothercallblocker.data.YacbHolder;
import dummydomain.yetanothercallblocker.event.CallEndedEvent;
import dummydomain.yetanothercallblocker.event.MainDbDownloadFinishedEvent;
import dummydomain.yetanothercallblocker.event.MainDbDownloadingEvent;
import dummydomain.yetanothercallblocker.event.SecondaryDbUpdateFinished;
import dummydomain.yetanothercallblocker.event.UserMarksChangedEvent;
import dummydomain.yetanothercallblocker.work.TaskService;
import dummydomain.yetanothercallblocker.work.UpdateScheduler;

public class MainActivity extends BaseActivity {

    private static final String STATE_CALL_LOG_DATA_LAST_KEY = "call_log_data_last_key";
    private static final String STATE_CALL_LOG_LAYOUT_MANAGER = "call_log_layout_manager";

    private final Settings settings = App.getSettings();

    private final UpdateScheduler updateScheduler = UpdateScheduler.get(App.getInstance());

    private CallLogItemRecyclerViewAdapter callLogAdapter;
    private RecyclerView recyclerView;
    private CallLogDataSource.Factory callLogDsFactory;

    private Parcelable callLogLayoutManagerState;

    private AsyncTask<Void, Void, Boolean> checkMainDbTask;

    private boolean activityFirstStart = true;

    @Override
    protected int getNavigationItemId() {
        return R.id.nav_call_log;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        callLogAdapter = new CallLogItemRecyclerViewAdapter(this::onCallLogItemClicked);
        recyclerView = findViewById(R.id.callLogList);
        recyclerView.setAdapter(callLogAdapter);
        new ItemTouchHelper(new SwipeToBlockCallback(this, new SwipeToBlockCallback.Listener() {
            @Override
            public boolean canBlock(int position) {
                CallLogItemGroup group = callLogAdapter.getGroup(position);
                return group != null && !group.getItems().get(0).numberInfo.noNumber;
            }

            @Override
            public void onBlock(int position) {
                CallLogItemGroup group = callLogAdapter.getGroup(position);
                if (group != null) blockNumber(group.getItems().get(0).numberInfo);
            }

            @Override
            public boolean canMarkNotSpam(int position) {
                return canBlock(position) && YacbHolder.getUserMarksStore() != null;
            }

            @Override
            public void onMarkNotSpam(int position) {
                CallLogItemGroup group = callLogAdapter.getGroup(position);
                if (group != null) markNotSpam(group.getItems().get(0).numberInfo);
            }
        })).attachToRecyclerView(recyclerView);

        callLogDsFactory = new CallLogDataSource.Factory(getCallLogGroupConverter());

        PagedList.Config config = new PagedList.Config.Builder()
                .setPageSize(30)
                .setInitialLoadSizeHint(30)
                .setPrefetchDistance(15)
                .build();

        CallLogDataSource.GroupId initialKey = null;
        if (savedInstanceState != null) {
            initialKey = CallLogDataSource.GroupId.fromParcelable(
                    savedInstanceState.getParcelable(STATE_CALL_LOG_DATA_LAST_KEY));

            callLogLayoutManagerState = savedInstanceState
                    .getParcelable(STATE_CALL_LOG_LAYOUT_MANAGER);
        }

        LiveData<PagedList<CallLogItemGroup>> callLogData
                = new LivePagedListBuilder<>(callLogDsFactory, config)
                .setInitialLoadKey(initialKey)
                .build();

        callLogData.observe(this, data -> {
            callLogAdapter.submitList(data);

            if (callLogLayoutManagerState != null) {
                Objects.requireNonNull(recyclerView.getLayoutManager())
                        .onRestoreInstanceState(callLogLayoutManagerState);

                callLogLayoutManagerState = null;
            }
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.activity_main, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.findItem(R.id.menu_show_notifications).setChecked(
                settings.getIncomingCallNotifications());

        menu.findItem(R.id.menu_block_calls).setChecked(
                settings.getBlockNegativeSiaNumbers());

        menu.findItem(R.id.menu_auto_updates).setChecked(
                updateScheduler.isAutoUpdateScheduled());

        menu.findItem(R.id.menu_use_contacts).setChecked(
                settings.getUseContacts());

        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        PermissionHelper.handlePermissionsResult(this, requestCode, permissions, grantResults,
                settings.getIncomingCallNotifications(), settings.getCallBlockingEnabled(),
                settings.getUseContacts());

        updateCallLogVisibility();
        reloadCallLog();
    }

    @Override
    protected void onStart() {
        super.onStart();

        EventUtils.register(this);

        startCheckMainDbTask();

        checkPermissions();

        updateCallLogVisibility();
        if (activityFirstStart) {
            activityFirstStart = false;
        } else {
            callLogDsFactory.setGroupConverter(getCallLogGroupConverter());
            reloadCallLog();
        }
    }

    @Override
    protected void onStop() {
        EventUtils.unregister(this);

        super.onStop();
    }

    @Override
    protected void onDestroy() {
        cancelCheckMainDbTask();

        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);

        PagedList<CallLogItemGroup> currentList = callLogAdapter.getCurrentList();
        if (currentList != null) {
            Object lastKey = currentList.getLastKey();
            if (lastKey != null) {
                outState.putParcelable(STATE_CALL_LOG_DATA_LAST_KEY,
                        ((CallLogDataSource.GroupId) lastKey).saveInstanceState());
            }
        }

        outState.putParcelable(STATE_CALL_LOG_LAYOUT_MANAGER,
                Objects.requireNonNull(recyclerView.getLayoutManager()).onSaveInstanceState());
    }

    @Subscribe(threadMode = ThreadMode.MAIN_ORDERED)
    public void onCallEvent(CallEndedEvent event) {
        new Handler(getMainLooper()).postDelayed(this::reloadCallLog, 1000);
    }

    @Subscribe(threadMode = ThreadMode.MAIN_ORDERED)
    public void onMainDbDownloadFinished(MainDbDownloadFinishedEvent event) {
        reloadCallLog();
    }

    @Subscribe(threadMode = ThreadMode.MAIN_ORDERED)
    public void onSecondaryDbUpdateFinished(SecondaryDbUpdateFinished event) {
        if (event.updated) reloadCallLog();
    }

    private void checkPermissions() {
        PermissionHelper.checkPermissions(this,
                settings.getIncomingCallNotifications(), settings.getCallBlockingEnabled(),
                settings.getUseContacts());
    }

    private void startCheckMainDbTask() {
        cancelCheckMainDbTask();
        @SuppressLint("StaticFieldLeak")
        AsyncTask<Void, Void, Boolean> checkMainDbTask = this.checkMainDbTask
                = new AsyncTask<Void, Void, Boolean>() {
            @Override
            protected Boolean doInBackground(Void... voids) {
                return YacbHolder.getCommunityDatabase().isOperational();
            }

            @Override
            protected void onPostExecute(Boolean result) {
                if (!result && EventUtils.bus().getStickyEvent(MainDbDownloadingEvent.class) == null) {
                    showNoMainDbDialog();
                }
            }
        };
        checkMainDbTask.execute();
    }

    private void cancelCheckMainDbTask() {
        if (checkMainDbTask != null) {
            checkMainDbTask.cancel(true);
            checkMainDbTask = null;
        }
    }

    private void showNoMainDbDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.no_main_db_title)
                .setMessage(R.string.no_main_db_text)
                .setPositiveButton(R.string.download_main_db,
                        (d, w) -> downloadMainDb())
                .setNegativeButton(R.string.no, null)
                .show();
    }

    public void downloadMainDb() {
        TaskService.start(this, TaskService.TASK_DOWNLOAD_MAIN_DB);
    }

    public void onShowNotificationsChanged(MenuItem item) {
        settings.setIncomingCallNotifications(!item.isChecked());
        checkPermissions();
    }

    public void onBlockCallsChanged(MenuItem item) {
        settings.setBlockNegativeSiaNumbers(!item.isChecked());
        checkPermissions();
    }

    public void onAutoUpdatesChanged(MenuItem item) {
        if (!item.isChecked()) updateScheduler.scheduleAutoUpdates();
        else updateScheduler.cancelAutoUpdateWorker();
    }

    public void onUseContactsChanged(MenuItem item) {
        settings.setUseContacts(!item.isChecked());
        checkPermissions();
        reloadCallLog();
    }

    public void onOpenAbout(MenuItem item) {
        startActivity(new Intent(this, AboutActivity.class));
    }

    private void blockNumber(NumberInfo numberInfo) {
        String name = numberInfo.featuredDatabaseItem != null
                ? numberInfo.featuredDatabaseItem.getName() : null;
        startActivity(EditBlacklistItemActivity.getIntent(this, name, numberInfo.number));
    }

    private void markNotSpam(NumberInfo numberInfo) {
        UserMarkActions.Change change = UserMarkActions.setMark(
                numberInfo, UserMark.Type.NOT_SPAM);
        if (change == null) {
            Snackbar.make(recyclerView, R.string.user_mark_save_failed, Snackbar.LENGTH_LONG)
                    .setAnchorView(R.id.base_bottom_navigation)
                    .show();
            return;
        }

        // the call log is reloaded by onUserMarksChanged()
        Snackbar.make(recyclerView, R.string.user_mark_set_not_spam, Snackbar.LENGTH_LONG)
                .setAnchorView(R.id.base_bottom_navigation)
                .setAction(R.string.user_mark_undo, v -> UserMarkActions.undo(change))
                .show();
    }

    @Subscribe(threadMode = ThreadMode.MAIN_ORDERED)
    public void onUserMarksChanged(UserMarksChangedEvent event) {
        reloadCallLog();
    }

    private void onCallLogItemClicked(CallLogItemGroup item) {
        InfoDialogHelper.showDialog(this, item.getItems().get(0).numberInfo, null);
    }

    private void reloadCallLog() {
        callLogDsFactory.invalidate();
    }

    private void updateCallLogVisibility() {
        setCallLogVisibility(PermissionHelper.hasCallLogPermission(this));
    }

    private void setCallLogVisibility(boolean visible) {
        findViewById(R.id.callLogPermissionMessage)
                .setVisibility(visible ? View.GONE : View.VISIBLE);

        findViewById(R.id.callLogList).setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private Function<List<CallLogItem>, List<CallLogItemGroup>> getCallLogGroupConverter() {
        Function<List<CallLogItem>, List<CallLogItemGroup>> converter;
        switch (settings.getCallLogGrouping()) {
            case Settings.PREF_CALL_LOG_GROUPING_NONE:
                converter = CallLogItemGroup::noGrouping;
                break;
            case Settings.PREF_CALL_LOG_GROUPING_DAY:
                converter = CallLogItemGroup::groupInDay;
                break;
            default:
                converter = CallLogItemGroup::groupConsecutive;
                break;
        }
        return converter;
    }

}
