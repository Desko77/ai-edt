/**
 * AI-EDT Ordinary Forms - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.oform.BraceTree.ListNode;
import ru.aiedt.mcp.oform.BraceTree.Node;
import ru.aiedt.mcp.oform.BraceTree.Token;
import ru.aiedt.mcp.oform.layout.FormLayout;
import ru.aiedt.mcp.oform.layout.LayoutInspection;
import ru.aiedt.mcp.oform.layout.LayoutNames;
import ru.aiedt.mcp.oform.layout.LayoutReader;

/**
 * The read-only inspection of a layout: the findings it reports over the model the reader yields -
 * duplicate ids and names in their namespaces, references to pages, attributes, elements, action
 * sources and anchor targets the layout does not hold, handlers the module does not declare,
 * invalid boxes, anchor cycles - and its two geometry warnings, in which a Group or Panel frame is
 * never a party and elements of different pages never meet. The coverage names what was not
 * checked - records of unknown types, a missing module, an unloaded configuration, the anchor
 * slots past the four edges - and no answer ever claims the form is valid.
 *
 * <p>Every case works the real reader: the fixture is parsed, one record of it is rewritten
 * through the tree, and the model is read again off the rewritten tree. What the sanitized
 * fixture already carries - an anchor naming a gone element, a command panel sourcing a gone
 * element, a record of an unknown type - is asserted as it stands.</p>
 */
public class ALayoutIsInspectedTest
{
    private static final String PROJECT = "AiEdtOformInspectProbe"; //$NON-NLS-1$

    private static final String CRLF = "\r\n"; //$NON-NLS-1$

    private static final String UNKNOWN_TYPE = "00000000-1111-2222-3333-444444444444"; //$NON-NLS-1$

    private static final String TABLE_FIXTURE = "fixtures/panel-table-form-v27.form"; //$NON-NLS-1$

    /** A module that declares one of the six procedures the fixture binds. */
    private static final String MODULE = "Процедура ПриОткрытии()" + CRLF + "КонецПроцедуры" + CRLF; //$NON-NLS-1$ //$NON-NLS-2$

    /** A module that declares every procedure the fixture binds. */
    private static final String FULL_MODULE = MODULE
        + "Процедура ПередЗаписью()" + CRLF + "КонецПроцедуры" + CRLF //$NON-NLS-1$ //$NON-NLS-2$
        + "Процедура НаименованиеПриИзменении(Элемент)" + CRLF + "КонецПроцедуры" + CRLF //$NON-NLS-1$ //$NON-NLS-2$
        + "Процедура НаименованиеОткрытие(Элемент)" + CRLF + "КонецПроцедуры" + CRLF //$NON-NLS-1$ //$NON-NLS-2$
        + "Процедура ЗаписатьНажатие()" + CRLF + "КонецПроцедуры" + CRLF //$NON-NLS-1$ //$NON-NLS-2$
        + "Процедура КнопкаОКНажатие()" + CRLF + "КонецПроцедуры" + CRLF; //$NON-NLS-1$ //$NON-NLS-2$

    private static String fixtureText;

    private static Path root;

    private static IProject project;

    /** One inspection over the fixture as it stands. */
    private static Map<String, Object> inspected()
    {
        return inspected(treeOf(fixtureText), MODULE, null);
    }

    /** One inspection over a tree, a module and names. */
    private static Map<String, Object> inspected(ListNode tree, String module, LayoutNames names)
    {
        FormLayout layout = LayoutReader.read(tree, "Catalog"); //$NON-NLS-1$
        return LayoutInspection.inspect(tree, layout, BraceTree.serialize(tree), module, names);
    }

    private static ListNode treeOf(String text)
    {
        return BraceTree.parse(text);
    }

    @BeforeClass
    public static void aProjectWithOneOrdinaryForm() throws Exception
    {
        fixtureText = ALayoutIsReadOffTheContainerTest.fixture("fixtures/item-form-v27.form"); //$NON-NLS-1$
        root = Files.createTempDirectory("aiedt-oform-inspect"); //$NON-NLS-1$
        Path projectDir = root.resolve(PROJECT);
        Path form = projectDir.resolve("src/Catalogs/Products/Forms/ItemForm"); //$NON-NLS-1$
        Files.createDirectories(form);
        V8Container container = V8Container.empty();
        container.add(V8Container.Entry.of("form", withBom(fixtureText))); //$NON-NLS-1$
        container.add(V8Container.Entry.of("module", withBom(MODULE))); //$NON-NLS-1$
        Files.write(form.resolve("Form.oform"), container.write()); //$NON-NLS-1$
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        project = workspace.getRoot().getProject(PROJECT);
        IProjectDescription description = workspace.newProjectDescription(PROJECT);
        description.setLocation(new org.eclipse.core.runtime.Path(projectDir.toString()));
        project.create(description, new NullProgressMonitor());
        project.open(new NullProgressMonitor());
    }

    @AfterClass
    public static void theProjectGoes() throws Exception
    {
        if (project != null && project.exists())
        {
            project.delete(false, true, new NullProgressMonitor());
        }
        if (root != null)
        {
            try (var walk = Files.walk(root))
            {
                walk.sorted(java.util.Comparator.reverseOrder()).map(Path::toFile).forEach(java.io.File::delete);
            }
        }
    }

    /**
     * What the sanitized fixture carries: seven anchors naming elements it no longer holds, a
     * command panel sourcing a gone element, five handlers a one-procedure module does not declare,
     * and a record of a type the reader does not know - with the coverage saying so and no word of
     * the form being valid.
     */
    @Test
    public void theFixtureFindings()
    {
        Map<String, Object> answer = inspected();
        assertEquals(String.valueOf(answer.get("findings")), Integer.valueOf(13), answer.get("findingCount")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(has(answer, "anchor-target-missing", "НадписьНаименование", "element#213")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(has(answer, "anchor-target-missing", "Наименование", "element#213")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(has(answer, "anchor-target-missing", "Артикул", "element#213")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(has(answer, "anchor-target-missing", "ФлагАктивен", "element#100")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(has(answer, "action-source-missing", "ОсновныеДействияФормы", "element#151")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(has(answer, "handler-procedure-missing", "ПередЗаписью", "the form's ПередЗаписью")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(has(answer, "handler-procedure-missing", "НаименованиеПриИзменении", "Наименование ПриИзменении")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(has(answer, "handler-procedure-missing", "КнопкаОКНажатие", //$NON-NLS-1$ //$NON-NLS-2$
            "the button КнопкаОК of ОсновныеДействияФормы")); //$NON-NLS-1$
        // no geometry warning anywhere in the unmutated fixture, and no claim of correctness
        assertEquals(Integer.valueOf(0), answer.get("warningCount")); //$NON-NLS-1$
        assertTrue(hasCoverage(answer, "records", "partial", UNKNOWN_TYPE)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("the note names the unchecked parts", //$NON-NLS-1$
            "the parts coverage names as not performed were not checked: no findings over them " //$NON-NLS-1$
                + "is not a statement that the form is correct", //$NON-NLS-1$
            answer.get("note")); //$NON-NLS-1$
        String whole = String.valueOf(answer);
        assertFalse(whole.contains("clean")); //$NON-NLS-1$
        assertFalse(whole.contains("valid")); //$NON-NLS-1$
    }

    /** The same fixture with everything mended and every procedure declared inspects to no findings. */
    @Test
    public void aMendedFixtureInspectsToNoFindings()
    {
        ListNode tree = treeOf(fixtureText);
        for (String name : new String[] { "НадписьНаименование", "Наименование", "Артикул" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            ListNode common = LayoutReader.commonOf(recordNamed(tree, name));
            setAnchor(common, 8, "{2,-1,6,0}"); //$NON-NLS-1$
            setAnchor(common, 9, "{2,-1,6,0}"); //$NON-NLS-1$
        }
        ListNode check = recordNamed(tree, "ФлагАктивен"); //$NON-NLS-1$
        setAnchor(LayoutReader.commonOf(check), 8, "{2,0,3,108}"); //$NON-NLS-1$
        setAnchor(LayoutReader.commonOf(check), 9, "{2,0,3,180}"); //$NON-NLS-1$
        ListNode commands = recordNamed(tree, "ОсновныеДействияФормы"); //$NON-NLS-1$
        LayoutReader.identityOf(commands).items().set(2, new Token("0")); //$NON-NLS-1$
        removeUnknownRecord(tree);
        Map<String, Object> answer = inspected(tree, FULL_MODULE, null);
        assertEquals(String.valueOf(answer.get("findings")), Integer.valueOf(0), answer.get("findingCount")); //$NON-NLS-1$ //$NON-NLS-2$
        // the coverage still names the parts no check reaches, so the note stays
        assertNotNull(answer.get("note")); //$NON-NLS-1$
        assertTrue(hasCoverage(answer, "records", "performed", null)); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(hasCoverage(answer, "handler-procedures", "performed", null)); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(hasCoverage(answer, "data-paths", "partial", "configuration is not loaded")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** Version, page, anchor and geometry coverage identify the checks actually performed. */
    @Test
    public void theRemainingCoverageCategoriesAreExplicit()
    {
        Map<String, Object> answer = inspected();
        assertTrue(hasCoverage(answer, "layout-version", "performed", "27")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(hasCoverage(answer, "page-references", "performed", "page ordinal")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(hasCoverage(answer, "anchors", "partial", "two slots")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(hasCoverage(answer, "geometry", "partial", null)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(8, ((List<?>)answer.get("coverage")).size()); //$NON-NLS-1$

        ListNode older = treeOf(fixtureText);
        older.items().set(0, new Token("23")); //$NON-NLS-1$
        Map<String, Object> readOnlyVersion = inspected(older, FULL_MODULE, null);
        assertTrue(hasCoverage(readOnlyVersion, "layout-version", "performed", "23")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(hasCoverage(readOnlyVersion, "anchors", "partial", null)); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(hasCoverage(readOnlyVersion, "geometry", "partial", null)); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(readOnlyVersion.get("note")); //$NON-NLS-1$
    }

    /** A container without a module entry leaves the handler check named as not performed. */
    @Test
    public void noModuleLeavesTheHandlerCheckUnperformed()
    {
        Map<String, Object> answer = inspected(treeOf(fixtureText), null, null);
        assertTrue(hasCoverage(answer, "handler-procedures", "notPerformed", "no module entry")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        int bound = 0;
        for (Object one : (List<?>)answer.get("findings")) //$NON-NLS-1$
        {
            if ("handler-procedure-missing".equals(((Map<?, ?>)one).get("check"))) //$NON-NLS-1$ //$NON-NLS-2$
            {
                bound++;
            }
        }
        assertEquals("no handler finding without a module to compare with", 0, bound); //$NON-NLS-1$
    }

    /** Duplicate element id, element name, page name, attribute name and button name, each in its namespace. */
    @Test
    public void duplicatesAreNamedInTheirNamespaces()
    {
        ListNode tree = treeOf(fixtureText);
        LayoutReader.identityOf(recordNamed(tree, "Артикул")).items().set(1, Token.string("Наименование")); //$NON-NLS-1$ //$NON-NLS-2$
        Map<String, Object> byName = inspected(tree, FULL_MODULE, null);
        assertTrue(has(byName, "duplicate-element-name", "Наименование", "Наименование, Наименование")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        tree = treeOf(fixtureText);
        recordNamed(tree, "Артикул").items().set(1, new Token("12")); //$NON-NLS-1$ //$NON-NLS-2$
        Map<String, Object> byId = inspected(tree, FULL_MODULE, null);
        assertTrue(has(byId, "duplicate-element-id", null, "Наименование, Артикул")); //$NON-NLS-1$ //$NON-NLS-2$

        tree = treeOf(fixtureText);
        secondRootPage(tree).items().set(6, Token.string("Основная")); //$NON-NLS-1$
        Map<String, Object> byPage = inspected(tree, FULL_MODULE, null);
        assertTrue(has(byPage, "duplicate-page-name", "Основная", "the form")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        tree = treeOf(fixtureText);
        renameAttribute(tree, 2, "Объект"); //$NON-NLS-1$ //$NON-NLS-2$
        Map<String, Object> byAttribute = inspected(tree, FULL_MODULE, null);
        assertTrue(has(byAttribute, "duplicate-attribute-name", "Объект", "attribute")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        tree = treeOf(fixtureText);
        renameButton(tree, "КнопкаЗакрыть", "КнопкаОК"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Map<String, Object> byButton = inspected(tree, FULL_MODULE, null);
        assertTrue(has(byButton, "duplicate-button-name", "КнопкаОК", "ОсновныеДействияФормы")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** A page ordinal past the holder's pages, and a main attribute the table does not hold. */
    @Test
    public void missingPageAndMainAttributeAreNamed()
    {
        ListNode tree = treeOf(fixtureText);
        ListNode common = LayoutReader.commonOf(recordNamed(tree, "Артикул")); //$NON-NLS-1$
        common.items().set(common.size() - 5, new Token("9")); //$NON-NLS-1$
        Map<String, Object> answer = inspected(tree, FULL_MODULE, null);
        assertTrue(has(answer, "page-reference-missing", "Артикул", "ordinal 9")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        tree = treeOf(fixtureText);
        asList(asList(tree.get(2)).get(0)).items().set(0, new Token("999")); //$NON-NLS-1$
        answer = inspected(tree, FULL_MODULE, null);
        assertTrue(has(answer, "main-attribute-missing", "the form", "999")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** A binding for an element that is not there, and one opening with an attribute that is not either. */
    @Test
    public void unresolvableDataPathsAreNamed()
    {
        ListNode tree = treeOf(fixtureText);
        ListNode registry = asList(asList(tree.get(2)).get(3));
        asList(registry.get(3)).items().set(0, new Token("999")); //$NON-NLS-1$
        asList(asList(asList(registry.get(1)).get(1)).get(1)).items().set(0, new Token("555")); //$NON-NLS-1$
        Map<String, Object> answer = inspected(tree, FULL_MODULE, null);
        assertTrue(has(answer, "binding-element-missing", "element#999", "registry binds")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(has(answer, "binding-attribute-missing", "Наименование", "attribute id 555")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** A row-binding target absent from the layout is named; an existing target is not refused. */
    @Test
    public void aMissingRowBindingTargetIsNamed()
    {
        ListNode tree = treeOf(fixtureText);
        String elementId = tokenText(recordNamed(tree, "Наименование").get(1)); //$NON-NLS-1$
        ListNode registry = asList(asList(tree.get(2)).get(3));
        ListNode binding = asList(registry.get(1));
        assertEquals(elementId, tokenText(binding.get(0)));
        binding.items().set(1, BraceTree.parse("{3,{-2},{999},{0}}")); //$NON-NLS-1$
        Map<String, Object> missing = inspected(tree, FULL_MODULE, null);
        assertTrue(String.valueOf(missing),
            has(missing, "binding-row-element-missing", "Наименование", "element#999")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        binding.items().set(1, BraceTree.parse("{3,{-2},{" + elementId + "},{0}}")); //$NON-NLS-1$ //$NON-NLS-2$
        Map<String, Object> present = inspected(tree, FULL_MODULE, null);
        assertFalse(has(present, "binding-row-element-missing", "Наименование", null)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A member segment of a table column: found when the configuration names the row's type, skipped when not. */
    @Test
    public void aColumnMemberIsJudgedThroughTheNames() throws IOException
    {
        String text = ALayoutIsReadOffTheContainerTest.fixture(TABLE_FIXTURE);
        ListNode tree = treeOf(text);
        FormLayout tables = LayoutReader.read(tree, "Catalog"); //$NON-NLS-1$
        FormLayout.Element table = null;
        for (FormLayout.Element element : tables.allElements())
        {
            if ("Table".equals(element.type) && !element.columns.isEmpty()) //$NON-NLS-1$
            {
                table = element;
                break;
            }
        }
        assertNotNull("the fixture holds a table with columns", table); //$NON-NLS-1$
        ListNode columns = LayoutReader.columnsList(LayoutReader.ownOf(recordOf(tree, table.id)));
        assertNotNull(columns);
        // the column record is {kind, {8, {8, <body>, <segment>...}, ...}}; the segments sit in the
        // inner record the reader reads them from
        ListNode record = asList(asList(asList(columns.get(1)).get(1)).get(1));
        assertTrue("the column's first path slot is a segment", record.size() > 2); //$NON-NLS-1$
        record.items().set(2, BraceTree.parse("{0,\"11111111-2222-3333-4444-555555555555\"}")); //$NON-NLS-1$

        Map<String, Object> withNames = inspected(tree, null, new LayoutNames()
        {
            @Override
            public String typeName(String producedTypeUuid)
            {
                return null;
            }

            @Override
            public String ownerKind(String producedTypeUuid)
            {
                return null;
            }

            @Override
            public String memberName(String producedTypeUuid, String memberUuid)
            {
                return null;
            }

            @Override
            public String referencedKind(String producedTypeUuid, String memberUuid)
            {
                return null;
            }

            @Override
            public String memberTypeUuid(String producedTypeUuid, String memberUuid)
            {
                return null;
            }

            @Override
            public String memberUuid(String producedTypeUuid, String memberName)
            {
                return null;
            }

            @Override
            public ListNode memberPattern(String producedTypeUuid, String memberUuid)
            {
                return null;
            }
        });
        assertTrue(String.valueOf(withNames.get("findings")), //$NON-NLS-1$
            has(withNames, "column-member-missing", table.name, "11111111-2222-3333-4444-555555555555")); //$NON-NLS-1$ //$NON-NLS-2$

        Map<String, Object> withoutNames = inspected(tree, null, null);
        assertFalse(String.valueOf(withoutNames.get("findings")), //$NON-NLS-1$
            has(withoutNames, "column-member-missing", table.name, null)); //$NON-NLS-1$
        assertTrue(hasCoverage(withoutNames, "column-paths", "notPerformed", null)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** An invalid box: the right before the left. */
    @Test
    public void anInvalidBoxIsNamed()
    {
        ListNode tree = treeOf(fixtureText);
        LayoutReader.commonOf(recordNamed(tree, "Артикул")).items().set(3, new Token("50")); //$NON-NLS-1$ //$NON-NLS-2$
        Map<String, Object> answer = inspected(tree, FULL_MODULE, null);
        assertTrue(has(answer, "invalid-box", "Артикул", "110,60,50,79")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** Two anchors that point at each other close a cycle, named as a path. */
    @Test
    public void anAnchorCycleIsNamed()
    {
        ListNode tree = treeOf(fixtureText);
        setAnchor(LayoutReader.commonOf(recordNamed(tree, "Наименование")), 6, "{2,11,1,0}"); //$NON-NLS-1$ //$NON-NLS-2$
        setAnchor(LayoutReader.commonOf(recordNamed(tree, "НадписьНаименование")), 8, "{2,12,3,0}"); //$NON-NLS-1$ //$NON-NLS-2$
        Map<String, Object> answer = inspected(tree, FULL_MODULE, null);
        assertTrue(has(answer, "anchor-cycle", "НадписьНаименование", //$NON-NLS-1$ //$NON-NLS-2$
            "НадписьНаименование -> Наименование -> НадписьНаименование")); //$NON-NLS-1$
        // the rewritten left edge of the label no longer names the gone element
        assertFalse(has(answer, "anchor-target-missing", "НадписьНаименование", null)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Elements of different pages never meet: the article field's box lands on the name field's
     * box, and no warning comes while it stays on its own page; moved to the first page, the
     * warning names both. The group frame the article field geometrically overlaps on that page is
     * never a party to the warning.
     */
    @Test
    public void overlapsAreJudgedPerPageAndFramesAreNeverAParty()
    {
        ListNode tree = treeOf(fixtureText);
        ListNode common = LayoutReader.commonOf(recordNamed(tree, "Артикул")); //$NON-NLS-1$
        setBox(common, 110, 20, 400, 39);
        Map<String, Object> otherPage = inspected(tree, FULL_MODULE, null);
        assertEquals("the article field shows on another page", Integer.valueOf(0), otherPage.get("warningCount")); //$NON-NLS-1$ //$NON-NLS-2$

        tree = treeOf(fixtureText);
        common = LayoutReader.commonOf(recordNamed(tree, "Артикул")); //$NON-NLS-1$
        setBox(common, 110, 20, 400, 39);
        common.items().set(common.size() - 5, new Token("0")); //$NON-NLS-1$
        Map<String, Object> samePage = inspected(tree, FULL_MODULE, null);
        assertTrue(String.valueOf(samePage),
            has(samePage, "sibling-overlap", "Артикул", "Наименование") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                || has(samePage, "sibling-overlap", "Наименование", "Артикул")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        // the group frame sits on the same page over the same ground and is not named
        assertFalse(has(samePage, "sibling-overlap", "РамкаДоп", null)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Integer.valueOf(1), samePage.get("warningCount")); //$NON-NLS-1$
    }

    /** A child whose box runs past its parent panel's box. */
    @Test
    public void aChildPastItsParentIsNamed()
    {
        ListNode tree = treeOf(fixtureText);
        LayoutReader.commonOf(recordNamed(tree, "ФлагАктивен")).items().set(3, new Token("500")); //$NON-NLS-1$ //$NON-NLS-2$
        Map<String, Object> answer = inspected(tree, FULL_MODULE, null);
        assertTrue(has(answer, "box-outside-parent", "ФлагАктивен", "Панель1")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** The operation answers by FQN and by module address, and names what it cannot find. */
    @Test
    public void theOperationAnswers()
    {
        OrdinaryFormsTool tool = new OrdinaryFormsTool();
        String byFqn = tool.execute(Map.of("operation", "inspect_layout", "projectName", PROJECT, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "formFqn", "Catalog.Products.Form.ItemForm")); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject answer = JsonParser.parseString(byFqn).getAsJsonObject();
        assertTrue(byFqn, answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("inspect_layout", answer.get("operation").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(13, answer.get("findingCount").getAsInt()); //$NON-NLS-1$
        assertEquals(answer.getAsJsonArray("findings").size(), answer.get("findingCount").getAsInt()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(answer.getAsJsonArray("findings").size(), //$NON-NLS-1$
            answer.get("errorCount").getAsInt() + answer.get("warningCount").getAsInt()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(answer.get("note").getAsString().isEmpty()); //$NON-NLS-1$
        assertEquals(8, answer.get("elementCount").getAsInt()); //$NON-NLS-1$

        String byAddress = tool.execute(Map.of("operation", "inspect_layout", "projectName", PROJECT, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "modulePath", "Catalogs/Products/Forms/ItemForm/Module.bsl")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(byAddress, JsonParser.parseString(byAddress).getAsJsonObject().get("success").getAsBoolean()); //$NON-NLS-1$

        String missing = tool.execute(Map.of("operation", "inspect_layout", "projectName", PROJECT, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "formFqn", "Catalog.Products.Form.Nowhere")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(missing, missing.contains("No Form.oform for Catalog.Products.Form.Nowhere")); //$NON-NLS-1$

        String neither = tool.execute(Map.of("operation", "inspect_layout", "projectName", PROJECT)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(neither, neither.contains("inspect_layout needs formFqn")); //$NON-NLS-1$
    }

    // ---- the tree, rewritten ------------------------------------------------------------------

    /** The element records of the root panel, the nested after their parent. */
    private static List<ListNode> elementRecords(ListNode root)
    {
        List<ListNode> out = new ArrayList<>();
        ListNode body = root.size() > 1 ? asList(root.get(1)) : null;
        ListNode panel = body != null && body.size() > 2 ? asList(body.get(2)) : null;
        ListNode children = panel != null && panel.size() > 2 ? asList(panel.get(2)) : null;
        if (children != null)
        {
            collectRecords(children, out);
        }
        return out;
    }

    private static void collectRecords(ListNode holder, List<ListNode> out)
    {
        for (Node item : holder.items())
        {
            ListNode record = asList(item);
            if (record == null)
            {
                continue;
            }
            if (LayoutReader.isElementRecord(record))
            {
                out.add(record);
                ListNode nested = LayoutReader.childrenOf(record);
                if (nested != null)
                {
                    collectRecords(nested, out);
                }
            }
            else
            {
                collectRecords(record, out);
            }
        }
    }

    /** The record of the element a name names, the first in document order. */
    private static ListNode recordNamed(ListNode root, String name)
    {
        ListNode record = recordOf(root, null, name);
        assertNotNull(name, record);
        return record;
    }

    /** The record of the element an id names. */
    private static ListNode recordOf(ListNode root, String id)
    {
        ListNode record = recordOf(root, id, null);
        assertNotNull(id, record);
        return record;
    }

    private static ListNode recordOf(ListNode root, String id, String name)
    {
        for (ListNode record : elementRecords(root))
        {
            if (id != null && id.equals(tokenText(record.get(1))))
            {
                return record;
            }
            if (name != null && name.equals(LayoutReader.elementName(LayoutReader.identityOf(record))))
            {
                return record;
            }
        }
        return null;
    }

    /** Rewrites the anchor a slot's first position holds. */
    private static void setAnchor(ListNode common, int slot, String anchor)
    {
        asList(common.get(slot)).items().set(1, BraceTree.parse(anchor));
    }

    /** Rewrites an element's box inside its common list. */
    private static void setBox(ListNode common, int left, int top, int right, int bottom)
    {
        common.items().set(1, new Token(Integer.toString(left)));
        common.items().set(2, new Token(Integer.toString(top)));
        common.items().set(3, new Token(Integer.toString(right)));
        common.items().set(4, new Token(Integer.toString(bottom)));
    }

    /** Drops the record of the unknown type from the root's children and mends the count. */
    private static void removeUnknownRecord(ListNode root)
    {
        ListNode children = asList(asList(asList(root.get(1)).get(2)).get(2));
        children.items().removeIf(record -> record instanceof ListNode list
            && UNKNOWN_TYPE.equals(tokenText(list.get(0))));
        children.items().set(0, new Token(Integer.toString(children.size() - 1)));
    }

    /** The second page record of the root panel. */
    private static ListNode secondRootPage(ListNode root)
    {
        List<ListNode> pages = new ArrayList<>();
        collectPages(asList(asList(asList(root.get(1)).get(2)).get(1)), pages);
        assertTrue("the root panel holds two pages", pages.size() >= 2); //$NON-NLS-1$
        return pages.get(1);
    }

    private static void collectPages(ListNode node, List<ListNode> pages)
    {
        for (Node item : node.items())
        {
            ListNode child = asList(item);
            if (child == null)
            {
                continue;
            }
            if (LayoutReader.isPageRecord(child))
            {
                pages.add(child);
            }
            else if (!LayoutReader.isElementRecord(child))
            {
                collectPages(child, pages);
            }
        }
    }

    /** The rows of the attribute table, the count token left out. */
    private static List<ListNode> attributeRows(ListNode root)
    {
        List<ListNode> rows = new ArrayList<>();
        ListNode table = asList(asList(root.get(2)).get(2));
        for (Node row : table.items())
        {
            if (row instanceof ListNode list)
            {
                rows.add(list);
            }
        }
        return rows;
    }

    /** Rewrites an attribute row's name, which is its first string. */
    private static void renameAttribute(ListNode root, int row, String name)
    {
        List<Node> items = attributeRows(root).get(row).items();
        for (int i = 0; i < items.size(); i++)
        {
            if (items.get(i) instanceof Token token && token.isString())
            {
                items.set(i, Token.string(name));
                return;
            }
        }
        throw new AssertionError("no name in the attribute row"); //$NON-NLS-1$
    }

    /** Renames a button of a command panel by rewriting its record's name. */
    private static void renameButton(ListNode root, String from, String to)
    {
        assertTrue("the button " + from + " was not renamed", rename(root, from, to)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static boolean rename(ListNode node, String from, String to)
    {
        for (Node item : node.items())
        {
            ListNode child = asList(item);
            if (child == null)
            {
                continue;
            }
            if (child.size() >= 2 && tokenText(child.get(0)) != null && from.equals(stringOf(child.get(1))))
            {
                child.items().set(1, Token.string(to));
                return true;
            }
            if (rename(child, from, to))
            {
                return true;
            }
        }
        return false;
    }

    // ---- answers -----------------------------------------------------------------------------

    /** Whether one finding of a check exists whose subject and detail contain the parts given. */
    private static boolean has(Map<String, Object> answer, String check, String subject, String detailPart)
    {
        for (Object one : (List<?>)answer.get("findings")) //$NON-NLS-1$
        {
            Map<?, ?> finding = (Map<?, ?>)one;
            if (!check.equals(finding.get("check"))) //$NON-NLS-1$
            {
                continue;
            }
            if (subject != null && !String.valueOf(finding.get("subject")).contains(subject)) //$NON-NLS-1$
            {
                continue;
            }
            if (detailPart != null && !String.valueOf(finding.get("detail")).contains(detailPart)) //$NON-NLS-1$
            {
                continue;
            }
            return true;
        }
        return false;
    }

    /** Whether one coverage entry of a check exists with the status and the detail part given. */
    private static boolean hasCoverage(Map<String, Object> answer, String check, String status, String detailPart)
    {
        for (Object one : (List<?>)answer.get("coverage")) //$NON-NLS-1$
        {
            Map<?, ?> entry = (Map<?, ?>)one;
            if (!check.equals(entry.get("check")) || !status.equals(entry.get("status"))) //$NON-NLS-1$ //$NON-NLS-2$
            {
                continue;
            }
            if (detailPart != null && !String.valueOf(entry.get("detail")).contains(detailPart)) //$NON-NLS-1$
            {
                continue;
            }
            return true;
        }
        return false;
    }

    private static ListNode asList(Node node)
    {
        return node instanceof ListNode list ? list : null;
    }

    private static String tokenText(Node node)
    {
        return node instanceof Token token ? token.text() : null;
    }

    private static String stringOf(Node node)
    {
        return node instanceof Token token && token.isString() ? token.value() : null;
    }

    private static byte[] withBom(String text)
    {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[body.length + 3];
        out[0] = (byte)0xEF;
        out[1] = (byte)0xBB;
        out[2] = (byte)0xBF;
        System.arraycopy(body, 0, out, 3, body.length);
        return out;
    }
}
