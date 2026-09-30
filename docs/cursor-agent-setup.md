# Setting up AI-EDT in Cursor: recipe for the agent

**This page is written for the AI agent inside Cursor.** It installs the AI-EDT plugin into 1C:EDT,
configures it, connects it to Cursor and checks the result, with the person clicking as little as
possible.

If you are a person: open your EDT project folder in Cursor, open the chat (`Ctrl+L`), pick the
Agent mode and paste this prompt:

```text
Установи и настрой мне AI-EDT для Cursor полностью сам.
Рецепт для агента: https://github.com/Desko77/ai-edt/blob/main/docs/cursor-agent-setup.md
Прочитай его целиком и выполняй по шагам. Ничего не завершай принудительно.
Если нужно мое действие - остановись и скажи, что именно сделать.
```

Two steps stay with the person, and the agent asks for them at the right moment: closing EDT on a
first installation (a window close raises a prompt only a human can answer) and allowing the
`ai-edt` server in Cursor (Customize, MCPs).

## Rules for the agent

- Never force-kill EDT or any other process. If something does not exit, report it and stop.
- Touch only the EDT workspace the person works in. Other EDT sessions on the machine are not
  yours.
- Before changing any existing file, copy it next to itself with a timestamp suffix.
- Do not print the bearer token in the chat.
- Talk to the person in their language; keep each request to them to one concrete action.

## 1. Find EDT and the workspace

```powershell
Get-CimInstance Win32_Process -Filter "Name='1cedt.exe'" |
  Select-Object ProcessId, ExecutablePath, CommandLine | Format-List
```

- The workspace is the `-data <path>` argument. Several sessions may run: ask the person which one,
  naming the paths. Keep the full command line of that session - it is how you start it again.
- The EDT installation directory is the one holding `1cedtc.exe` next to `1cedt.exe`
  (typically under `C:\Program Files\1C\1CE\components\1c-edt-*`).
- If no session runs, ask the person for the workspace path and find the installation directory.

## 2. Is the plugin installed?

Try `http://localhost:<port>/health` for ports 12250 to 12259:

```powershell
12250..12259 | ForEach-Object {
  try { "$_ " + (Invoke-RestMethod -TimeoutSec 2 "http://localhost:$_/health" | ConvertTo-Json -Compress) } catch {}
}
```

An answer with `status: ok` means the plugin is installed and running on that port. No answer
means it is not installed, or installed and not started - the person can tell from EDT:
**Help → About → Installation Details** lists `AI-EDT` when it is installed.

## 3. Install or update the plugin

Skip this step when the plugin is installed and you are not asked to update it.

Follow [agent-install.md](agent-install.md), steps 1 to 4: record the session's command line, close
the session, wait for the process to exit, run the p2 director against
`https://desko77.github.io/ai-edt/`. On a first installation there is no tool to close EDT: ask the
person to close it and wait until the process is gone. Do not relaunch EDT yet.

## 4. Set the plugin preferences while EDT is closed

The plugin reads its settings from the workspace:

```
<workspace>\.metadata\.plugins\org.eclipse.core.runtime\.settings\ru.aiedt.mcp.server.prefs
```

Ensure these two lines are present, keeping every other line as it is (create the file with a first
line `eclipse.preferences.version=1` when it does not exist):

```
mcpPlainTextMode=true
mcpServerAutoStart=true
```

- `mcpPlainTextMode` - Cursor does not read MCP resources; with it on, results come back as plain
  text.
- `mcpServerAutoStart` - the server starts together with EDT.

**Write this file only while no `1cedt.exe` with this `-data` workspace is running.** EDT writes its
preferences on exit and would overwrite the change.

If the plugin was already installed and running and step 3 was skipped, EDT is still open. Then do
not close it for this: ask the person to open **Window → Preferences → AI-EDT**, tick
**Plain-text responses (for Cursor)** and **Start the server when EDT opens**, and press
**Apply and Close**.

Also read `mcpServerPort` (the port, default 12250), `mcpAuthEnabled` and `mcpAuthToken` from the
same file for step 6.

## 5. Start EDT and wait for the server

Start the session with the command line recorded in step 1, or with
`1cedt.exe -data "<workspace>"` from the installation directory when no session was running. Poll
`/health` on the port from step 4 until it answers `status: ok` and `phase: ready`. `phase: indexing`
means EDT is still loading the project; keep waiting.

## 6. Connect Cursor

Create or update `.cursor/mcp.json` in the root of the folder opened in Cursor. Keep any servers
already there; add or replace only `ai-edt`:

```json
{
  "mcpServers": {
    "ai-edt": {
      "url": "http://localhost:12250/mcp"
    }
  }
}
```

- Put the real port from step 4 or 5 into the address.
- Do not add `"type": "sse"`: the server speaks Streamable HTTP, and Cursor fails to connect with
  `sse`.
- When `mcpAuthEnabled=true`, add `"headers": {"Authorization": "Bearer <mcpAuthToken>"}`. If the
  folder is under git, check that `.cursor/mcp.json` is ignored before writing the token into it;
  otherwise use `"Bearer ${env:AI_EDT_TOKEN}"` and tell the person to set that variable.

## 7. Install the skill and the rule

They tell you how to use the plugin's tools: which tool fits a job, which checks follow an edit, how
to collect the result of a long operation.

```powershell
git clone --depth 1 https://github.com/Desko77/cursor-1c-skills.git "$env:TEMP\cursor-1c-skills"
New-Item -ItemType Directory -Force .cursor\skills, .cursor\rules | Out-Null
Copy-Item -Recurse "$env:TEMP\cursor-1c-skills\skills\ai-edt-tools" .cursor\skills\
Copy-Item "$env:TEMP\cursor-1c-skills\rules\mcp-tool-priority.mdc" .cursor\rules\
Remove-Item -Recurse -Force "$env:TEMP\cursor-1c-skills"
```

If `.cursor\skills\ai-edt-tools` or `.cursor\rules\mcp-tool-priority.mdc` already exists and
differs, show the person the difference and ask before replacing it.

## 8. Allow the server in Cursor

Cursor does not load a new MCP server until it is allowed.

- If `cursor-agent` is on PATH, run `cursor-agent mcp enable ai-edt` and then
  `cursor-agent mcp list-tools ai-edt`; the second command lists the tools when the connection
  works.
- In the Cursor editor, ask the person to do it, in these words: press `Ctrl+Shift+J`, choose
  **Customize** on the left (or **Open Customize** on the banner at the top), press the **MCPs**
  filter at the top of the Customize tab. Servers are grouped: **Connected** (green mark, number of
  tools enabled) and **Needs Attention** (not allowed yet, or failed). If `ai-edt` is under **Needs
  Attention**, click it and allow it; for a failed one, **Show Output** gives the error. It is done
  when `ai-edt` is under **Connected**. Wait for the person to confirm.
- The skill and the rule show up in the same tab: filter **Skills**, group **Workspaces**,
  `ai-edt-tools`; filter **Rules**, `mcp-tool-priority`.

## 9. Check

Call the `ai-edt` tools `get_edt_version` and `project_admin` with `operation=list_projects`. When
both answer, report to the person: the EDT version, the workspace, the projects, the port, the files
you created or changed (with backups), and that they can now give tasks in plain words, for example
"find where Catalog.Products is used".

If the tools are not visible to you, the server is not allowed in Cursor (step 8) or Cursor needs
the window reloaded: ask the person to run **Developer: Reload Window** from `Ctrl+Shift+P`, then
check again.
