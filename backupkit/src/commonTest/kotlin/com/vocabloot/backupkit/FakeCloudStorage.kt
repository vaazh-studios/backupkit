package com.vocabloot.backupkit

/** In-memory [CloudStorage]. [readLocal] resolves the local paths the engine passes to [writeFile]. */
class FakeCloudStorage(
    override val provider: CloudProvider = CloudProvider.ICloud,
    private val readLocal: (String) -> ByteArray?,
    private val writeLocal: (String, ByteArray) -> Unit,
) : CloudStorage {
    val remote = linkedMapOf<String, ByteArray>()
    private val ids = mutableMapOf<String, String>()
    private var nextId = 1

    var availability: CloudAvailability = CloudAvailability.Available
    var identity: String? = "identity-A"
    var assignsRemoteIds: Boolean = false
    var failPutsContaining: String? = null
    var failError: CloudError = CloudError.Transport
    var failDeletes: Boolean = false
    var failListing: CloudError? = null
    var failReadsContaining: String? = null
    var failDownloadsContaining: String? = null
    val prefetchLog = mutableListOf<List<String>>()
    val downloadLog = mutableListOf<String>()

    val putLog = mutableListOf<String>()
    val deleteLog = mutableListOf<String>()
    var listCalls = 0

    override suspend fun availability(): CloudAvailability = availability
    override suspend fun identityKey(): String? = identity

    override suspend fun list(): List<RemoteFile> {
        listCalls += 1
        failListing?.let { throw CloudStorageException(it, "listing failed") }
        return remote.map { (path, bytes) -> RemoteFile(path = path, size = bytes.size.toLong(), remoteId = ids[path]) }
    }

    override suspend fun exists(path: String): Boolean = path in remote

    override suspend fun writeFile(path: String, localPath: String, mimeType: String, existingRemoteId: String?): String? {
        val bytes = readLocal(localPath) ?: throw CloudStorageException(CloudError.NotFound, "local missing: $localPath")
        return writeBytes(path = path, bytes = bytes, mimeType = mimeType, existingRemoteId = existingRemoteId)
    }

    override suspend fun writeBytes(path: String, bytes: ByteArray, mimeType: String, existingRemoteId: String?): String? {
        failPutsContaining?.let { if (path.contains(it)) throw CloudStorageException(failError, "put failed: $path") }
        putLog += path
        // Drive semantics: a CREATE (path not present) mints a fresh id; an update keeps it.
        val created = !remote.containsKey(path)
        remote[path] = bytes
        if (!assignsRemoteIds) return null
        if (created || path !in ids) ids[path] = "id-${nextId++}"
        return ids.getValue(path)
    }

    override suspend fun readBytes(path: String, remoteId: String?): ByteArray? {
        failReadsContaining?.let { if (path.contains(it)) throw CloudStorageException(CloudError.Transport, "read failed: $path") }
        return remote[path]
    }

    override suspend fun prefetch(paths: List<String>) { prefetchLog += paths }

    override suspend fun downloadFile(path: String, toLocalPath: String, remoteId: String?) {
        failDownloadsContaining?.let { if (path.contains(it)) throw CloudStorageException(CloudError.Transport, "download failed: $path") }
        val bytes = remote[path] ?: throw CloudStorageException(CloudError.NotFound, "remote missing: $path")
        downloadLog += path
        writeLocal(toLocalPath, bytes)
    }

    override suspend fun delete(path: String, remoteId: String?) {
        if (failDeletes) throw CloudStorageException(CloudError.Transport, "delete failed: $path")
        deleteLog += path
        remote.remove(path)
        ids.remove(path)
    }
}
