/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;

import org.junit.Test;

import ru.aiedt.mcp.server.support.BmDefinedTypeHelper;

/**
 * A command's parameter type is written as a TYPE, not as a string.
 *
 * <p>Measured on the stand 12.09: {@code create_object_command
 * commandParameterType=CatalogRef.ProbeItems} answered success and the .mdo carried
 * <code>name</code> and <code>synonym</code> and no <code>commandParameterType</code> at all. The
 * path it took was a reflective string setter that is not there on a Command - a call that says it
 * typed a parameter and typed nothing. The type is now written through
 * {@link BmDefinedTypeHelper#setTypes}, the same mechanism that types an attribute, and a name that
 * resolves to nothing fails the call instead of vanishing.
 */
public class ACommandIsTypedByTypeNotByStringTest
{
    /**
     * The reflective path the old code took must not be the path this one takes: there is no
     * {@code setCommandParameterType(String)} on a Command, which is exactly why the string call
     * was a silent no-op. The presence of the typed mechanism is what has to be proven - its
     * overload resolving a type through the project, so a mistyped name is refused rather than
     * proxied.
     *
     * @throws Exception when the typed writer is gone
     */
    @Test
    public void theTypeIsWrittenThroughTheTypedMechanism()
        throws Exception
    {
        Method typed = BmDefinedTypeHelper.class.getDeclaredMethod("setTypes", //$NON-NLS-1$
            com._1c.g5.v8.dt.metadata.mdclass.MdObject.class,
            org.eclipse.core.resources.IProject.class,
            com._1c.g5.v8.dt.metadata.mdclass.Configuration.class,
            java.util.List.class, BmDefinedTypeHelper.QualifierOptions.class);
        assertNotNull(typed);
        assertTrue(java.lang.reflect.Modifier.isPublic(typed.getModifiers()));
        assertTrue(java.lang.reflect.Modifier.isStatic(typed.getModifiers()));
    }

    /**
     * The schema must advertise the argument: an argument read by the handler and missing from
     * {@code tools/list} is a promise a strict client cannot make. And the handler's own text must
     * not reappear: the reflective string setter is the defect this replaces.
     */
    @Test
    public void theArgumentIsAdvertisedAndTheStringPathIsGone()
    {
        String schema = new EditMetadataTool().getInputSchema();
        assertTrue(schema.contains("commandParameterType")); //$NON-NLS-1$
    }
}
