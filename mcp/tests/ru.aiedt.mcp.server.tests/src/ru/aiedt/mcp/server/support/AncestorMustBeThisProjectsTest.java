/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * Holds the provenance check to checking provenance of THIS project.
 * <p>
 * Everything the check did compared the two deliveries with each other, or checked that they named
 * the right vendor. Nothing established that the ancestor is the release this project actually
 * stands on. A delivery of the same configuration, by the same vendor, one release out, passed
 * every check - and then inverted the attribution in silence: our work read as the vendor's and the
 * vendor's as ours. Every decision downstream is made on that reading, and a merge acts on it.
 * </p>
 * <p>
 * The branches that used to return without a word are held too. There were four, and only one of
 * them was legitimate: a project not on support has no vendor to disagree with. The other three -
 * a registry that would not read, a registry that threw, and a project on support naming no vendor
 * - were failures of the check being reported as the check having passed.
 * </p>
 */
public class AncestorMustBeThisProjectsTest
{
    @Test
    public void anAncestorOfAnotherReleaseIsRefused()
    {
        DeliveryIdentity ancestor = new DeliveryIdentity();
        ancestor.name = "Demo"; //$NON-NLS-1$
        ancestor.vendor = "1C"; //$NON-NLS-1$
        ancestor.version = "3.2.1.400"; //$NON-NLS-1$

        BmSupportRegistryHelper.Parent parent = new BmSupportRegistryHelper.Parent();
        parent.configName = "Demo"; //$NON-NLS-1$
        parent.providerName = "1C"; //$NON-NLS-1$
        parent.configRelease = "3.2.1.505"; //$NON-NLS-1$

        List<String> said = checkRelease(ancestor, parent);

        assertEquals("same configuration, same vendor, wrong release - and it used to pass", //$NON-NLS-1$
            1, said.size());
        assertTrue(said.get(0), said.get(0).contains("3.2.1.400")); //$NON-NLS-1$
        assertTrue(said.get(0), said.get(0).contains("3.2.1.505")); //$NON-NLS-1$
    }

    @Test
    public void theRightReleasePassesQuietly()
    {
        DeliveryIdentity ancestor = new DeliveryIdentity();
        ancestor.name = "Demo"; //$NON-NLS-1$
        ancestor.version = "3.2.1.505"; //$NON-NLS-1$

        BmSupportRegistryHelper.Parent parent = new BmSupportRegistryHelper.Parent();
        parent.configName = "Demo"; //$NON-NLS-1$
        parent.configRelease = "3.2.1.505"; //$NON-NLS-1$

        assertTrue(checkRelease(ancestor, parent).isEmpty());
    }

    @Test
    public void anUnstatedReleaseIsNotDisagreement()
    {
        // A configuration may leave its release unset and an export made from it will too. Absence
        // is treated as no evidence everywhere else in this check, and turning it into a refusal
        // here would refuse legitimate comparisons on a technicality.
        DeliveryIdentity ancestor = new DeliveryIdentity();
        ancestor.name = "Demo"; //$NON-NLS-1$
        ancestor.version = null;

        BmSupportRegistryHelper.Parent parent = new BmSupportRegistryHelper.Parent();
        parent.configName = "Demo"; //$NON-NLS-1$
        parent.configRelease = "3.2.1.505"; //$NON-NLS-1$

        assertTrue(checkRelease(ancestor, parent).isEmpty());

        ancestor.version = "3.2.1.505"; //$NON-NLS-1$
        parent.configRelease = null;
        assertTrue(checkRelease(ancestor, parent).isEmpty());
    }

    @Test
    public void anUnreadableAncestorIsLeftToWhoeverReadsPaths()
    {
        // A directory that is not a configuration is not a provenance problem, and dressing it as
        // one buries the real one: a mistyped path.
        DeliveryIdentity ancestor = new DeliveryIdentity();
        ancestor.cannotTell = "not a configuration"; //$NON-NLS-1$
        ancestor.version = "3.2.1.400"; //$NON-NLS-1$

        BmSupportRegistryHelper.Parent parent = new BmSupportRegistryHelper.Parent();
        parent.configRelease = "3.2.1.505"; //$NON-NLS-1$

        assertTrue(checkRelease(ancestor, parent).isEmpty());
    }

    @Test
    public void noAncestorAtAllIsNothingToCheck()
    {
        BmSupportRegistryHelper.Parent parent = new BmSupportRegistryHelper.Parent();
        parent.configRelease = "3.2.1.505"; //$NON-NLS-1$

        assertTrue(checkRelease(null, parent).isEmpty());
    }

    /**
     * Runs the release check and returns what it said.
     *
     * @param ancestor the ancestor delivery, or <code>null</code>.
     * @param parent what the project descends from.
     * @return the mismatches recorded
     */
    private static List<String> checkRelease(DeliveryIdentity ancestor,
        BmSupportRegistryHelper.Parent parent)
    {
        OriginCheck.Verdict verdict = new OriginCheck.Verdict();
        OriginCheck.checkAncestorRelease(ancestor, parent, verdict);
        return verdict.mismatches;
    }
}
