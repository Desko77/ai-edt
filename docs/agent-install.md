# Installing AI-EDT from an AI agent

This page is written for an AI coding agent with shell access to the machine where 1C:EDT
runs. It describes an unattended installation that needs no clicking.

If you are a person: point your agent at this file and ask it to install or update the
plugin. If you would rather do it yourself, the wizard walkthrough is in the
[README](../README.md#3-install-into-edt).

## Why not the installation wizard

**Help → Install New Software** is a modal wizard. An agent cannot drive it, and on a first
installation of unsigned content it raises a trust dialog that blocks until a human answers
it. The Equinox p2 director is the headless equivalent: it runs as a separate process, asks
nothing, and reports its result as an exit code.

## Before you start

- A built p2 repository. If it does not exist yet, build it first - see
  [Quick start](../README.md#2-build). The output is
  `mcp/repositories/ru.aiedt.mcp.server.repository/target/repository`.
- The EDT installation directory. It contains `1cedtc.exe`, the console launcher used to run
  the director. Do not use `1cedt.exe` for this - that one opens the IDE.
- The workspace path of the EDT session you are updating, if any session is running.

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

Never force-kill EDT. If the process does not exit, something is holding it (usually an open
dialog) - report that and stop rather than killing it.

**3. Wait for the process to actually exit** before touching the profile.

**4. Run the director.**

```powershell
& "<EDT>\1cedtc.exe" -nosplash `
  -application org.eclipse.equinox.p2.director `
  -repository "file:///C:/path/to/AI-EDT/mcp/repositories/ru.aiedt.mcp.server.repository/target/repository" `
  -uninstallIU ru.aiedt.mcp.server.feature.feature.group `
  -installIU ru.aiedt.mcp.server.feature.feature.group `
  -profileProperties org.eclipse.update.reconcile=true
```

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
