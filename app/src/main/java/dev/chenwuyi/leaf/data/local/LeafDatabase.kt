package dev.chenwuyi.leaf.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ScribbleEntity::class, EssayEntity::class, EssayBlockEntity::class, OutboxEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class LeafDatabase : RoomDatabase() {
    abstract fun dao(): LeafDao

    companion object {
        fun open(context: Context): LeafDatabase =
            Room.databaseBuilder(context.applicationContext, LeafDatabase::class.java, "leaf.db").build()
    }
}
