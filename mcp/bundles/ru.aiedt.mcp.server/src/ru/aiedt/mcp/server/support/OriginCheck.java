/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks that the sides of a comparison belong together before anything is compared.
 * <p>
 * <b>The failure this exists to stop is silent.</b> A comparison takes directories; a directory that
 * holds some configuration will compare against any other. Name the wrong ancestor and the run
 * succeeds, the counts look plausible, and every attribution is inverted - what we changed reads as
 * the vendor's work and the vendor's changes read as ours. A bulk decision taken on that then
 * applies the wrong rule to everything at once. Nothing in the answer would have said so.
 * </p>
 * <p>
 * The check compares identities read from the configurations themselves against each other, and
 * against what the project's own support registry says it descends from.
 * </p>
 */
public final class OriginCheck
{
    /** What the check found. */
    public static final class Verdict
    {
        /** Identity of the delivery being compared against. */
        public DeliveryIdentity other;

        /** Identity of the delivery both sides came from, when a third side was given. */
        public DeliveryIdentity ancestor;

        /** What the project says it descends from, when it is on support. */
        public String projectDescendsFrom;

        /** Everything that did not line up, one sentence each. */
        public final List<String> mismatches = new ArrayList<>();

        /**
         * Sides that could not be read at all, which is a different fault.
         * <p>
         * A directory that is absent or is not a configuration is not a provenance mismatch, and
         * dressing it as one buries the real problem: the caller mistyped a path. Those are left to
         * the comparison's own path handling, which names them plainly, and recorded here only so
         * the identity check knows it has nothing to compare.
         * </p>
         */
        public final List<String> unreadable = new ArrayList<>();

        /** True when nothing was found to disagree. */
        public boolean agrees()
        {
            return mismatches.isEmpty();
        }
    }

    private OriginCheck()
    {
        // Static helper.
    }

    /**
     * Checks the sides of a comparison against each other and against the project.
     *
     * @param projectName the project playing our side.
     * @param otherPath the delivery being compared against.
     * @param ancestorPath the delivery both came from; may be <code>null</code>.
     * @return what was found, never <code>null</code>
     */
    public static Verdict check(String projectName, String otherPath, String ancestorPath)
    {
        return check(projectName, otherPath, ancestorPath, null);
    }

    /**
     * The same check, told which vendor configuration to measure against.
     *
     * @param projectName the open project.
     * @param otherPath the delivery to compare against.
     * @param ancestorPath the delivery both sides came from; may be <code>null</code>.
     * @param parentId which vendor configuration of the project to check against, by id or name.
     *     Required when the project descends from more than one.
     * @return what lined up and what did not
     */
    public static Verdict check(String projectName, String otherPath, String ancestorPath,
        String parentId)
    {
        Verdict verdict = new Verdict();
        verdict.other = identify(otherPath, verdict, "otherPath"); //$NON-NLS-1$
        if (ancestorPath != null && !ancestorPath.trim().isEmpty())
        {
            verdict.ancestor = identify(ancestorPath, verdict, "ancestorPath"); //$NON-NLS-1$
        }
        compareSides(verdict);
        compareWithProject(projectName, parentId, verdict);
        return verdict;
    }

    /**
     * Reads one side, recording a mismatch when it will not identify itself.
     *
     * @param path the directory.
     * @param verdict where to record a failure.
     * @param argument the argument name, so a refusal points at what to fix.
     * @return the identity, which may carry its own refusal
     */
    private static DeliveryIdentity identify(String path, Verdict verdict, String argument)
    {
        if (path == null || path.trim().isEmpty())
        {
            return null;
        }
        DeliveryIdentity identity;
        try
        {
            identity = DeliveryIdentity.read(Paths.get(path.trim()));
        }
        catch (RuntimeException badPath)
        {
            verdict.unreadable.add(argument + " is not a usable path: " + badPath.getMessage()); //$NON-NLS-1$
            return null;
        }
        if (identity.cannotTell != null)
        {
            verdict.unreadable.add(argument + ": " + identity.cannotTell); //$NON-NLS-1$
        }
        return identity;
    }

    /**
     * Checks the two deliveries against each other.
     * <p>
     * They must be the same configuration in different versions. The same version twice is a
     * mistake worth naming too: a comparison against itself reports no vendor changes at all, which
     * reads exactly like a delivery that changed nothing.
     * </p>
     *
     * @param verdict what has been read so far, and where to record disagreement.
     */
    private static void compareSides(Verdict verdict)
    {
        DeliveryIdentity other = verdict.other;
        DeliveryIdentity ancestor = verdict.ancestor;
        if (other == null || ancestor == null || other.cannotTell != null
            || ancestor.cannotTell != null)
        {
            return;
        }
        if (other.uuid != null && ancestor.uuid != null && !other.uuid.equals(ancestor.uuid))
        {
            verdict.mismatches.add("the two deliveries are different configurations: otherPath is " //$NON-NLS-1$
                + describe(other) + ", ancestorPath is " + describe(ancestor) //$NON-NLS-1$
                + ". Comparing them attributes every change to the wrong side."); //$NON-NLS-1$
        }
        else if (differs(other.name, ancestor.name))
        {
            verdict.mismatches.add("the two deliveries carry different names: " + other.name //$NON-NLS-1$
                + " against " + ancestor.name); //$NON-NLS-1$
        }
        if (differs(other.vendor, ancestor.vendor))
        {
            verdict.mismatches.add("the two deliveries come from different vendors: " //$NON-NLS-1$
                + other.vendor + " against " + ancestor.vendor); //$NON-NLS-1$
        }
        if (other.version != null && other.version.equals(ancestor.version))
        {
            verdict.mismatches.add("both deliveries are version " + other.version //$NON-NLS-1$
                + ", so the comparison would report no vendor changes at all - which is not the " //$NON-NLS-1$
                + "same as a delivery that changed nothing"); //$NON-NLS-1$
        }
    }

    /**
     * Checks the deliveries against what the project descends from.
     * <p>
     * Only when the project is on support and descends from exactly one vendor configuration. With
     * several, which one a delivery should match is a question this cannot answer by itself, and
     * guessing would produce a refusal nobody could act on.
     * </p>
     *
     * @param projectName the project.
     * @param verdict where to record disagreement.
     */
    private static void compareWithProject(String projectName, String parentId, Verdict verdict)
    {
        if (org.eclipse.core.resources.ResourcesPlugin.getWorkspace() == null
            || ProjectResolver.resolve(projectName) == null)
        {
            // No project at all is not a provenance question: there is nothing whose origin could
            // disagree. Kept apart from the branches below because the registry reports both an
            // absent project and an unreachable support service through one field, and refusing on
            // that field alone refuses every comparison made outside a workspace.
            return;
        }
        BmSupportRegistryHelper.Registry registry;
        try
        {
            registry = BmSupportRegistryHelper.read(projectName);
        }
        catch (RuntimeException | LinkageError cannotAsk)
        {
            // Named, not swallowed. This used to return in silence beside a comment saying a
            // project that cannot be asked about support is not a mismatch - true of a project
            // that is not on support, and not true of one whose support subsystem threw. The two
            // are indistinguishable from here, and reading the second as the first turns a failed
            // check into a passed one.
            verdict.mismatches.add("the support state of " + projectName + " could not be read, " //$NON-NLS-1$ //$NON-NLS-2$
                + "so where this project came from cannot be established: " + cannotAsk //$NON-NLS-1$
                + ". Pass ignoreOriginMismatch to compare without that check."); //$NON-NLS-1$
            return;
        }
        if (registry.cannotTell != null)
        {
            verdict.mismatches.add("where this project came from cannot be established: " //$NON-NLS-1$
                + registry.cannotTell
                + ". Pass ignoreOriginMismatch to compare without that check."); //$NON-NLS-1$
            return;
        }
        if (!registry.onSupport)
        {
            // The one branch that is legitimately quiet: there is no vendor to disagree with, so
            // there is nothing to check rather than a check that failed. Said out loud so a reader
            // can tell it apart from the two above.
            verdict.projectDescendsFrom = "nothing - " + projectName + " is not on support"; //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        if (registry.parents.isEmpty())
        {
            verdict.mismatches.add(projectName + " is on support and names no vendor " //$NON-NLS-1$
                + "configuration, which is a state nothing here can act on."); //$NON-NLS-1$
            return;
        }
        BmSupportRegistryHelper.Parent parent = chooseParent(registry, parentId, verdict);
        if (parent == null)
        {
            return;
        }
        verdict.projectDescendsFrom = parent.configName
            + (parent.configRelease == null ? "" : " " + parent.configRelease) //$NON-NLS-1$ //$NON-NLS-2$
            + (parent.providerName == null ? "" : " by " + parent.providerName); //$NON-NLS-1$ //$NON-NLS-2$
        checkAgainstParent(verdict.other, parent, "otherPath", verdict); //$NON-NLS-1$
        checkAgainstParent(verdict.ancestor, parent, "ancestorPath", verdict); //$NON-NLS-1$
        checkAncestorRelease(verdict.ancestor, parent, verdict);
    }

    /**
     * Picks which vendor configuration of the project to measure the deliveries against.
     * <p>
     * <b>With several vendors this used to skip the check entirely and say nothing.</b> A typical
     * application sits on its vendor's support, which sits on a library's, and there the question
     * of which one a delivery should match has an answer - it just is not one this code can guess.
     * Guessing produces a refusal nobody can act on; staying silent produces a comparison whose
     * provenance was never checked, which is worse.
     * </p>
     * <p>
     * The identity of a vendor configuration is allowed to be absent, so matching falls back to the
     * configuration name, and says so when it does.
     * </p>
     *
     * @param registry the support state of the project.
     * @param parentId what the caller named, or <code>null</code>.
     * @param verdict where to record a refusal.
     * @return the vendor configuration to check against, or <code>null</code> when there is no
     *     unambiguous answer
     */
    private static BmSupportRegistryHelper.Parent chooseParent(
        BmSupportRegistryHelper.Registry registry, String parentId, Verdict verdict)
    {
        String wanted = parentId == null || parentId.trim().isEmpty() ? null : parentId.trim();
        if (wanted == null)
        {
            if (registry.parents.size() == 1)
            {
                return registry.parents.get(0);
            }
            verdict.mismatches.add("this project descends from " + registry.parents.size() //$NON-NLS-1$
                + " vendor configurations (" + names(registry) + "), so which one the deliveries " //$NON-NLS-1$ //$NON-NLS-2$
                + "should match is not decidable here. Pass parentId."); //$NON-NLS-1$
            return null;
        }
        List<BmSupportRegistryHelper.Parent> hits = new ArrayList<>();
        for (BmSupportRegistryHelper.Parent parent : registry.parents)
        {
            if (wanted.equals(parent.id) || wanted.equals(parent.configName))
            {
                hits.add(parent);
            }
        }
        if (hits.isEmpty())
        {
            verdict.mismatches.add("no vendor configuration of this project answers to '" + wanted //$NON-NLS-1$
                + "'. It descends from: " + names(registry)); //$NON-NLS-1$
            return null;
        }
        if (hits.size() > 1)
        {
            // Two vendors with no identity and the same name. Rare, and the one case where a
            // choice made here would be a coin toss over which support a delivery belongs to.
            verdict.mismatches.add("'" + wanted + "' matches " + hits.size() //$NON-NLS-1$ //$NON-NLS-2$
                + " vendor configurations of this project, so it does not name one."); //$NON-NLS-1$
            return null;
        }
        return hits.get(0);
    }

    /**
     * Names the vendor configurations a project descends from, for a refusal that has to list them.
     *
     * @param registry the support state.
     * @return the names, comma separated
     */
    private static String names(BmSupportRegistryHelper.Registry registry)
    {
        List<String> named = new ArrayList<>();
        for (BmSupportRegistryHelper.Parent parent : registry.parents)
        {
            named.add(parent.id == null ? parent.configName : parent.configName + " (" //$NON-NLS-1$
                + parent.id + ")"); //$NON-NLS-1$
        }
        return String.join(", ", named); //$NON-NLS-1$
    }

    /**
     * Checks one delivery against the vendor configuration the project descends from.
     *
     * @param identity the delivery; may be <code>null</code>.
     * @param parent what the project descends from.
     * @param argument the argument name, for the message.
     * @param verdict where to record disagreement.
     */
    private static void checkAgainstParent(DeliveryIdentity identity,
        BmSupportRegistryHelper.Parent parent, String argument, Verdict verdict)
    {
        if (identity == null || identity.cannotTell != null)
        {
            return;
        }
        if (differs(identity.name, parent.configName))
        {
            verdict.mismatches.add(argument + " holds " + identity.name //$NON-NLS-1$
                + ", but this project is on the support of " + parent.configName); //$NON-NLS-1$
        }
        if (differs(identity.vendor, parent.providerName))
        {
            verdict.mismatches.add(argument + " comes from " + identity.vendor //$NON-NLS-1$
                + ", but this project is on the support of " + parent.providerName); //$NON-NLS-1$
        }
    }

    /**
     * Checks that the ancestor is the release this project actually stands on.
     * <p>
     * <b>Everything else here compares the two deliveries with each other, or checks that they come
     * from the right vendor. Nothing checked that the ancestor is THIS project's ancestor.</b> A
     * delivery of the same configuration by the same vendor, one release older or newer, passed
     * every check and then inverted the attribution silently: work of ours read as the vendor's,
     * and the vendor's as ours. Every decision downstream is made on that reading.
     * </p>
     * <p>
     * Only the ancestor is held to it. The other side is a different release by definition - that
     * is what an update is.
     * </p>
     *
     * @param ancestor the delivery both sides came from; may be <code>null</code>.
     * @param parent what the project descends from.
     * @param verdict where to record disagreement
     */
    // Package-visible for the test: it is the check that keeps attribution the right way round,
    // and reaching it any other way needs a project on real vendor support.
    static void checkAncestorRelease(DeliveryIdentity ancestor,
        BmSupportRegistryHelper.Parent parent, Verdict verdict)
    {
        if (ancestor == null || ancestor.cannotTell != null)
        {
            return;
        }
        if (ancestor.version == null || parent.configRelease == null)
        {
            // Absence is not disagreement, as everywhere else here: a configuration may leave its
            // release unset, and so will an export made from it. Saying nothing is right; the
            // caller is told what the project descends from either way.
            return;
        }
        if (!ancestor.version.equals(parent.configRelease))
        {
            verdict.mismatches.add("ancestorPath is release " + ancestor.version //$NON-NLS-1$
                + ", but this project stands on " + parent.configRelease //$NON-NLS-1$
                + ". Comparing against a release the project never had attributes changes to the " //$NON-NLS-1$
                + "wrong side."); //$NON-NLS-1$
        }
    }

    /**
     * Compares two values, treating an absent one as no evidence.
     * <p>
     * A configuration may leave its vendor or version unset, and an export made from it will too.
     * Absence is not disagreement; only two different stated values are.
     * </p>
     *
     * @param left one value.
     * @param right the other.
     * @return <code>true</code> when both are stated and differ
     */
    private static boolean differs(String left, String right)
    {
        return left != null && right != null && !left.equals(right);
    }

    /**
     * Describes a delivery for a message.
     *
     * @param identity the delivery.
     * @return a readable phrase
     */
    private static String describe(DeliveryIdentity identity)
    {
        return identity.toString();
    }

    /**
     * Turns the mismatches into one refusal.
     *
     * @param verdict what was found.
     * @return the refusal, or <code>null</code> when the sides agree
     */
    public static String refusal(Verdict verdict)
    {
        if (verdict.agrees())
        {
            return null;
        }
        StringBuilder said = new StringBuilder("the sides of this comparison do not belong " //$NON-NLS-1$
            + "together, and comparing them anyway would attribute changes to the wrong side:"); //$NON-NLS-1$
        for (String mismatch : verdict.mismatches)
        {
            said.append("\n- ").append(mismatch); //$NON-NLS-1$
        }
        if (verdict.projectDescendsFrom != null)
        {
            said.append("\nThe project descends from: ").append(verdict.projectDescendsFrom); //$NON-NLS-1$
        }
        said.append("\nPass ignoreOriginMismatch=true to compare them regardless - legitimate " //$NON-NLS-1$
            + "cases exist, such as a configuration renamed or taken over by another vendor."); //$NON-NLS-1$
        return said.toString();
    }

    /**
     * Reads a path argument the way the comparison tool does, for reuse in the check.
     *
     * @param path the argument.
     * @return the path, or <code>null</code> when nothing was given
     */
    public static Path pathOf(String path)
    {
        return path == null || path.trim().isEmpty() ? null : Paths.get(path.trim());
    }
}
