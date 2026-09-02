/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * Every declared async domain is in the list that resolves a bare key.
 * <p>
 * A domain left out of {@code domains()} still runs its work and still answers a poll made
 * straight to its own tool, so nothing fails and no test goes red. What it loses is silent: a bare
 * runKey from it resolves to no domain, and a task-capable client can never turn its runs into
 * tasks. This is counted rather than listed, so the next domain added is enrolled or the count
 * says which one was not.
 * </p>
 */
public class EveryDomainIsFindableByKeyTest
{
    @Test
    public void everyDeclaredRegistryIsInTheList() throws Exception
    {
        List<String> declared = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (Field field : PendingWorkRegistry.class.getDeclaredFields())
        {
            if (!Modifier.isStatic(field.getModifiers())
                || !PendingWorkRegistry.class.equals(field.getType()))
            {
                continue;
            }
            declared.add(field.getName());
            if (!PendingWorkRegistry.domains().contains(field.get(null)))
            {
                missing.add(field.getName());
            }
        }

        assertTrue("no domains were found by reflection, so this proves nothing", //$NON-NLS-1$
            declared.size() >= 5);
        assertTrue("declared but absent from domains(), so a bare key from it resolves to no " //$NON-NLS-1$
            + "domain and its runs never become tasks: " + missing, missing.isEmpty()); //$NON-NLS-1$
        assertTrue("domains() lists something undeclared, or lists one twice", //$NON-NLS-1$
            PendingWorkRegistry.domains().size() == declared.size());
    }
}
