# Agent skills

A skill is a set of instructions an AI agent loads when it recognises the task. `ai-edt` teaches
an agent how to drive this server: which facade to reach for, which checks are mandatory after an
edit, how to spend context, and what the responses mean.

Without it an agent still calls the tools, but it tends to discover the same things the expensive
way - reading whole modules, hand-editing files EDT owns, or retrying a call that returned a
resume key.

## Install

Copy the folder into the agent's skills directory. For Claude Code:

```powershell
Copy-Item -Recurse skills\ai-edt "$env:USERPROFILE\.claude\skills\ai-edt"
```

```bash
cp -r skills/ai-edt ~/.claude/skills/ai-edt
```

Per project instead of per user, copy it to `.claude/skills/ai-edt` inside the project.

For another agent, follow that product's own convention: `SKILL.md` is plain Markdown with a name
and a description in the front matter, and the files under `references/` are loaded on demand.

## Keep it honest

The catalogue in `references/facades.md` is a map of a moving target. The server's own
`operation=help` is authoritative, and the skill says so. If you extend the plugin, update the
skill in the same change rather than letting the two drift.
