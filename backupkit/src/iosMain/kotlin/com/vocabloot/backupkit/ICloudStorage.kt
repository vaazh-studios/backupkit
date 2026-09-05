package com.vocabloot.backupkit

import com.vocabloot.backupkit.internal.LocalFiles
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.BooleanVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileCoordinator
import platform.Foundation.NSFileCoordinatorWritingForDeleting
import platform.Foundation.NSFileCoordinatorWritingForReplacing
import platform.Foundation.NSFileCoordinatorWritingOptions
import platform.Foundation.NSFileManager
import platform.Foundation.NSKeyedArchiver
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSURL
import platform.Foundation.NSURLUbiquitousItemDownloadingStatusCurrent
import platform.Foundation.NSURLUbiquitousItemDownloadingStatusKey
import platform.Foundation.NSUUID
import platform.Foundation.NSUbiquityIdentityDidChangeNotification
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.posix.memcpy
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * iCloud Drive transport: plain files under `<ubiquity container>/<folder>/`, OUTSIDE `Documents/`,
 * so they stay private to the app and invisible in Files. Writes land locally through
 * NSFileCoordinator and the iCloud daemon uploads on its own. Reads force a download and wait
 * with a bounded poll (a not-yet-downloaded file is never coordinated directly: that blocks).
 *
 * Requires the iCloud Documents capability and a ubiquity container in the app's entitlements
 * (see docs/setup-ios.md). [containerIdentifier] null means the first container listed there.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
public class ICloudStorage(
    private val folder: String = "backupkit",
    private val containerIdentifier: String? = null,
    private val downloadTimeout: Duration = 30.seconds,
) : CloudStorage {

    override val provider: CloudProvider = CloudProvider.ICloud

    private val fileManager = NSFileManager.defaultManager
    private var cachedRoot: String? = null

    init {
        // Account switched / signed out: forget the container; the next call re-resolves it.
        NSNotificationCenter.defaultCenter.addObserverForName(
            name = NSUbiquityIdentityDidChangeNotification,
            `object` = null,
            queue = null,
        ) { _ -> cachedRoot = null }
    }

    override suspend fun availability(): CloudAvailability {
        val token = withContext(Dispatchers.Main) { fileManager.ubiquityIdentityToken }
        if (token == null) return CloudAvailability.NoAccount
        return if (root() != null) CloudAvailability.Available else CloudAvailability.NoAccount
    }

    /**
     * Apple's documented way to compare the token across launches is to ARCHIVE it. Its
     * `description` may carry a per-process pointer, which would look like a new account on
     * every start and force a full re-upload.
     */
    override suspend fun identityKey(): String? {
        val token = withContext(Dispatchers.Main) { fileManager.ubiquityIdentityToken } ?: return null
        val archived = NSKeyedArchiver.archivedDataWithRootObject(token, requiringSecureCoding = false, error = null)
            ?: return null
        return sha256Hex(archived.toByteArray())
    }

    override suspend fun list(): List<RemoteFile> = withContext(Dispatchers.IO) {
        val out = mutableListOf<RemoteFile>()
        scanDir(dir = requireRoot(), prefix = "", into = out)
        out
    }

    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        val target = "${requireRoot()}/$path"
        fileManager.fileExistsAtPath(target) || fileManager.fileExistsAtPath(placeholderPath(target))
    }

    override suspend fun writeBytes(path: String, bytes: ByteArray, mimeType: String, existingRemoteId: String?): String? =
        withContext(Dispatchers.IO) {
            val root = requireRoot()
            val temp = tempPath(root)
            if (!bytes.toNSData().writeToFile(temp, atomically = true)) {
                throw CloudStorageException(CloudError.Transport, "temp write failed for $path")
            }
            coordinatedReplace(temp = temp, target = "$root/$path")
            null
        }

    override suspend fun writeFile(path: String, localPath: String, mimeType: String, existingRemoteId: String?): String? =
        withContext(Dispatchers.IO) {
            if (!fileManager.fileExistsAtPath(localPath)) throw CloudStorageException(CloudError.NotFound, "local file missing: $localPath")
            val root = requireRoot()
            val temp = tempPath(root)
            check("copy $path") { err -> fileManager.copyItemAtPath(localPath, temp, err) }
            coordinatedReplace(temp = temp, target = "$root/$path")
            null
        }

    override suspend fun delete(path: String, remoteId: String?): Unit = withContext(Dispatchers.IO) {
        val target = "${requireRoot()}/$path"
        if (!fileManager.fileExistsAtPath(target) && !fileManager.fileExistsAtPath(placeholderPath(target))) return@withContext
        coordinate(target = target, options = NSFileCoordinatorWritingForDeleting) { dest ->
            fileManager.removeItemAtPath(dest, error = null)
            fileManager.removeItemAtPath(placeholderPath(dest), error = null)
        }
    }

    override suspend fun downloadFile(path: String, toLocalPath: String, remoteId: String?): Unit = withContext(Dispatchers.IO) {
        val source = "${requireRoot()}/$path"
        if (!fileManager.fileExistsAtPath(source) && !fileManager.fileExistsAtPath(placeholderPath(source))) {
            throw CloudStorageException(CloudError.NotFound, "remote missing: $path")
        }
        ensureDownloaded(source)
        LocalFiles.ensureParentDir(toLocalPath)
        fileManager.removeItemAtPath(toLocalPath, error = null)
        check("download $path") { err -> fileManager.copyItemAtPath(source, toLocalPath, err) }
    }

    override suspend fun readBytes(path: String, remoteId: String?): ByteArray? = withContext(Dispatchers.IO) {
        val source = "${requireRoot()}/$path"
        if (!fileManager.fileExistsAtPath(source) && !fileManager.fileExistsAtPath(placeholderPath(source))) return@withContext null
        ensureDownloaded(source)
        fileManager.contentsAtPath(source)?.toByteArray()
    }

    // region container

    /** Resolved once per identity, on a background dispatcher (Apple: never on main). */
    private suspend fun root(): String? = cachedRoot ?: withContext(Dispatchers.IO) {
        val url = fileManager.URLForUbiquityContainerIdentifier(containerIdentifier) ?: return@withContext null
        val path = url.path ?: return@withContext null
        val root = "$path/$folder"
        ensureDir(root)
        cachedRoot = root
        root
    }

    private suspend fun requireRoot(): String =
        root() ?: throw CloudStorageException(CloudError.NotAvailable, "iCloud container unavailable")

    private fun tempPath(root: String) = "$root/$TEMP_PREFIX${NSUUID().UUIDString}"

    private fun ensureDir(path: String) {
        fileManager.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)
    }

    // endregion

    // region listing

    private fun scanDir(dir: String, prefix: String, into: MutableList<RemoteFile>) {
        val names = fileManager.contentsOfDirectoryAtPath(dir, error = null)?.filterIsInstance<String>().orEmpty()
        for (raw in names) {
            if (raw.startsWith(TEMP_PREFIX)) continue
            val placeholder = raw.startsWith(".") && raw.endsWith(PLACEHOLDER_SUFFIX)
            val name = if (placeholder) raw.removePrefix(".").removeSuffix(PLACEHOLDER_SUFFIX) else raw
            if (name.startsWith(".")) continue
            val full = "$dir/$raw"
            if (!placeholder && isDirectory(full)) {
                scanDir(dir = full, prefix = "$prefix$name/", into = into)
                continue
            }
            into += RemoteFile(path = prefix + name, size = if (placeholder) -1L else fileSize(full) ?: -1L, remoteId = null)
        }
    }

    private fun isDirectory(path: String): Boolean = memScoped {
        val isDir = alloc<BooleanVar>()
        fileManager.fileExistsAtPath(path, isDirectory = isDir.ptr) && isDir.value
    }

    private fun fileSize(path: String): Long? =
        (fileManager.attributesOfItemAtPath(path, error = null)?.get("NSFileSize") as? Number)?.toLong()

    /** iCloud represents a not-yet-downloaded `x` as `.x.icloud` in the same directory. */
    private fun placeholderPath(path: String): String {
        val dir = path.substringBeforeLast('/')
        val file = path.substringAfterLast('/')
        return "$dir/.$file$PLACEHOLDER_SUFFIX"
    }

    // endregion

    // region coordinated writes

    private fun coordinatedReplace(temp: String, target: String) {
        try {
            coordinate(target = target, options = NSFileCoordinatorWritingForReplacing) { dest ->
                fileManager.removeItemAtPath(dest, error = null)
                check("move to $dest") { err -> fileManager.moveItemAtPath(temp, dest, err) }
            }
        } finally {
            fileManager.removeItemAtPath(temp, error = null)
        }
    }

    private fun coordinate(target: String, options: NSFileCoordinatorWritingOptions, block: (String) -> Unit) {
        ensureDir(target.substringBeforeLast('/'))
        var failure: Throwable? = null
        memScoped {
            val coordError = alloc<ObjCObjectVar<NSError?>>()
            NSFileCoordinator(filePresenter = null).coordinateWritingItemAtURL(
                url = NSURL.fileURLWithPath(target),
                options = options,
                error = coordError.ptr,
            ) { newUrl ->
                try {
                    block(newUrl?.path ?: target)
                } catch (t: Throwable) {
                    failure = t
                }
            }
            coordError.value?.let { failure = failure ?: CloudStorageException(errorFor(it), it.localizedDescription) }
        }
        failure?.let { throw it }
    }

    /** Runs [block] with an NSError out-param; throws a mapped [CloudStorageException] when it returns false. */
    private inline fun check(op: String, block: (CPointer<ObjCObjectVar<NSError?>>) -> Boolean) {
        memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            if (!block(error.ptr)) {
                val err = error.value
                throw CloudStorageException(
                    error = err?.let(::errorFor) ?: CloudError.Transport,
                    message = "$op failed: ${err?.localizedDescription ?: "unknown error"}",
                )
            }
        }
    }

    /** NSCocoaErrorDomain codes: 640 = NSFileWriteOutOfSpaceError, 4354 = NSUbiquitousFileNotUploadedDueToQuotaError. */
    private fun errorFor(error: NSError): CloudError = when (error.code) {
        640L, 4354L -> CloudError.StorageFull
        else -> CloudError.Transport
    }

    // endregion

    // region downloads

    private suspend fun ensureDownloaded(path: String) {
        val url = NSURL.fileURLWithPath(path)
        if (downloadStatus(url) == NSURLUbiquitousItemDownloadingStatusCurrent) return
        check("startDownloading $path") { err -> fileManager.startDownloadingUbiquitousItemAtURL(url, err) }
        val started = TimeSource.Monotonic.markNow()
        while (downloadStatus(url) != NSURLUbiquitousItemDownloadingStatusCurrent) {
            if (started.elapsedNow() > downloadTimeout) {
                throw CloudStorageException(CloudError.Transport, "iCloud download timed out: $path")
            }
            delay(DOWNLOAD_POLL_MS)
        }
    }

    private fun downloadStatus(url: NSURL): String? = memScoped {
        val value = alloc<ObjCObjectVar<Any?>>()
        if (!url.getResourceValue(value.ptr, forKey = NSURLUbiquitousItemDownloadingStatusKey, error = null)) return null
        value.value as? String
    }

    // endregion

    private fun ByteArray.toNSData(): NSData = memScoped {
        NSData.create(bytes = allocArrayOf(this@toNSData), length = size.toULong())
    }

    private fun NSData.toByteArray(): ByteArray {
        val size = length.toInt()
        if (size == 0) return ByteArray(0)
        return ByteArray(size).also { out -> out.usePinned { memcpy(it.addressOf(0), bytes, length) } }
    }

    private companion object {
        const val TEMP_PREFIX = ".tmp-"
        const val PLACEHOLDER_SUFFIX = ".icloud"
        const val DOWNLOAD_POLL_MS = 250L
    }
}
