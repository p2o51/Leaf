package dev.chenwuyi.leaf.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ScribbleEntity::class, EssayEntity::class, EssayBlockEntity::class, OutboxEntity::class],
    version = 4,
    exportSchema = true,
)
abstract class LeafDatabase : RoomDatabase() {
    abstract fun dao(): LeafDao

    companion object {
        fun open(context: Context): LeafDatabase =
            Room.databaseBuilder(context.applicationContext, LeafDatabase::class.java, "leaf.db")
                .addMigrations(Migration1To2, Migration2To3, Migration3To4)
                .build()

        /** 2: image blocks in essays keep their picture. */
        val Migration1To2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE essay_blocks ADD COLUMN photo TEXT")
            }
        }

        /** 3: essays keep their 标签 for the drawer. */
        val Migration2To3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE essays ADD COLUMN tags TEXT NOT NULL DEFAULT ''")
            }
        }

        /** 4: essays keep the day they were written, for the page's date line; blocks know if they can move. */
        val Migration3To4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE essays ADD COLUMN createdAt INTEGER")
                // Blocks learn whether they can be moved; until each body loads again, synced text stays put.
                db.execSQL("ALTER TABLE essay_blocks ADD COLUMN fixed INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE essay_blocks SET fixed = 1 WHERE blockId IS NOT NULL AND type NOT IN ('image', 'divider')")
                db.execSQL("UPDATE essays SET bodyLoaded = 0 WHERE pageId IS NOT NULL")
            }
        }
    }
}
