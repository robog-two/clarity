package net.robog.clarity.sandbox

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Thrown for any request the sandbox refuses; the message tells the model what to do instead. */
class SandboxException(message: String) : Exception(message)

/**
 * The agent's one writable area. Every path a tool receives goes through [resolve], which keeps it
 * inside [root] even through `..` and symlinks (a link inside the workspace pointing outside is
 * rejected, whether or not the final component exists yet).
 */
class Workspace(root: Path) {
    val root: Path = root.toAbsolutePath().normalize().let { if (Files.exists(it)) it.toRealPath() else it }

    /** Directory (inside [root]) where full command output is kept. */
    val logsDir: Path get() = root.resolve(".logs")

    /** Resolves [path] (absolute or relative to [root]) to a real location inside [root]. */
    fun resolve(path: String): Path {
        if (path.isBlank()) throw SandboxException("Path is empty. Give a path inside /workspace, e.g. \"notes/todo.md\".")
        if (path.contains('\u0000')) throw SandboxException("Path contains a NUL character.")
        val requested = root.resolve(Path.of(path)).normalize()
        if (!requested.startsWith(root)) outside(path)
        // Resolve symlinks of the deepest existing ancestor and make sure that is still inside.
        var existing: Path = requested
        while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS) && existing != root) {
            existing = existing.parent ?: outside(path)
        }
        val real = try {
            existing.toRealPath()
        } catch (e: IOException) {
            outside(path) // dangling or looping symlink
        }
        if (!real.startsWith(root)) outside(path)
        return real.resolve(existing.relativize(requested)).normalize()
    }

    /** Path as shown to the model: relative to the workspace root, "." for the root itself. */
    fun display(path: Path): String = root.relativize(path).toString().ifEmpty { "." }

    private fun outside(path: String): Nothing =
        throw SandboxException("Path \"$path\" is outside /workspace. Only files under /workspace are accessible; use a relative path like \"notes/todo.md\".")
}
