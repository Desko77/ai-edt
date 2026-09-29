/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.util.List;

import org.junit.Test;

import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IDtProject;

/**
 * A wait that waited for nothing is not a wait that confirmed anything.
 * <p>
 * The current EDT builds declare no {@code waitComputation} anywhere - neither
 * {@code IBmModelManager} nor its implementation carries it - so the segment wait always fell
 * through to the fixed sleep and answered {@code true}. {@code waitComputationOk} then said yes
 * about a confirmation that never happened, on every call.
 * </p>
 */
public class AWaitThatWaitedForNothingIsNotOkTest
{
    /**
     * The {@code waitComputation} shape older EDT builds answered to. The manager proxy carries it
     * beside the real interface, because a proxy of the interface alone has no such method for the
     * lookup to find - which is exactly the situation on the builds in use now.
     */
    public interface WaitComputationCarrier
    {
        /**
         * Waits for derived-data segments.
         *
         * @param project the project.
         * @param segments the segment names.
         * @param timeoutMs the budget.
         * @return whether they settled
         */
        boolean waitComputation(IDtProject project, List<String> segments, long timeoutMs);
    }

    private static IDtProject anyDtProject()
    {
        return (IDtProject)Proxy.newProxyInstance(IDtProject.class.getClassLoader(),
            new Class<?>[]{ IDtProject.class }, (proxy, method, args) -> null);
    }

    /** A build that declares no waitComputation confirms nothing, but still waits the grace sleep. */
    @Test
    public void aBuildWithNoWaitComputationIsNotConfirmed() throws InterruptedException
    {
        IBmModelManager manager = (IBmModelManager)Proxy.newProxyInstance(
            IBmModelManager.class.getClassLoader(), new Class<?>[]{ IBmModelManager.class },
            (proxy, method, args) -> null);

        long startedAt = System.currentTimeMillis();
        boolean confirmed = BmExportHelper.waitForSegments(manager, anyDtProject(),
            List.of(BmExportHelper.DD_SEGMENT_EXPORT_OBJECT), 300L);

        assertFalse("nothing confirmed the segments, whatever the sleep did", confirmed);
        long waited = System.currentTimeMillis() - startedAt;
        assertTrue("the fallback sleep still gave the export its moment: " + waited + "ms", //$NON-NLS-1$
            waited >= 250L);
    }

    /** A waitComputation that threw is a wait that confirmed nothing. */
    @Test
    public void aCallThatThrewIsNotConfirmed() throws InterruptedException
    {
        IBmModelManager manager = (IBmModelManager)Proxy.newProxyInstance(
            WaitComputationCarrier.class.getClassLoader(),
            new Class<?>[]{ IBmModelManager.class, WaitComputationCarrier.class },
            (proxy, method, args) -> {
                if ("waitComputation".equals(method.getName())) //$NON-NLS-1$
                {
                    throw new IllegalStateException("probe: waitComputation refused"); //$NON-NLS-1$
                }
                return null;
            });

        boolean confirmed = BmExportHelper.waitForSegments(manager, anyDtProject(),
            List.of(BmExportHelper.DD_SEGMENT_EXPORT_OBJECT), 300L);

        assertFalse("a wait that threw did not confirm the segments", confirmed); //$NON-NLS-1$
    }

    /** A wait EDT answered with true stays confirmed - the honest no does not eat the yes. */
    @Test
    public void aWaitThatAnsweredTrueIsConfirmed() throws InterruptedException
    {
        IBmModelManager manager = (IBmModelManager)Proxy.newProxyInstance(
            WaitComputationCarrier.class.getClassLoader(),
            new Class<?>[]{ IBmModelManager.class, WaitComputationCarrier.class },
            (proxy, method, args) -> {
                if ("waitComputation".equals(method.getName())) //$NON-NLS-1$
                {
                    return Boolean.TRUE;
                }
                return null;
            });

        assertTrue(BmExportHelper.waitForSegments(manager, anyDtProject(),
            List.of(BmExportHelper.DD_SEGMENT_EXPORT_OBJECT), 300L));
    }
}
