/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.upkeep;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.eclipse.equinox.p2.metadata.Version;

/**
 * Every decision the upkeep feature makes that does not need a running platform: which version
 * counts as newer, which URL may be consulted, which addresses belong to the configured source, and
 * when the next check is due.
 * <p>
 * Kept apart from the classes that touch p2 so the rules can be exercised as a table. Two of them
 * carry the whole security posture of the feature and are wrong in ways that read as correct, which
 * is exactly why they live here with tests rather than inline at the call site.
 * </p>
 * <p>
 * <b>What this class is not.</b> The only real boundary in this feature is the user's decision to
 * trust a URL: whoever controls that address executes code in the IDE. The checks below limit
 * surprise and accidental reach - they are not a barrier against a hostile site.
 * </p>
 */
public final class UpkeepPolicy
{
    private static final long MINUTE_MILLIS = 60L * 1000L;
    private static final long HOUR_MILLIS = 60L * MINUTE_MILLIS;

    /** Shortened wait after a failed check, so an unreachable site is retried sooner than a day. */
    private static final long RETRY_INTERVAL_MILLIS = HOUR_MILLIS;

    private static final int MIN_INTERVAL_HOURS = 1;
    private static final int MAX_INTERVAL_HOURS = 24 * 365;
    private static final int MIN_STARTUP_DELAY_MINUTES = 1;
    private static final int MAX_STARTUP_DELAY_MINUTES = 24 * 60;

    private static final String SCHEME_HTTPS = "https"; //$NON-NLS-1$
    private static final String SCHEME_HTTP = "http"; //$NON-NLS-1$
    private static final String SCHEME_FILE = "file"; //$NON-NLS-1$

    private UpkeepPolicy()
    {
    }

    /**
     * The outcome of examining a configured site string: an accepted URI, a refusal with a reason,
     * or the neutral answer that nothing is configured at all.
     * <p>
     * "Not configured" is deliberately not a refusal. It is the shipped default and puts the
     * feature to sleep; reporting it as an error would make every fresh installation look broken.
     * </p>
     */
    public static final class SiteVerdict
    {
        private final URI uri;
        private final String reason;
        private final boolean configured;

        private SiteVerdict(URI uri, String reason, boolean configured)
        {
            this.uri = uri;
            this.reason = reason;
            this.configured = configured;
        }

        /**
         * @return <code>true</code> when the site may be consulted
         */
        public boolean accepted()
        {
            return uri != null;
        }

        /**
         * @return <code>false</code> when no site is configured at all
         */
        public boolean configured()
        {
            return configured;
        }

        /**
         * @return the normalized URI, or <code>null</code> unless {@link #accepted()}
         */
        public URI uri()
        {
            return uri;
        }

        /**
         * @return why the site was refused, or <code>null</code> when it was not
         */
        public String reason()
        {
            return reason;
        }
    }

    /**
     * Whether the offered version is genuinely above the installed one.
     * <p>
     * Both arguments must come from p2 - the profile for the installed side, the repository for the
     * offered side. The plugin's own advertised version is not usable here: it is reduced to
     * major.minor.micro for the wire handshake, and an empty qualifier sorts before any real one,
     * so it would report an update to the very build already running, every time, for ever.
     * </p>
     *
     * @param installed version recorded in the profile, may be <code>null</code>
     * @param offered version published by the site, may be <code>null</code>
     * @return <code>true</code> when both are known and the offered one is strictly newer
     */
    public static boolean isNewer(Version installed, Version offered)
    {
        if (installed == null || offered == null)
        {
            return false;
        }
        return offered.compareTo(installed) > 0;
    }

    /**
     * Examines a configured site string and decides whether it may be consulted at all.
     * <p>
     * <b>Only https.</b> Plain http is refused because the payload is executable code and an
     * unauthenticated transport lets anyone on the path decide what the IDE runs.
     * </p>
     * <p>
     * <b>file: only when explicitly allowed</b>, and then only for a genuinely local directory. On
     * Windows a file URL with an authority - <code>file://server/share</code> - is a network path,
     * so allowing it would turn the local-testing concession into a way to deliver executable code
     * over the network while bypassing the https requirement.
     * </p>
     * <p>
     * <b>Credentials in the URL are refused</b> on either scheme: a password in a preference string
     * is stored in the clear, and p2 has its own secure storage for that purpose.
     * </p>
     *
     * @param raw configured value, may be <code>null</code> or blank
     * @param allowLocalSite whether the local-site preference is switched on
     * @return the verdict, never <code>null</code>
     */
    public static SiteVerdict examineSite(String raw, boolean allowLocalSite)
    {
        if (raw == null || raw.trim().isEmpty())
        {
            return new SiteVerdict(null, null, false);
        }
        URI uri;
        try
        {
            uri = new URI(raw.trim()).normalize();
        }
        catch (URISyntaxException e)
        {
            return refuse("not a valid URL: " + e.getMessage()); //$NON-NLS-1$
        }
        if (!uri.isAbsolute() || uri.getScheme() == null)
        {
            return refuse("the update site must be an absolute URL including its scheme"); //$NON-NLS-1$
        }
        if (uri.isOpaque())
        {
            return refuse("the update site must be a hierarchical URL with a path"); //$NON-NLS-1$
        }
        if (uri.getUserInfo() != null)
        {
            return refuse("credentials in the URL are not accepted"); //$NON-NLS-1$
        }
        if (canonicalSegments(uri.getPath()) == null)
        {
            return refuse("the path climbs above the root of the host"); //$NON-NLS-1$
        }
        String scheme = lower(uri.getScheme());
        if (SCHEME_HTTPS.equals(scheme))
        {
            if (uri.getHost() == null || uri.getHost().isEmpty())
            {
                return refuse("the update site has no usable host name"); //$NON-NLS-1$
            }
            return new SiteVerdict(uri, null, true);
        }
        if (SCHEME_HTTP.equals(scheme))
        {
            return refuse(
                "plain http is not accepted: an update site delivers executable code, so use https"); //$NON-NLS-1$
        }
        if (SCHEME_FILE.equals(scheme))
        {
            if (!allowLocalSite)
            {
                return refuse("a local update site is only used when allowed in the preferences"); //$NON-NLS-1$
            }
            if (uri.getAuthority() != null && !uri.getAuthority().isEmpty())
            {
                return refuse(
                    "a file URL with a host is a network path, not a local directory"); //$NON-NLS-1$
            }
            if (uri.getPath() == null || uri.getPath().isEmpty())
            {
                return refuse("the file URL has no path"); //$NON-NLS-1$
            }
            return new SiteVerdict(uri, null, true);
        }
        return refuse("unsupported scheme '" + scheme + "': only https is accepted"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Whether a candidate address belongs to the configured source.
     * <p>
     * Restricting the provisioning context to one URL means nothing on its own: composite sites,
     * declared mirrors, HTTP redirects and absolute mapping rules in <code>artifacts.xml</code> all
     * move the actual download somewhere else without being a repository reference. Each of those
     * addresses passes through here.
     * </p>
     * <p>
     * <b>Origin and path subtree, never a string prefix.</b> A prefix test accepts
     * <code>/site-evil/</code> for a site at <code>/site</code>, accepts a different port, and is
     * defeated by <code>%2e%2e</code>. This compares scheme, host and effective port, then compares
     * path segments after percent-decoding and resolving <code>.</code> and <code>..</code>, so
     * only a genuine subtree of the configured address is accepted.
     * </p>
     *
     * @param site the configured, already accepted site
     * @param candidate the address p2 is about to reach for
     * @return <code>true</code> when the candidate lies inside the configured source
     */
    public static boolean isWithin(URI site, URI candidate)
    {
        if (site == null || candidate == null || !site.isAbsolute() || !candidate.isAbsolute())
        {
            return false;
        }
        if (site.isOpaque() || candidate.isOpaque())
        {
            return false;
        }
        if (!lower(site.getScheme()).equals(lower(candidate.getScheme())))
        {
            return false;
        }
        if (site.getUserInfo() != null || candidate.getUserInfo() != null)
        {
            return false;
        }
        String siteHost = site.getHost();
        String candidateHost = candidate.getHost();
        if (siteHost == null || candidateHost == null)
        {
            // A file URL has no host at all. Anything else whose authority does not parse as a host
            // is refused rather than guessed at, because an unparsable authority is exactly where a
            // look-alike address hides.
            if (siteHost != null || candidateHost != null)
            {
                return false;
            }
            if (site.getAuthority() != null || candidate.getAuthority() != null)
            {
                return false;
            }
        }
        else if (!lower(siteHost).equals(lower(candidateHost)))
        {
            return false;
        }
        if (effectivePort(site) != effectivePort(candidate))
        {
            return false;
        }
        List<String> base = canonicalSegments(site.getPath());
        List<String> probe = canonicalSegments(candidate.getPath());
        if (base == null || probe == null || probe.size() < base.size())
        {
            return false;
        }
        for (int i = 0; i < base.size(); i++)
        {
            if (!base.get(i).equals(probe.get(i)))
            {
                return false;
            }
        }
        return true;
    }

    /**
     * Converts a configured interval into milliseconds, clamping it into a sane range so a stray
     * zero cannot turn the schedule into a hot loop against someone else's server.
     *
     * @param hours configured interval
     * @return the interval in milliseconds
     */
    public static long intervalMillis(int hours)
    {
        int clamped = Math.max(MIN_INTERVAL_HOURS, Math.min(MAX_INTERVAL_HOURS, hours));
        return clamped * HOUR_MILLIS;
    }

    /**
     * The wait after a failed check.
     * <p>
     * A failure moves the mark like a success does - otherwise an unreachable site would reschedule
     * the task without end - but waiting a full day to retry a transient outage is useless, so the
     * retry uses a shorter interval, never longer than the configured one.
     * </p>
     *
     * @param hours configured interval
     * @return the retry interval in milliseconds
     */
    public static long retryIntervalMillis(int hours)
    {
        return Math.min(intervalMillis(hours), RETRY_INTERVAL_MILLIS);
    }

    /**
     * Converts the configured startup delay into milliseconds, clamped so the first check cannot be
     * dragged into the middle of IDE startup.
     *
     * @param minutes configured delay
     * @return the delay in milliseconds
     */
    public static long startupDelayMillis(int minutes)
    {
        int clamped =
            Math.max(MIN_STARTUP_DELAY_MINUTES, Math.min(MAX_STARTUP_DELAY_MINUTES, minutes));
        return clamped * MINUTE_MILLIS;
    }

    /**
     * How long to wait before the next check.
     * <p>
     * Counted from the last check rather than from startup, so six restarts in a day do not produce
     * six checks. An overdue check still waits out the startup delay instead of firing at once:
     * the delay exists to stay out of the way while the workspace loads, and that reason holds
     * whether or not a check is due.
     * </p>
     *
     * @param now current wall-clock time
     * @param lastCheckMillis time of the last completed check, <code>0</code> when there was none
     * @param intervalMillis interval between checks
     * @param startupDelayMillis minimum wait before any check
     * @return milliseconds to wait
     */
    public static long delayUntilDueMillis(long now, long lastCheckMillis, long intervalMillis,
        long startupDelayMillis)
    {
        if (lastCheckMillis <= 0L)
        {
            return startupDelayMillis;
        }
        long remaining = (lastCheckMillis + intervalMillis) - now;
        if (remaining > intervalMillis)
        {
            // The mark lies in the future, so the clock moved backwards or the stored value is
            // nonsense. Waiting it out literally could park the check for months.
            remaining = intervalMillis;
        }
        return Math.max(startupDelayMillis, remaining);
    }

    /**
     * Whether a check is due, used to throttle background checks. A manual check bypasses this.
     *
     * @param now current wall-clock time
     * @param lastCheckMillis time of the last completed check, <code>0</code> when there was none
     * @param intervalMillis interval between checks
     * @return <code>true</code> when enough time has passed
     */
    public static boolean isDue(long now, long lastCheckMillis, long intervalMillis)
    {
        if (lastCheckMillis <= 0L || lastCheckMillis > now)
        {
            return true;
        }
        return now - lastCheckMillis >= intervalMillis;
    }

    /**
     * The usable last-check mark for a site.
     * <p>
     * The mark belongs to an address, not to the installation. Without that binding, checking site
     * A and then correcting the setting to B would postpone the first check of B by a full
     * interval - the opposite of what a user fixing a wrong URL expects.
     * </p>
     *
     * @param site the site about to be checked
     * @param recordedSite the site the stored mark was produced for, may be <code>null</code>
     * @param recordedMillis the stored mark
     * @return the mark when it belongs to this site, otherwise <code>0</code>
     */
    public static long markFor(URI site, String recordedSite, long recordedMillis)
    {
        if (site == null || recordedSite == null || recordedMillis <= 0L)
        {
            return 0L;
        }
        return site.toString().equals(recordedSite.trim()) ? recordedMillis : 0L;
    }

    /**
     * Whether new work may be started in the given state.
     * <p>
     * A pending restart is the one state that refuses: the profile on disk and the running code
     * already disagree, and checking or installing on top of that would compare the wrong versions
     * and could stack a second update over an unapplied one.
     * </p>
     *
     * @param state current state
     * @return <code>true</code> when a check or install may begin
     */
    public static boolean mayStartWork(ReleaseOffer.State state)
    {
        return state != ReleaseOffer.State.RESTART_PENDING;
    }

    /**
     * Whether a newly found version is worth putting on screen unasked.
     * <p>
     * <b>Once per version, and that is the whole point of remembering one.</b> The check runs daily,
     * so without this the same notice would appear every day until the update was installed - which
     * teaches people to dismiss it without reading, and then the one that mattered goes unread too.
     * The status bar keeps showing the update either way; this governs only the interruption.
     * </p>
     *
     * @param offer the current snapshot
     * @param enabled whether the user wants to be told at all
     * @param announcedVersion the version a notice was already shown for, may be <code>null</code>
     * @return <code>true</code> when this version has not been announced yet
     */
    public static boolean shouldAnnounce(ReleaseOffer offer, boolean enabled, String announcedVersion)
    {
        if (offer == null || !enabled || !offer.hasUpdate())
        {
            return false;
        }
        String offered = offer.offered().toString();
        return announcedVersion == null || !offered.equals(announcedVersion.trim());
    }

    private static SiteVerdict refuse(String reason)
    {
        return new SiteVerdict(null, reason, true);
    }

    private static String lower(String value)
    {
        return value == null ? "" : value.toLowerCase(Locale.ROOT); //$NON-NLS-1$
    }

    private static int effectivePort(URI uri)
    {
        int port = uri.getPort();
        if (port >= 0)
        {
            return port;
        }
        String scheme = lower(uri.getScheme());
        if (SCHEME_HTTPS.equals(scheme))
        {
            return 443;
        }
        if (SCHEME_HTTP.equals(scheme))
        {
            return 80;
        }
        return -1;
    }

    /**
     * Splits a path into comparable segments, decoding first and resolving relative steps
     * afterwards.
     * <p>
     * The order matters: {@link URI#normalize()} works on the raw path and leaves
     * <code>%2e%2e</code> alone, so resolving before decoding would let an encoded step escape the
     * subtree. Decoding first also splits an encoded separator into real segments, which can only
     * make the comparison stricter.
     * </p>
     *
     * @param decodedPath the already percent-decoded path from {@link URI#getPath()}
     * @return the segments, or <code>null</code> when the path climbs above the root
     */
    private static List<String> canonicalSegments(String decodedPath)
    {
        List<String> segments = new ArrayList<>();
        if (decodedPath == null || decodedPath.isEmpty())
        {
            return segments;
        }
        for (String segment : decodedPath.split("/")) //$NON-NLS-1$
        {
            if (segment.isEmpty() || ".".equals(segment)) //$NON-NLS-1$
            {
                continue;
            }
            if ("..".equals(segment)) //$NON-NLS-1$
            {
                if (segments.isEmpty())
                {
                    return null;
                }
                segments.remove(segments.size() - 1);
                continue;
            }
            segments.add(segment);
        }
        return segments;
    }
}
