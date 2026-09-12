/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Guards the help a tool gives about its own parameters.
 * <p>
 * The prose lives in one place - the schema - and this renders it rather than repeating it. A
 * second copy maintained by hand drifts, and once it has drifted the help states one contract while
 * the call is validated against another; of the two the help is the one a caller believes, because
 * it was written to be read.
 * </p>
 */
public class HelpIsRenderedFromTheSchemaItselfTest
{
    @Test
    public void aParameterIsNamedWithItsKindAndWhetherItIsRequired()
    {
        String help = ParameterHelp.render("a_tool", "{\"type\":\"object\","
            + "\"properties\":{\"projectName\":{\"type\":\"string\","
            + "\"description\":\"which project to work on\"}},"
            + "\"required\":[\"projectName\"]}");

        assertTrue(help, help.contains("projectName"));
        assertTrue(help, help.contains("string"));
        assertTrue("a caller who cannot tell an optional parameter from a required one has to "
            + "guess, and a guess here is a refused call", help.contains("required"));
        assertTrue(help, help.contains("which project to work on"));
    }

    @Test
    public void anArraySaysWhatItIsAnArrayOf()
    {
        // "array" alone leaves the caller to pick between strings and objects, and the client
        // refuses the wrong one before the request is ever made.
        String help = ParameterHelp.render("a_tool", "{\"type\":\"object\","
            + "\"properties\":{\"objectFqns\":{\"type\":\"array\","
            + "\"items\":{\"type\":\"string\"}}}}");

        assertTrue(help, help.contains("array of string"));
    }

    @Test
    public void aParameterWithNoDescriptionIsStillNamed()
    {
        // Leaving it out would say the tool does not take it. It does - undescribed is not absent.
        String help = ParameterHelp.render("a_tool",
            "{\"type\":\"object\",\"properties\":{\"limit\":{\"type\":\"integer\"}}}");

        assertTrue(help, help.contains("limit"));
        assertTrue(help, help.contains("integer"));
        assertTrue(help, help.contains("No description is declared"));
    }

    @Test
    public void takingNothingIsSaidRatherThanLeftBlank()
    {
        String help = ParameterHelp.render("a_tool", "{\"type\":\"object\",\"properties\":{}}");

        assertTrue(help, help.contains("no parameters"));
        assertTrue(help, help.contains("a_tool"));
    }

    @Test
    public void aSchemaThatWillNotParseIsNotAnsweredAsNoParameters()
    {
        // The two read the same to a caller and mean opposite things: one tool takes nothing, the
        // other takes something nobody could list. Only the first is knowledge.
        String unreadable = ParameterHelp.render("a_tool", "{this is not json");
        String empty = ParameterHelp.render("a_tool", "{\"type\":\"object\",\"properties\":{}}");

        assertFalse("an unreadable schema must not render as a tool that takes nothing",
            unreadable.equals(empty));
        assertTrue(unreadable, unreadable.contains("cannot be listed"));
    }

    @Test
    public void aSchemaWithNoPropertiesIsNotAToolThatTakesNothing()
    {
        // Two different facts that used to share an answer: a tool with an empty properties object
        // takes nothing, and a schema with no properties member says nothing about what it takes.
        String silent = ParameterHelp.render("a_tool", "{\"type\":\"object\"}");
        String empty = ParameterHelp.render("a_tool", "{\"type\":\"object\",\"properties\":{}}");

        assertFalse("a schema that says nothing must not answer as one that says none",
            silent.equals(empty));
        assertTrue(silent, silent.contains("no properties member"));
        assertTrue(empty, empty.contains("takes no parameters"));
    }

    @Test
    public void aPropertiesMemberThatIsNotAnObjectIsCalledMalformed()
    {
        String help = ParameterHelp.render("a_tool", "{\"type\":\"object\",\"properties\":[]}");

        assertTrue(help, help.contains("not an object"));
        assertFalse("malformed is not empty", help.contains("takes no parameters"));
    }

    @Test
    public void aRequiredListThatIsNotAListOfNamesLeavesRequirednessUnstated()
    {
        // It used to be read as nothing being required, which is a claim about every parameter
        // made from a member nobody could read.
        String help = ParameterHelp.render("a_tool", "{\"type\":\"object\",\"properties\":"
            + "{\"projectName\":{\"type\":\"string\"}},\"required\":\"projectName\"}");

        assertTrue(help, help.contains("projectName"));
        assertTrue("the caller has to be told that requiredness is not stated here",
            help.contains("not a list of names"));
        assertFalse(help, help.contains(", required_"));
    }

    @Test
    public void aNumberInTheRequiredListDoesNotMakeAParameterOfThatNameRequired()
    {
        // getAsString turns 1 into "1", which marked a property named "1" required - a fact
        // nobody wrote into the schema.
        String help = ParameterHelp.render("a_tool", "{\"type\":\"object\",\"properties\":"
            + "{\"1\":{\"type\":\"string\"}},\"required\":[1]}");

        assertFalse(help, help.contains(", required_"));
        assertTrue(help, help.contains("not a list of names"));
    }

    @Test
    public void aParameterDeclaredAsSomethingOtherThanAnObjectIsStillListed()
    {
        // It used to disappear. A caller reading the answer then learned the tool does not take
        // the parameter, when what is true is that its declaration cannot be read.
        String help = ParameterHelp.render("a_tool", "{\"type\":\"object\",\"properties\":"
            + "{\"good\":{\"type\":\"string\"},\"broken\":\"not a property object\"}}");

        assertTrue(help, help.contains("good"));
        assertTrue("the parameter exists and its declaration does not read; both are facts",
            help.contains("broken"));
        assertTrue(help, help.contains("nothing can be said about it"));
    }

    @Test
    public void everyParameterOfTheSchemaAppears()
    {
        // Rendering some of them is worse than rendering none: the missing ones read as parameters
        // the tool does not have, and the caller stops looking for them.
        String help = ParameterHelp.render("a_tool", "{\"type\":\"object\",\"properties\":{"
            + "\"alpha\":{\"type\":\"string\"},"
            + "\"beta\":{\"type\":\"boolean\"},"
            + "\"gamma\":{\"type\":\"integer\"}}}");

        assertTrue(help, help.contains("alpha"));
        assertTrue(help, help.contains("beta"));
        assertTrue(help, help.contains("gamma"));
    }
}
