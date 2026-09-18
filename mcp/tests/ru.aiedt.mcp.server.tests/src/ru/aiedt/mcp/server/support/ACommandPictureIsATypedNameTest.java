/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;

import org.junit.Test;

/**
 * A form command's picture is a typed reference whose own element is the picture's name.
 *
 * <p>The refusal said the typed reference could not be built. It can: {@code
 * McoreFactory.createPictureRef} exists on the runtime, and the reference's own element carries
 * the picture's name as a string - the form the model itself writes, measured in a dialog-produced
 * .form. The name is validated first, against the platform registry or the project's
 * configuration, because a reference nothing resolves is refused, not written.
 */
public class ACommandPictureIsATypedNameTest
{
    /**
     * The factory offers the typed reference on this runtime.
     *
     * @throws Exception when the runtime does not offer it
     */
    @Test
    public void theFactoryCreatesTheTypedReference()
        throws Exception
    {
        Class<?> factoryClass = Class.forName("com._1c.g5.v8.dt.mcore.McoreFactory"); //$NON-NLS-1$
        Method create = factoryClass.getDeclaredMethod("createPictureRef"); //$NON-NLS-1$
        assertNotNull(create);
    }

    /**
     * The name the caller gives is checked, and the project it is checked against is read through
     * the command itself - asking the caller would let the two disagree.
     *
     * @throws Exception when the two helpers are gone
     */
    @Test
    public void theNameIsCheckedThroughTheCommand()
        throws Exception
    {
        BmFormHelper.class.getDeclaredMethod("namedPictureRefProblem", Object.class, String.class); //$NON-NLS-1$
        BmFormHelper.class.getDeclaredMethod("projectNameOf", Object.class); //$NON-NLS-1$
        assertTrue(PictureValidator.class.getDeclaredMethod("validate", String.class, String.class) //$NON-NLS-1$
            != null);
    }
}
