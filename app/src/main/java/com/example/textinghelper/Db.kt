package com.example.textinghelper

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import kotlinx.coroutines.flow.Flow

// No row = Ignore. frequencyDays null = Ignore too (kept so snooze/reminder history survives).
@Entity
data class ContactSetting(
    @PrimaryKey val contactId: Long,
    val phone: String,
    val name: String,
    val frequencyDays: Int?,
    val snoozedUntil: Long? = null,
    val lastReminded: Long? = null,
    val handledAt: Long? = null, // tapped "Done": counts like a text I sent
    val jitterCycle: Long? = null, // cycle start (lastOut) that jitterDays was decided for
    @ColumnInfo(defaultValue = "0") val jitterDays: Int = 0, // this cycle's shift, see rollJitter()
    val style: String? = null, // Style.key for suggestions; null = Friends
)

@Entity
data class ReminderLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val contactId: Long,
    val time: Long,
    val reason: String, // "due" or "unreplied"
    val action: String? = null, // "text", "snooze", "done" once I act on the notification
    // Claude usage for this reminder's suggestion (null = no API call). Output includes thinking tokens.
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val images: Int? = null,
)

@Dao
interface AppDao {
    @Query("SELECT * FROM ContactSetting")
    fun all(): Flow<List<ContactSetting>> // Flow = live query; the UI re-renders when the table changes

    @Query("SELECT * FROM ContactSetting")
    suspend fun list(): List<ContactSetting>

    @Query("SELECT * FROM ContactSetting WHERE contactId = :id")
    suspend fun get(id: Long): ContactSetting?

    @Upsert
    suspend fun save(s: ContactSetting)

    @Insert
    suspend fun log(entry: ReminderLog)

    @Query("SELECT * FROM ReminderLog WHERE inputTokens IS NOT NULL AND time >= :since ORDER BY id DESC")
    fun usageSince(since: Long): Flow<List<ReminderLog>>

    @Query("SELECT * FROM ReminderLog WHERE action IS NOT NULL ORDER BY id DESC LIMIT 15")
    fun recentActions(): Flow<List<ReminderLog>>

    @Query("UPDATE ReminderLog SET action = :action WHERE id = (SELECT MAX(id) FROM ReminderLog WHERE contactId = :id) AND action IS NULL")
    suspend fun completePending(id: Long, action: String): Int

    /** Record what I did: fills in the pending reminder, or logs a new entry if there isn't one (Home screen). */
    suspend fun setLastAction(id: Long, action: String) {
        if (completePending(id, action) == 0) log(ReminderLog(contactId = id, time = System.currentTimeMillis(), reason = "manual", action = action))
    }
}

@Database(entities = [ContactSetting::class, ReminderLog::class], version = 5, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        @Volatile private var instance: AppDb? = null
        fun get(ctx: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "app.db")
                .addMigrations(object : Migration(1, 2) {
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) =
                        db.execSQL("ALTER TABLE ContactSetting ADD COLUMN handledAt INTEGER")
                }, object : Migration(2, 3) {
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("ALTER TABLE ContactSetting ADD COLUMN jitterCycle INTEGER")
                        db.execSQL("ALTER TABLE ContactSetting ADD COLUMN jitterDays INTEGER NOT NULL DEFAULT 0")
                    }
                }, object : Migration(3, 4) {
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        for (c in listOf("inputTokens", "outputTokens", "images")) db.execSQL("ALTER TABLE ReminderLog ADD COLUMN $c INTEGER")
                    }
                }, object : Migration(4, 5) {
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) =
                        db.execSQL("ALTER TABLE ContactSetting ADD COLUMN style TEXT")
                })
                .build().also { instance = it }
        }
    }
}
