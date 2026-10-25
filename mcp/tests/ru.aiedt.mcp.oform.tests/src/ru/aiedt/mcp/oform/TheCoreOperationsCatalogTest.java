/**
 * AI-EDT Ordinary Forms - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.BeforeClass;
import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.oform.layout.FormLayout;
import ru.aiedt.mcp.oform.layout.LayoutEditor;
import ru.aiedt.mcp.oform.layout.LayoutReader;
import ru.aiedt.mcp.oform.ops.CoreOperations;
import ru.aiedt.mcp.oform.ops.LayoutContext;
import ru.aiedt.mcp.oform.ops.OperationResult;
import ru.aiedt.mcp.oform.ops.OperationSpec;
import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * The core catalog's own handlers at work: a property is set through the registered handler and
 * read back, an unknown property is refused by name with nothing changed, a box comes from a
 * width and a height beside the edges it keeps, a handler is bound with its stub appended to the
 * module and recognized when the module declares it - also when the module declares it in
 * another letter case, where no second stub may appear - and unbound, a page is added beside the
 * form's own pages and a page that holds elements is refused, and the help answers without a
 * project. The answers carry the outcome; a refusal leaves the layout as it was.
 */
public class TheCoreOperationsCatalogTest
{
    private static final String CRLF = "\r\n"; //$NON-NLS-1$

    private static String layoutText;

    /** The module the fixture's form arrives with. */
    private static final String MODULE = "Процедура ПриОткрытии()" + CRLF + "КонецПроцедуры" + CRLF; //$NON-NLS-1$ //$NON-NLS-2$

    /** Reads the fixture and builds the core catalog once; the properties register as a side effect. */
    @BeforeClass
    public static void theFixtureAndTheCatalog() throws IOException
    {
        layoutText = ALayoutIsReadOffTheContainerTest.fixture("fixtures/item-form-v27.form"); //$NON-NLS-1$
        CoreOperations.specs();
    }

    /**
     * @param name the operation's name
     * @return the spec of that name in the core catalog's own list
     */
    private static OperationSpec spec(String name)
    {
        for (OperationSpec one : CoreOperations.specs())
        {
            if (one.name.equals(name))
            {
                return one;
            }
        }
        throw new AssertionError("no operation " + name + " in the core catalog"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * @param module the module text the form carries
     * @return the form as a container over a temp path, the way a handler reads it
     */
    private static OrdinaryFormFile moduleFile(String module)
    {
        V8Container container = V8Container.empty();
        container.add(V8Container.Entry.of("module", module.getBytes(StandardCharsets.UTF_8))); //$NON-NLS-1$
        return OrdinaryFormFile.of(Path.of("form.oform"), container); //$NON-NLS-1$
    }

    /**
     * @param module the module text
     * @param params the operation's arguments
     * @return a context over a fresh editor of the fixture
     */
    private static LayoutContext context(String module, Map<String, String> params)
    {
        LayoutEditor editor = LayoutEditor.over(BraceTree.parse(layoutText), "Catalog", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
        return new LayoutContext(null, "Catalog.Products.Form.ItemForm", "Catalog", moduleFile(module), //$NON-NLS-1$ //$NON-NLS-2$
            editor.root(), editor, params, ToolResult.success());
    }

    /** The catalog declares its operations with their kinds, and the help needs no project. */
    @Test
    public void theCatalogDeclaresItsOperations()
    {
        List<String> names = CoreOperations.specs().stream().map(spec -> spec.name).toList();
        for (String expected : new String[] { "audit", "layout", "diff_form", "inspect_layout", "set_property", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "bind_handler", "unbind_handler", "add_element", "add_page", "remove_page", "remove_element", "help" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        {
            assertTrue(expected, names.contains(expected));
        }
        assertEquals(OperationSpec.Kind.QUERY, spec("audit").kind); //$NON-NLS-1$
        assertFalse(spec("audit").writes); //$NON-NLS-1$
        assertEquals(OperationSpec.Kind.LAYOUT, spec("set_property").kind); //$NON-NLS-1$
        assertTrue(spec("set_property").writes); //$NON-NLS-1$
        assertFalse("help answers without a project", spec("help").needsProject); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** The form's title is set through the property handler and read back; an unknown property is refused by name. */
    @Test
    public void aTitleIsSetAndAnUnknownPropertyIsRefused()
    {
        LayoutContext titled = context(MODULE, Map.of("property", "title", "value", "Товар")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        OperationResult set = spec("set_property").layout.apply(titled); //$NON-NLS-1$
        assertTrue(set.outcome.message, set.outcome.done);
        FormLayout after = LayoutReader.read(titled.editor.root(), "Catalog"); //$NON-NLS-1$
        assertEquals("Товар", after.title.get("ru")); //$NON-NLS-1$ //$NON-NLS-2$

        LayoutContext unknown = context(MODULE, Map.of("property", "glow", "value", "yes")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        String before = unknown.editor.text();
        OperationResult refused = spec("set_property").layout.apply(unknown); //$NON-NLS-1$
        assertFalse(refused.outcome.done);
        assertTrue(refused.outcome.message, refused.outcome.message.contains("Unknown property glow")); //$NON-NLS-1$
        assertTrue(refused.outcome.message, refused.outcome.message.contains("title")); //$NON-NLS-1$
        assertEquals("a refusal changes nothing", before, unknown.editor.text()); //$NON-NLS-1$
    }

    /** A box comes from a width and a height beside the edges the element keeps; an empty box is refused. */
    @Test
    public void aBoxComesFromAWidthAndAHeight()
    {
        LayoutContext sized = context(MODULE, Map.of("property", "box", "elementName", "Наименование", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "width", "500", "height", "45")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        OperationResult box = spec("set_property").layout.apply(sized); //$NON-NLS-1$
        assertTrue(box.outcome.message, box.outcome.done);
        FormLayout.Element field = LayoutReader.read(sized.editor.root(), "Catalog").elementNamed("Наименование"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(List.of(110, 20, 610, 65), //$NON-NLS-1$
            List.of(Integer.valueOf(field.box[0]), Integer.valueOf(field.box[1]), Integer.valueOf(field.box[2]),
                Integer.valueOf(field.box[3])));

        LayoutContext empty = context(MODULE, Map.of("property", "box", "elementName", "Наименование")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        String before = empty.editor.text();
        OperationResult refused = spec("set_property").layout.apply(empty); //$NON-NLS-1$
        assertFalse(refused.outcome.done);
        assertTrue(refused.outcome.message, refused.outcome.message.contains("needs any of")); //$NON-NLS-1$
        assertEquals(before, empty.editor.text());
    }

    /** A handler is bound, its stub appended to the module, and recognized when the module declares it. */
    @Test
    public void aHandlerIsBoundWithItsStubAndRecognizedWhenDeclared()
    {
        LayoutContext bound = context(MODULE, Map.of("elementName", "Артикул", "event", "ПриИзменении")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        OperationResult first = spec("bind_handler").layout.apply(bound); //$NON-NLS-1$
        assertTrue(first.outcome.message, first.outcome.done);
        JsonObject answer = JsonParser.parseString(bound.answer.toJson()).getAsJsonObject();
        assertEquals("АртикулПриИзменении", answer.get("procedure").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Элемент", answer.get("parameters").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(answer.get("procedureDeclared").getAsBoolean()); //$NON-NLS-1$
        assertNotNull("the undeclared procedure was stubbed", first.moduleText); //$NON-NLS-1$
        assertTrue(first.moduleText, first.moduleText.contains("Процедура АртикулПриИзменении(Элемент)")); //$NON-NLS-1$
        FormLayout layout = LayoutReader.read(bound.editor.root(), "Catalog"); //$NON-NLS-1$
        assertEquals("АртикулПриИзменении", layout.elementNamed("Артикул").handlers.get(0).procedure); //$NON-NLS-1$ //$NON-NLS-2$

        String withStub = MODULE + "Процедура АртикулПриИзменении(Элемент)" + CRLF + "КонецПроцедуры" + CRLF; //$NON-NLS-1$ //$NON-NLS-2$
        LayoutContext again = context(withStub, Map.of("elementName", "Артикул", "event", "ПриИзменении")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        OperationResult second = spec("bind_handler").layout.apply(again); //$NON-NLS-1$
        assertTrue(second.outcome.message, second.outcome.done);
        JsonObject read = JsonParser.parseString(again.answer.toJson()).getAsJsonObject();
        assertTrue(read.get("procedureDeclared").getAsBoolean()); //$NON-NLS-1$
        assertFalse("no second stub beside the declared procedure", read.has("stub")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(second.moduleText);
    }

    /**
     * A handler the module declares in another letter case is recognized, not stubbed a second
     * time. The declaration search compares Cyrillic names without regard to letter case.
     */
    @Test
    public void aHandlerDeclaredInAnotherLetterCaseIsRecognized()
    {
        String shouted = "Процедура АРТИКУЛПРИИЗМЕНЕНИИ(Элемент)" + CRLF + "КонецПроцедуры" + CRLF; //$NON-NLS-1$ //$NON-NLS-2$
        LayoutContext bound = context(MODULE + shouted,
            Map.of("elementName", "Артикул", "event", "ПриИзменении", "procedure", "АртикулПриИзменении")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        OperationResult result = spec("bind_handler").layout.apply(bound); //$NON-NLS-1$
        assertTrue(result.outcome.message, result.outcome.done);
        JsonObject answer = JsonParser.parseString(bound.answer.toJson()).getAsJsonObject();
        assertTrue("the module declares the procedure in another letter case", //$NON-NLS-1$
            answer.get("procedureDeclared").getAsBoolean()); //$NON-NLS-1$
        assertFalse("no stub beside the declared procedure", answer.has("stub")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull("the module stays as it is", result.moduleText); //$NON-NLS-1$
    }

    /** A handler is unbound from an element and from the form, and a missing event is refused by name. */
    @Test
    public void handlersAreUnbound()
    {
        LayoutContext element = context(MODULE, Map.of("elementName", "Наименование", "event", "ПриИзменении")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        OperationResult unbound = spec("unbind_handler").layout.apply(element); //$NON-NLS-1$
        assertTrue(unbound.outcome.message, unbound.outcome.done);
        FormLayout afterElement = LayoutReader.read(element.editor.root(), "Catalog"); //$NON-NLS-1$
        assertTrue(afterElement.elementNamed("Наименование").handlers.stream() //$NON-NLS-1$
            .noneMatch(handler -> "ПриИзменении".equals(handler.event))); //$NON-NLS-1$
        List<String> expectedOthers = LayoutReader.read(BraceTree.parse(layoutText), "Catalog") //$NON-NLS-1$
            .elementNamed("Наименование").handlers.stream() //$NON-NLS-1$
            .filter(handler -> !"ПриИзменении".equals(handler.event)) //$NON-NLS-1$
            .map(handler -> handler.event + ":" + handler.procedure).toList(); //$NON-NLS-1$
        assertEquals(expectedOthers, afterElement.elementNamed("Наименование").handlers.stream() //$NON-NLS-1$
            .map(handler -> handler.event + ":" + handler.procedure).toList()); //$NON-NLS-1$

        LayoutContext form = context(MODULE, Map.of("event", "ПриОткрытии")); //$NON-NLS-1$ //$NON-NLS-2$
        OperationResult unboundForm = spec("unbind_handler").layout.apply(form); //$NON-NLS-1$
        assertTrue(unboundForm.outcome.message, unboundForm.outcome.done);
        for (FormLayout.Handler handler : LayoutReader.read(form.editor.root(), "Catalog").formHandlers) //$NON-NLS-1$
        {
            assertFalse(handler.event, "ПриОткрытии".equals(handler.event)); //$NON-NLS-1$ //$NON-NLS-2$
        }

        LayoutContext missing = context(MODULE, Map.of("elementName", "Артикул")); //$NON-NLS-1$ //$NON-NLS-2$
        OperationResult refused = spec("unbind_handler").layout.apply(missing); //$NON-NLS-1$
        assertFalse(refused.outcome.done);
        assertTrue(refused.outcome.message, refused.outcome.message.contains("unbind_handler needs event")); //$NON-NLS-1$
    }

    /** A page is added beside the form's pages; a page that holds elements is refused with nothing changed. */
    @Test
    public void aPageIsAddedAndAPageWithElementsIsRefused()
    {
        LayoutContext added = context(MODULE, Map.of("name", "Прочее", "title", "Прочее")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        OperationResult page = spec("add_page").layout.apply(added); //$NON-NLS-1$
        assertTrue(page.outcome.message, page.outcome.done);
        FormLayout withPage = LayoutReader.read(added.editor.root(), "Catalog"); //$NON-NLS-1$
        assertEquals(List.of("Основная", "Дополнительно", "Прочее"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            withPage.pages.stream().map(one -> one.name).toList());

        LayoutContext noName = context(MODULE, Map.of("title", "x")); //$NON-NLS-1$ //$NON-NLS-2$
        OperationResult refused = spec("add_page").layout.apply(noName); //$NON-NLS-1$
        assertFalse(refused.outcome.done);
        assertTrue(refused.outcome.message, refused.outcome.message.contains("add_page needs name")); //$NON-NLS-1$

        LayoutContext held = context(MODULE, Map.of("name", "Дополнительно")); //$NON-NLS-1$ //$NON-NLS-2$
        String before = held.editor.text();
        OperationResult kept = spec("remove_page").layout.apply(held); //$NON-NLS-1$
        assertFalse("the page holds elements", kept.outcome.done); //$NON-NLS-1$
        assertEquals(before, held.editor.text());
    }

    /** The help answers without a project and names every operation the catalog declares. */
    @Test
    public void theHelpAnswersWithoutAProject()
    {
        String help = spec("help").query.run(null, Map.of()); //$NON-NLS-1$
        JsonObject answer = JsonParser.parseString(help).getAsJsonObject();
        assertTrue(help, answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(help, answer.get("operations").getAsJsonArray().toString().contains("inspect_layout")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(help, answer.get("events").getAsJsonObject().has("form")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
