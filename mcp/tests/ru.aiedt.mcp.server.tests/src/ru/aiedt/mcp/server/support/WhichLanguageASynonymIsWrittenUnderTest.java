/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * A synonym written under a language the configuration does not declare is stored where nothing
 * reads it: the object then shows an empty synonym while the write reported success. The language
 * is therefore asked of the configuration, and only a configuration that cannot be reached at all
 * is treated as the one this assumed everywhere before.
 */
public class WhichLanguageASynonymIsWrittenUnderTest
{
    /**
     * No project, no configuration, no language to ask - and the answer is the one that was
     * hard-coded before, so a path that worked keeps working rather than meeting an exception.
     */
    @Test
    public void nothingToAskAnswersTheFallback()
    {
        assertEquals(DefaultLanguage.FALLBACK, DefaultLanguage.codeFor((org.eclipse.core.resources.IProject) null));
        assertEquals(DefaultLanguage.FALLBACK, DefaultLanguage.codeFor((org.eclipse.emf.ecore.EObject) null));
    }

    /** The fallback is the language this assumed before it could ask, not a new choice. */
    @Test
    public void theFallbackIsWhatWasAssumedBefore()
    {
        assertEquals("ru", DefaultLanguage.FALLBACK); //$NON-NLS-1$
    }
}
