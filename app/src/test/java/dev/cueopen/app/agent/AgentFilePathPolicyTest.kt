package dev.cueopen.app.agent

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class AgentFilePathPolicyTest {
    @Test fun `relative and explicit workspace aliases resolve to the task workspace`() = withTempDir { temp ->
        val workspace = File(temp, "chat/public/workspace").apply { mkdirs() }
        val expected = File(workspace, "site/index.html").canonicalFile

        assertEquals(expected, AgentFilePathPolicy.taskWorkspace(workspace, "site/index.html"))
        assertEquals(expected, AgentFilePathPolicy.taskWorkspace(workspace, "/root/workspace/site/index.html"))
        assertEquals(expected, AgentFilePathPolicy.taskWorkspace(workspace, "/home/workspace/site/index.html"))
        assertEquals(workspace.canonicalFile, AgentFilePathPolicy.taskWorkspace(workspace, "/root/workspace", allowRoot = true))
        assertNull(AgentFilePathPolicy.taskWorkspace(workspace, "/root/workspace", allowRoot = false))
    }

    @Test fun `Android absolute paths arbitrary absolute paths and traversal are rejected`() = withTempDir { temp ->
        val workspace = File(temp, "workspace").apply { mkdirs() }
        listOf(
            "/data/user/0/com.fluwy.bot/files/chat-environments/session/public/workspace/data.txt",
            "/etc/passwd",
            "/storage/emulated/0/Download/data.txt",
            "file:///root/workspace/data.txt",
            "../outside.txt",
            "a/../../outside.txt",
            "folder\\outside.txt",
        ).forEach { assertNull("path=$it", AgentFilePathPolicy.taskWorkspace(workspace, it)) }
        assertNull(AgentFilePathPolicy.taskWorkspace(workspace, ""))
    }

    @Test fun `canonical resolution prevents a symlink from escaping the task workspace`() = withTempDir { temp ->
        val workspace = File(temp, "workspace").apply { mkdirs() }
        val outside = File(temp, "outside").apply { mkdirs() }
        Files.createSymbolicLink(File(workspace, "escape").toPath(), outside.toPath())
        assertNull(AgentFilePathPolicy.taskWorkspace(workspace, "escape/new.txt"))
    }

    @Test fun `connector workspaces are readable by exact id but malformed paths are rejected`() = withTempDir { temp ->
        val taskWorkspace = File(temp, "task/workspace").apply { mkdirs() }
        val connectorsRoot = File(temp, "connectors").apply { mkdirs() }
        val connectorWorkspace = File(connectorsRoot, "ytbaixar/workspace").apply { mkdirs() }
        val expected = File(connectorWorkspace, "data.txt").canonicalFile

        assertEquals(expected, AgentFilePathPolicy.readableWorkspace(taskWorkspace, connectorsRoot, "/root/connectors/ytbaixar/workspace/data.txt"))
        assertEquals(connectorWorkspace.canonicalFile, AgentFilePathPolicy.readableWorkspace(taskWorkspace, connectorsRoot, "/root/connectors/ytbaixar/workspace", allowRoot = true))
        assertEquals(connectorsRoot.canonicalFile, AgentFilePathPolicy.readableWorkspace(taskWorkspace, connectorsRoot, "/root/connectors", allowRoot = true))
        assertNull(AgentFilePathPolicy.readableWorkspace(taskWorkspace, connectorsRoot, "/root/connectors/workspace", allowRoot = true))
        assertNull(AgentFilePathPolicy.readableWorkspace(taskWorkspace, connectorsRoot, "/root/connectors/ytbaixar/output/data.txt"))
        assertNull(AgentFilePathPolicy.readableWorkspace(taskWorkspace, connectorsRoot, "/root/connectors/../outside/workspace/data.txt"))
        assertNotNull(AgentFilePathPolicy.readableWorkspace(taskWorkspace, connectorsRoot, "own.txt"))
    }

    private inline fun withTempDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("agent-file-path-policy-").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }
}
