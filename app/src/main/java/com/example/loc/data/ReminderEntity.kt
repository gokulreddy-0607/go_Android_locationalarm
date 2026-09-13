package com.example.loc.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

@Entity(tableName = "reminders")
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radius: Float,
    val isActive: Boolean = false,
    val itemsJson: String = "[]",
    val audioUri: String? = null
)

data class ReminderItem(
    val name: String,
    var isCompleted: Boolean = false,
    var isFailed: Boolean = false // Red X
)

class Converters {
    @TypeConverter
    fun fromString(value: String): List<ReminderItem> {
        val listType = object : TypeToken<List<ReminderItem>>() {}.type
        return Gson().fromJson(value, listType)
    }

    @TypeConverter
    fun fromList(list: List<ReminderItem>): String {
        return Gson().toJson(list)
    }
}
