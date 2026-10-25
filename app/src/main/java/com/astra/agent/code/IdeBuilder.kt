package com.astra.agent.code

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.astra.agent.Settings
import com.astra.agent.llm.Completion
import com.astra.agent.term.Stage
import com.astra.agent.term.TermSetup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

// ── What the screens watch ───────────────────────────────────────────────────

/** One file that is being written in front of the user. [text] is everything received so far, [shown] how much of it the typing animation has revealed. */
class LiveFile(val path: String) {
    var text by mutableStateOf("")
    var shown by mutableStateOf(0)
    var done by mutableStateOf(false)
}

enum class TaskState { PENDING, RUNNING, DONE, FAILED }
class TaskRow(val title: String) { var state by mutableStateOf(TaskState.PENDING) }
enum class Phase { THINKING, PLAN, BUILDING, DONE, FAILED }

/**
 * Everything the IDE / Agent screens show about one run: thinking text, status, plan, task list, files being typed,
 * terminal lines. The builder writes to it, the UI only reads it.
 */
class BuildSession(
    val mode: CodeMode,
    val prompt: String,
    /** true = no plan approval step (Claude Code agent). */
    val autoApprove: Boolean,
    /** set = build INTO this existing project folder name instead of creating a new project. */
    val existingProject: String? = null
) {
    var phase by mutableStateOf(Phase.THINKING)
    var status by mutableStateOf("Analyzing request")
    /** Short animated label next to the spinner: Thinking, Researching, Creating file, Writing code, Installing dependencies, Running build, Fixing error. */
    var action by mutableStateOf("Thinking")
    var thinking by mutableStateOf("")
    var plan by mutableStateOf("")
    var title by mutableStateOf("")
    var stack by mutableStateOf("html")
    var projectName by mutableStateOf<String?>(null)
    val tasks = mutableStateListOf<TaskRow>()
    val files = mutableStateListOf<LiveFile>()
    var active by mutableStateOf<LiveFile?>(null)
    val terminal = mutableStateListOf<String>()
    var error by mutableStateOf<String?>(null)
    /** The user's decision on the plan (possibly edited). Completed by the Approve button. */
    val approval = CompletableDeferred<String>()

    /** Called once per frame by the UI: reveals a few more characters of every file that is behind. Returns true while something is still typing. */
    fun typeTick(): Boolean {
        var busy = false
        for (f in files) {
            val total = f.text.length
            if (f.shown > total) f.shown = total
            if (f.shown < total) {
                val backlog = total - f.shown
                f.shown = (f.shown + maxOf(3, backlog / 10)).coerceAtMost(total)
                busy = true
            }
        }
        return busy
    }

    val typingDone: Boolean get() = files.all { it.shown >= it.text.length }

    fun term(line: String) {
        terminal.add(line.take(300))
        while (terminal.size > 400) terminal.removeAt(0)
    }
}

// ── The pipeline ─────────────────────────────────────────────────────────────

object IdeBuilder {
    private const val ANALYZE_SYS = "You are a senior software architect thinking before building. Think deeply about the user's request. " +
        "Write short notes (no code) covering: the goal, who uses it, every feature it needs, the data it stores, the screens, the backend routes, " +
        "edge cases and risks. Plain text, at most 25 lines."

    private fun planSystem(stackHint: String) = "You are a senior software architect. Write a brief but COMPLETE implementation plan using EXACTLY this markdown layout:\n\n" +
        "# <Project title>\nStack: html\n## Overview\n2-3 sentences.\n## Features\n- every feature the user asked for, plus the essentials the app needs\n" +
        "## Files\n- path/to/file.ext — what it contains\n## Data & API\n- data model, backend routes (METHOD /api/...) and how the frontend uses them\n## Tasks\n- short build step\n\n" +
        "Rules:\n- The Stack line is either \"Stack: html\" (plain HTML/CSS/JavaScript) or \"Stack: react\". $stackHint\n" +
        "- Cover the frontend, the backend (a server/ folder with Express routes under /api) and the API whenever the app stores or shares data.\n" +
        "- List every file the app needs, 4 to 12 files, each with its relative path. Do not list node_modules, lock files or images.\n" +
        "- Tasks are 4 to 8 short steps in build order.\n- Output only the plan. No code."

    private const val FILE_SYS = "You are an expert full-stack engineer writing ONE file of a project, following the approved plan.\n" +
        "Rules:\n- Output ONLY the raw content of the requested file. No markdown fences, no explanation before or after it.\n" +
        "- The file must be COMPLETE and working. No placeholders, no TODO, no \"...\".\n" +
        "- Use the relative file names exactly as the plan lists them, and link files to each other correctly.\n" +
        "- If the plan has a backend, the frontend talks to it only through fetch('/api/...') and ALSO falls back to localStorage when the server is not reachable, so the app works when opened without the server.\n" +
        "- Make the interface responsive, polished and accessible. Implement EVERY feature of the plan that belongs in this file."

    private val SCAFFOLD = setOf("package.json", "vite.config.js", "index.html", "src/main.jsx")

    fun wantsReact(prompt: String) = Regex("\\breact\\b", RegexOption.IGNORE_CASE).containsMatchIn(prompt)

    /** The real Linux environment (Node + npm) is only used when its setup has finished. */
    fun linuxReady(): Boolean = TermSetup.ui.value.stage == Stage.READY

    // ── Step 1: think and research, then plan ────────────────────────────────

    suspend fun think(s: Settings, session: BuildSession) {
        val cloud = Completion.usesCloud(s)
        session.phase = Phase.THINKING
        session.status = "Analyzing request"; session.action = "Thinking"
        session.thinking = "Analysis\n"
        Completion.stream(s, ANALYZE_SYS, session.prompt, if (cloud) 1000 else 450, 0.4f) { session.thinking += it }

        session.status = "Researching"; session.action = "Researching"
        session.thinking += "\n\nResearch\n"
        val found = ArrayList<Pair<String, String>>()
        val short = session.prompt.replace(Regex("\\s+"), " ").take(70)
        for (q in listOf("$short features best practices", "$short example app design")) {
            val r = WebLookup.search(q)
            if (r.isEmpty() && found.isEmpty()) { session.thinking += "No internet results, so the plan uses built-in knowledge.\n"; break }
            for (x in r) if (found.none { it.second == x.second } && found.size < 6) { found.add(x); session.thinking += "• ${x.first}\n" }
        }
        session.thinking = session.thinking.trimEnd()

        session.status = "Drafting plan"; session.action = "Drafting plan"
        session.plan = ""
        val hint = when {
            wantsReact(session.prompt) -> "The user asked for React, so use Stack: react."
            else -> "Use Stack: react only if the app really needs a component framework; otherwise Stack: html."
        }
        val research = if (found.isEmpty()) "" else "\n\nIdeas from web research (use only what helps):\n" + found.joinToString("\n") { "- ${it.first}: ${it.second.take(160)}" }
        val text = Completion.stream(s, planSystem(hint), "Request: ${session.prompt}\n\nMy analysis:\n${session.thinking.take(2500)}$research", if (cloud) 1500 else 750, 0.3f) { session.plan += it }
        session.plan = text.ifBlank { session.plan }
        applyPlanMeta(session)
    }

    fun applyPlanMeta(session: BuildSession) {
        val p = session.plan
        session.title = Regex("(?m)^#\\s+(.+)$").find(p)?.groupValues?.get(1)?.trim()?.take(60)?.ifBlank { null } ?: CodeIntent.projectName(session.prompt).replace('-', ' ').replaceFirstChar { it.uppercase() }
        session.stack = if (Regex("(?im)^\\s*stack\\s*:\\s*react").containsMatchIn(p)) "react" else "html"
        session.tasks.clear()
        parseTasks(p).forEach { session.tasks.add(TaskRow(it)) }
    }

    private fun section(plan: String, name: String): List<String> {
        val lines = plan.lines()
        val start = lines.indexOfFirst { Regex("^#{2,3}\\s*" + Regex.escape(name), RegexOption.IGNORE_CASE).containsMatchIn(it.trim()) }
        if (start < 0) return emptyList()
        val out = ArrayList<String>()
        for (l in lines.drop(start + 1)) { if (l.trim().startsWith("#")) break; out.add(l) }
        return out
    }

    fun parseTasks(plan: String): List<String> =
        section(plan, "Tasks").mapNotNull { Regex("^\\s*(?:[-*•]|\\d+[.)])\\s+(.+)$").find(it)?.groupValues?.get(1)?.trim()?.take(90) }.take(10)

    private val fileLine = Regex("^\\s*(?:[-*•]|\\d+[.)])?\\s*[`*]*([\\w@.-]+(?:/[\\w@.-]+)*\\.[A-Za-z0-9]{1,6})[`*]*\\s*(?:[-–—:(]\\s*(.*))?$")

    /** (path, purpose) pairs from the plan's Files section. */
    fun parseFiles(plan: String, max: Int): List<Pair<String, String>> {
        val out = LinkedHashMap<String, String>()
        for (l in section(plan, "Files")) {
            val m = fileLine.find(l) ?: continue
            val path = m.groupValues[1].trimStart('/').removePrefix("./")
            if (path.contains("..") || path.startsWith("node_modules") || path.endsWith(".lock") || path.startsWith(".")) continue
            if (path !in out) out[path] = m.groupValues[2].trim().trimEnd(')').take(160)
        }
        return out.map { it.key to it.value }.take(max)
    }

    // ── Step 2: build ────────────────────────────────────────────────────────

    suspend fun build(ctx: Context, s: Settings, session: BuildSession, modeRoot: File) {
        val cloud = Completion.usesCloud(s)
        session.phase = Phase.BUILDING
        session.status = "Setting up the project"; session.action = "Creating file"
        val name = session.existingProject ?: ProjectStore.uniqueName(modeRoot, session.title.ifBlank { CodeIntent.projectName(session.prompt) })
        session.projectName = name
        val dir = File(modeRoot, name)
        val ws = Workspace(dir)
        ProjectStore.writeMeta(dir, session.title.ifBlank { name }, session.prompt, session.stack, false)
        session.term("$ mkdir -p " + name)
        if (session.existingProject != null) {
            try { if (ws.tree().any { !it.dir }) { ws.commit("Before agent run"); session.term("$ checkpoint saved (use History to restore)") } } catch (_: Throwable) {}
        }

        val vite = session.stack == "react" && linuxReady()
        val cdnReact = session.stack == "react" && !vite
        var planned = parseFiles(session.plan, if (cloud) 12 else 6).filter { it.first !in SCAFFOLD || !vite }
        if (planned.isEmpty()) planned = listOf((if (vite) "src/App.jsx" else "index.html") to "the whole application")
        if (vite && planned.none { it.first == "src/App.jsx" }) planned = listOf("src/App.jsx" to "the root component") + planned
        if (!vite && planned.none { it.first.endsWith(".html") } && session.stack == "html") planned = listOf("index.html" to "the page") + planned
        val hasServer = planned.any { it.first.startsWith("server/") }
        val stackNote = when {
            vite -> "Stack: React + Vite (JSX in src/, entry src/main.jsx imports ./App.jsx, index.html is generated). Frontend code uses ES modules. Backend files use ES module syntax (import/export)."
            cdnReact -> "Stack: React WITHOUT a build step. index.html loads React 18 and @babel/standalone from https://unpkg.com and contains the app inside <script type=\"text/babel\">. Keep all UI code in index.html."
            else -> "Stack: plain HTML, CSS and JavaScript. Backend files (if any) use CommonJS (require)."
        }

        session.status = "Setting up the project"
        if (vite) {
            session.action = "Creating file"
            for ((path, content) in viteScaffold(session.title.ifBlank { name }, hasServer)) putInstant(ws, session, path, content)
        } else if (hasServer && planned.none { it.first == "package.json" }) {
            putInstant(ws, session, "package.json", plainPackageJson(name))
        }

        val written = ArrayList<LiveFile>()
        val total = planned.size
        for ((i, pair) in planned.withIndex()) {
            val (path, purpose) = pair
            advanceTasks(session, i, total)
            val lf = writeFile(s, session, ws, path, purpose, planned, written, stackNote, null)
            if (lf != null) written.add(lf)
        }
        advanceTasks(session, total, total)
        try { ws.commit("Initial build") } catch (_: Throwable) {}

        // verify and repair
        session.status = "Checking the project"; session.action = "Running build"
        session.term("$ zeniq check")
        var problems = verify(ws)
        if (problems.isEmpty()) session.term("  all files present, links and JSON valid")
        var round = 0
        while (problems.isNotEmpty() && round < 2) {
            round++
            session.action = "Fixing error"; session.status = "Fixing ${problems.size} problem(s)"
            for (pr in problems.take(3)) {
                session.term("  ! " + pr.message)
                val fixPath = pr.path ?: continue
                writeFile(s, session, ws, fixPath, pr.fix, planned, written, stackNote, pr.message)
            }
            session.term("$ zeniq check")
            problems = verify(ws)
            if (problems.isEmpty()) session.term("  fixed, all checks pass")
        }
        if (problems.isNotEmpty()) problems.forEach { session.term("  ! still open: " + it.message) }

        if (vite) buildWithNpm(ctx, s, session, ws, planned, written, stackNote)

        ProjectStore.writeMeta(dir, session.title.ifBlank { name }, session.prompt, session.stack, true)
        session.tasks.forEach { if (it.state != TaskState.FAILED) it.state = TaskState.DONE }
        session.status = "Done"; session.action = "Done"
        session.phase = Phase.DONE
    }

    private fun advanceTasks(session: BuildSession, done: Int, total: Int) {
        val n = session.tasks.size
        if (n == 0 || total == 0) return
        val cur = if (done >= total) n else (done * n / total).coerceAtMost(n - 1)
        for ((i, t) in session.tasks.withIndex()) t.state = when { i < cur -> TaskState.DONE; i == cur -> TaskState.RUNNING; else -> TaskState.PENDING }
    }

    /** Writes a file whose content is already known (config files). It still "types out" on screen. */
    private fun putInstant(ws: Workspace, session: BuildSession, path: String, content: String) {
        val lf = LiveFile(path)
        session.files.add(lf); session.active = lf
        session.status = "Creating $path"; session.action = "Creating file"
        lf.text = content
        ws.write(path, content)
        lf.done = true
        session.term("$ write $path")
    }

    private suspend fun writeFile(
        s: Settings, session: BuildSession, ws: Workspace, path: String, purpose: String,
        planned: List<Pair<String, String>>, written: List<LiveFile>, stackNote: String, problem: String?
    ): LiveFile? {
        val cloud = Completion.usesCloud(s)
        val lf = session.files.firstOrNull { it.path == path }?.also { it.text = ""; it.shown = 0; it.done = false } ?: LiveFile(path).also { session.files.add(it) }
        session.active = lf
        session.action = if (problem == null) "Creating file" else "Fixing error"
        session.status = (if (problem == null) "Creating " else "Fixing ") + path
        delay(350)                                         // the file appears in the tree first, then the code starts to type
        if (problem == null) session.action = "Writing code"
        val budget = if (cloud) 12_000 else 2_000
        val ctxText = StringBuilder()
        for (w in written) {
            if (w.path == path) continue
            if (ctxText.length > budget) break
            ctxText.append("--- ").append(w.path).append(" ---\n").append(w.text.take(if (cloud) 1500 else 500)).append("\n\n")
        }
        val user = "Original request: ${session.prompt}\n\nApproved plan:\n${session.plan.take(if (cloud) 4000 else 1500)}\n\n$stackNote\n\n" +
            "All files of the project: " + planned.joinToString(", ") { it.first } + "\n\n" +
            (if (ctxText.isNotEmpty()) "Files already written (excerpts):\n$ctxText" else "") +
            (if (problem != null) "Problem to fix: $problem\n" else "") +
            "Now write the COMPLETE file: $path" + (if (purpose.isNotBlank()) " ($purpose)" else "") + "\nOutput only the file content."
        val raw = StringBuilder()
        try {
            Completion.stream(s, FILE_SYS, user, if (cloud) 4000 else 1400, 0.2f) { chunk ->
                raw.append(chunk)
                lf.text = cleanStream(raw.toString(), false)
            }
        } catch (e: CancellationException) { throw e
        } catch (e: Throwable) {
            session.term("! could not write $path: ${e.message ?: "unknown error"}")
            if (raw.isEmpty()) { lf.done = true; return null }
        }
        val clean = cleanStream(raw.toString(), true).trimEnd() + "\n"
        lf.text = clean
        ws.write(path, clean)
        lf.done = true
        session.term("$ write $path (${clean.lines().size} lines)")
        return lf
    }

    /** Removes the markdown fence a model sometimes puts around a file. While streaming, a half-written closing fence is hidden. */
    fun cleanStream(raw: String, final: Boolean): String {
        var t = raw
        val trimmed = t.trimStart()
        if (trimmed.startsWith("```")) { val nl = trimmed.indexOf('\n'); t = if (nl < 0) "" else trimmed.substring(nl + 1) }
        else if (final) {
            val m = Regex("^[\\s\\S]{0,300}?```[A-Za-z0-9_+#.-]*[ \\t]*\\n([\\s\\S]*?)\\n```", RegexOption.MULTILINE).find(trimmed)
            if (m != null && !trimmed.startsWith("<") && !trimmed.startsWith("{") ) t = m.groupValues[1]
        }
        val lastNl = t.lastIndexOf('\n')
        val tailLine = t.substring(lastNl + 1)
        if (if (final) tailLine.trim().startsWith("```") else tailLine.trim().startsWith("`")) t = if (lastNl < 0) "" else t.substring(0, lastNl + 1)
        return t
    }

    // ── Checks ───────────────────────────────────────────────────────────────

    class Problem(val message: String, val path: String?, val fix: String)

    fun verify(ws: Workspace): List<Problem> {
        val out = ArrayList<Problem>()
        val files = ws.tree().filter { !it.dir }
        for (f in files) {
            val ext = f.rel.substringAfterLast('.', "").lowercase()
            val text = try { ws.read(f.rel) } catch (_: Throwable) { continue }
            if (text.isBlank() && ext in setOf("html", "js", "jsx", "css", "json")) out.add(Problem("${f.rel} is empty", f.rel, "the file was empty"))
            if (ext == "json" && text.isNotBlank()) try { JSONObject(text) } catch (_: Throwable) { out.add(Problem("${f.rel} is not valid JSON", f.rel, "invalid JSON, rewrite it as valid JSON")) }
            if (ext == "html") {
                val dir = f.rel.substringBeforeLast('/', "")
                for (m in Regex("(?:src|href)=[\"']([^\"'#?]+)[\"']", RegexOption.IGNORE_CASE).findAll(text)) {
                    val ref = m.groupValues[1].trim()
                    if (ref.isEmpty() || ref.contains(":") || ref.startsWith("//") || ref == "/") continue
                    val rel = (if (ref.startsWith("/") || dir.isEmpty()) ref.trimStart('/') else "$dir/$ref").removePrefix("./")
                    if (rel.contains("..")) continue
                    val e = rel.substringAfterLast('.', "").lowercase()
                    if (e !in setOf("js", "jsx", "css", "mjs", "html", "json")) continue
                    if (files.none { it.rel == rel }) out.add(Problem("${f.rel} uses $rel but that file does not exist", rel, "referenced by ${f.rel} but missing"))
                }
            }
        }
        return out.distinctBy { it.path ?: it.message }
    }

    private suspend fun buildWithNpm(ctx: Context, s: Settings, session: BuildSession, ws: Workspace, planned: List<Pair<String, String>>, written: List<LiveFile>, stackNote: String) {
        session.status = "Installing dependencies"; session.action = "Installing dependencies"
        session.term("$ npm install")
        var rc = try { TermSetup.runInRoot(ctx, "cd /workspace && npm install --no-audit --no-fund 2>&1", 12 * 60_000L, null, ws.root) { session.term(it) } } catch (e: CancellationException) { throw e } catch (e: Throwable) { session.term("! ${e.message}"); -1 }
        if (rc != 0) { session.term("! npm install failed (code $rc). The project is saved; run it from the Claude Code terminal."); markFailed(session, "Installing dependencies"); return }
        for (attempt in 1..2) {
            session.status = "Running build"; session.action = "Running build"
            session.term("$ npm run build")
            val log = StringBuilder()
            rc = try { TermSetup.runInRoot(ctx, "cd /workspace && npm run build 2>&1", 8 * 60_000L, null, ws.root) { log.appendLine(it); session.term(it) } } catch (e: CancellationException) { throw e } catch (e: Throwable) { session.term("! ${e.message}"); -1 }
            if (rc == 0) { session.term("build finished: dist/ is ready"); return }
            if (attempt == 2) break
            val bad = Regex("((?:src|server)/[\\w./-]+\\.(?:jsx?|tsx?|css))").findAll(log).lastOrNull()?.groupValues?.get(1)
            if (bad == null) break
            session.action = "Fixing error"; session.status = "Fixing $bad"
            writeFile(s, session, ws, bad, "fix the build error", planned, written, stackNote, "npm run build failed:\n" + log.toString().takeLast(1500))
        }
        session.term("! the build still fails. Open the Claude Code terminal and run: npm run build")
        markFailed(session, "Running build")
    }

    private fun markFailed(session: BuildSession, label: String) {
        session.tasks.lastOrNull()?.state = TaskState.FAILED
        session.error = "$label did not finish. Your files are saved; the live preview may be missing until the build passes."
    }

    // ── Templates for React + Vite ───────────────────────────────────────────

    private fun viteScaffold(title: String, hasServer: Boolean): List<Pair<String, String>> {
        val safe = title.replace("\"", "'").replace("<", "").replace(">", "")
        val serverDeps = if (hasServer) ",\n    \"express\": \"^4.21.1\",\n    \"cors\": \"^2.8.5\"" else ""
        val serverScript = if (hasServer) ",\n    \"start\": \"node server/index.js\"" else ""
        return listOf(
            "package.json" to "{\n  \"name\": \"${ProjectStore.slug(title)}\",\n  \"private\": true,\n  \"version\": \"1.0.0\",\n  \"type\": \"module\",\n  \"scripts\": {\n    \"dev\": \"vite\",\n    \"build\": \"vite build\",\n    \"preview\": \"vite preview\"$serverScript\n  },\n  \"dependencies\": {\n    \"react\": \"^18.3.1\",\n    \"react-dom\": \"^18.3.1\"$serverDeps\n  },\n  \"devDependencies\": {\n    \"@vitejs/plugin-react\": \"^4.3.3\",\n    \"vite\": \"^5.4.10\"\n  }\n}\n",
            "vite.config.js" to "import { defineConfig } from 'vite'\nimport react from '@vitejs/plugin-react'\n\n// base './' so the built page also works when it is opened from a folder or inside ZENIQ's preview\nexport default defineConfig({ base: './', plugins: [react()] })\n",
            "index.html" to "<!DOCTYPE html>\n<html lang=\"en\">\n  <head>\n    <meta charset=\"UTF-8\" />\n    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\" />\n    <title>$safe</title>\n  </head>\n  <body>\n    <div id=\"root\"></div>\n    <script type=\"module\" src=\"/src/main.jsx\"></script>\n  </body>\n</html>\n",
            "src/main.jsx" to "import React from 'react'\nimport { createRoot } from 'react-dom/client'\nimport App from './App.jsx'\n\ncreateRoot(document.getElementById('root')).render(<App />)\n"
        )
    }

    private fun plainPackageJson(name: String) =
        "{\n  \"name\": \"${ProjectStore.slug(name)}\",\n  \"version\": \"1.0.0\",\n  \"private\": true,\n  \"scripts\": { \"start\": \"node server/index.js\" },\n  \"dependencies\": { \"express\": \"^4.21.1\", \"cors\": \"^2.8.5\" }\n}\n"
}

/** Small web lookup for the research step (DuckDuckGo HTML results). Returns (title, snippet) pairs; empty when offline. */
object WebLookup {
    suspend fun search(q: String): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        try {
            val c = java.net.URL("https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(q, "UTF-8")).openConnection() as java.net.HttpURLConnection
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
            c.connectTimeout = 8_000; c.readTimeout = 10_000
            val html = c.inputStream.bufferedReader().use { it.readText() }.take(400_000)
            val links = Regex("<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).findAll(html).toList()
            val snippets = Regex("class=\"result__snippet\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).findAll(html).map { it.groupValues[1] }.toList()
            fun clean(x: String) = x.replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'").replace(Regex("\\s+"), " ").trim()
            val out = ArrayList<Pair<String, String>>()
            for ((i, m) in links.withIndex()) {
                val title = clean(m.groupValues[2])
                if (title.isNotBlank()) out.add(title.take(90) to clean(snippets.getOrNull(i).orEmpty()))
                if (out.size >= 4) break
            }
            out
        } catch (e: CancellationException) { throw e
        } catch (_: Throwable) { emptyList() }
    }
}
