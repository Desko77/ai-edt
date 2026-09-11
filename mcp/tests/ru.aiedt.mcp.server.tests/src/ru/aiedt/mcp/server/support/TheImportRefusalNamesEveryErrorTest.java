/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.MultiStatus;
import org.eclipse.core.runtime.Status;
import org.junit.Test;

/**
 * A refused import names every error the operation collected, not the line that counts them.
 * <p>
 * The import operation answers with a multi-status whose own message reads "several errors
 * occurred"; the errors are its children.
 * </p>
 */
public class TheImportRefusalNamesEveryErrorTest
{
    private static final String PLUGIN = "ru.aiedt.mcp.server.tests"; //$NON-NLS-1$

    @Test
    public void theChildrenOfAMultiStatusAreNamedUnderItsOwnLine()
    {
        MultiStatus several = new MultiStatus(PLUGIN, 0, "several errors occurred", null); //$NON-NLS-1$
        several.add(new Status(IStatus.ERROR, PLUGIN, "the form ФормаОбр has no root")); //$NON-NLS-1$
        several.add(new Status(IStatus.WARNING, PLUGIN, "the template Макет is empty")); //$NON-NLS-1$
        several.add(new Status(IStatus.INFO, PLUGIN, "3 files read")); //$NON-NLS-1$

        String text = BmExternalObjectProjectHelper.statusText(several);

        assertEquals("several errors occurred\n  the form ФормаОбр has no root\n  the template Макет is empty", text); //$NON-NLS-1$
    }

    @Test
    public void anExceptionBehindAStatusIsNamedOnce()
    {
        Status failed = new Status(IStatus.ERROR, PLUGIN, "the object could not be attached", //$NON-NLS-1$
            new IllegalStateException("no such feature: template")); //$NON-NLS-1$

        String text = BmExternalObjectProjectHelper.statusText(failed);

        assertTrue(text, text.startsWith("the object could not be attached: ")); //$NON-NLS-1$
        assertTrue(text, text.contains("no such feature: template")); //$NON-NLS-1$
        assertEquals(1, text.split("\n").length); //$NON-NLS-1$
    }

    @Test
    public void aStatusWhoseMessageIsTheExceptionsIsNotDoubled()
    {
        Status failed = new Status(IStatus.ERROR, PLUGIN, "disk full", new java.io.IOException("disk full")); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("disk full", BmExternalObjectProjectHelper.statusText(failed)); //$NON-NLS-1$
    }

    @Test
    public void aStatusTreeDeeperThanTheCapEndsWithAnEllipsis()
    {
        MultiStatus many = new MultiStatus(PLUGIN, 0, "many", null); //$NON-NLS-1$
        for (int i = 0; i < 30; i++)
        {
            many.add(new Status(IStatus.ERROR, PLUGIN, "error " + i)); //$NON-NLS-1$
        }

        String text = BmExternalObjectProjectHelper.statusText(many);

        assertTrue(text, text.contains("error 19")); //$NON-NLS-1$
        assertFalse(text, text.contains("error 20")); //$NON-NLS-1$
        assertTrue(text, text.endsWith("...")); //$NON-NLS-1$
    }
}
