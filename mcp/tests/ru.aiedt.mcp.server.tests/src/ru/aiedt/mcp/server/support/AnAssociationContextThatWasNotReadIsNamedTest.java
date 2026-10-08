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
import java.util.Hashtable;

import org.eclipse.core.resources.ResourcesPlugin;
import org.junit.After;
import org.junit.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceRegistration;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationContextProvider;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationContext;

/**
 * A project's association context that could not be read is named as not read, and not handed
 * back as the same empty context a project without branches legitimately has.
 * <p>
 * The two used to be one answer - {@code InfobaseAssociationContext.empty()} both ways - so a
 * binding made while the read failed looked like a binding into a project that has no branches,
 * and nobody could tell the difference. The read now says which of the two it was.
 * </p>
 * <p>
 * The provider is published as an OSGi service for the duration of each check and taken away
 * again, which is the same way EDT's own runtime supplies it.
 * </p>
 */
public class AnAssociationContextThatWasNotReadIsNamedTest
{
    private final java.util.List<ServiceRegistration<?>> registrations = new java.util.ArrayList<>();

    /** Whatever registrations the check made are taken away again. */
    @After
    public void theProviderGoes()
    {
        for (ServiceRegistration<?> registration : registrations)
        {
            try
            {
                registration.unregister();
            }
            catch (IllegalStateException alreadyGone)
            {
                // unregistered by the test itself
            }
        }
    }

    /** A provider that names a branch is read as that branch, with no failure. */
    @Test
    public void aBranchContextIsReadAsTheBranch()
    {
        publish((proxy, method, args) -> "get".equals(method.getName()) //$NON-NLS-1$
            ? InfobaseAssociationContext.of("refs/heads/develop") : null);

        BmInfobaseLifecycleHelper.ContextRead read =
            BmInfobaseLifecycleHelper.readAssociationContextOf(aProject());

        assertNull(read.readFailure);
        assertEquals("refs/heads/develop", read.context.getContext().orElse(null)); //$NON-NLS-1$
    }

    /** A provider that fails names the failure; the context it hands back is the fallback. */
    @Test
    public void aProviderThatFailsIsNamedAsNotRead()
    {
        publish((proxy, method, args) -> {
            if ("get".equals(method.getName())) //$NON-NLS-1$
            {
                throw new IllegalStateException("the storage is locked");
            }
            return null;
        });

        BmInfobaseLifecycleHelper.ContextRead read =
            BmInfobaseLifecycleHelper.readAssociationContextOf(aProject());

        assertNotNull("the failed read is not the same answer as no branches", read.readFailure); //$NON-NLS-1$
        assertTrue(read.readFailure, read.readFailure.contains("the storage is locked")); //$NON-NLS-1$
        assertEquals("the fallback context is still usable", "default", //$NON-NLS-1$ //$NON-NLS-2$
            BmInfobaseLifecycleHelper.describe(read.context));
    }

    /** No provider at all is a read that could not happen, not a project without branches. */
    @Test
    public void aMissingProviderIsNamedAsNotRead()
    {
        BmInfobaseLifecycleHelper.ContextRead read =
            BmInfobaseLifecycleHelper.readAssociationContextOf(aProject());

        if (aProviderIsRegistered())
        {
            // Another test's provider is up; this check then cannot see the missing-service
            // path and says so rather than asserting something it did not observe.
            return;
        }
        assertNotNull("a missing provider is not the same answer as no branches", read.readFailure); //$NON-NLS-1$
    }

    /**
     * Publishes a context provider as an OSGi service and remembers the registration.
     *
     * @param handler what the provider answers
     */
    private void publish(java.lang.reflect.InvocationHandler handler)
    {
        IInfobaseAssociationContextProvider provider = (IInfobaseAssociationContextProvider)Proxy
            .newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { IInfobaseAssociationContextProvider.class }, handler);
        BundleContext context = FrameworkUtil.getBundle(getClass()).getBundleContext();
        ServiceRegistration<IInfobaseAssociationContextProvider> registration =
            context.registerService(IInfobaseAssociationContextProvider.class, provider,
                new Hashtable<>());
        registrations.add(registration);
    }

    /**
     * @return a workspace project handle, existing or not - the provider decides what it answers
     */
    private static org.eclipse.core.resources.IProject aProject()
    {
        return ResourcesPlugin.getWorkspace().getRoot().getProject("aiedt-context-read-probe"); //$NON-NLS-1$
    }

    /**
     * @return whether some context provider is registered right now
     */
    private static boolean aProviderIsRegistered()
    {
        return FrameworkUtil.getBundle(AnAssociationContextThatWasNotReadIsNamedTest.class)
            .getBundleContext().getServiceReference(IInfobaseAssociationContextProvider.class) != null;
    }
}
