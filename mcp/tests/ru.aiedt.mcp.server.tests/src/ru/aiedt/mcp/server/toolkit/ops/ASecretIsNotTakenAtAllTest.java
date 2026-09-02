/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
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
    public void everyNameThePlatformGivesAPasswordIsSeen()
    {
        // Pwd is the one in a file or server connection string; a web-server connection names it
        // WSP, and a database one DBPwd. Catching only the first would leave a run reporting that
        // no secret was passed while passing one.
        assertTrue("web server password", VanessaTool.carriesASecret( //$NON-NLS-1$
            "ws=\"https://host/base\";WSN=\"tester\";WSP=\"s3cret\";")); //$NON-NLS-1$
        assertTrue("database password", VanessaTool.carriesASecret( //$NON-NLS-1$
            "Srvr=\"host\";Ref=\"base\";DBPwd=\"s3cret\";")); //$NON-NLS-1$
        assertTrue("whatever the case", //$NON-NLS-1$
            VanessaTool.carriesASecret("ws=\"h\";wsp=\"s3cret\";")); //$NON-NLS-1$
    }

    @Test
    public void aSemicolonInsideQuotesIsNotAFieldSeparator()
    {
        // A path may carry one. Reading it as a separator turned an innocent call into a refusal.
        assertFalse(VanessaTool.carriesASecret("File=\"C:\\\\Bases\\\\archive;Pwd=old\";")); //$NON-NLS-1$
        assertNull(VanessaTool.whyASecretCannotBePassed(
            "File=\"C:\\\\Bases\\\\archive;Pwd=old\";")); //$NON-NLS-1$
    }

    @Test
    public void theFieldsOfAConnectionStringAreSplitOnRealSeparators()
    {
        assertEquals(3, VanessaTool.fieldsOf("Srvr=\"host\";Ref=\"base\";Usr=\"t\"").size()); //$NON-NLS-1$
        assertEquals("a quoted separator does not split", 2, //$NON-NLS-1$
            VanessaTool.fieldsOf("File=\"C:\\\\a;b\";Usr=\"t\"").size()); //$NON-NLS-1$
    }

    @Test
    public void aFieldNameThatReadsLikeAPasswordIsSeenWithoutBeingListed()
    {
        // Four names were found one at a time, each after the list looked complete. So the rule
        // asks what a name reads like; the set holds only what no rule would catch.
        assertTrue("server administrator", VanessaTool.carriesASecret( //$NON-NLS-1$
            "Srvr=\"host\";Ref=\"base\";SUsr=\"admin\";SPwd=\"s3cret\";")); //$NON-NLS-1$
        assertTrue("a name nobody has listed yet", //$NON-NLS-1$
            VanessaTool.carriesASecret("Srvr=\"host\";ConnPwd=\"s3cret\";")); //$NON-NLS-1$
        assertTrue("spelt out in full", //$NON-NLS-1$
            VanessaTool.carriesASecret("Srvr=\"host\";UserPassword=\"s3cret\";")); //$NON-NLS-1$
        assertTrue("and the one no rule would catch", //$NON-NLS-1$
            VanessaTool.carriesASecret("ws=\"h\";WSP=\"s3cret\";")); //$NON-NLS-1$
    }

    @Test
    public void aUserNameIsStillNotAPassword()
    {
        assertFalse(VanessaTool.carriesASecret("Srvr=\"host\";SUsr=\"admin\";")); //$NON-NLS-1$
        assertFalse(VanessaTool.carriesASecret("ws=\"h\";WSN=\"tester\";")); //$NON-NLS-1$
    }

    @Test
    public void aPasswordJoinedToItsSwitchIsSeen()
    {
        // /P takes its value joined to it, so the field never carries an equals sign and the name
        // alone is never seen: the whole field reads /Psecret.
        assertTrue(VanessaTool.carriesASecret("/Psecret")); //$NON-NLS-1$
        assertTrue(VanessaTool.carriesASecret("File=\"C:\\ib\";/Psecret")); //$NON-NLS-1$
    }

    // -- what the log is told about the run ---------------------------------

    @Test
    public void theParameterDocumentIsLoggedByItsKeysNotItsValues()
    {
        String logged = VanessaTool.keysOf("{\"КаталогФич\":\"C:\\f\"," //$NON-NLS-1$
            + "\"ПарольПользователя\":\"s3cret\"}"); //$NON-NLS-1$

        assertTrue(logged, logged.contains("ПарольПользователя")); //$NON-NLS-1$
        assertFalse("a caller may put a secret in a parameter of their own", //$NON-NLS-1$
            logged.contains("s3cret")); //$NON-NLS-1$
    }

    @Test
    public void anUnreadableDocumentIsSaidToBeUnreadable()
    {
        assertEquals("(unreadable)", VanessaTool.keysOf("{ not json")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("(not an object)", VanessaTool.keysOf("[1,2]")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // -- parameters the caller adds -----------------------------------------

    @Test
    public void aCallerMayAddTheParametersTheirVanessaUnderstands()
    {
        String[] refusal = new String[1];

        com.google.gson.JsonObject added =
            VanessaTool.extraParams("{\"ТегиСценариев\":\"smoke\"}", refusal); //$NON-NLS-1$

        assertNull(refusal[0]);
        assertNotNull(added);
        assertEquals("smoke", added.get("ТегиСценариев").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aParameterThisToolReadsItsResultFromIsRefused()
    {
        String[] refusal = new String[1];

        VanessaTool.extraParams("{\"ПутьКФайлуРезультатовJUnit\":\"C:\\elsewhere.xml\"}", //$NON-NLS-1$
            refusal);

        assertNotNull("moving the report would leave the run reading nothing", refusal[0]); //$NON-NLS-1$
        assertTrue(refusal[0], refusal[0].contains("ПутьКФайлуРезультатовJUnit")); //$NON-NLS-1$
    }

    @Test
    public void everyParameterTheToolDependsOnIsProtected()
    {
        for (String key : new String[] {"СохранятьРезультатыВФорматеJUnit", //$NON-NLS-1$
            "ПутьКФайлуРезультатовJUnit", "КаталогСохраненияСкриншотов", //$NON-NLS-1$ //$NON-NLS-2$
            "ЗакрыватьTestClientПослеПрогона", "ВыходИзПриложенияПослеЗапускаСценариев"}) //$NON-NLS-1$ //$NON-NLS-2$
        {
            String[] refusal = new String[1];
            VanessaTool.extraParams("{\"" + key + "\":\"x\"}", refusal); //$NON-NLS-1$ //$NON-NLS-2$
            assertNotNull(key + " must not be the caller's to move", refusal[0]); //$NON-NLS-1$
        }
    }

    @Test
    public void somethingThatIsNotAnObjectIsRefusedSaying()
    {
        String[] refusal = new String[1];
        VanessaTool.extraParams("[\"smoke\"]", refusal); //$NON-NLS-1$
        assertNotNull("an array names no parameters", refusal[0]); //$NON-NLS-1$

        refusal[0] = null;
        VanessaTool.extraParams("{ not json", refusal); //$NON-NLS-1$
        assertNotNull(refusal[0]);
    }

    @Test
    public void addingNothingIsNotAnError()
    {
        String[] refusal = new String[1];

        assertNull(VanessaTool.extraParams(null, refusal));
        assertNull(VanessaTool.extraParams("", refusal)); //$NON-NLS-1$
        assertNull(refusal[0]);
    }

    @Test
    public void aParameterGivenAnObjectIsRefused()
    {
        String[] refusal = new String[1];

        VanessaTool.extraParams("{\"ТегиСценариев\":{\"value\":\"smoke\"}}", refusal); //$NON-NLS-1$

        assertNotNull("Vanessa reads a value, and ignores a shape it cannot use", refusal[0]); //$NON-NLS-1$
        assertTrue(refusal[0], refusal[0].contains("ТегиСценариев")); //$NON-NLS-1$
    }

    @Test
    public void aListOfObjectsIsRefusedToo()
    {
        String[] refusal = new String[1];

        // Checking that it is a list without looking at what is in it let this through, carrying
        // both a shape Vanessa cannot read and, in another call, a protected name.
        VanessaTool.extraParams("{\"ТегиСценариев\":[{\"value\":\"smoke\"}]}", refusal); //$NON-NLS-1$

        assertNotNull("a list is only a list of values", refusal[0]); //$NON-NLS-1$
    }

    @Test
    public void aProtectedNameHiddenInsideAListDoesNotGetIn()
    {
        String[] refusal = new String[1];

        VanessaTool.extraParams(
            "{\"x\":[{\"ПутьКФайлуРезультатовJUnit\":\"C:\\elsewhere.xml\"}]}", refusal); //$NON-NLS-1$

        assertNotNull("the shape is refused, and with it what was hidden in it", refusal[0]); //$NON-NLS-1$
    }

    @Test
    public void aListOfValuesIsStillAccepted()
    {
        String[] refusal = new String[1];

        com.google.gson.JsonObject added =
            VanessaTool.extraParams("{\"ТегиСценариев\":[\"smoke\",\"slow\"]}", refusal); //$NON-NLS-1$

        assertNull(refusal[0]);
        assertNotNull(added);
    }

    @Test
    public void aProtectedParameterIsRefusedWhateverItsCase()
    {
        String[] refusal = new String[1];

        VanessaTool.extraParams("{\"путькфайлурезультатовjunit\":\"C:\\\\elsewhere.xml\"}", //$NON-NLS-1$
            refusal);

        assertNotNull("comparing exactly let the same parameter through in another case", //$NON-NLS-1$
            refusal[0]);
    }

    @Test
    public void whatTheNamedArgumentsSetIsNotThePassthroughToChange()
    {
        // The merge happens last, so a parameter the caller set through an argument of this tool
        // would be silently overruled - and the answer would still describe the argument.
        for (String key : new String[] {"КаталогФич", "ФайлСценария", //$NON-NLS-1$ //$NON-NLS-2$
            "ДелатьСкриншотПриОшибке", "ПаузаМеждуШагами"}) //$NON-NLS-1$ //$NON-NLS-2$
        {
            String[] refusal = new String[1];
            VanessaTool.extraParams("{\"" + key + "\":\"x\"}", refusal); //$NON-NLS-1$ //$NON-NLS-2$
            assertNotNull(key + " is set from an argument and must not be overruled", refusal[0]); //$NON-NLS-1$
        }
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
