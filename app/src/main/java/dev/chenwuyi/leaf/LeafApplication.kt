package dev.chenwuyi.leaf

import android.app.Application
import android.content.Context
import dev.chenwuyi.leaf.data.local.LeafDatabase
import dev.chenwuyi.leaf.data.local.PhotoStore
import dev.chenwuyi.leaf.data.local.RoomLeafRepository
import dev.chenwuyi.leaf.data.notion.HttpNotionOAuthRelay
import dev.chenwuyi.leaf.data.notion.LeafNotion
import dev.chenwuyi.leaf.data.notion.NotionOAuthConfig
import dev.chenwuyi.leaf.data.settings.PrefsAppearanceStore
import dev.chenwuyi.leaf.data.settings.PrefsConnectionStore
import dev.chenwuyi.leaf.data.settings.VaultSpeechKeyStore
import dev.chenwuyi.leaf.data.sync.NotionAuth
import dev.chenwuyi.leaf.data.sync.NotionSetup
import dev.chenwuyi.leaf.data.sync.SyncEngine
import dev.chenwuyi.leaf.data.sync.SyncScheduler
import dev.chenwuyi.leaf.data.voice.ScribeTranscriber
import dev.chenwuyi.leaf.quick.WidgetUpdates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class LeafApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        container = AppContainer(this)
    }
}

/** Everything long-lived, built once per process. */
class AppContainer(context: Context) {
    /** Work that outlives a screen, such as a quick capture still saving after its sheet closed. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val database = LeafDatabase.open(context)
    private val photos = PhotoStore(context)
    val connection = PrefsConnectionStore(context)
    val speechKey = VaultSpeechKeyStore(context)
    val appearance = PrefsAppearanceStore(context)
    val transcriber = ScribeTranscriber()
    private val notion = LeafNotion()
    private val oauth = if (NotionOAuthConfig.enabled) HttpNotionOAuthRelay() else null
    val sync = SyncEngine(database.dao(), notion, connection, readPhoto = photos::read, downloadPhoto = photos::download, auth = NotionAuth(connection, oauth))
    val scheduler = SyncScheduler(context)
    val setup = NotionSetup(notion, connection, database.dao(), sync, oauth)
    val repository = RoomLeafRepository(
        dao = database.dao(),
        isConnected = { connection.connection.value != null },
        requestSync = { scheduler.request() },
        importPhoto = photos::import,
        loadBody = sync::loadBody,
        freshFileUrl = sync::fileUrl,
        readPhoto = photos::read,
        downloadPhoto = photos::download,
    )

    init {
        WidgetUpdates.start(context, scope, repository.scribbles)
    }
}
