/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import ru.aiedt.mcp.server.support.FormExtensionDataPathGuard.Answer;
import ru.aiedt.mcp.server.support.FormExtensionDataPathGuard.EdtPort;

/**
 * The runtime port never guesses an answer it did not get.
 * <p>
 * The guard treats {@link Answer#NOT_ASKED} as neither yes nor no, and the
 * runtime port produces it in exactly three situations: the EDT service is not
 * installed, the service of this release does not carry the method, and the
 * call failed. A catch that returned an answer instead - the fail-open this
 * guard replaced - would read a write the guard could not check as a checked
 * one, so all three branches are pinned here against the port the runtime
 * really uses, with the service lookup swapped for a fake.
 * </p>
 */
public class TheRuntimePortDoesNotGuessTest
{
    /**
     * A service this EDT does not have is not an answer: no question of the
     * port may come back resolved or exported on it.
     */
    @Test
    public void aServiceThisEdtDoesNotHaveIsNotAnAnswer()
    {
        EdtPort port = new EdtPort((serviceName, bundleName, pluginName) -> null);

        assertEquals(Answer.NOT_ASKED, port.isExtensionBelongingObject(new Object()));
        assertEquals(Answer.NOT_ASKED, port.exportSkip(new Object(), new Object()));
        assertEquals(Answer.NOT_ASKED, port.pathResolved(new Object(), new Object()));
    }

    /**
     * A service without the method is not an answer either: an EDT release
     * that renamed it must read as "not asked", not as "no".
     */
    @Test
    public void aServiceWithoutTheMethodIsNotAnAnswer()
    {
        EdtPort port = new EdtPort((serviceName, bundleName, pluginName) -> new Object());

        assertEquals(Answer.NOT_ASKED, port.isExtensionBelongingObject(new Object()));
        assertEquals(Answer.NOT_ASKED, port.exportSkip(new Object(), new Object()));
        assertEquals(Answer.NOT_ASKED, port.pathResolved(new Object(), new Object()));
    }

    /**
     * A call that failed is not an answer: the exception says nothing about
     * the path, so it must not become a yes or a no.
     */
    @Test
    public void aCallThatFailsIsNotAnAnswer()
    {
        EdtPort port =
            new EdtPort((serviceName, bundleName, pluginName) -> new ThrowingService());

        assertEquals(Answer.NOT_ASKED, port.isExtensionBelongingObject(new Object()));
        assertEquals(Answer.NOT_ASKED, port.exportSkip(new Object(), new Object()));
        assertEquals(Answer.NOT_ASKED, port.pathResolved(new Object(), new Object()));
    }

    /** A service whose methods are all there and every one of them throws. */
    public static final class ThrowingService
    {
        public boolean isExtensionBelongingObject(Object attribute)
        {
            throw new IllegalStateException("the call failed"); //$NON-NLS-1$
        }

        public boolean shouldSkipForExport(Object form, Object dataPath, Object feature,
            Object version)
        {
            throw new IllegalStateException("the call failed"); //$NON-NLS-1$
        }

        public boolean isPathResolved(Object form, Object dataPath)
        {
            throw new IllegalStateException("the call failed"); //$NON-NLS-1$
        }
    }
}
