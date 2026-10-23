package dev.chenwuyi.leaf.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.chenwuyi.leaf.LeafApplication
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val engine = (applicationContext as LeafApplication).container.sync
        return when (engine.run(pull = inputData.getBoolean(KeyPull, true))) {
            RunResult.Done, RunResult.Stopped -> Result.success()
            RunResult.RetryLater -> Result.retry()
        }
    }

    companion object {
        const val KeyPull = "pull"
    }
}

/**
 * Asks WorkManager to sync. Edits arrive keystroke by keystroke, so requests are gathered for a
 * moment and then appended to one serial chain; a running sync is never cancelled half-way through
 * an append (that could leave a block in Notion that Leaf does not know about).
 */
class SyncScheduler(context: Context, private val debounceMillis: Long = 1_500) {
    private val work = WorkManager.getInstance(context.applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pending: Job? = null

    @Synchronized
    fun request(pull: Boolean = false) {
        pending?.cancel()
        pending = scope.launch {
            delay(debounceMillis)
            enqueue(pull)
        }
    }

    fun now() = enqueue(pull = true)

    private fun enqueue(pull: Boolean) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(androidx.work.workDataOf(SyncWorker.KeyPull to pull))
            .build()
        work.enqueueUniqueWork("leaf-sync", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}
