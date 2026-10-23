package dev.chenwuyi.leaf

import android.app.Application
import android.content.Context
import dev.chenwuyi.leaf.data.local.LeafDatabase
import dev.chenwuyi.leaf.data.local.PhotoStore
import dev.chenwuyi.leaf.data.local.RoomLeafRepository
import dev.chenwuyi.leaf.data.notion.LeafNotion
import dev.chenwuyi.leaf.data.settings.PrefsConnectionStore
import dev.chenwuyi.leaf.data.sync.NotionSetup
import dev.chenwuyi.leaf.data.sync.SyncEngine
import dev.chenwuyi.leaf.data.sync.SyncScheduler

class LeafApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Everything long-lived, built once per process. */
class AppContainer(context: Context) {
    private val database = LeafDatabase.open(context)
    private val photos = PhotoStore(context)
    val connection = PrefsConnectionStore(context)
    private val notion = LeafNotion()
    val sync = SyncEngine(database.dao(), notion, connection, readPhoto = photos::read)
    val scheduler = SyncScheduler(context)
    val setup = NotionSetup(notion, connection, database.dao(), sync)
    val repository = RoomLeafRepository(
        dao = database.dao(),
        isConnected = { connection.connection.value != null },
        requestSync = { scheduler.request() },
        importPhoto = photos::import,
        loadBody = sync::loadBody,
    )
}
