/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.upkeep;

import java.net.URI;

import org.eclipse.equinox.p2.metadata.Version;

/**
 * Immutable snapshot of everything this installation knows about its own upkeep: which site was
 * consulted, which version runs, which version that site offers, when the answer was obtained and
 * why it failed when it did.
 * <p>
 * A snapshot rather than a set of mutable fields, because three consumers read it - the MCP tool,
 * the background sweep and the status bar - and a torn read would show a version from one check
 * next to a timestamp from another. Publishing a whole new instance makes every observed
 * combination one that actually occurred.
 * </p>
 * <p>
 * <b>The snapshot is bound to its source.</b> It carries the normalized URI it came from and the
 * exact version it offers, so an install can refuse when the configured site has changed since the
 * offer was made. Without that binding an offer obtained from site A could start an install from
 * site B.
 * </p>
 */
public final class ReleaseOffer
{
    /**
     * What the feature is currently doing or knows. Deliberately explicit about work in progress:
     * with a bounded wait on the MCP side a tool call can return before an install finishes, and
     * without {@link #INSTALLING} the status bar would keep offering an install that is already
     * running.
     */
    public enum State
    {
        /** No update site is configured. The feature is asleep: no schedule, no network, no mark. */
        DORMANT,
        /** A site is configured but no answer has been obtained from it yet. */
        NO_DATA,
        /** A check is in flight. Fields carry the previous answer so nothing blinks meanwhile. */
        CHECKING,
        /** The site offers nothing newer than what is installed. */
        UP_TO_DATE,
        /** The site offers a version above the installed one. */
        UPDATE_AVAILABLE,
        /** An install is in flight. */
        INSTALLING,
        /** The profile has been updated but the old code is still running; a restart completes it. */
        RESTART_PENDING,
        /** The last check could not be completed. {@link ReleaseOffer#note()} says why. */
        CHECK_FAILED
    }

    private final State state;
    private final URI site;
    private final Version installed;
    private final Version offered;
    private final long checkedAtMillis;
    private final String note;
    private final boolean managed;

    private ReleaseOffer(State state, URI site, Version installed, Version offered,
        long checkedAtMillis, String note, boolean managed)
    {
        this.state = state;
        this.site = site;
        this.installed = installed;
        this.offered = offered;
        this.checkedAtMillis = checkedAtMillis;
        this.note = note;
        this.managed = managed;
    }

    /**
     * The state of an installation that has no update site configured - the shipped default, and
     * therefore the state most users are in until they choose a source.
     *
     * @return a dormant snapshot
     */
    public static ReleaseOffer dormant()
    {
        return new ReleaseOffer(State.DORMANT, null, null, null, 0L, null, true);
    }

    /**
     * The state of a configured installation that has not been asked anything yet.
     *
     * @param site normalized site URI, never <code>null</code>
     * @return a snapshot awaiting its first answer
     */
    public static ReleaseOffer noData(URI site)
    {
        return new ReleaseOffer(State.NO_DATA, site, null, null, 0L, null, true);
    }

    /**
     * The state of an installation p2 does not manage: the feature is absent from the profile, as
     * happens for a PDE launch, a dropins folder or a different profile.
     * <p>
     * Not a failure and not something to repair - it means updating is out of scope here, so no
     * schedule is started and no install is offered.
     * </p>
     *
     * @param site normalized site URI, may be <code>null</code> when none is configured
     * @param note human-readable explanation
     * @return an unmanaged snapshot
     */
    public static ReleaseOffer unmanaged(URI site, String note)
    {
        return new ReleaseOffer(State.NO_DATA, site, null, null, 0L, note, false);
    }

    /**
     * The site was read and offers nothing newer.
     *
     * @param site normalized site URI
     * @param installed version currently recorded in the profile
     * @param checkedAtMillis wall-clock time of the answer
     * @return an up-to-date snapshot
     */
    public static ReleaseOffer upToDate(URI site, Version installed, long checkedAtMillis)
    {
        return new ReleaseOffer(State.UP_TO_DATE, site, installed, null, checkedAtMillis, null, true);
    }

    /**
     * The site offers a version above the installed one.
     *
     * @param site normalized site URI the offer came from
     * @param installed version currently recorded in the profile
     * @param offered version the site publishes
     * @param checkedAtMillis wall-clock time of the answer
     * @return an offer snapshot
     */
    public static ReleaseOffer available(URI site, Version installed, Version offered,
        long checkedAtMillis)
    {
        return new ReleaseOffer(State.UPDATE_AVAILABLE, site, installed, offered, checkedAtMillis,
            null, true);
    }

    /**
     * The check could not be completed. The installed version is kept when it is known, so a
     * failure does not erase what the profile already told us without any network.
     *
     * @param site normalized site URI, may be <code>null</code>
     * @param installed version currently recorded in the profile, may be <code>null</code>
     * @param note reason, shown to the user verbatim
     * @param checkedAtMillis wall-clock time of the attempt
     * @return a failed snapshot
     */
    public static ReleaseOffer failed(URI site, Version installed, String note, long checkedAtMillis)
    {
        return new ReleaseOffer(State.CHECK_FAILED, site, installed, null, checkedAtMillis, note,
            true);
    }

    /**
     * The profile now holds a different version than the code currently running, so a restart is
     * what completes the update.
     * <p>
     * Reached after a successful install, and also computed from scratch at startup: comparing the
     * running bundle against the profile costs nothing and covers the case where the user vetoed
     * the restart by keeping an unsaved editor open.
     * </p>
     *
     * @param site normalized site URI the install came from, may be <code>null</code>
     * @param running version of the code executing right now
     * @param pending version recorded in the profile, which a restart will activate
     * @param atMillis wall-clock time the divergence was established; passed in rather than read
     *            here so the whole class stays a value type a test can drive
     * @return a restart-pending snapshot
     */
    public static ReleaseOffer restartPending(URI site, Version running, Version pending,
        long atMillis)
    {
        return new ReleaseOffer(State.RESTART_PENDING, site, running, pending, atMillis, null, true);
    }

    /**
     * Returns this snapshot marked as a check in flight, carrying the present knowledge forward.
     * <p>
     * The previous versions and timestamp are kept on purpose: a check that fails must not wipe a
     * known offer, and the status bar must not drop its marker for the duration of every check.
     * </p>
     *
     * @return a new snapshot in {@link State#CHECKING}
     */
    public ReleaseOffer checking()
    {
        return new ReleaseOffer(State.CHECKING, site, installed, offered, checkedAtMillis, note,
            managed);
    }

    /**
     * Returns this snapshot marked as an install in flight, carrying the present knowledge forward.
     *
     * @return a new snapshot in {@link State#INSTALLING}
     */
    public ReleaseOffer installing()
    {
        return new ReleaseOffer(State.INSTALLING, site, installed, offered, checkedAtMillis, note,
            managed);
    }

    /**
     * @return the current state, never <code>null</code>
     */
    public State state()
    {
        return state;
    }

    /**
     * @return the normalized site this snapshot belongs to, or <code>null</code> when none is
     *         configured
     */
    public URI site()
    {
        return site;
    }

    /**
     * @return the version recorded in the profile, or the running version in
     *         {@link State#RESTART_PENDING}; <code>null</code> when unknown
     */
    public Version installed()
    {
        return installed;
    }

    /**
     * @return the version the site offers, or the version a restart will activate in
     *         {@link State#RESTART_PENDING}; <code>null</code> when there is none
     */
    public Version offered()
    {
        return offered;
    }

    /**
     * @return wall-clock time of the last completed answer, or <code>0</code> when there was none
     */
    public long checkedAtMillis()
    {
        return checkedAtMillis;
    }

    /**
     * @return the failure reason or explanation, or <code>null</code> when there is nothing to say
     */
    public String note()
    {
        return note;
    }

    /**
     * @return <code>false</code> only once a lookup established that p2 does not manage this
     *         installation; <code>true</code> otherwise, including before the first lookup
     */
    public boolean managed()
    {
        return managed;
    }

    /**
     * Whether an install can be offered right now. Restart-pending deliberately answers
     * <code>false</code> even though it carries two versions: what it needs is a restart, not
     * another install.
     *
     * @return <code>true</code> when a newer version is known and installable
     */
    public boolean hasUpdate()
    {
        return state == State.UPDATE_AVAILABLE && offered != null;
    }

    @Override
    public String toString()
    {
        return "ReleaseOffer[" + state //$NON-NLS-1$
            + " site=" + site //$NON-NLS-1$
            + " installed=" + installed //$NON-NLS-1$
            + " offered=" + offered //$NON-NLS-1$
            + " managed=" + managed //$NON-NLS-1$
            + (note == null ? "" : " note=" + note) //$NON-NLS-1$ //$NON-NLS-2$
            + "]"; //$NON-NLS-1$
    }
}
