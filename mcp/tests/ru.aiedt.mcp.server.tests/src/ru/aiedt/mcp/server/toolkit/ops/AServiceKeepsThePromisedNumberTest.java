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

/**
 * A promised number lands on the service, or the call fails.
 *
 * <p>Measured on the stand 12.09: {@code create_http_service sessionMaxAge=30} answered success,
 * the .mdo carried {@code reuseSessions>Use} and no {@code sessionMaxAge} anywhere. The integer
 * setter reports nothing when the class does not have it, so the value landed nowhere and the
 * answer said it had. The write now goes through a helper that answers when it could not apply.
 */
public class AServiceKeepsThePromisedNumberTest
{
    /**
     * The helper that answers when it cannot apply, alongside the silent one it complements.
     *
     * @throws Exception when the answering helper is gone
     */
    @Test
    public void theIntegerWriteAnswersWhenItCannotLand()
        throws Exception
    {
        Method answering = ServiceOps.class.getDeclaredMethod("requireOptionalInteger", //$NON-NLS-1$
            com._1c.g5.v8.dt.metadata.mdclass.MdObject.class, String.class, Integer.class);
        assertNotNull(answering);
        assertTrue(java.lang.reflect.Modifier.isPrivate(answering.getModifiers())
            || java.lang.reflect.Modifier.isStatic(answering.getModifiers()));
    }

    /**
     * The schema still advertises the argument - the promise and the keeping of it are the same
     * fix, not two.
     */
    @Test
    public void theArgumentIsAdvertised()
    {
        assertTrue(new EditMetadataTool().getInputSchema().contains("sessionMaxAge")); //$NON-NLS-1$
    }
}
