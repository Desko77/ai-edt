# Installing AI-EDT from an AI agent

This page is written for an AI coding agent with shell access to the machine where 1C:EDT
runs. It describes an unattended installation that needs no clicking.

If you are a person: point your agent at this file and ask it to install or update the
plugin. A prompt you can copy as is sits in the README, under
[Just ask your agent to install the plugin](../README.en.md#-just-ask-your-agent-to-install-the-plugin).
If you would rather do it yourself, the wizard
walkthrough is right below it.

## Why not the installation wizard

**Help → Install New Software** is a modal wizard. An agent cannot drive it, and on a first
installation of unsigned content it raises a trust dialog that blocks until a human answers
it. The Equinox p2 director is the headless equivalent: it runs as a separate process, asks
nothing, and reports its result as an exit code.

## Before you start

- A p2 repository to install from. Any of these works:
  - the published update site `https://desko77.github.io/ai-edt/`, which needs nothing built
    locally and is the normal choice for a first installation;
  - the latest release archive read in place, which cannot lag behind a release because it is
    the release: `jar:https://github.com/Desko77/ai-edt/releases/latest/download/AI-EDT-update-site.zip!/`
    (the `jar:` prefix and the trailing `!/` are both required);
  - a local build output, `mcp/repositories/ru.aiedt.mcp.server.repository/target/repository`.
    If it does not exist yet, build it first - see [Quick start](../README.en.md#-2-build).
- The EDT installation directory. It contains `1cedtc.exe`, the console launcher used to run
  the director. Do not use `1cedt.exe` for this - that one opens the IDE.
- The workspace path of the EDT session you are updating, if any session is running.

Whether the plugin is already installed changes two steps: how you close EDT (step 2) and
whether the director gets `-uninstallIU` (step 4). Establish that first - a running server
answering on `/health`, or the feature listed in EDT under **Help -> About -> Installation
Details**, means it is installed.

No administrator rights are needed as long as EDT is installed for the current user.

## The sequence

**1. Find the session you are going to update and record how to start it again.**

```powershell
Get-CimInstance Win32_Process -Filter "Name='1cedt.exe'" |
  Select-Object ProcessId, ExecutablePath, CommandLine | Format-List
```

Several sessions may share one EDT installation. Identify yours by the `-data <workspace>`
argument and keep the full command line: you will relaunch with exactly these arguments.

**2. Close that one session, gracefully.**

Use the plugin's own restart tool: `project_admin` with `operation=restart_edt` and
`action=shutdown`. Under the default Canonical preset the standalone `restart_edt` name is
hidden from `tools/list`, though it remains callable; the facade is the route you will see.

It closes the workbench programmatically, so no "Exit 1C:EDT?" prompt appears - an ordinary
window close would stall waiting for that prompt. The MCP connection drops as the server goes
down with the IDE; that is expected, not a failure.

On a first installation that tool does not exist yet, and there is no unattended way to close
EDT: a window close raises the "Exit 1C:EDT?" prompt and waits for a human. Ask the person to
close the session, then continue once the process is gone. If no session is running at all,
skip to step 4.

Never force-kill EDT. If the process does not exit, something is holding it (usually an open
dialog) - report that and stop rather than killing it.

**3. Wait for the process to actually exit** before touching the profile.

**4. Run the director.**

```powershell
& "<EDT>\1cedtc.exe" -nosplash `
  -application org.eclipse.equinox.p2.director `
  -repository "https://desko77.github.io/ai-edt/" `
  -uninstallIU ru.aiedt.mcp.server.feature.feature.group `
  -installIU ru.aiedt.mcp.server.feature.feature.group `
  -profileProperties org.eclipse.update.reconcile=true
```

To install a local build instead, point `-repository` at the build output as a file URL:
`file:///C:/path/to/AI-EDT/mcp/repositories/ru.aiedt.mcp.server.repository/target/repository`.
Everything else stays the same.

The feature is a p2 singleton, so installing a new version while the old one is still present
fails with "only one can be installed". Uninstalling and installing the same unit in one
director request is an atomic update: the old version is removed and the highest version in
the repository is added.

On a very first installation there is nothing to remove, and `-uninstallIU` makes the director
fail instead. Drop that line the first time.

**5. Relaunch the session** with the command line recorded in step 1.

**6. Verify.** Poll `http://localhost:<port>/health` until it answers `status: ok`. The port is
the one configured in that workspace (`mcpServerPort` in the workspace preferences), which is
not necessarily the default. Then confirm the running build is the one you installed.

**7. Install the skill for yourself.** The plugin gives you the tools; the skill tells you how to
use them - which facade fits a job, which checks are mandatory after an edit, what a resume key
means. Without it you still work, just the expensive way: reading whole modules, hand-editing files
EDT owns, retrying a call that already told you to come back for the result.

```powershell
Copy-Item -Recurse skills\ai-edt "$env:USERPROFILE\.claude\skills\ai-edt"
```

That is the per-user location for Claude Code; `.claude/skills/ai-edt` inside the project scopes it
to one project instead. For another agent, follow that product's convention - `SKILL.md` is plain
Markdown with a name and a description in its front matter. Details are in
[skills/README.md](../skills/README.md).

Installing from the update site usually means there is no checkout on the machine. Fetch one shallow
copy, take the folder, drop the rest - do not skip the step:

```powershell
git clone --depth 1 https://github.com/Desko77/ai-edt.git "$env:TEMP\ai-edt-skill"
Copy-Item -Recurse "$env:TEMP\ai-edt-skill\skills\ai-edt" "$env:USERPROFILE\.claude\skills\ai-edt"
Remove-Item -Recurse -Force "$env:TEMP\ai-edt-skill"
```

The folder is four files: `SKILL.md` plus `references/facades.md`, `references/workflows.md` and
`references/expected-behavior.md`, which the agent loads on demand rather than all at once.

**8. Connect the client and call the server once.** An installed plugin answers on
`http://localhost:12250/mcp`; the client still has to be told so. [clients.md](clients.md) carries the
configuration fragment of each client - write the one for the client you are running in, and
nothing else. For Claude Code it is one command:

```powershell
claude mcp add --transport http --scope user AI-EDT http://localhost:12250/mcp
```

The port is the one `GET /health` answered on in step 6; a server moved off the default port is
connected by that port. When **Require bearer token** is on in the AI-EDT preferences, the client
also needs the `Authorization: Bearer <token>` header - ask the user for the token, do not look for
it yourself.

A client reads its server list at start, so the new entry answers in the next session. Say so, and
have the first call of that session be `get_edt_version`: the EDT version in the answer is what
tells an installed and connected server from a configuration line that points at nothing.

## Or run the bundled script

`scripts/edt-selfupdate.ps1` implements the whole sequence above, including the graceful close
and the health poll.

```powershell
pwsh -NoProfile -File scripts\edt-selfupdate.ps1 -WorkspaceMatch <workspace>
```

With a single EDT session running, `-WorkspaceMatch` can be omitted entirely.

| Parameter | Purpose |
|---|---|
| `-WorkspaceMatch` | Substring of the target workspace path. Empty matches any session, which is enough when only one is open. |
| `-RepoPath` | P2 repository to install from. Defaults to the local build output. |
| `-FeatureIU` | Installable unit. Defaults to `ru.aiedt.mcp.server.feature.feature.group`. |
| `-SkipUninstall` | First installation of a feature id that is not in the profile yet. |
| `-NoRestart` | Leave the session closed after installing. |
| `-McpPort` | MCP port. `0` discovers it from the workspace preferences. |
| `-CloseTimeoutSec`, `-HealthTimeoutSec` | Waiting limits for the close and for the health check. |

## Install into every installation, with EDT closed

One machine commonly holds several installations - the one under Program Files and the ones the 1C
launcher keeps in `%LOCALAPPDATA%\1C\1cedtstart\installations` - and each of them records the plugin
in its own `bundles.info`, or in a shared profile under `%USERPROFILE%\.eclipse`. Updating one of
them leaves the others on the build they already had.

`-AllInstallations` on its own prints what it found - the installation path, the EDT version, every
record of the plugin it holds and which of them it loads, and the `java.exe` its director would run -
and installs nothing. Naming the installations with `-InstallationMatch` or `-Every` makes the run
work on them and report the version each carries before and after:

```powershell
pwsh -NoProfile -File scripts\edt-selfupdate.ps1 -AllInstallations -RepoPath <repository>
pwsh -NoProfile -File scripts\edt-selfupdate.ps1 -AllInstallations -Every -RepoPath <repository>
pwsh -NoProfile -File scripts\edt-selfupdate.ps1 -AllInstallations -InstallationMatch 2026.2 -RepoPath <repository>
```

The mode starts no session and closes none. It refuses before installing anything when a
`bundles.info` record of the plugin belongs to no installation found on the machine, when a
`bundles.info` cannot be read, and, naming the PIDs, while a `1cedt.exe` or the JVM it started
belongs to a named installation: close those sessions yourself and run it again. `-WhatIf` prints
the plan and the director command of each named installation without downloading or installing
anything, so a release can be checked before the sessions are closed.

The `java.exe` of a director is taken in this order: the `-vm` of the installation's own `1cedt.ini`
or `1cedtc.ini`, then `-JavaExe`, then `JAVA_HOME`, then the `java` on `PATH`. The source of each
choice is printed with it before the install. An installation whose ini names no `-vm` - which is
what the 1C launcher writes - needs `-JavaExe` when `JAVA_HOME` points at another Java version than
the one that installation starts with.

Release step: with every EDT closed, run `-AllInstallations -Every` against the published
repository, then run `-AllInstallations` alone and read the version each installation reports - it
prints the same table and installs nothing.

| Parameter | Purpose |
|---|---|
| `-AllInstallations` | Work on the installations that record the plugin, with EDT closed. Alone it prints them and installs nothing. |
| `-Every` | Install into every installation found. |
| `-InstallationMatch` | Substring of the installation path; several are separated by `;` in one value. A substring matching no installation is an error. |
| `-WhatIf` (alias `-DryRun`) | Print the plan and change nothing. Requires `-AllInstallations`. |
| `-PythonExe` | Interpreter for `scripts/report-plugin-jars.py`, which lists the installations. Empty searches `python` and `python3`. |
| `-JavaExe` | `java.exe` for installations whose ini names no `-vm`. Empty takes `JAVA_HOME`, and with that empty too the director uses the `java` on `PATH`. |
| `-BundleSymbolicName` | Bundle whose installations and versions are read. Defaults to `ru.aiedt.mcp.server`. |

Exit codes: `0` every named installation carries the installed version; `2` repository, interpreter
or argument problem; `3` refused (no installation named, a record of no installation found here, an
unreadable `bundles.info`, an EDT of a named installation running, or an installation carrying a
higher version without `-AllowDowngrade`); `5` a director run failed; `6` a version was not
confirmed afterwards.

## Rules worth keeping

- **Never force-kill EDT** and never close a session other than the one you were asked to
  update. Several sessions commonly share one installation.
- **The director changes the on-disk profile only.** Any other running session keeps its
  in-memory plugin until that session is itself restarted.
- The director usually succeeds while another session of the same installation is open,
  because a session holds an exclusive profile lock only while it is performing its own
  provisioning operation. If it does fail on a lock, relaunch the session you closed rather
  than leaving the developer without an IDE.
- A restart is required either way: a running instance will not pick up a new version in
  place.
