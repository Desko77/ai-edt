/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationContext;

/**
 * A dissociation that failed in one context is warned about even when another context
 * dissociated fine, and every failed context is named.
 * <p>
 * The warning used to be written only when NOTHING dissociated: one successful context set the
 * flag that suppressed it, so a dangling binding left behind by a second, failed context came
 * back as silence. Each failure is its own warning now, and they are all in the one text.
 * </p>
 */
public class ADissociationFailureInALaterContextIsWarnedTest
{
    private static final InfobaseAssociationContext BRANCH =
        InfobaseAssociationContext.of("refs/heads/develop"); //$NON-NLS-1$

    private static final InfobaseAssociationContext DEFAULT = InfobaseAssociationContext.empty();

    /**
     * A manager whose every association exists and whose dissociation fails from the second
     * context on.
     *
     * @param failuresFromTheContext the dissociation call the failures start at (1-based)
     * @return the manager
     */
    private static IInfobaseAssociationManager failingFromThe(int failuresFromThe)
    {
        AtomicInteger dissociations = new AtomicInteger();
        return (IInfobaseAssociationManager)Proxy.newProxyInstance(
            ADissociationFailureInALaterContextIsWarnedTest.class.getClassLoader(),
            new Class<?>[] { IInfobaseAssociationManager.class }, (proxy, method, args) -> {
                if ("getAssociation".equals(method.getName())) //$NON-NLS-1$
                {
                    return Optional.of(new Object());
                }
                if ("dissociate".equals(method.getName())) //$NON-NLS-1$
                {
                    if (dissociations.incrementAndGet() >= failuresFromThe)
                    {
                        throw new IllegalStateException("the context refused");
                    }
                    return null;
                }
                return null;
            });
    }

    /** A failure in the second context is warned about although the first dissociated fine. */
    @Test
    public void aFailureInALaterContextIsStillWarned()
    {
        BmInfobaseLifecycleHelper.DeleteResult r = new BmInfobaseLifecycleHelper.DeleteResult();

        BmInfobaseLifecycleHelper.dissociateEverywhere(failingFromThe(2), null, null,
            contexts(BRANCH, DEFAULT), r, "the-project"); //$NON-NLS-1$

        assertTrue("the first context did dissociate", r.dissociated); //$NON-NLS-1$
        assertNotNull("a failure after a success is not silence", r.dissociateWarning); //$NON-NLS-1$
        assertTrue(r.dissociateWarning, r.dissociateWarning.contains("default")); //$NON-NLS-1$
        assertTrue(r.dissociateWarning, r.dissociateWarning.contains("the context refused")); //$NON-NLS-1$
        assertTrue(r.dissociateWarning, r.dissociateWarning.contains("deletion proceeded")); //$NON-NLS-1$
    }

    /** Failures in every context are all named, and none of them dissociated anything. */
    @Test
    public void everyFailingContextIsNamed()
    {
        BmInfobaseLifecycleHelper.DeleteResult r = new BmInfobaseLifecycleHelper.DeleteResult();

        BmInfobaseLifecycleHelper.dissociateEverywhere(failingFromThe(1), null, null,
            contexts(BRANCH, DEFAULT), r, "the-project"); //$NON-NLS-1$

        assertEquals(false, r.dissociated);
        assertNotNull(r.dissociateWarning);
        assertTrue("both contexts are named: " + r.dissociateWarning, //$NON-NLS-1$
            r.dissociateWarning.contains("refs/heads/develop") && r.dissociateWarning.contains("default")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(r.dissociateWarning, r.dissociateWarning.contains("2 contexts")); //$NON-NLS-1$
    }

    /** A dissociation with no failure anywhere writes no warning. */
    @Test
    public void aCleanDissociationWarnsAboutNothing()
    {
        BmInfobaseLifecycleHelper.DeleteResult r = new BmInfobaseLifecycleHelper.DeleteResult();

        BmInfobaseLifecycleHelper.dissociateEverywhere(failingFromThe(Integer.MAX_VALUE), null,
            null, contexts(BRANCH, DEFAULT), r, "the-project"); //$NON-NLS-1$

        assertTrue(r.dissociated);
        assertNull(r.dissociateWarning);
    }

    /**
     * The contexts to dissociate in, in order.
     *
     * @param first the first context
     * @param second the second context
     * @return them as a set, ordered
     */
    private static Set<InfobaseAssociationContext> contexts(InfobaseAssociationContext first,
        InfobaseAssociationContext second)
    {
        Set<InfobaseAssociationContext> contexts = new LinkedHashSet<>();
        contexts.add(first);
        contexts.add(second);
        return contexts;
    }
}
