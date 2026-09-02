/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Why a scenario run does not accept a password.
 * <p>
 * The connection string reaches the client as a command-line argument, and a command line is
 * readable by every process on the machine. Masking covers this plugin's log and its answer; it
 * cannot reach a process the operating system has already started. So the secret is not taken:
 * what was never accepted cannot be disclosed.
 * </p>
 * <p>
 * The environment's own launch configuration does carry credentials, and a run cannot go through
 * it: its attributes offer a startup option and no equivalent of the switch that runs an external
 * data processor, which is how a scenario is played. That was measured, not assumed, and it is why
 * this is a refusal rather than a change of channel.
 * </p>
 */
public class ASecretIsNotTakenAtAllTest
{
    @Test
    public void aConnectionStringCarryingAPasswordIsRefused()
    {
        String why = VanessaTool.whyASecretCannotBePassed(
            "File=\"C:\\\\ib\";Usr=\"tester\";Pwd=\"s3cret\";"); //$NON-NLS-1$

        assertNotNull("a password would reach the command line, so it is not accepted", why); //$NON-NLS-1$
        assertFalse("and the refusal must not repeat it back", why.contains("s3cret")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the refusal has to say what to do instead: " + why, //$NON-NLS-1$
            why.contains("authentication")); //$NON-NLS-1$
    }

    @Test
    public void everySpellingOfAPasswordIsSeen()
    {
        assertTrue("unquoted", VanessaTool.carriesASecret("Srvr=host;Ref=base;Pwd=s3cret")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("quoted", //$NON-NLS-1$
            VanessaTool.carriesASecret("File=\"C:\\\\ib\";Pwd=\"s3cret\";")); //$NON-NLS-1$
        assertTrue("spaced around the equals", //$NON-NLS-1$
            VanessaTool.carriesASecret("File=\"C:\\\\ib\";pwd = \"s3cret\"")); //$NON-NLS-1$
        assertTrue("whatever the case", //$NON-NLS-1$
            VanessaTool.carriesASecret("Srvr=host;PWD=s3cret")); //$NON-NLS-1$
        assertTrue("and a string that is nothing but the password", //$NON-NLS-1$
            VanessaTool.carriesASecret("Pwd=s3cret")); //$NON-NLS-1$
    }

    @Test
    public void aStringWithoutOneIsLetThrough()
    {
        assertNull(VanessaTool.whyASecretCannotBePassed("File=\"C:\\\\ib\";Usr=\"tester\";")); //$NON-NLS-1$
        assertNull(VanessaTool.whyASecretCannotBePassed("Srvr=\"host\";Ref=\"base\";")); //$NON-NLS-1$
        assertFalse("a user name is not a secret", //$NON-NLS-1$
            VanessaTool.carriesASecret("Usr=\"tester\"")); //$NON-NLS-1$
    }

    @Test
    public void nothingPassedIsNothingToRefuse()
    {
        assertNull(VanessaTool.whyASecretCannotBePassed(null));
        assertFalse(VanessaTool.carriesASecret(null));
        assertFalse(VanessaTool.carriesASecret("")); //$NON-NLS-1$
    }

    @Test
    public void aWordMerelyContainingThoseLettersIsNotAPassword()
    {
        // The check looks for the argument, not for the letters: a path or a name that happens to
        // contain them must not stop a run that carries no secret at all.
        assertFalse(VanessaTool.carriesASecret("File=\"C:\\\\PwdBackup\";")); //$NON-NLS-1$
        assertFalse(VanessaTool.carriesASecret("Ref=\"KeepPwdSafe\"")); //$NON-NLS-1$
    }
}
