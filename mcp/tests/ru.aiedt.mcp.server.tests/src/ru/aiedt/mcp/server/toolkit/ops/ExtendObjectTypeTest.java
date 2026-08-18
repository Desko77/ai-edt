/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.support.BmExtensionTypeHelper;

/**
 * Pins {@code extend_object_type}, the operation that adds a type to an object an extension
 * has adopted.
 * <p>
 * It exists because {@code set_object_type} cannot do this and does not say so: measured on a
 * clean probe, setting a type on an adopted DefinedType answered {@code applied:true} and left
 * the file byte-for-byte unchanged, because an adopted object keeps its types in the extension
 * block rather than in a type property of its own. Anything that survives a rename or a
 * refactor of that path has to keep both halves true - the operation is reachable, and it
 * refuses rather than pretends when it has nothing to write.
 * </p>
 * <p>
 * What is checked headlessly: the operation is registered (a facade that dropped it would
 * answer "not implemented"), missing arguments are named, and the helper refuses an absent
 * object instead of reporting a write. The writing half needs a live workspace with an
 * extension project and is a live-verify item.
 * </p>
 */
public class ExtendObjectTypeTest
{
    /** What dispatch answers for an operation it has no handler for. */
    private static final String UNIMPLEMENTED = "not implemented"; //$NON-NLS-1$

    private static String invokeDispatch(String op, Map<String, String> params) throws Exception
    {
        Method dispatch = EditMetadataTool.class.getDeclaredMethod("dispatch", String.class, Map.class); //$NON-NLS-1$
        dispatch.setAccessible(true);
        return (String)dispatch.invoke(new EditMetadataTool(), op, params);
    }

    /**
     * The operation answers at all. Registration is the half a refactor drops silently:
     * an unregistered name still returns a well-formed response, just the wrong one.
     *
     * @throws Exception when the reflective dispatch fails.
     */
    @Test
    public void extendObjectTypeIsRegisteredInTheFacade() throws Exception
    {
        String result = invokeDispatch("extend_object_type", new HashMap<>()); //$NON-NLS-1$
        assertNotNull("extend_object_type answered nothing", result); //$NON-NLS-1$
        assertFalse("extend_object_type is not registered in edit_metadata: " + result, //$NON-NLS-1$
            result.toLowerCase().contains(UNIMPLEMENTED));
    }

    /**
     * Called with nothing, it names what it wants, as JSON - the facade's response type is
     * JSON and the router parses whatever comes back.
     *
     * @throws Exception when the reflective dispatch fails.
     */
    @Test
    public void missingArgumentsAreNamedAsJson() throws Exception
    {
        String result = invokeDispatch("extend_object_type", new HashMap<>()); //$NON-NLS-1$
        JsonParser.parseString(result);
        assertTrue("the missing projectName is not named: " + result, //$NON-NLS-1$
            result.contains("projectName")); //$NON-NLS-1$
        assertTrue("the missing ownerFqn is not named: " + result, result.contains("ownerFqn")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the missing type is not named: " + result, result.contains("type")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A project that does not exist is refused by name rather than by a stack trace.
     *
     * @throws Exception when the reflective dispatch fails.
     */
    @Test
    public void anUnknownProjectIsRefusedByName() throws Exception
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "NoSuchProjectHere"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("ownerFqn", "DefinedType.Anything"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("type", "Boolean"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = invokeDispatch("extend_object_type", params); //$NON-NLS-1$
        JsonParser.parseString(result);
        assertTrue("the unknown project is not named back: " + result, //$NON-NLS-1$
            result.contains("NoSuchProjectHere")); //$NON-NLS-1$
    }

    /**
     * With no object to write to, the helper reports a failure - not an empty success.
     * This is the shape the whole operation exists to correct, so it is pinned at the
     * helper too, where no workspace is involved.
     */
    @Test
    public void anAbsentObjectIsAFailureNotAnEmptySuccess()
    {
        BmExtensionTypeHelper.ExtendResult r =
            BmExtensionTypeHelper.extendTypes(null, null, null, Collections.singletonList("Boolean")); //$NON-NLS-1$
        assertFalse("a missing object was reported as a successful extend", r.ok); //$NON-NLS-1$
        assertNotNull("the failure carries no reason", r.error); //$NON-NLS-1$
        assertFalse("nothing was written, yet the result claims a change", r.mutated); //$NON-NLS-1$
        assertTrue("a failed extend must add nothing", r.added.isEmpty()); //$NON-NLS-1$
    }

    /**
     * An empty type list is refused for the same reason: there is nothing to add, and a
     * result saying otherwise would be the silent success again.
     */
    @Test
    public void anEmptyTypeListIsRefused()
    {
        BmExtensionTypeHelper.ExtendResult r =
            BmExtensionTypeHelper.extendTypes(null, null, null, Collections.emptyList());
        assertFalse("an empty request was reported as a successful extend", r.ok); //$NON-NLS-1$
        assertNotNull("the failure carries no reason", r.error); //$NON-NLS-1$
    }
}
