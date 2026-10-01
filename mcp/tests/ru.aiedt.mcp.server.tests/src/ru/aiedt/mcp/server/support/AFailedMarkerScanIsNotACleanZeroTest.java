/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.junit.Test;

/**
 * A marker scan that failed says so in the answer; it does not read as a project with no errors.
 * <p>
 * The scan's failure used to reach only the log, so the report printed zero errors and zero
 * warnings over a project that was never looked at - a floor dressed as a measurement. The answer
 * now carries partial=true and the reason.
 * </p>
 */
public class AFailedMarkerScanIsNotACleanZeroTest
{
    /**
     * A project whose marker store cannot be read: every {@code findMarkers} call fails the way an
     * inaccessible resource makes it fail.
     *
     * @return the project stand-in
     */
    private static IProject projectWhoseMarkersFail()
    {
        return (IProject)Proxy.newProxyInstance(AFailedMarkerScanIsNotACleanZeroTest.class
            .getClassLoader(), new Class<?>[] { IProject.class }, (proxy, method, args) ->
            {
                if ("findMarkers".equals(method.getName())) //$NON-NLS-1$
                {
                    throw new CoreException(
                        new Status(IStatus.ERROR, "test", "marker store is gone")); //$NON-NLS-1$ //$NON-NLS-2$
                }
                throw new UnsupportedOperationException(method.getName());
            });
    }

    /** The failure is in the answer: partial, with the reason named. */
    @Test
    public void aFailedMarkerScanMarksTheAnswerPartialWithTheReason()
    {
        ProjectMetricsCollector collector =
            new ProjectMetricsCollector(projectWhoseMarkersFail(), 0, false);

        collector.scanMarkers();

        assertTrue("the scan failed, so every count is a floor", collector.isPartial()); //$NON-NLS-1$
        Map<String, Object> metrics = collector.toMetrics(null, null, true);
        assertEquals(Boolean.TRUE, metrics.get("partial")); //$NON-NLS-1$
        assertEquals("marker store is gone", metrics.get("markerScanError")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
