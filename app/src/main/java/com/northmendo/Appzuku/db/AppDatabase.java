package com.northmendo.Appzuku.db;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(entities = {AppStats.class}, version = 1)
public abstract class AppDatabase extends RoomDatabase {
    private static AppDatabase instance;

    public abstract AppStatsDao appStatsDao();

    public static synchronized AppDatabase getInstance(Context context) {
        if (instance == null) {
            instance = Room.databaseBuilder(context.getApplicationContext(),
                    AppDatabase.class, "appzuku_db")
                    // WARNING: This will destroy all user data (kill history, stats) on schema changes.
                    // For production, consider implementing proper migrations instead.
                    .fallbackToDestructiveMigration()
                    .build();
        }
        return instance;
    }
}
