/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.lang.reflect.Proxy;

import org.junit.Test;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationContextProvider;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationContext;

/**
 * A project's association context that could not be read is named as not read, and not handed
 * back as the same empty context a project without branches legitimately has.
 * <p>
 * The two used to be one answer - {@code InfobaseAssociationContext.empty()} both ways - so a
 * binding made while the read failed looked like a binding into a project that has no branches,
 * and nobody could tell the difference. The read now says which of the two it was. The provider
 * is handed in directly, which is the seam the distinction is decided at; the service lookup
 * around it answers the same way, by throwing, and lands in the same failure branch.
 * </p>
 */
public class AnAssociationContextThatWasNotReadIsNamedTest
{
    /** A provider that names a branch is read as that branch, with no failure. */
    @Test
    public void aBranchContextIsReadAsTheBranch()
    {
        BmInfobaseLifecycleHelper.ContextRead read = BmInfobaseLifecycleHelper.theProviderAnswered(
            aProvider("refs/heads/develop"), null); //$NON-NLS-1$

        assertNull(read.readFailure);
        assertEquals("refs/heads/develop", read.context.getContext().orElse(null)); //$NON-NLS-1$
    }

    /** A provider that fails names the failure; the context it hands back is the fallback. */
    @Test
    public void aProviderThatFailsIsNamedAsNotRead()
    {
        BmInfobaseLifecycleHelper.ContextRead read = BmInfobaseLifecycleHelper.theProviderAnswered(
            failingProvider(), null);

        assertNotNull("the failed read is not the same answer as no branches", read.readFailure); //$NON-NLS-1$
        assertEquals("the fallback context is still usable", "default", //$NON-NLS-1$ //$NON-NLS-2$
            BmInfobaseLifecycleHelper.describe(read.context));
    }

    /** A provider that answers nothing reads as the default context, with no failure. */
    @Test
    public void aProviderWithoutABranchReadsAsDefault()
    {
        BmInfobaseLifecycleHelper.ContextRead read = BmInfobaseLifecycleHelper.theProviderAnswered(
            aProvider(null), null);

        assertNull("a project without branches is a read that went through", read.readFailure); //$NON-NLS-1$
        assertEquals("default", BmInfobaseLifecycleHelper.describe(read.context)); //$NON-NLS-1$
    }

    /**
     * A provider naming one context.
     *
     * @param ref the branch ref it answers, or {@code null} to answer none
     * @return the provider
     */
    private static IInfobaseAssociationContextProvider aProvider(String ref)
    {
        InfobaseAssociationContext answer =
            ref == null ? InfobaseAssociationContext.empty() : InfobaseAssociationContext.of(ref);
        return (IInfobaseAssociationContextProvider)Proxy.newProxyInstance(
            AnAssociationContextThatWasNotReadIsNamedTest.class.getClassLoader(),
            new Class<?>[] { IInfobaseAssociationContextProvider.class }, (proxy, method, args) -> {
                if ("get".equals(method.getName())) //$NON-NLS-1$
                {
                    return ref == null ? null : answer;
                }
                return null;
            });
    }

    /**
     * A provider whose read fails.
     *
     * @return the provider
     */
    private static IInfobaseAssociationContextProvider failingProvider()
    {
        return (IInfobaseAssociationContextProvider)Proxy.newProxyInstance(
            AnAssociationContextThatWasNotReadIsNamedTest.class.getClassLoader(),
            new Class<?>[] { IInfobaseAssociationContextProvider.class }, (proxy, method, args) -> {
                if ("get".equals(method.getName())) //$NON-NLS-1$
                {
                    throw new IllegalStateException("the storage is locked"); //$NON-NLS-1$
                }
                return null;
            });
    }
}
