/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * Covers the decisions {@link BmFormGeneratorHelper} makes around EDT's form generator: which
 * {@code getFormGeneratorFields} overload answers, what happens when the field tree comes back
 * empty, how an argument that fails its parameter type is reported, and how the produced layout is
 * counted.
 * <p>
 * The real generator lives behind the form bundle's Guice injector, which a unit run has no reason
 * to start. {@code invokeGeneration} is the same code {@code generate} drives after resolving that
 * injector, so the doubles here only have to expose the two method shapes the helper calls
 * reflectively ({@code getMethods()} sees public members only): {@code getFormGeneratorFields}
 * (4 and 5 arguments) and {@code generateForm} (9 arguments).
 * </p>
 */
public class BmFormGeneratorHelperTest
{
    /** Stands in for a {@code FormFieldInfo} node: a tree node with children. */
    public static final class FieldTreeDouble
    {
        private final List<Object> children = new ArrayList<>();

        FieldTreeDouble addLeaf()
        {
            children.add(new FieldTreeDouble());
            return this;
        }

        /** @return the children reflection walks through {@code getChildren()} */
        public List<Object> getChildren()
        {
            return children;
        }
    }

    /** Stands in for a form container item: a group or a table page exposing nested items. */
    public static final class FormItemDouble
    {
        private final List<Object> items = new ArrayList<>();

        FormItemDouble add(Object item)
        {
            items.add(item);
            return this;
        }

        /** @return the nested items */
        public List<Object> getItems()
        {
            return items;
        }
    }

    /**
     * Stands in for the generated form: {@code getItems()} is what the item counter walks. A double
     * with no items plays both a leaf field and an empty form.
     */
    public static final class FormDouble
    {
        private final List<Object> items = new ArrayList<>();

        FormDouble add(Object item)
        {
            items.add(item);
            return this;
        }

        FormDouble addGroup(Object... children)
        {
            FormItemDouble group = new FormItemDouble();
            for (Object child : children)
            {
                group.add(child);
            }
            return add(group);
        }

        /** @return the form's top-level items */
        public List<Object> getItems()
        {
            return items;
        }
    }

    /**
     * Stands in for {@code IFormFieldGenerator} carrying both overloads the interface has had since
     * EDT 2026.2: the 4-argument one and the 5-argument one the New Form wizard calls. Records
     * which of them answered.
     */
    public static final class FieldGeneratorDouble
    {
        boolean fourArgCalls;
        boolean fiveArgCalls;
        Object answer;

        /** The pre-2026.2 overload; the field-tree answer is {@link #answer}. */
        public Object getFormGeneratorFields(Object owner, Object formType, Object scriptVariant,
            Object version)
        {
            fourArgCalls = true;
            return answer;
        }

        /** The overload the New Form wizard calls; the field-tree answer is {@link #answer}. */
        public Object getFormGeneratorFields(Object owner, Object formType, Object scriptVariant,
            Object version, Object compatibilityVersion)
        {
            fiveArgCalls = true;
            return answer;
        }
    }

    /** Stands in for {@code IFormFieldGenerator} on a runtime that predates the 5-argument overload. */
    public static final class FourArgOnlyFieldGeneratorDouble
    {
        Object answer;

        /** The only overload this runtime carries. */
        public Object getFormGeneratorFields(Object owner, Object formType, Object scriptVariant,
            Object version)
        {
            return answer;
        }
    }

    /**
     * Stands in for {@code IFormGenerator} with the wizard's 9-argument {@code generateForm} shape.
     * Every parameter is {@code Object}, so every resolved value passes coercion and the happy-path
     * runs stay about the layout, not about types. Records what it was called with.
     */
    public static final class FormGeneratorDouble
    {
        Object receivedScriptVariant;
        Integer receivedColumnCount;
        Object answer = new FormDouble();

        /** The wizard's signature with every parameter loosened to {@code Object}. */
        public Object generateForm(Object owner, Object mdForm, Object formType,
            Object scriptVariant, String languageCode, Object version, Object fields,
            Integer columnCount, Object compatibilityMode)
        {
            receivedScriptVariant = scriptVariant;
            receivedColumnCount = columnCount;
            return answer;
        }
    }

    /**
     * Stands in for {@code IFormGenerator} whose {@code scriptVariant} parameter is declared
     * {@code String}: a value resolved as the mdclass {@code ScriptVariant} enum cannot be passed
     * to it, and that drop is exactly what has to become visible instead of silent.
     */
    public static final class StringTypedFormGeneratorDouble
    {
        Object receivedScriptVariant;
        Object answer = new FormDouble();

        /** Like the wizard's signature, but {@code scriptVariant} only accepts a String. */
        public Object generateForm(Object owner, Object mdForm, Object formType,
            String scriptVariant, String languageCode, Object version, Object fields,
            Integer columnCount, Object compatibilityMode)
        {
            receivedScriptVariant = scriptVariant;
            return answer;
        }
    }

    private static BmFormGeneratorHelper.Result run(Object generator, Object fieldGenerator,
        Object treeAnswer)
    {
        if (fieldGenerator instanceof FieldGeneratorDouble)
        {
            ((FieldGeneratorDouble) fieldGenerator).answer = treeAnswer;
        }
        else if (fieldGenerator instanceof FourArgOnlyFieldGeneratorDouble)
        {
            ((FourArgOnlyFieldGeneratorDouble) fieldGenerator).answer = treeAnswer;
        }
        return BmFormGeneratorHelper.invokeGeneration(generator, fieldGenerator, "OBJECT", null, //$NON-NLS-1$
            null, "OBJECT", null, null); //$NON-NLS-1$
    }

    private static FieldTreeDouble treeWithLeaves(int leaves)
    {
        FieldTreeDouble root = new FieldTreeDouble();
        for (int i = 0; i < leaves; i++)
        {
            root.addLeaf();
        }
        return root;
    }

    @Test
    public void anEmptyFieldTreeRefusesInsteadOfSucceeding()
    {
        FormGeneratorDouble generator = new FormGeneratorDouble();

        BmFormGeneratorHelper.Result result = run(generator, new FieldGeneratorDouble(),
            new FieldTreeDouble());

        assertFalse("a root with no children leaves the generator nothing to lay out - " //$NON-NLS-1$
            + "success over that tree is the defect this guards", result.ok); //$NON-NLS-1$
        assertTrue("the refusal has to say what was empty", //$NON-NLS-1$
            result.error != null && result.error.contains("empty field tree")); //$NON-NLS-1$
        assertFalse("the generator was found, it answered with a useless tree", //$NON-NLS-1$
            result.generatorNotFound);
        assertEquals("nothing was generated, so no item count exists", -1, result.itemCount); //$NON-NLS-1$
    }

    @Test
    public void aNullFieldTreeRefuses()
    {
        BmFormGeneratorHelper.Result result = run(new FormGeneratorDouble(),
            new FieldGeneratorDouble(), null);

        assertFalse(result.ok);
        assertTrue("a null answer is its own refusal reason", //$NON-NLS-1$
            result.error != null && result.error.contains("no field tree")); //$NON-NLS-1$
        assertEquals("the overload answered, and what it answered was nothing", 0, //$NON-NLS-1$
            result.fieldTreeSize);
    }

    @Test
    public void aMissingFieldGeneratorRefuses()
    {
        BmFormGeneratorHelper.Result result = run(new FormGeneratorDouble(), null, null);

        assertFalse(result.ok);
        assertTrue("the refusal names whether the field generator was there at all", //$NON-NLS-1$
            result.error != null && result.error.contains("available: false")); //$NON-NLS-1$
    }

    @Test
    public void aFieldGeneratorWithoutEitherOverloadRefuses()
    {
        BmFormGeneratorHelper.Result result = run(new FormGeneratorDouble(), new Object(),
            treeWithLeaves(1));

        assertFalse(result.ok);
        assertEquals("no overload matched, so the arity fact stays at zero", 0, //$NON-NLS-1$
            result.fieldsOverloadArgs);
    }

    @Test
    public void theFiveArgumentOverloadAnswersBeforeTheFourArgumentOne()
    {
        FormGeneratorDouble generator = new FormGeneratorDouble();
        FieldGeneratorDouble fieldGenerator = new FieldGeneratorDouble();

        BmFormGeneratorHelper.Result result = run(generator, fieldGenerator, treeWithLeaves(2));

        assertTrue(result.ok);
        assertTrue("the wizard's overload is the one that has to answer", //$NON-NLS-1$
            fieldGenerator.fiveArgCalls);
        assertFalse("the 4-argument overload is a fallback, not a first choice", //$NON-NLS-1$
            fieldGenerator.fourArgCalls);
        assertEquals(5, result.fieldsOverloadArgs);
        assertEquals("root plus two leaves", 3, result.fieldTreeSize); //$NON-NLS-1$
    }

    @Test
    public void theFourArgumentOverloadIsTheFallback()
    {
        BmFormGeneratorHelper.Result result = run(new FormGeneratorDouble(),
            new FourArgOnlyFieldGeneratorDouble(), treeWithLeaves(1));

        assertTrue(result.ok);
        assertEquals(4, result.fieldsOverloadArgs);
        assertEquals(2, result.fieldTreeSize);
    }

    @Test
    public void anArgumentThatFailsItsTypeIsReportedAndPassedAsNull()
    {
        StringTypedFormGeneratorDouble generator = new StringTypedFormGeneratorDouble();

        BmFormGeneratorHelper.Result result = run(generator, new FieldGeneratorDouble(),
            treeWithLeaves(1));

        assertTrue("one dropped optional argument must not fail the generation", result.ok); //$NON-NLS-1$
        assertEquals("exactly one argument failed coercion: the String-typed scriptVariant", 1, //$NON-NLS-1$
            result.coercionMismatches.size());
        String mismatch = result.coercionMismatches.get(0);
        assertTrue("the record names the argument: " + mismatch, //$NON-NLS-1$
            mismatch.startsWith("generateForm.scriptVariant: expected java.lang.String, got ")); //$NON-NLS-1$
        assertNull("the value that did not fit reaches the generator as null", //$NON-NLS-1$
            generator.receivedScriptVariant);
    }

    @Test
    public void theColumnCountArgumentIsTheWizardDefault()
    {
        FormGeneratorDouble generator = new FormGeneratorDouble();

        BmFormGeneratorHelper.Result result = run(generator, new FieldGeneratorDouble(),
            treeWithLeaves(1));

        assertTrue(result.ok);
        assertEquals("one column is the New Form wizard's default - the value generateForm " //$NON-NLS-1$
            + "dereferences, so it cannot be null", Integer.valueOf(1), //$NON-NLS-1$
            generator.receivedColumnCount);
    }

    @Test
    public void itemsOfTheGeneratedTreeAreCountedRecursively()
    {
        FormGeneratorDouble generator = new FormGeneratorDouble();
        FormDouble form = new FormDouble();
        // A group holding two fields, a table page holding one, and a bare field.
        form.addGroup(new FormItemDouble(), new FormItemDouble());
        form.addGroup(new FormItemDouble());
        form.add(new FormItemDouble());
        generator.answer = form;

        BmFormGeneratorHelper.Result result = run(generator, new FieldGeneratorDouble(),
            treeWithLeaves(1));

        assertTrue(result.ok);
        assertEquals("(1 + 2 nested) + (1 + 1 nested) + 1 bare = 6", 6, result.itemCount); //$NON-NLS-1$
    }
}
