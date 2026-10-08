/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.function.Function;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.ExternalDataProcessor;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

/**
 * An FQN an external-object project does not hold is refused by name.
 * <p>
 * A misspelled FQN used to be answered with the description of the project's root object: the root
 * resolves, the fallback handed it back, and the caller read a full description of an object they
 * never asked for instead of learning their request found nothing. The root is described only when
 * the root is what was asked for.
 * </p>
 */
public class AnUnknownExternalFqnIsRefusedNotRedirectedTest
{
    private static final String ROOT_FQN = "ExternalDataProcessor.MyTool"; //$NON-NLS-1$

    /**
     * Resolves the way a live external project does: the root FQN answers its object, anything
     * else answers nothing.
     *
     * @return the resolver
     */
    private static Function<String, MdObject> rootOnlyResolver()
    {
        ExternalDataProcessor root = MdClassFactory.eINSTANCE.createExternalDataProcessor();
        root.setName("MyTool"); //$NON-NLS-1$
        return fqn -> ROOT_FQN.equals(fqn) ? root : null;
    }

    /** A misspelled FQN is an error naming it, not a description of the root. */
    @Test
    public void aMisspelledFqnIsRefusedEvenThoughTheRootResolves()
    {
        String out = MetadataDetailsReader.externalObjectSection(rootOnlyResolver(),
            "ExternalDataProcessor.MyTol", false, "ru", null, false); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the refusal names the FQN that was asked for", //$NON-NLS-1$
            out.contains("**Error:** no such object: ExternalDataProcessor.MyTol")); //$NON-NLS-1$
        assertFalse("nothing of the root object is described instead", out.contains("MyTool")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** The root itself, asked for by its own FQN, is still described. */
    @Test
    public void theRootAskedForByItsOwnFqnIsDescribed()
    {
        String out = MetadataDetailsReader.externalObjectSection(rootOnlyResolver(), ROOT_FQN, false,
            "ru", null, false); //$NON-NLS-1$
        assertFalse("the root is described, not refused", out.contains("no such object")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
