package dev.chenwuyi.leaf.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ScribbleEntity::class, EssayEntity::class, EssayBlockEntity::class, OutboxEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class LeafDatabase : RoomDatabase() {
    abstract fun dao(): LeafDao

    companion object {
        fun open(context: Context): LeafDatabase =
            Room.databaseBuilder(context.applicationContext, LeafDatabase::class.java, "leaf.db")
                .addMigrations(Migration1To2)
                .build()

        /** 2: image blocks in essays keep their picture. */
        val Migration1To2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE essay_blocks ADD COLUMN photo TEXT")
            }
        }
    }
}
