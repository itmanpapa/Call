package dummydomain.yetanothercallblocker.data;

import android.content.Context;
import android.os.Bundle;
import android.os.Parcelable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.arch.core.util.Function;
import androidx.paging.DataSource;
import androidx.paging.ItemKeyedDataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dummydomain.yetanothercallblocker.App;

import static dummydomain.yetanothercallblocker.data.CallLogHelper.loadCalls;
import static dummydomain.yetanothercallblocker.data.CallLogHelper.loadLatestCalls;

public class CallLogDataSource extends ItemKeyedDataSource<CallLogDataSource.GroupId, CallLogItemGroup> {

    private static final Logger LOG = LoggerFactory.getLogger(CallLogDataSource.class);

    public static class Factory extends DataSource.Factory<GroupId, CallLogItemGroup> {
        private Function<List<CallLogItem>, List<CallLogItemGroup>> groupConverter;

        private volatile CallLogDataSource ds;

        public Factory(Function<List<CallLogItem>, List<CallLogItemGroup>> groupConverter) {
            this.groupConverter = groupConverter;
        }

        public void setGroupConverter(Function<List<CallLogItem>, List<CallLogItemGroup>> converter) {
            this.groupConverter = converter;
        }

        public void invalidate() {
            LOG.debug("invalidate()");

            CallLogDataSource ds = this.ds;
            if (ds != null) ds.invalidate();
        }

        @NonNull
        @Override
        public DataSource<GroupId, CallLogItemGroup> create() {
            return ds = new CallLogDataSource(groupConverter);
        }
    }

    public static class GroupId {
        private static final String KEY_FIRST = "CallLogDataSource.ComplexId.first";
        private static final String KEY_LAST = "CallLogDataSource.ComplexId.last";
        private static final String KEY_FIRST_DATE = "CallLogDataSource.ComplexId.firstDate";
        private static final String KEY_LAST_DATE = "CallLogDataSource.ComplexId.lastDate";

        final long firstId, lastId;
        final long firstDate, lastDate;

        GroupId(long firstId, long firstDate, long lastId, long lastDate) {
            this.firstId = firstId;
            this.firstDate = firstDate;
            this.lastId = lastId;
            this.lastDate = lastDate;
        }

        public static GroupId fromParcelable(@Nullable Parcelable parcelable) {
            if (parcelable instanceof Bundle) {
                Bundle bundle = (Bundle) parcelable;
                if (bundle.containsKey(KEY_FIRST) && bundle.containsKey(KEY_LAST)
                        && bundle.containsKey(KEY_FIRST_DATE) && bundle.containsKey(KEY_LAST_DATE)) {
                    return new GroupId(bundle.getLong(KEY_FIRST), bundle.getLong(KEY_FIRST_DATE),
                            bundle.getLong(KEY_LAST), bundle.getLong(KEY_LAST_DATE));
                }
            }
            return null;
        }

        public Parcelable saveInstanceState() {
            Bundle bundle = new Bundle();
            bundle.putLong(KEY_FIRST, firstId);
            bundle.putLong(KEY_LAST, lastId);
            bundle.putLong(KEY_FIRST_DATE, firstDate);
            bundle.putLong(KEY_LAST_DATE, lastDate);
            return bundle;
        }

        @SuppressWarnings("NullableProblems")
        @Override
        public String toString() {
            return "ComplexId{" +
                    "firstId=" + firstId +
                    ", lastId=" + lastId +
                    '}';
        }
    }

    private final Function<List<CallLogItem>, List<CallLogItemGroup>> groupConverter;

    private final Map<String, NumberInfo> numberInfoCache = new HashMap<>();

    public CallLogDataSource(Function<List<CallLogItem>, List<CallLogItemGroup>> groupConverter) {
        this.groupConverter = groupConverter;
    }

    @NonNull
    @Override
    public GroupId getKey(@NonNull CallLogItemGroup group) {
        List<CallLogItem> items = group.getItems();
        CallLogItem first = items.get(0), last = items.get(items.size() - 1);
        return new GroupId(first.id, first.timestamp, last.id, last.timestamp);
    }

    @Override
    public void loadInitial(@NonNull LoadInitialParams<GroupId> params,
                            @NonNull LoadInitialCallback<CallLogItemGroup> callback) {
        LOG.debug("loadInitial({}, {})", params.requestedInitialKey, params.requestedLoadSize);

        int size = params.requestedLoadSize * 3 / 2; // compensate for grouping

        List<CallLogItem> items;

        if (params.requestedInitialKey != null) {
            // load something or the list will be empty

            items = new ArrayList<>(size);
            GroupId key = params.requestedInitialKey;
            items.addAll(loadCalls(getContext(), key.firstId, key.firstDate, true, false, size / 2));
            items.addAll(loadCalls(getContext(), key.firstId, key.firstDate, false, true, size / 2));
        } else {
            items = loadLatestCalls(getContext(), size);
        }

        callback.onResult(groupConverter.apply(loadInfo(items)));
    }

    @Override
    public void loadBefore(@NonNull LoadParams<GroupId> params,
                           @NonNull LoadCallback<CallLogItemGroup> callback) {
        LOG.debug("loadBefore({}, {})", params.key, params.requestedLoadSize);

        int size = params.requestedLoadSize * 3 / 2; // compensate for grouping

        List<CallLogItem> items = loadCalls(getContext(),
                params.key.firstId, params.key.firstDate, true, false, size);

        callback.onResult(groupConverter.apply(loadInfo(items)));
    }

    @Override
    public void loadAfter(@NonNull LoadParams<GroupId> params,
                          @NonNull LoadCallback<CallLogItemGroup> callback) {
        LOG.debug("loadAfter({}, {})", params.key, params.requestedLoadSize);

        int size = params.requestedLoadSize * 3 / 2; // compensate for grouping

        List<CallLogItem> items = loadCalls(getContext(),
                params.key.lastId, params.key.lastDate, false, false, size);

        callback.onResult(groupConverter.apply(loadInfo(items)));
    }

    private List<CallLogItem> loadInfo(List<CallLogItem> items) {
        String countryCode = App.getSettings().getCachedAutoDetectedCountryCode();

        for (CallLogItem item : items) {
            NumberInfo numberInfo = numberInfoCache.get(item.number);
            if (numberInfo == null) {
                numberInfo = YacbHolder.getNumberInfo(item.number, countryCode);
                numberInfoCache.put(item.number, numberInfo);
            }

            item.numberInfo = numberInfo;
        }

        return items;
    }

    private Context getContext() {
        return App.getInstance();
    }

}
