package app.vinkje.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RenameColumn
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec

@Database(
    entities = [GroupEntity::class, ListEntity::class, TaskEntity::class, OutboxEntity::class],
    version = 5,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4, spec = AppDatabase.OutboxRowId::class),
        AutoMigration(from = 4, to = 5),
    ],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): TodoDao

    /** The outbox took lists and groups besides tasks. */
    @RenameColumn(tableName = "outbox", fromColumnName = "taskId", toColumnName = "rowId")
    class OutboxRowId : AutoMigrationSpec

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "todo.db").build()
    }
}
