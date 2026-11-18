package dev.cueopen.app.agent

/** Saídas antigas arquivadas em .cue/out: o que dizer ao agente quando uma delas não existe mais. */
object ContextArchive {
    private val ARCHIVE = Regex("""(^|/)\.cue/out/o[0-9a-f]+_\d+\.txt$""")

    fun isArchivePath(path: String) = ARCHIVE.containsMatchIn(path.trim())

    /** Não começa com "ERRO:" de propósito: não é falha de arquivo do agente e não deve interromper o loop. */
    const val MISSING = "AVISO: esta saída antiga arquivada (.cue/out) não está mais disponível. Não tente outros arquivos de .cue/out. " +
        "Use o que já está nas suas anotações (notes_read) ou refaça a consulta original (web_fetch, browser_read, comando) se ainda precisar do dado."
}
