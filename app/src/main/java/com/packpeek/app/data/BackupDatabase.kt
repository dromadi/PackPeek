package com.packpeek.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "jobs")
data class BackupJob(
    @PrimaryKey val id: String,
    val created: Long = System.currentTimeMillis(),
    val folder: String,
    val state: String = "QUEUED",
    val dismissed: Boolean = false,
)

@Entity(tableName = "backups", indices = [Index("jobId")])
data class BackupRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val jobId: String,
    val packageName: String,
    val label: String,
    val version: String,
    val format: String,
    val created: Long = System.currentTimeMillis(),
    val state: String = "QUEUED",
    val uri: String = "",
    val fileName: String = "",
    val bytes: Long = 0,
    val error: String = "",
    val hidden: Boolean = false,
)

data class BackupStats(val count: Int, val bytes: Long)

@Dao
interface BackupDao {
    @Insert suspend fun addJob(job: BackupJob)

    @Insert suspend fun addRecords(records: List<BackupRecord>)

    @Query("SELECT * FROM jobs WHERE id=:id") suspend fun job(id: String): BackupJob?

    @Query("SELECT * FROM backups WHERE jobId=:id ORDER BY id")
    suspend fun records(id: String): List<BackupRecord>

    @Query("SELECT * FROM backups WHERE jobId=:id ORDER BY id")
    fun observeRecords(id: String): Flow<List<BackupRecord>>

    @Query("SELECT * FROM jobs ORDER BY created DESC LIMIT 1") fun latest(): Flow<BackupJob?>

    @Query("SELECT * FROM backups WHERE hidden=0 AND state='SUCCESS' ORDER BY created DESC")
    fun history(): Flow<List<BackupRecord>>

    @Query(
        "SELECT COUNT(*) AS count,COALESCE(SUM(bytes),0) AS bytes FROM backups WHERE state='SUCCESS'"
    )
    fun stats(): Flow<BackupStats>

    @Query("UPDATE jobs SET state=:state WHERE id=:id")
    suspend fun jobState(id: String, state: String)

    @Query("UPDATE jobs SET dismissed=1 WHERE id=:id") suspend fun dismiss(id: String)

    @Query(
        "UPDATE backups SET state=:state,uri=:uri,fileName=:name,bytes=:bytes,error=:error WHERE id=:id"
    )
    suspend fun finish(
        id: Long,
        state: String,
        uri: String = "",
        name: String = "",
        bytes: Long = 0,
        error: String = "",
    )

    @Query("UPDATE backups SET state='CANCELLED' WHERE jobId=:id AND state IN ('QUEUED','RUNNING')")
    suspend fun cancelPending(id: String)

    @Query("UPDATE backups SET hidden=1 WHERE id=:id") suspend fun hide(id: Long)

    @Query("UPDATE backups SET hidden=1 WHERE uri=:uri") suspend fun hideUri(uri: String)

    @Transaction
    suspend fun create(job: BackupJob, records: List<BackupRecord>) {
        addJob(job)
        addRecords(records)
    }
}

@Database(entities = [BackupJob::class, BackupRecord::class], version = 1, exportSchema = true)
abstract class BackupDatabase : RoomDatabase() {
    abstract fun backups(): BackupDao
}
