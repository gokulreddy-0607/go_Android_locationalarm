package com.example.loc

import android.content.Context

object SyncManager {
    fun syncAll(context: Context, uid: String, username: String, email: String) {}
    fun syncProfile(context: Context, uid: String, username: String, email: String) {}
    fun syncAlarms(context: Context, uid: String) {}
    fun syncFavorites(context: Context, uid: String) {}
    fun syncReminders(context: Context, uid: String) {}
    fun syncInbox(context: Context, uid: String) {}
}