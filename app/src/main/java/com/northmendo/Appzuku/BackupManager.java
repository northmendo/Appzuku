package com.northmendo.Appzuku;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Set;

import static com.northmendo.Appzuku.PreferenceKeys.*;

public class BackupManager {
    private final Context context;
    private final SharedPreferences prefs;

    public BackupManager(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PreferenceKeys.PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    public String createBackupJson() {
        try {
            JSONObject root = new JSONObject();
            root.put(KEY_HIDDEN_APPS, new JSONArray(prefs.getStringSet(KEY_HIDDEN_APPS, new HashSet<>())));
            root.put(KEY_WHITELISTED_APPS, new JSONArray(prefs.getStringSet(KEY_WHITELISTED_APPS, new HashSet<>())));
            root.put(KEY_BLACKLISTED_APPS, new JSONArray(prefs.getStringSet(KEY_BLACKLISTED_APPS, new HashSet<>())));
            root.put(KEY_AUTOSTART_DISABLED_APPS, new JSONArray(prefs.getStringSet(KEY_AUTOSTART_DISABLED_APPS, new HashSet<>())));
            return root.toString(4); // Pretty print with 4 spaces
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public boolean restoreBackupJson(String json) {
        try {
            JSONObject root = new JSONObject(json);
            SharedPreferences.Editor editor = prefs.edit();

            restoreSet(editor, root, KEY_HIDDEN_APPS);
            restoreSet(editor, root, KEY_WHITELISTED_APPS);
            restoreSet(editor, root, KEY_BLACKLISTED_APPS);
            restoreSet(editor, root, KEY_AUTOSTART_DISABLED_APPS);

            editor.apply();
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private void restoreSet(SharedPreferences.Editor editor, JSONObject root, String key) throws Exception {
        if (root.has(key)) {
            JSONArray array = root.getJSONArray(key);
            Set<String> set = new HashSet<>();
            for (int i = 0; i < array.length(); i++) {
                set.add(array.getString(i));
            }
            editor.putStringSet(key, set);
        }
    }
}
