/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * What a table bound to a path that names nothing answers.
 * <p>
 * The table used to be created and put on the form before its {@code dataPath} was looked at, so a
 * made-up name produced a table bound to nothing and a successful answer. The user sees an empty
 * spot where a table should be and the project looks healthy. Measured on the stand 30.08 with
 * {@code dataPath=НетТакогоРеквизита}.
 * </p>
 * <p>
 * Only the root segment is judged: a standard attribute such as {@code Object.Posted} is not among
 * the owner's attributes and would look absent though it exists, so a deep miss must not refuse.
 * </p>
 */
public class ATableNeedsARootThatExistsTest
{
    /** Stands in for a form attribute: the checker only asks it for its name. */
    public static final class NamedStub
    {
        private final String name;

        public NamedStub(String name)
        {
            this.name = name;
        }

        public String getName()
        {
            return name;
        }
    }

    /** Stands in for the form: the checker only asks it for its attributes. */
    public static final class FormStub
    {
        private final List<NamedStub> attributes;

        public FormStub(NamedStub... attrs)
        {
            this.attributes = Arrays.asList(attrs);
        }

        public List<NamedStub> getAttributes()
        {
            return attributes;
        }
    }

    private static final Object AN_OWNER = new Object();

    @Test
    public void aRootThatIsNotThereIsRefused()
    {
        FormStub form = new FormStub(new NamedStub("Список")); //$NON-NLS-1$
        String refusal = EditFormTool.refusalForUnknownTableRoot(form, AN_OWNER, "НетТакого"); //$NON-NLS-1$
        assertNotNull("a table bound to nothing must not be created", refusal); //$NON-NLS-1$
    }

    @Test
    public void theRefusalListsWhatTheFormDoesHave()
    {
        FormStub form = new FormStub(new NamedStub("Список"), new NamedStub("Дерево")); //$NON-NLS-1$ //$NON-NLS-2$
        String refusal = EditFormTool.refusalForUnknownTableRoot(form, AN_OWNER, "НетТакого"); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("Список")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("Дерево")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("Object")); //$NON-NLS-1$
    }

    @Test
    public void aRootThatIsTherePassesThrough()
    {
        FormStub form = new FormStub(new NamedStub("Дерево")); //$NON-NLS-1$
        assertNull(EditFormTool.refusalForUnknownTableRoot(form, AN_OWNER, "Дерево")); //$NON-NLS-1$
    }

    @Test
    public void aDeepSegmentIsNotJudged()
    {
        FormStub form = new FormStub(new NamedStub("Дерево")); //$NON-NLS-1$
        assertNull("only the root is judged - a standard member may not be listed", //$NON-NLS-1$
            EditFormTool.refusalForUnknownTableRoot(form, AN_OWNER, "Дерево.ЧегоТоТам")); //$NON-NLS-1$
    }

    @Test
    public void theOwnerRootPassesWhenThereIsAnOwner()
    {
        FormStub form = new FormStub();
        assertNull(EditFormTool.refusalForUnknownTableRoot(form, AN_OWNER, "Object.Товары")); //$NON-NLS-1$
    }

    @Test
    public void theOwnerRootIsRefusedOnAFormWithoutOne()
    {
        FormStub form = new FormStub();
        String refusal = EditFormTool.refusalForUnknownTableRoot(form, null, "Object.Товары"); //$NON-NLS-1$
        assertNotNull("a common form has no owner to bind to", refusal); //$NON-NLS-1$
    }

    @Test
    public void noPathIsNotRefused()
    {
        FormStub form = new FormStub();
        assertNull(EditFormTool.refusalForUnknownTableRoot(form, AN_OWNER, null));
        assertNull(EditFormTool.refusalForUnknownTableRoot(form, AN_OWNER, "")); //$NON-NLS-1$
    }

    @Test
    public void aFormThatCannotListItsAttributesIsNotRefused()
    {
        // Nothing to judge against, so the write goes through rather than being blocked on a guess.
        assertNull(EditFormTool.refusalForUnknownTableRoot(new Object(), AN_OWNER, "Что")); //$NON-NLS-1$
    }
}
