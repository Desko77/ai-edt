/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.Locale;
import java.util.Map;

import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.upkeep.ReleaseOffer;
import ru.aiedt.mcp.server.upkeep.ReleaseSweep;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * Reports whether a newer build of this plugin is published on the configured update site, and asks
 * the site on demand.
 * <p>
 * Companion to {@code self_status}, which describes the running server; this one describes where
 * that server stands against its source of updates. Both are about the plugin itself rather than
 * about any project, which is why neither takes one.
 * </p>
 * <p>
 * <b>Installing needs an explicit confirmation and replaces the running IDE's own code.</b> It
 * installs the version already reported, not whatever the site holds at that moment, and refuses if
 * the address was edited in between. A restart is what completes it.
 * </p>
 * <p>
 * <b>Trust is the IDE's business, not this tool's.</b> Until releases are signed by a key this
 * plugin vouches for, p2 cannot verify what it downloads and asks through EDT's own trust prompt -
 * so an install may sit waiting for a person at the keyboard. Answering that prompt on their behalf
 * would mean silencing it for every other provisioning operation in the IDE, which is not a trade
 * this tool makes.
 * </p>
 * <p>
 * <b>The parameter is {@code action}, not {@code operation}</b>, following {@code restart_edt}. A
 * facade consumes {@code operation} as the name of the operation it is dispatching and passes the
 * rest through untouched, so a tool keyed on {@code operation} would receive the facade's own
 * routing token instead of its action and could not be reached through a facade at all.
 * </p>
 */
public class SelfUpkeepTool
    implements IMcpTool
{
    public static final String NAME = "self_upkeep"; //$NON-NLS-1$

    private static final String ACTION_STATUS = "status"; //$NON-NLS-1$

    private static final String ACTION_CHECK = "check"; //$NON-NLS-1$

    private static final String ACTION_INSTALL = "install"; //$NON-NLS-1$

    private static final String ACTION_HELP = "help"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Whether a newer build of the AI-EDT plugin is published on the update site " //$NON-NLS-1$
            + "configured in EDT Preferences > AI-EDT > General > Updates. " //$NON-NLS-1$
            + "action=status (default) reports what is already known without touching the network; " //$NON-NLS-1$
            + "action=check asks the site now, ignoring the daily throttle, and BLOCKS until it " //$NON-NLS-1$
            + "answers; action=install applies the version already reported (needs confirm=true) " //$NON-NLS-1$
            + "and restarts EDT unless restart=false; action=help explains the settings. " //$NON-NLS-1$
            + "Until releases are signed, an install may wait on EDT's own trust prompt, which " //$NON-NLS-1$
            + "only a person at the keyboard can answer. " //$NON-NLS-1$
            + "The feature ships switched off with no site set: until someone configures one, " //$NON-NLS-1$
            + "every answer here is 'dormant' and nothing goes to the network."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("action", //$NON-NLS-1$
                "status (default) - report what is known, no network; " //$NON-NLS-1$
                    + "check - ask the configured site now and wait for the answer; " //$NON-NLS-1$
                    + "install - apply the version already reported; " //$NON-NLS-1$
                    + "help - what the settings mean and how to point this at a site.") //$NON-NLS-1$
            .booleanProperty("confirm", //$NON-NLS-1$
                "install only, and required: this replaces the running IDE's plugin code. " //$NON-NLS-1$
                    + "Anything but true refuses.") //$NON-NLS-1$
            .booleanProperty("restart", //$NON-NLS-1$
                "install only: restart EDT afterwards to activate the new version (default " //$NON-NLS-1$
                    + "true). false leaves the profile updated and the old code running until " //$NON-NLS-1$
                    + "EDT is restarted, and guarantees this response is delivered first.") //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String action = JsonUtils.extractStringArgument(params, "action"); //$NON-NLS-1$
        action = action == null || action.trim().isEmpty() ? ACTION_STATUS
            : action.trim().toLowerCase(Locale.ROOT);

        if (ACTION_HELP.equals(action))
        {
            return ToolResult.success().put("action", action).put("help", help()).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (ACTION_STATUS.equals(action))
        {
            return report(action, ReleaseSweep.get().ledger().current());
        }
        if (ACTION_CHECK.equals(action))
        {
            return report(action, ReleaseSweep.get().checkNow(null));
        }
        if (ACTION_INSTALL.equals(action))
        {
            return install(params);
        }
        return ToolResult.error("Unknown action '" + action //$NON-NLS-1$
            + "' - use status, check, install or help.").toJson(); //$NON-NLS-1$
    }

    /**
     * Installs the offer that is already on the table, then optionally restarts.
     * <p>
     * Confirmation is a boolean and it is required. Anything else - missing, false, or a string that
     * is not a boolean at all - is a refusal, because this replaces the running IDE's own code and a
     * caller that did not clearly say yes has not said yes.
     * </p>
     *
     * @param params the call parameters
     * @return the JSON response
     */
    private static String install(Map<String, String> params)
    {
        Boolean confirmed = JsonUtils.extractBooleanArgumentNullable(params, "confirm"); //$NON-NLS-1$
        if (confirmed == null || !confirmed.booleanValue())
        {
            return ToolResult.error("Installing replaces the running IDE's own plugin code, so it " //$NON-NLS-1$
                + "needs confirm=true. Call action=check first and show the user which version " //$NON-NLS-1$
                + "would be installed.").toJson(); //$NON-NLS-1$
        }
        ReleaseSweep sweep = ReleaseSweep.get();
        ReleaseOffer before = sweep.ledger().current();
        if (!before.hasUpdate())
        {
            return ToolResult.error("There is nothing to install: the current state is " //$NON-NLS-1$
                + before.state().name().toLowerCase(Locale.ROOT)
                + ". Use action=check first.").toJson(); //$NON-NLS-1$
        }

        ReleaseOffer after = sweep.installNow(null);
        if (after.state() != ReleaseOffer.State.RESTART_PENDING)
        {
            return report(ACTION_INSTALL, after);
        }

        Boolean restartArg = JsonUtils.extractBooleanArgumentNullable(params, "restart"); //$NON-NLS-1$
        boolean restart = restartArg == null || restartArg.booleanValue();
        String json = report(ACTION_INSTALL, after);
        if (restart)
        {
            // Deliberately after the response is built. Restarting tears down this server, so the
            // existing restart primitive defers the act on a short-lived thread; that is
            // best-effort, not a guarantee, which is why restart=false exists for a caller that
            // needs the answer to arrive for certain.
            new RestartEdtTool().execute(Map.of("action", "restart")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return json;
    }

    /**
     * Renders a snapshot.
     *
     * @param action the action that produced it
     * @param offer the snapshot, never <code>null</code>
     * @return the JSON response
     */
    private static String report(String action, ReleaseOffer offer)
    {
        ToolResult result = ToolResult.success()
            .put("action", action) //$NON-NLS-1$
            .put("state", offer.state().name().toLowerCase(Locale.ROOT)) //$NON-NLS-1$
            .put("updateAvailable", offer.hasUpdate()) //$NON-NLS-1$
            .put("managed", offer.managed()) //$NON-NLS-1$
            .put("watcherRunning", ReleaseSweep.get().isRunning()) //$NON-NLS-1$
            .put("canInstall", offer.hasUpdate()); //$NON-NLS-1$
        if (offer.site() != null)
        {
            result.put("site", offer.site().toString()); //$NON-NLS-1$
        }
        if (offer.installed() != null)
        {
            result.put("installedVersion", offer.installed().toString()); //$NON-NLS-1$
        }
        if (offer.offered() != null)
        {
            // Two meanings, one field in the snapshot, so they are told apart here: awaiting a
            // restart, the second version is already in the profile rather than merely on offer.
            result.put(offer.state() == ReleaseOffer.State.RESTART_PENDING ? "pendingVersion" //$NON-NLS-1$
                : "offeredVersion", offer.offered().toString()); //$NON-NLS-1$
        }
        if (offer.checkedAtMillis() > 0L)
        {
            result.put("checkedAtMillis", offer.checkedAtMillis()); //$NON-NLS-1$
        }
        if (offer.note() != null)
        {
            result.put("note", offer.note()); //$NON-NLS-1$
        }
        return result.put("nextStep", nextStep(offer.state(), offer.managed())).toJson(); //$NON-NLS-1$
    }

    /**
     * What the caller can usefully do about this state.
     * <p>
     * Keyed on the state alone so a test can walk every one of them: a state added later without a
     * hint here would otherwise answer with the fallback text and nobody would notice.
     * </p>
     *
     * @param state the state to advise on
     * @param managed whether p2 manages this installation at all
     * @return one sentence, never <code>null</code>
     */
    static String nextStep(ReleaseOffer.State state, boolean managed)
    {
        if (!managed)
        {
            return "Nothing. This copy of the plugin was not installed through p2 - a PDE launch, " //$NON-NLS-1$
                + "a dropins folder or another profile - so it cannot be updated from a site."; //$NON-NLS-1$
        }
        switch (state)
        {
            case DORMANT:
                return "Set an update site in EDT Preferences > AI-EDT > General > Updates and " //$NON-NLS-1$
                    + "tick the checkbox. Nothing is looked up until then. Whoever controls that " //$NON-NLS-1$
                    + "address controls what the IDE would install, so it should be one the user " //$NON-NLS-1$
                    + "chose deliberately."; //$NON-NLS-1$
            case NO_DATA:
                return "The site has not been asked yet. Use action=check."; //$NON-NLS-1$
            case CHECKING:
                return "A check is in flight. Ask again with action=status."; //$NON-NLS-1$
            case INSTALLING:
                return "An install is in flight. Ask again with action=status."; //$NON-NLS-1$
            case UP_TO_DATE:
                return "Nothing to do - the site offers nothing newer than what is installed."; //$NON-NLS-1$
            case UPDATE_AVAILABLE:
                return "A newer build is published. Show the user which version, then call " //$NON-NLS-1$
                    + "action=install with confirm=true. Until releases are signed, EDT may ask " //$NON-NLS-1$
                    + "the user to trust the artifacts before it proceeds."; //$NON-NLS-1$
            case RESTART_PENDING:
                return "Restart EDT to finish an update that is already in the profile. Until " //$NON-NLS-1$
                    + "then the running code and the profile disagree, and no further check runs."; //$NON-NLS-1$
            case CHECK_FAILED:
                return "The last attempt did not produce an answer - see note. A failure is " //$NON-NLS-1$
                    + "retried sooner than the configured interval."; //$NON-NLS-1$
            default:
                return "No action defined for this state."; //$NON-NLS-1$
        }
    }

    private static String help()
    {
        return "# self_upkeep\n\n" //$NON-NLS-1$
            + "Reports this plugin against its update site. Actions: `status` (default, no " //$NON-NLS-1$
            + "network), `check` (ask the site now, blocking), `help`.\n\n" //$NON-NLS-1$
            + "## Settings\n\n" //$NON-NLS-1$
            + "EDT Preferences > AI-EDT > General > Updates.\n\n" //$NON-NLS-1$
            + "- **Check for updates** - off when shipped. While off, nothing is scheduled and " //$NON-NLS-1$
            + "nothing goes to the network.\n" //$NON-NLS-1$
            + "- **Update site** - empty when shipped. Must be `https`; plain `http` is refused " //$NON-NLS-1$
            + "because the payload is executable code.\n" //$NON-NLS-1$
            + "- **Check every N hours** - the wait counts from the last answer, not from IDE " //$NON-NLS-1$
            + "startup, so restarting does not produce a check each time.\n" //$NON-NLS-1$
            + "- **Allow a local site** - off when shipped. Permits a `file:` directory for " //$NON-NLS-1$
            + "testing. A `file:` URL with a host is a network path and stays refused.\n\n" //$NON-NLS-1$
            + "## Trust\n\n" //$NON-NLS-1$
            + "Whoever controls the configured address decides what would be installed into the " //$NON-NLS-1$
            + "IDE. That is the whole security boundary; nothing else here substitutes for " //$NON-NLS-1$
            + "choosing an address you trust.\n\n" //$NON-NLS-1$
            + "## Installing\n\n" //$NON-NLS-1$
            + "`action=install` needs `confirm=true` and applies the version already reported, " //$NON-NLS-1$
            + "not whatever the site holds at that moment. EDT restarts afterwards unless " //$NON-NLS-1$
            + "`restart=false`. Until releases are signed, p2 cannot verify what it downloads " //$NON-NLS-1$
            + "and asks through EDT's own trust prompt, so an install may wait for a person.\n\n" //$NON-NLS-1$
            + "That prompt is why installing from the status bar - where somebody is looking - is " //$NON-NLS-1$
            + "the reliable route today, and why an install requested through this tool can sit " //$NON-NLS-1$
            + "waiting if nobody is at the keyboard.\n\n" //$NON-NLS-1$
            + "An IDE owner who wants installs to proceed unattended can add " //$NON-NLS-1$
            + "`-Declipse.p2.unsignedPolicy=allow` to `eclipse.ini`. Note what that costs: it " //$NON-NLS-1$
            + "stops that EDT installation checking signatures on ANY provisioning operation, not " //$NON-NLS-1$
            + "just ours. It is their decision to make in their own configuration, and this " //$NON-NLS-1$
            + "plugin neither sets it nor asks for it.\n"; //$NON-NLS-1$
    }
}
