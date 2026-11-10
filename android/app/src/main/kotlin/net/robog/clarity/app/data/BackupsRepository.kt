package net.robog.clarity.app.data

import net.robog.clarity.app.net.BackupsApi
import net.robog.clarity.shared.backup.BackupInfo
import net.robog.clarity.shared.backup.BackupList
import net.robog.clarity.shared.backup.RollbackAccepted
import net.robog.clarity.shared.backup.RollbackRequest

/** The server's database backups and rolling back to one. */
interface BackupsRepository {
    suspend fun list(): ApiResult<BackupList>
    suspend fun backUpNow(): ApiResult<BackupInfo>
    suspend fun rollback(request: RollbackRequest): ApiResult<RollbackAccepted>
}

class DefaultBackupsRepository(private val api: BackupsApi) : BackupsRepository {
    override suspend fun list() = api.backups()
    override suspend fun backUpNow() = api.backUpNow()
    override suspend fun rollback(request: RollbackRequest) = api.rollback(request)
}
