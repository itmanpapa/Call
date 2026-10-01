package dummydomain.yetanothercallblocker.data;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.CallLog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dummydomain.yetanothercallblocker.PermissionHelper;

public class CallLogHelper {

    private static final String[] QUERY_PROJECTION = new String[]{
            CallLog.Calls._ID, CallLog.Calls.TYPE, CallLog.Calls.NUMBER,
            CallLog.Calls.DATE, CallLog.Calls.DURATION
    };

    /**
     * Loads calls ordered from the newest to the oldest.
     *
     * @param anchorId   id of the anchor call or null to load from the newest call
     * @param anchorDate date of the anchor call (ignored if {@code anchorId} is null)
     * @param before     load calls newer than the anchor (otherwise older)
     * @param inclusive  include the anchor call itself (only when loading older calls)
     */
    public static List<CallLogItem> loadLatestCalls(Context context, int limit) {
        return loadCalls(context, null, 0, false, false, limit);
    }

    public static List<CallLogItem> loadCalls(Context context, Long anchorId, long anchorDate,
                                              boolean before, boolean inclusive, int limit) {
        if (!PermissionHelper.hasCallLogPermission(context)) {
            return new ArrayList<>();
        }

        boolean reverseOrder = false;

        String selection;
        String[] selectionArgs;
        if (anchorId != null) {
            // the anchor is (date, id): ids alone don't follow the date order
            // (e.g. after a call log restore)
            String date = CallLog.Calls.DATE, id = CallLog.Calls._ID;
            if (before) {
                selection = date + " > ? OR (" + date + " = ? AND " + id + " > ?)";
                reverseOrder = true;
            } else {
                selection = date + " < ? OR (" + date + " = ? AND " + id
                        + (inclusive ? " <= ?)" : " < ?)");
            }
            String dateArg = String.valueOf(anchorDate);
            selectionArgs = new String[]{dateArg, dateArg, String.valueOf(anchorId)};
        } else {
            selection = null;
            selectionArgs = null;
        }

        Uri uri = CallLog.Calls.CONTENT_URI;

        String direction = reverseOrder ? " ASC" : " DESC";
        String sortOrder = CallLog.Calls.DATE + direction + ", " + CallLog.Calls._ID + direction;

        // should probably work since JELLY_BEAN_MR1
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            uri = uri.buildUpon()
                    .appendQueryParameter(CallLog.Calls.LIMIT_PARAM_KEY, String.valueOf(limit))
                    .build();
        } else {
            sortOrder += " limit " + limit;
        }

        List<CallLogItem> items = new ArrayList<>(limit);

        try (Cursor cursor = context.getContentResolver()
                .query(uri, QUERY_PROJECTION, selection, selectionArgs, sortOrder)) {
            if (cursor != null) {
                int idIndex = cursor.getColumnIndexOrThrow(CallLog.Calls._ID);
                int typeIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE);
                int numberIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER);
                int dateIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE);
                int durationIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION);

                while (cursor.moveToNext()) {
                    long id = cursor.getLong(idIndex);
                    int callType = cursor.getInt(typeIndex);
                    String number = cursor.getString(numberIndex);
                    long callDate = cursor.getLong(dateIndex);
                    long callDuration = cursor.getLong(durationIndex);

                    items.add(new CallLogItem(id, CallLogItem.Type.fromProviderType(callType),
                            number, callDate, callDuration));
                }
            }
        }

        if (reverseOrder) {
            Collections.reverse(items);
        }

        return items;
    }

}
