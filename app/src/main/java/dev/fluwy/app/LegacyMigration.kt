package dev.fluwy.app

import android.content.Context
import dev.fluwy.app.uiconnector.CredentialVault
import java.io.File

/**
 * Migração única dos dados gravados com o nome antigo do projeto para os nomes "fluwy".
 *
 * É o ÚNICO lugar do código que conhece os nomes antigos: arquivos, banco, chave do Keystore,
 * pastas de workspace e formato de backup. Roda no início do app, antes de qualquer leitura.
 * Pode ser removida quando não houver mais instalações anteriores ao rename.
 */
object LegacyMigration {
    private const val OLD = "cueopen"
    private const val OLD_DIR = ".cue"
    const val OLD_BACKUP_FORMAT = "$OLD-uiconnectors-backup"
    private const val PREF = "legacy_migration"
    private const val VERSION = 1

    fun run(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (prefs.getInt("v", 0) >= VERSION) return
        runCatching { moveFile(File(ctx.filesDir, "${OLD}_state.json"), File(ctx.filesDir, "fluwy_state.json")) }
        runCatching { moveDatabase(ctx, "${OLD}_wallet_requests.db", "fluwy_wallet_requests.db") }
        runCatching { CredentialVault(ctx).migrateKey("${OLD}_uic_vault_v1") }
        runCatching { migrateWorkspaces(ctx.filesDir) }
        prefs.edit().putInt("v", VERSION).apply()
    }

    private fun moveFile(from: File, to: File) {
        if (from.exists() && !to.exists()) from.renameTo(to)
    }

    private fun moveDatabase(ctx: Context, old: String, new: String) {
        val from = ctx.getDatabasePath(old)
        if (!from.exists() || ctx.getDatabasePath(new).exists()) return
        listOf("", "-journal", "-wal", "-shm").forEach { s ->
            moveFile(File(from.path + s), File(ctx.getDatabasePath(new).path + s))
        }
    }

    /** Renomeia as pastas ocultas de workspace e corrige a linha do hook no .bashrc. */
    private fun migrateWorkspaces(root: File) {
        root.walkTopDown().maxDepth(8).filter { it.isDirectory && it.name == OLD_DIR }.toList().forEach { dir ->
            moveFile(dir, File(dir.parentFile, ".fluwy"))
            val rc = File(dir.parentFile, ".bashrc")
            if (rc.isFile) rc.writeText(rc.readLines().filterNot { "/$OLD_DIR/" in it }.joinToString("\n", postfix = "\n"))
        }
    }
}
