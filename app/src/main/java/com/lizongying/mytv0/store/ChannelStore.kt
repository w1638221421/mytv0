package com.lizongying.mytv0.store

import java.io.File
import java.io.FileOutputStream

data class CommitResult(
    val success: Boolean,
    val reason: String = "",
    val bytes: Int = 0,
)

/**
 * 频道配置持久化：原子写入 + 自动备份 + 可回滚。
 *
 * 写入流程：
 *   channels.txt.tmp  ← 新内容（fsync）
 *          ↓ rename（同目录，原子）
 *   channels.txt      （旧内容在此之前已复制到 channels.txt.bak）
 *
 * 任何一步失败都不会破坏现有 channels.txt。
 */
class ChannelStore(
    private val dir: File,
    private val fileName: String = DEFAULT_FILE_NAME,
    private val maxBytes: Int = MAX_BYTES,
) {
    val file: File get() = File(dir, fileName)
    val backupFile: File get() = File(dir, "$fileName.bak")
    private val tempFile: File get() = File(dir, "$fileName.tmp")

    fun exists(): Boolean = try {
        file.exists() && file.length() > 0
    } catch (_: Exception) {
        false
    }

    fun read(): String? = try {
        if (file.exists()) file.readText(Charsets.UTF_8) else null
    } catch (_: Exception) {
        null
    }

    fun readBackup(): String? = try {
        if (backupFile.exists()) backupFile.readText(Charsets.UTF_8) else null
    } catch (_: Exception) {
        null
    }

    fun hasBackup(): Boolean = try {
        backupFile.exists() && backupFile.length() > 0
    } catch (_: Exception) {
        false
    }

    /**
     * 提交新配置。仅在内容非空、大小合法时执行。
     * 旧文件在替换前被复制为 .bak，用于回滚。
     */
    fun commit(text: String): CommitResult {
        if (text.isBlank()) return CommitResult(false, "empty")
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > maxBytes) return CommitResult(false, "too_large")

        return try {
            if (!dir.exists() && !dir.mkdirs()) return CommitResult(false, "no_dir")

            tempFile.delete()
            FileOutputStream(tempFile).use { out ->
                out.write(bytes)
                out.flush()
                try {
                    out.fd.sync()
                } catch (_: Exception) {
                    // 部分文件系统不支持 fsync，忽略
                }
            }

            if (file.exists() && file.length() > 0) {
                try {
                    file.copyTo(backupFile, overwrite = true)
                } catch (_: Exception) {
                    // 备份失败不阻断主流程，但保留旧文件内容仍可通过 temp 恢复
                }
            }

            if (!tempFile.renameTo(file)) {
                // rename 失败时退化为复制
                tempFile.copyTo(file, overwrite = true)
                tempFile.delete()
            }

            CommitResult(true, "", bytes.size)
        } catch (e: Exception) {
            try {
                tempFile.delete()
            } catch (_: Exception) {
            }
            CommitResult(false, e.javaClass.simpleName)
        }
    }

    /** 用备份覆盖当前配置，失败返回 false（此时原文件保持不变） */
    fun rollback(): Boolean {
        val bak = try {
            if (!backupFile.exists() || backupFile.length() == 0L) return false
            backupFile.readText(Charsets.UTF_8)
        } catch (_: Exception) {
            return false
        }
        return commit(bak).success
    }

    fun clear(): Boolean = try {
        file.delete()
        backupFile.delete()
        tempFile.delete()
        true
    } catch (_: Exception) {
        false
    }

    companion object {
        const val DEFAULT_FILE_NAME = "channels.txt"
        const val MAX_BYTES = 8 * 1024 * 1024
    }
}
