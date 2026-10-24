package com.example.waspmobile.service

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.util.concurrent.Executors

/** Locally encrypted history/draft only. Never restores commands, sessions, pending sends or approvals. */
internal class AgentHistoryStore(context: Context) {
    private val target = AtomicFile(File(context.noBackupFilesDir, "osrs-agent-history.enc"))
    // A single process-wide writer preserves ordering across an Accessibility service recreation.
    fun restore(desk: AgentDeskState) {
        runCatching {
            if (!target.baseFile.exists() || target.baseFile.length() > 400_000) return
            val encoded = String(target.readFully(), Charsets.UTF_8)
            desk.restoreHistory(PrivateStorageCipher.decrypt(encoded, "osrs-agent-history-v1"))
        }.onFailure { desk.persistenceWarning("Previous local chat could not be read. Pairing and calibration were not erased.") }
    }
    fun save(json: String, failed: () -> Unit = {}) {
        writer.execute {
            runCatching {
                val encoded = PrivateStorageCipher.encrypt(json, "osrs-agent-history-v1").toByteArray(Charsets.UTF_8)
                val stream = target.startWrite()
                try { stream.write(encoded); target.finishWrite(stream) }
                catch (error: Exception) { target.failWrite(stream); throw error }
            }.onFailure { failed() }
        }
    }
    companion object { private val writer = Executors.newSingleThreadExecutor { task ->
        Thread(task, "osrs-private-history").apply { isDaemon = true }
    } }
}
