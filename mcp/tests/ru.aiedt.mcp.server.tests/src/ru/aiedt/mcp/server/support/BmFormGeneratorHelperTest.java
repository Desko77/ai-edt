/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.CompatibilityMode;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.platform.version.Version;

/**
 * Covers the decisions {@link BmFormGeneratorHelper} makes around EDT's form generator: which
 * {@code getFormGeneratorFields} overload answers and which {@code Version} it is fed, what
 * happens when the field tree comes back null or the call throws, how an argument that fails its
 * parameter type is reported, and how the produced layout is counted.
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
     * which of them answered and what each {@code Version} slot received.
     */
    public static final class FieldGeneratorDouble
    {
        boolean fourArgCalls;
        boolean fiveArgCalls;
        Object answer;
        Object fourArgVersion;
        Object fiveArgVersion;
        Object fiveArgCompatibility;

        /** The pre-2026.2 overload; the field-tree answer is {@link #answer}. */
        public Object getFormGeneratorFields(Object owner, Object formType, Object scriptVariant,
            Object version)
        {
            fourArgCalls = true;
            fourArgVersion = version;
            return answer;
        }

        /** The overload the New Form wizard calls; the field-tree answer is {@link #answer}. */
        public Object getFormGeneratorFields(Object owner, Object formType, Object scriptVariant,
            Object version, Object compatibilityVersion)
        {
            fiveArgCalls = true;
            fiveArgVersion = version;
            fiveArgCompatibility = compatibilityVersion;
            return answer;
        }
    }

    /**
     * Stands in for {@code IFormFieldGenerator} on a runtime that predates the 5-argument overload.
     * Records the single {@code Version} it was fed.
     */
    public static final class FourArgOnlyFieldGeneratorDouble
    {
        Object answer;
        Object receivedVersion;

        /** The only overload this runtime carries. */
        public Object getFormGeneratorFields(Object owner, Object formType, Object scriptVariant,
            Object version)
        {
            receivedVersion = version;
            return answer;
        }
    }

    /**
     * Stands in for {@code IFormFieldGenerator} whose field computation blows up: the call throws,
     * and the refusal has to carry the exception text rather than only "no field tree".
     */
    public static final class ThrowingFieldGeneratorDouble
    {
        /** The only overload, and it throws. */
        public Object getFormGeneratorFields(Object owner, Object formType, Object scriptVariant,
            Object version)
        {
            throw new IllegalStateException("field computation blew up");
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
        Object receivedFields;
        String receivedLanguageCode;
        Integer receivedColumnCount;
        Object answer = new FormDouble();

        /** The wizard's signature with every parameter loosened to {@code Object}. */
        public Object generateForm(Object owner, Object mdForm, Object formType,
            Object scriptVariant, String languageCode, Object version, Object fields,
            Integer columnCount, Object compatibilityMode)
        {
            receivedScriptVariant = scriptVariant;
            receivedFields = fields;
            receivedLanguageCode = languageCode;
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
        return run(generator, fieldGenerator, treeAnswer, null);
    }

    private static BmFormGeneratorHelper.Result run(Object generator, Object fieldGenerator,
        Object treeAnswer, Configuration config)
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
            null, "OBJECT", config, null); //$NON-NLS-1$
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

    /** A configuration whose compatibility mode is set, as a project with a mode is seen. */
    private static Configuration configurationWithMode(CompatibilityMode mode)
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        config.setCompatibilityMode(mode);
        return config;
    }

    @Test
    public void anEmptyFieldTreeReachesTheGenerator()
    {
        FormGeneratorDouble generator = new FormGeneratorDouble();

        BmFormGeneratorHelper.Result result = run(generator, new FieldGeneratorDouble(),
            new FieldTreeDouble());

        assertTrue("a root without children is the legal tree of an owner without attributes - " //$NON-NLS-1$
            + "the wizard's generators build a form over it, so refusing it hid a valid form", //$NON-NLS-1$
            result.ok);
        assertEquals("the tree-size fact still counts the childless root", 1, //$NON-NLS-1$
            result.fieldTreeSize);
        assertNotNull("the root itself reached generateForm", generator.receivedFields); //$NON-NLS-1$
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
    public void aThrowingFieldGeneratorRefusesNamingTheException()
    {
        BmFormGeneratorHelper.Result result = run(new FormGeneratorDouble(),
            new ThrowingFieldGeneratorDouble(), null);

        assertFalse("a field computation that throws is a refusal, not an empty answer", //$NON-NLS-1$
            result.ok);
        assertNotNull("the exception text reaches the result, not only the log", //$NON-NLS-1$
            result.fieldsError);
        assertTrue("the text names the exception and its message: " + result.fieldsError, //$NON-NLS-1$
            result.fieldsError.contains("IllegalStateException") //$NON-NLS-1$
                && result.fieldsError.contains("field computation blew up")); //$NON-NLS-1$
        assertTrue("the refusal still names the empty answer", //$NON-NLS-1$
            result.error != null && result.error.contains("no field tree")); //$NON-NLS-1$
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
    public void theFourArgumentOverloadReceivesTheCompatibilityVersion()
    {
        CompatibilityMode mode = CompatibilityMode.VERSION8_322;
        FourArgOnlyFieldGeneratorDouble fieldGenerator = new FourArgOnlyFieldGeneratorDouble();

        BmFormGeneratorHelper.Result result = run(new FormGeneratorDouble(), fieldGenerator,
            treeWithLeaves(1), configurationWithMode(mode));

        assertTrue(result.ok);
        String expected = String.valueOf(Version.parseCompatibilityMode(mode));
        assertEquals("on a runtime with only this overload, the wizard feeds it the " //$NON-NLS-1$
            + "compatibility version (parseCompatibilityMode of the mode), not the project one", //$NON-NLS-1$
            expected, String.valueOf(fieldGenerator.receivedVersion));
    }

    @Test
    public void theFiveArgumentOverloadKeepsProjectAndCompatibilityApart()
    {
        CompatibilityMode mode = CompatibilityMode.VERSION8_322;
        FieldGeneratorDouble fieldGenerator = new FieldGeneratorDouble();

        BmFormGeneratorHelper.Result result = run(new FormGeneratorDouble(), fieldGenerator,
            treeWithLeaves(1), configurationWithMode(mode));

        assertTrue(result.ok);
        String expected = String.valueOf(Version.parseCompatibilityMode(mode));
        assertEquals("the compatibility version sits in its own fifth slot", expected, //$NON-NLS-1$
            String.valueOf(fieldGenerator.fiveArgCompatibility));
        assertNull("the project version keeps its own slot (no project in a unit run)", //$NON-NLS-1$
            fieldGenerator.fiveArgVersion);
    }

    @Test
    public void theLanguageCodeIsResolvedNotHardcoded()
    {
        FormGeneratorDouble generator = new FormGeneratorDouble();

        BmFormGeneratorHelper.Result result = run(generator, new FieldGeneratorDouble(),
            treeWithLeaves(1));

        assertTrue(result.ok);
        assertNotNull("the wizard always passes a real code - the titles are keyed by it", //$NON-NLS-1$
            generator.receivedLanguageCode);
        // No OSGi runtime here, so the editing-language service is absent and the
        // configuration-default fallback answers ("ru" when nothing can be asked).
        assertEquals("ru", generator.receivedLanguageCode); //$NON-NLS-1$
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
