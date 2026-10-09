package com.katsuyamaki.mychatgpt.notifications;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Device-local notification history, independent of Android's notification shade.
 * Dismissing an Android notification does not delete its matching database row.
 * The database is private to MyChatGPT and is not included in Android backup.
 */
public final class NotificationStore extends SQLiteOpenHelper {
    private static final String DATABASE = "mychatgpt_notifications.db";
    private static final int VERSION = 2;
    private static final String TABLE = "events";

    public static final class Entry {
        public final long id;
        public final String title;
        public final String body;
        public final String chatUrl;
        public final String source;
        public final long createdAt;
        public final long readAt;

        Entry(long id, String title, String body, String chatUrl,
              String source, long createdAt, long readAt) {
            this.id = id;
            this.title = title;
            this.body = body;
            this.chatUrl = chatUrl;
            this.source = source;
            this.createdAt = createdAt;
            this.readAt = readAt;
        }

        public boolean isUnread() {
            return readAt == 0L;
        }
    }

    public NotificationStore(Context context) {
        super(context.getApplicationContext(), DATABASE, null, VERSION);
        setWriteAheadLoggingEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "title TEXT NOT NULL,"
                + "body TEXT NOT NULL,"
                + "chat_url TEXT,"
                + "source TEXT NOT NULL,"
                + "created_at INTEGER NOT NULL,"
                + "read_at INTEGER NOT NULL DEFAULT 0,"
                + "external_key TEXT)");
        db.execSQL("CREATE UNIQUE INDEX events_external_key ON " + TABLE
                + " (external_key)");
        db.execSQL("CREATE INDEX events_created ON " + TABLE
                + " (created_at DESC, _id DESC)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // No destructive migration: existing site, app, and test entries persist.
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN external_key TEXT");
            db.execSQL("CREATE UNIQUE INDEX events_external_key ON " + TABLE
                    + " (external_key)");
        }
    }

    public synchronized long add(String title, String body, String chatUrl,
                                 String source, long createdAt) {
        ContentValues values = new ContentValues();
        values.put("title", title);
        values.put("body", body);
        values.put("chat_url", chatUrl);
        values.put("source", source);
        values.put("created_at", createdAt);
        return getWritableDatabase().insertOrThrow(TABLE, null, values);
    }

    /**
     * Atomically deduplicate third-party notifications across listener
     * reconnects, app restarts, and a history scan of active OS notifications.
     * The external key is a SHA-256 digest, not another app's raw content.
     *
     * @return inserted row ID; -1 when the exact notice was already recorded.
     */
    public synchronized long addExternal(String title, String body, String chatUrl,
                                         String source, long createdAt,
                                         String externalKey) {
        if (externalKey == null || externalKey.isEmpty()) {
            throw new IllegalArgumentException("Missing external notification key");
        }
        ContentValues values = new ContentValues();
        values.put("title", title);
        values.put("body", body);
        values.put("chat_url", chatUrl);
        values.put("source", source);
        values.put("created_at", createdAt);
        values.put("external_key", externalKey);
        return getWritableDatabase().insertWithOnConflict(
                TABLE, null, values, SQLiteDatabase.CONFLICT_IGNORE);
    }

    public synchronized Entry find(long id) {
        try (Cursor cursor = getReadableDatabase().query(
                TABLE, null, "_id=?", new String[]{Long.toString(id)},
                null, null, null, "1")) {
            return cursor.moveToFirst() ? fromCursor(cursor) : null;
        }
    }

    public synchronized List<Entry> recent(int limit) {
        return recent(limit, 0);
    }

    public synchronized List<Entry> recent(int limit, int offset) {
        List<Entry> entries = new ArrayList<>();
        int safeLimit = Math.min(100, Math.max(1, limit));
        int safeOffset = Math.max(0, offset);
        try (Cursor cursor = getReadableDatabase().query(
                TABLE, null, null, null, null, null,
                "created_at DESC, _id DESC", safeOffset + "," + safeLimit)) {
            while (cursor.moveToNext()) {
                entries.add(fromCursor(cursor));
            }
        }
        return entries;
    }

    public synchronized int totalCount() {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM " + TABLE, null)) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    public synchronized int unreadCount() {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM " + TABLE + " WHERE read_at=0", null)) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    public synchronized void markRead(long id) {
        ContentValues values = new ContentValues();
        values.put("read_at", System.currentTimeMillis());
        getWritableDatabase().update(TABLE, values, "_id=? AND read_at=0",
                new String[]{Long.toString(id)});
    }

    /** Clearing history is explicit; Android notification dismissal never calls this. */
    public synchronized void clear() {
        getWritableDatabase().delete(TABLE, null, null);
    }

    private static Entry fromCursor(Cursor c) {
        int urlColumn = c.getColumnIndexOrThrow("chat_url");
        return new Entry(
                c.getLong(c.getColumnIndexOrThrow("_id")),
                c.getString(c.getColumnIndexOrThrow("title")),
                c.getString(c.getColumnIndexOrThrow("body")),
                c.isNull(urlColumn) ? null : c.getString(urlColumn),
                c.getString(c.getColumnIndexOrThrow("source")),
                c.getLong(c.getColumnIndexOrThrow("created_at")),
                c.getLong(c.getColumnIndexOrThrow("read_at")));
    }
}
