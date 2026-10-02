package dummydomain.yetanothercallblocker;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.core.util.Supplier;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class GenericSettings {

    protected final Context context;
    protected final SharedPreferences pref;

    public GenericSettings(Context context, String name) {
        this(context, context.getSharedPreferences(name, Context.MODE_PRIVATE));
    }

    public GenericSettings(Context context, SharedPreferences pref) {
        this.context = context;
        this.pref = pref;
    }

    public boolean getBoolean(String key) {
        return getBoolean(key, false);
    }

    public boolean getBoolean(String key, boolean defValue) {
        return pref.getBoolean(key, defValue);
    }

    public void setBoolean(String key, boolean value) {
        pref.edit().putBoolean(key, value).apply();
    }

    public int getInt(String key, int defValue) {
        return pref.getInt(key, defValue);
    }

    public void setInt(String key, int value) {
        pref.edit().putInt(key, value).apply();
    }

    public long getLong(String key, long defValue) {
        return pref.getLong(key, defValue);
    }

    public void setLong(String key, long value) {
        pref.edit().putLong(key, value).apply();
    }

    public String getString(String key) {
        return getString(key, null);
    }

    public String getString(String key, String defValue) {
        return pref.getString(key, defValue);
    }

    public String getNonEmptyString(String key, String defValue) {
        String value = getString(key);
        if (TextUtils.isEmpty(value)) value = defValue;
        return value;
    }

    public void setString(String key, String value) {
        pref.edit().putString(key, value).apply();
    }

    public Set<String> getStringSet(String key, Set<String> defValue) {
        return pref.getStringSet(key, defValue);
    }

    public Set<String> getStringSet(String key, Supplier<Set<String>> defValueSupplier) {
        Set<String> val = pref.getStringSet(key, null);
        return val != null ? val : defValueSupplier.get();
    }

    public void setStringSet(String key, Set<String> value) {
        pref.edit().putStringSet(key, value).apply();
    }

    public boolean isSet(String key) {
        return pref.contains(key);
    }

    public void unset(String key) {
        pref.edit().remove(key).apply();
    }

    /** @return a snapshot of all stored values (e.g. for a backup) */
    public Map<String, ?> getAll() {
        return new HashMap<>(pref.getAll());
    }

    /**
     * Removes and sets several values in one synchronous commit (e.g. restoring a backup).
     * Supported value types: Boolean, Integer, Long, Float, String and Set of String.
     *
     * @return whether the values were written
     */
    @SuppressWarnings("unchecked")
    public boolean applyAll(Map<String, ?> values, Collection<String> keysToRemove) {
        SharedPreferences.Editor editor = pref.edit();
        for (String key : keysToRemove) editor.remove(key);
        for (Map.Entry<String, ?> e : values.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Boolean) editor.putBoolean(e.getKey(), (Boolean) v);
            else if (v instanceof Integer) editor.putInt(e.getKey(), (Integer) v);
            else if (v instanceof Long) editor.putLong(e.getKey(), (Long) v);
            else if (v instanceof Float) editor.putFloat(e.getKey(), (Float) v);
            else if (v instanceof String) editor.putString(e.getKey(), (String) v);
            else if (v instanceof Set) editor.putStringSet(e.getKey(), (Set<String>) v);
        }
        return editor.commit();
    }

}
