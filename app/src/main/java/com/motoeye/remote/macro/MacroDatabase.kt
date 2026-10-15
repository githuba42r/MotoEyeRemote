package com.motoeye.remote.macro

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import android.content.Context
import kotlinx.coroutines.flow.Flow

/**
 * A macro is a saved sequence of actions executed with one button.
 * Steps are stored as JSON-ish strings for flexibility.
 *
 * Step grammar (one per line):
 *   key:<keyCode>[;delay:<ms>]        - send BLE key click
 *   keydown:<keyCode> / keyup:<keyCode>
 *   wait:<ms>
 *   media:play|pause|next|previous|playpause
 *   intent:<package>[;activity:<cls>] - launch phone app
 *   aakey:home|back                   - accessibility key injection
 *   shell:<cmd>                       - (not used, reserved)
 */
data class MacroStep(val type: String, val value: String, val delayMs: Long = 0)

@Entity(tableName = "macros")
data class MacroEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val icon: String = "play",
    val steps: List<MacroStep>,
    val builtin: Boolean = false,
    val sortOrder: Int = 0
)

class Converters {
    @TypeConverter
    fun fromSteps(steps: List<MacroStep>): String =
        steps.joinToString("\n") { step ->
            val base = "${step.type}:${step.value}"
            if (step.delayMs > 0) "$base;delay:${step.delayMs}" else base
        }

    @TypeConverter
    fun toSteps(data: String): List<MacroStep> =
        data.lines().filter { it.isNotBlank() }.map { line ->
            val (head, rest) = line.split(":", limit = 2).let {
                if (it.size == 2) it[0] to it[1] else it[0] to ""
            }
            val parts = rest.split(";")
            val value = parts.first()
            val delay = parts.firstOrNull { it.startsWith("delay:") }
                ?.removePrefix("delay:")?.toLongOrNull() ?: 0
            MacroStep(head, value, delay)
        }
}

@Dao
interface MacroDao {
    @Query("SELECT * FROM macros ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<MacroEntity>>

    @Query("SELECT * FROM macros")
    suspend fun getAll(): List<MacroEntity>

    @Insert
    suspend fun insert(macro: MacroEntity): Long

    @Update
    suspend fun update(macro: MacroEntity)

    @Query("DELETE FROM macros WHERE id = :id")
    suspend fun delete(id: Long)
}

@Database(entities = [MacroEntity::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class MacroDatabase : RoomDatabase() {
    abstract fun macroDao(): MacroDao

    companion object {
        @Volatile
        private var instance: MacroDatabase? = null

        fun get(context: Context): MacroDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MacroDatabase::class.java,
                    "macros.db"
                ).build().also { instance = it }
            }
    }
}
