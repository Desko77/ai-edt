/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Pins the probe order of {@link FormCommandInterfaceOps#commandFqnOfItem(Object)} without an EDT
 * runtime: the EDT-native {@code bmGetFqn()} of the bound command is the path that actually resolves
 * on EDT 2026.1 (the {@code FormCommandInterfaceItem} model exposes {@code getCommand(Command)}, not a
 * {@code getCommandFqn}); the direct {@code getCommandFqn} probe is kept for a hypothetical runtime
 * that exposes one, and {@code Command.toString()} is the last resort. Each stub mirrors one shape the
 * reflection can meet at runtime.
 */
public class FormCommandInterfaceOpsFqnTest
{
    /** A command EObject that exposes the EDT-native BM FQN. */
    public static final class BmCommand
    {
        private final String fqn;

        public BmCommand(String fqn)
        {
            this.fqn = fqn;
        }

        public String bmGetFqn()
        {
            return fqn;
        }
    }

    /** An item whose bound command carries a BM FQN - the common EDT 2026.1 case. */
    public static final class ItemWithBmCommand
    {
        private final BmCommand cmd;

        public ItemWithBmCommand(String fqn)
        {
            this.cmd = new BmCommand(fqn);
        }

        public BmCommand getCommand()
        {
            return cmd;
        }
    }

    /** A command with no {@code bmGetFqn} - exercises the toString fallback. */
    public static final class PlainCommand
    {
        @Override
        public String toString()
        {
            return "PlainCmd"; //$NON-NLS-1$
        }
    }

    public static final class ItemWithPlainCommand
    {
        public PlainCommand getCommand()
        {
            return new PlainCommand();
        }
    }

    /** A command whose {@code bmGetFqn} returns empty - must not match on empty, falls to toString. */
    public static final class EmptyBmCommand
    {
        public String bmGetFqn()
        {
            return ""; //$NON-NLS-1$
        }

        @Override
        public String toString()
        {
            return "Fallback"; //$NON-NLS-1$
        }
    }

    public static final class ItemWithEmptyBmCommand
    {
        public EmptyBmCommand getCommand()
        {
            return new EmptyBmCommand();
        }
    }

    /** A hypothetical runtime exposing the FQN directly on the item. */
    public static final class ItemWithCommandFqn
    {
        public String getCommandFqn()
        {
            return "CommonCommand.Direct"; //$NON-NLS-1$
        }
    }

    /** An item exposing neither accessor. */
    public static final class EmptyItem
    {
        // no command getters
    }

    @Test
    public void resolvesBmGetFqnOfBoundCommand()
    {
        assertEquals("CommonCommand.X", //$NON-NLS-1$
            FormCommandInterfaceOps.commandFqnOfItem(new ItemWithBmCommand("CommonCommand.X"))); //$NON-NLS-1$
    }

    @Test
    public void prefersDirectCommandFqnWhenPresent()
    {
        assertEquals("CommonCommand.Direct", //$NON-NLS-1$
            FormCommandInterfaceOps.commandFqnOfItem(new ItemWithCommandFqn()));
    }

    @Test
    public void fallsBackToCommandToString()
    {
        assertEquals("PlainCmd", FormCommandInterfaceOps.commandFqnOfItem(new ItemWithPlainCommand())); //$NON-NLS-1$
    }

    @Test
    public void emptyBmFqnFallsThroughToToString()
    {
        assertEquals("Fallback", FormCommandInterfaceOps.commandFqnOfItem(new ItemWithEmptyBmCommand())); //$NON-NLS-1$
    }

    @Test
    public void returnsNullWhenNoProbeResolves()
    {
        assertNull(FormCommandInterfaceOps.commandFqnOfItem(new EmptyItem()));
    }

    @Test
    public void returnsNullForNullItem()
    {
        assertNull(FormCommandInterfaceOps.commandFqnOfItem(null));
    }
}
