package com.example.smartgallery

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.smartgallery.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Worker that permanently removes a quarantine backup and its DB row.
 *
 * Expects inputData:
 *  - "quarantine_id" (Long)
 */
class PurgeQuarantineWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    private val db by lazy { AppDatabase.get(applicationContext) }
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val qid = inputData.getLong("quarantine_id", -1L)
            if (qid <= 0L) return@withContext Result.failure()

            val qDao = db.quarantineDao()
            val list = qDao.findExpired(System.currentTimeMillis()) // fetch expired
            val target = list.firstOrNull { it.id == qid } ?: return@withContext Result.success()

            // delete backup file
            try {
                val f = File(target.backupPath)
                if (f.exists()) f.delete()
            } catch (_: Throwable) { /* ignore */ }

            // remove DB row
            qDao.deleteByIds(listOf(qid))
            return@withContext Result.success()
        } catch (t: Throwable) {
            t.printStackTrace()
            return@withContext Result.retry()
        }
    }
}
