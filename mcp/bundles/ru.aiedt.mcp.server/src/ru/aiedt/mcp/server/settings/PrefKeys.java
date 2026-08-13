/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.settings;

/**
 * The names and the shipped values of everything this plugin remembers between sessions.
 * <p>
 * These key literals are a wire format, not an implementation detail. They are what appears in
 * <code>ru.aiedt.mcp.server.prefs</code> in every workspace the plugin has ever run in, and a
 * workspace that was set up by an older build is read by a newer one through exactly these names.
 * Renaming a key does not migrate anything and does not fail either - the new name simply has no
 * value, so the user quietly gets the default back and their configuration is gone. Add keys; do not
 * rename them.
 * </p>
 * <p>
 * The <code>DEFAULT_</code> values are compile-time constants because two of them are read where
 * there is no preference store to ask - the server and the protocol handler both run in workspaces
 * with no workbench, and fall back to these.
 * </p>
 */
public final class PrefKeys
{
    // ---- keys -------------------------------------------------------------------------------

    /** TCP port the MCP endpoint listens on. */
    public static final String PREF_PORT = "mcpServerPort"; //$NON-NLS-1$

    /** Whether the endpoint opens by itself when EDT starts. */
    public static final String PREF_AUTO_START = "mcpServerAutoStart"; //$NON-NLS-1$

    /** Folder of markdown descriptions for the EDT validation checks. Empty: use the bundled ones. */
    public static final String PREF_CHECKS_FOLDER = "mcpChecksFolder"; //$NON-NLS-1$

    /** Whether rich results are flattened into a single text block, for clients that need that. */
    public static final String PREF_PLAIN_TEXT_MODE = "mcpPlainTextMode"; //$NON-NLS-1$

    /** Path to the BSL Language Server executable jar. Empty: <code>code_review</code> is off. */
    public static final String PREF_BSL_LS_JAR = "mcpBslLsJar"; //$NON-NLS-1$

    /** Path to a JVM for the BSL Language Server. Empty: run it on the JVM EDT itself runs on. */
    public static final String PREF_BSL_LS_JAVA = "mcpBslLsJava"; //$NON-NLS-1$

    /** Path to vanessa-automation.epf. Empty: <code>vanessa</code> is off. */
    public static final String PREF_VANESSA_EPF = "mcpVanessaEpf"; //$NON-NLS-1$

    /** Path to the 1C thick client that plays Vanessa scenarios. Empty: <code>vanessa</code> is off. */
    public static final String PREF_VANESSA_1C_EXE = "mcpVanessa1cExe"; //$NON-NLS-1$

    /** Whether <code>/mcp</code> demands a bearer token. */
    public static final String PREF_AUTH_ENABLED = "mcpAuthEnabled"; //$NON-NLS-1$

    /** The bearer token itself. */
    public static final String PREF_AUTH_TOKEN = "mcpAuthToken"; //$NON-NLS-1$

    /** Whether the socket binds to every interface rather than to loopback alone. */
    public static final String PREF_BIND_ALL_INTERFACES = "mcpBindAllInterfaces"; //$NON-NLS-1$

    /**
     * Whether 152-FZ PII (INN / SNILS / passport / card / phone / email) is masked in tool
     * responses before they leave the server. Default off: redaction is conservative
     * (checksum-validated) but a wrong mask corrupts an agent's view, so it is opt-in.
     */
    public static final String PREF_PII_REDACT_ENABLED = "mcpPiiRedactEnabled"; //$NON-NLS-1$

    /** The tools the user has switched off: their names, comma-separated, sorted. */
    public static final String PREF_DISABLED_TOOLS = "mcpDisabledTools"; //$NON-NLS-1$

    /**
     * The tools hidden from <code>tools/list</code> but still callable: their names,
     * comma-separated, sorted. This is the third visibility state - a tool here is dropped from the
     * advertised catalogue yet {@code tools/call} still runs it, which is what the "Canonical" preset
     * uses to fold the legacy standalone tools a facade now covers out of sight while every old name
     * keeps working. A tool that is also in {@link #PREF_DISABLED_TOOLS} stays rejected: the call gate
     * only ever consults the disabled set, so disabled wins.
     */
    public static final String PREF_UNLISTED_TOOLS = "mcpUnlistedTools"; //$NON-NLS-1$

    /**
     * Which preset is in force, by its enum constant name; empty when the choice is hand-picked.
     * <p>
     * Without this, applying a preset only ever wrote down the names it covered at the time, and a
     * tool added by a later version was in nobody's list - so an upgrade quietly switched it on for
     * everyone, including the people who had chosen the strictest preset precisely to keep such
     * things off. Recording the choice instead of its outcome lets the sets be worked out afresh
     * against the tools that exist now.
     * </p>
     * <p>
     * The name sets are still written alongside it: they are what a hand-picked selection is made of,
     * and they remain the answer whenever this is empty or names a preset this version no longer has.
     * </p>
     */
    public static final String PREF_TOOL_PRESET = "mcpToolPreset"; //$NON-NLS-1$

    /** How many heavy (expensive) tools may run at once before further ones get a retryable 503. */
    public static final String PREF_HEAVY_TOOL_LIMIT = "mcpHeavyToolLimit"; //$NON-NLS-1$

    /**
     * The share of the heap, as a percentage, that may still be held after a collection before heavy
     * tools are turned away with a retryable 503.
     * <p>
     * The concurrency limit above bounds how many expensive tools run at once, which does nothing for a
     * single agent calling one after another: that series is never concurrent and still walks the heap
     * to its ceiling. Past the ceiling it is not the call that fails but the JVM, and with it the
     * workbench and this server - so the agent loses the whole session rather than one answer.
     * </p>
     * <p>
     * A value outside 1..99 switches the guard off.
     * </p>
     */
    public static final String PREF_HEAP_REFUSAL_PERCENT = "mcpHeapRefusalPercent"; //$NON-NLS-1$

    /** Whether the opt-in debug trace file is written. Off by default; a developer turns it on. */
    public static final String PREF_DEBUG_LOG_ENABLED = "mcpDebugLogEnabled"; //$NON-NLS-1$

    /**
     * The largest tool response, in UTF-8 bytes, the server will send. A larger result is
     * truncated at a code-point boundary with a {@code truncated} marker, so one runaway
     * response cannot exhaust the heap through the redact/parse/frame amplification or swamp
     * the client. {@code 0} or negative disables the cap.
     */
    public static final String PREF_MAX_RESPONSE_BYTES = "mcpMaxResponseBytes"; //$NON-NLS-1$

    /** Whether markers are painted onto metadata objects in the Navigator. */
    public static final String PREF_MARKERS_SHOW_IN_NAVIGATOR = "markers.showInNavigator"; //$NON-NLS-1$

    /** How markers are painted: one of the three <code>MARKERS_STYLE_</code> values. */
    public static final String PREF_MARKERS_DECORATION_STYLE = "markers.decorationStyle"; //$NON-NLS-1$

    /** Set once {@link MarkerSettingsMigration} has carried the pre-rename keys over. */
    public static final String PREF_MARKER_KEYS_MIGRATED = "markers.settingsMigrated"; //$NON-NLS-1$

    // ---- upkeep --------------------------------------------------------------------------------
    //
    // The plugin can look for a newer build of itself on a p2 update site and install it on command.
    // The whole feature sleeps until a site is configured, which is why the shipped address is empty:
    // no schedule, no network, no indicator, nothing to explain to someone who never asked for it.

    /** Whether the plugin looks for newer builds of itself at all. */
    public static final String PREF_UPKEEP_ENABLED = "mcpUpkeepEnabled"; //$NON-NLS-1$

    /**
     * The p2 update site to read.
     * <p>
     * Whoever controls this address executes code in this IDE. That is the whole security model,
     * stated plainly: only <code>https</code> is accepted, and a local directory additionally needs
     * {@link #PREF_UPKEEP_ALLOW_LOCAL_SITE}.
     * </p>
     */
    public static final String PREF_UPKEEP_SITE_URL = "mcpUpkeepSiteUrl"; //$NON-NLS-1$

    /** Hours between background checks. */
    public static final String PREF_UPKEEP_INTERVAL_HOURS = "mcpUpkeepIntervalHours"; //$NON-NLS-1$

    /** Minutes between IDE startup and the first check, so checking stays out of the way of loading. */
    public static final String PREF_UPKEEP_STARTUP_DELAY_MINUTES = "mcpUpkeepStartupDelayMinutes"; //$NON-NLS-1$

    /**
     * When the last check completed. The next one is counted from here rather than from startup, so
     * six restarts in a day do not produce six checks.
     */
    public static final String PREF_UPKEEP_LAST_CHECK_MILLIS = "mcpUpkeepLastCheckMillis"; //$NON-NLS-1$

    /**
     * The site {@link #PREF_UPKEEP_LAST_CHECK_MILLIS} was recorded for.
     * <p>
     * The mark belongs to an address, not to the installation. Without this, checking one site and
     * then correcting the setting to another would postpone the first check of the corrected address
     * by a full interval - the opposite of what someone who just fixed a wrong URL expects.
     * </p>
     */
    public static final String PREF_UPKEEP_LAST_CHECK_SITE = "mcpUpkeepLastCheckSite"; //$NON-NLS-1$

    /** Whether a <code>file:</code> site is accepted, which exists for testing a local build. */
    public static final String PREF_UPKEEP_ALLOW_LOCAL_SITE = "mcpUpkeepAllowLocalSite"; //$NON-NLS-1$

    /** Whether finding a newer build raises a small notice that does not block anything. */
    public static final String PREF_UPKEEP_NOTIFY_POPUP = "mcpUpkeepNotifyPopup"; //$NON-NLS-1$

    /**
     * The version the notice has already been shown for. Without it a daily check would raise the
     * same notice daily until the update is installed.
     */
    public static final String PREF_UPKEEP_NOTIFIED_VERSION = "mcpUpkeepNotifiedVersion"; //$NON-NLS-1$

    // ---- pre-rename marker keys ----------------------------------------------------------------
    //
    // Workspaces configured before the rename still hold these, and a downgrade would read them
    // again, so the migration copies values across and keeps writing both names.

    /** Pre-rename name of {@link #PREF_MARKERS_SHOW_IN_NAVIGATOR}. */
    public static final String LEGACY_MARKERS_SHOW_IN_NAVIGATOR = "tags.showInNavigator"; //$NON-NLS-1$

    /** Pre-rename name of {@link #PREF_MARKERS_DECORATION_STYLE}. */
    public static final String LEGACY_MARKERS_DECORATION_STYLE = "tags.decorationStyle"; //$NON-NLS-1$

    /** Pre-rename spelling of {@link #MARKERS_STYLE_FIRST_MARKER}. */
    public static final String LEGACY_STYLE_FIRST_MARKER = "firstTag"; //$NON-NLS-1$

    // ---- marker decoration styles --------------------------------------------------------------
    //
    // Stored as strings and compared as strings by the decorator, so these are wire literals too.

    /** Append every marker to the label. */
    public static final String MARKERS_STYLE_SUFFIX = "suffix"; //$NON-NLS-1$

    /** Append only the first marker. */
    public static final String MARKERS_STYLE_FIRST_MARKER = "firstMarker"; //$NON-NLS-1$

    /** Append how many markers there are, and not what they are. */
    public static final String MARKERS_STYLE_COUNT = "count"; //$NON-NLS-1$

    // ---- defaults ---------------------------------------------------------------------------

    /**
     * Shipped port.
     * <p>
     * Deliberately not 8765. Another 1C EDT MCP plugin ships that port as its own default, and a
     * developer who has both installed would have the two servers fighting over one socket - the
     * second to start simply fails to bind, with nothing in the UI saying why. A port of our own
     * lets the two coexist in the same EDT.
     * </p>
     */
    public static final int DEFAULT_PORT = 12250;

    /** Shipped heavy-tool concurrency limit: a few at once, enough for parallel agents without a stampede. */
    public static final int DEFAULT_HEAVY_TOOL_LIMIT = 3;

    /**
     * Shipped heap guard: heavy tools are refused once more than this share of the heap survives a
     * collection. Set high enough that ordinary work on a large configuration is never touched - EDT
     * holds a great deal of a large model quite legitimately - and low enough to leave the headroom one
     * expensive call needs. On the 4 GB heap EDT ships with, this keeps roughly 300 MB in reserve.
     */
    public static final int DEFAULT_HEAP_REFUSAL_PERCENT = 92;

    /** Shipped debug trace: off, so nothing is written until a developer asks for it. */
    public static final boolean DEFAULT_DEBUG_LOG_ENABLED = false;

    /**
     * Default response cap: 4 MB of UTF-8. Numerically the same 4M as the Pending
     * registry's oversized threshold, but a different unit - that one counts Java
     * {@code char}s (~8 MB of UTF-16), this one counts wire bytes.
     */
    public static final int DEFAULT_MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

    /** Shipped auto-start: off, so installing the plugin opens no socket. */
    public static final boolean DEFAULT_AUTO_START = false;

    /** Shipped checks folder: none, meaning the descriptions inside the jar. */
    public static final String DEFAULT_CHECKS_FOLDER = ""; //$NON-NLS-1$

    /** Shipped plain-text mode: off, so results keep their structure. */
    public static final boolean DEFAULT_PLAIN_TEXT_MODE = false;

    /** Shipped BSL Language Server jar: none. */
    public static final String DEFAULT_BSL_LS_JAR = ""; //$NON-NLS-1$

    /** Shipped BSL Language Server JVM: none. */
    public static final String DEFAULT_BSL_LS_JAVA = ""; //$NON-NLS-1$

    /** Shipped Vanessa processor: none. */
    public static final String DEFAULT_VANESSA_EPF = ""; //$NON-NLS-1$

    /** Shipped Vanessa thick client: none. */
    public static final String DEFAULT_VANESSA_1C_EXE = ""; //$NON-NLS-1$

    /** Shipped authentication: off - which is only safe while the socket stays on loopback. */
    public static final boolean DEFAULT_AUTH_ENABLED = false;

    /** Shipped token: none. */
    public static final String DEFAULT_AUTH_TOKEN = ""; //$NON-NLS-1$

    /** Shipped binding: loopback only, so nothing off this machine can reach the tools. */
    public static final boolean DEFAULT_BIND_ALL_INTERFACES = false;

    /** Default for {@link #PREF_PII_REDACT_ENABLED}. */
    public static final boolean DEFAULT_PII_REDACT_ENABLED = false;

    /** Shipped disabled set: empty - every tool is on. */
    public static final String DEFAULT_DISABLED_TOOLS = ""; //$NON-NLS-1$

    /** Shipped marker decoration: on. */
    public static final boolean DEFAULT_MARKERS_SHOW_IN_NAVIGATOR = true;

    /** Shipped marker decoration style. */
    public static final String DEFAULT_MARKERS_DECORATION_STYLE = MARKERS_STYLE_SUFFIX;

    /** Shipped upkeep: off. Nothing goes looking for updates until someone asks it to. */
    public static final boolean DEFAULT_UPKEEP_ENABLED = false;

    /**
     * Shipped update site: the one this plugin is published to. Filling it in costs nothing while
     * {@link #DEFAULT_UPKEEP_ENABLED} keeps the check off - the address is read only once someone
     * turns the check on - and it saves that person from typing a URL they have no reason to know
     * by heart. Anyone serving their own builds overwrites the field.
     */
    public static final String DEFAULT_UPKEEP_SITE_URL = "https://desko77.github.io/ai-edt/"; //$NON-NLS-1$

    /** Shipped interval between checks, in hours. */
    public static final int DEFAULT_UPKEEP_INTERVAL_HOURS = 24;

    /** Shipped delay before the first check of a session, in minutes. */
    public static final int DEFAULT_UPKEEP_STARTUP_DELAY_MINUTES = 5;

    /** Shipped local-site permission: off. A local directory is a testing convenience, not a default. */
    public static final boolean DEFAULT_UPKEEP_ALLOW_LOCAL_SITE = false;

    /** Shipped notice: on, since being told about a new build is the point of the feature. */
    public static final boolean DEFAULT_UPKEEP_NOTIFY_POPUP = true;

    private PrefKeys()
    {
        // Constants only.
    }
}
