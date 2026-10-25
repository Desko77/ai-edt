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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.oform.layout.FormEvents;
import ru.aiedt.mcp.oform.layout.FormLayout;
import ru.aiedt.mcp.oform.layout.LayoutNames;
import ru.aiedt.mcp.oform.layout.LayoutReader;

/**
 * A layout is read off the brace tree of a container: the version and the title, the form's
 * attributes and which one is the main one, the pages, the form's handlers with their events,
 * and the element tree with types, names, captions, tooltips, boxes, pages, the data an
 * element shows, the handlers with their events and the buttons of a command panel with the
 * procedures their actions run. The ids the layout records are named through the
 * configuration's names when a tool has them, and stay ids otherwise. The {@code layout}
 * operation answers the same by the form's FQN and by the address of its module, and names
 * what it cannot find.
 *
 * <p>The fixture is a version-27 form assembled from records of real forms with the names
 * changed: every record has the shape the platform writes.</p>
 */
public class ALayoutIsReadOffTheContainerTest
{
    private static final String PROJECT = "AiEdtOformLayoutProbe"; //$NON-NLS-1$

    private static final String CRLF = "\r\n"; //$NON-NLS-1$

    private static final String UNKNOWN_TYPE = "00000000-1111-2222-3333-444444444444"; //$NON-NLS-1$

    private static final String OBJECT_TYPE = "4634bd51-751b-45b1-a081-6373e6f1250b"; //$NON-NLS-1$

    private static final String ARTICLE_UUID = "7a1f0c2e-5b6d-4e8f-9a0b-1c2d3e4f5a6b"; //$NON-NLS-1$

    /** What a configuration would say about the fixture's ids. */
    static final LayoutNames NAMES = new LayoutNames()
    {
        @Override
        public String typeName(String producedTypeUuid)
        {
            return OBJECT_TYPE.equals(producedTypeUuid) ? "CatalogObject.Products" : null; //$NON-NLS-1$
        }

        @Override
        public String ownerKind(String producedTypeUuid)
        {
            return OBJECT_TYPE.equals(producedTypeUuid) ? "Catalog" : null; //$NON-NLS-1$
        }

        @Override
        public String memberName(String producedTypeUuid, String memberUuid)
        {
            return OBJECT_TYPE.equals(producedTypeUuid) && ARTICLE_UUID.equals(memberUuid) ? "Артикул" : null; //$NON-NLS-1$
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
            return OBJECT_TYPE.equals(producedTypeUuid) && "Артикул".equalsIgnoreCase(memberName) ? ARTICLE_UUID : null; //$NON-NLS-1$
        }

        @Override
        public BraceTree.ListNode memberPattern(String producedTypeUuid, String memberUuid)
        {
            return OBJECT_TYPE.equals(producedTypeUuid) && ARTICLE_UUID.equals(memberUuid)
                ? BraceTree.parse("{\"Pattern\",{\"S\"}}") : null; //$NON-NLS-1$
        }
    };

    static String layoutText;

    private static Path root;

    private static IProject project;

    @BeforeClass
    public static void aProjectWithOneOrdinaryForm() throws Exception
    {
        layoutText = fixture("fixtures/item-form-v27.form"); //$NON-NLS-1$
        root = Files.createTempDirectory("aiedt-oform-layout"); //$NON-NLS-1$
        Path projectDir = root.resolve(PROJECT);
        Path form = projectDir.resolve("src/Catalogs/Products/Forms/ItemForm"); //$NON-NLS-1$
        Files.createDirectories(form);
        V8Container container = V8Container.empty();
        container.add(V8Container.Entry.of("form", withBom(layoutText))); //$NON-NLS-1$
        container.add(V8Container.Entry.of("module", withBom("Процедура ПриОткрытии()" + CRLF + "КонецПроцедуры" + CRLF))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
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
     * A fixture of the test fragment, as text.
     *
     * @param name the resource name beside this class
     * @return the text
     * @throws IOException when the resource is not there
     */
    static String fixture(String name) throws IOException
    {
        try (InputStream in = ALayoutIsReadOffTheContainerTest.class.getResourceAsStream(name))
        {
            if (in == null)
            {
                throw new IOException("no fixture " + name); //$NON-NLS-1$
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** The fixture survives the reader and the writer of the brace text byte for byte. */
    @Test
    public void theFixtureRoundTrips()
    {
        assertEquals(layoutText, BraceTree.serialize(BraceTree.parse(layoutText)));
    }

    /** The reader yields the version, the title, the attributes, the pages and the form's handlers with their events. */
    @Test
    public void theHeadIsRead()
    {
        FormLayout layout = LayoutReader.read(BraceTree.parse(layoutText), "Catalog"); //$NON-NLS-1$
        assertEquals("27", layout.version); //$NON-NLS-1$
        assertEquals("Карточка товара", layout.title.get("ru")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("101", layout.mainAttributeId); //$NON-NLS-1$
        assertEquals(3, layout.attributes.size());
        assertEquals("Объект", layout.attributes.get(0).name); //$NON-NLS-1$
        assertEquals(OBJECT_TYPE, layout.attributes.get(0).type);
        assertEquals("Активен", layout.attributes.get(1).name); //$NON-NLS-1$
        assertEquals("Boolean", layout.attributes.get(1).type); //$NON-NLS-1$
        assertEquals("String(25)", layout.attributes.get(2).type); //$NON-NLS-1$
        assertEquals(List.of("Основная", "Дополнительно"), //$NON-NLS-1$ //$NON-NLS-2$
            List.of(layout.pages.get(0).name, layout.pages.get(1).name));
        assertEquals("Дополнительно", layout.pages.get(1).title.get("ru")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(2, layout.formHandlers.size());
        assertEquals("ПередЗаписью", layout.formHandlers.get(0).event); //$NON-NLS-1$
        assertEquals(Integer.valueOf(80000), layout.formHandlers.get(0).code);
        assertEquals("ПриОткрытии", layout.formHandlers.get(1).procedure); //$NON-NLS-1$
        assertEquals("ПриОткрытии", layout.formHandlers.get(1).event); //$NON-NLS-1$

        // The same code names another event when the main attribute holds settings.
        FormLayout report = LayoutReader.read(BraceTree.parse(layoutText), "Report"); //$NON-NLS-1$
        assertEquals("ПередСохранениемЗначений", report.formHandlers.get(0).event); //$NON-NLS-1$
    }

    /** The element tree carries types, names, captions, tooltips, boxes, pages, children, handlers and buttons. */
    @Test
    public void theElementsAreRead()
    {
        FormLayout layout = LayoutReader.read(BraceTree.parse(layoutText), "Catalog"); //$NON-NLS-1$
        assertEquals(7, layout.elements.size());
        assertEquals(8, layout.allElements().size());

        FormLayout.Element label = layout.elements.get(0);
        assertEquals("Label", label.type); //$NON-NLS-1$
        assertEquals("НадписьНаименование", label.name); //$NON-NLS-1$
        assertEquals("Наименование:", label.title.get("ru")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Name:", label.title.get("#")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(label.tooltip);
        assertEquals("Основная", label.page); //$NON-NLS-1$

        FormLayout.Element name = layout.elements.get(1);
        assertEquals("Field", name.type); //$NON-NLS-1$
        assertEquals("Наименование", name.name); //$NON-NLS-1$
        assertNull("a field has no caption; its tooltip is not one", name.title); //$NON-NLS-1$
        assertEquals("Наименование позиции", name.tooltip.get("ru")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(List.of(110, 20, 400, 39), List.of(name.box[0], name.box[1], name.box[2], name.box[3]));
        assertEquals("String(100)", name.valueType); //$NON-NLS-1$
        assertEquals(2, name.handlers.size());
        assertEquals("ПриИзменении", name.handlers.get(0).event); //$NON-NLS-1$
        assertEquals("НаименованиеПриИзменении", name.handlers.get(0).procedure); //$NON-NLS-1$
        assertEquals("Открытие", name.handlers.get(1).event); //$NON-NLS-1$
        assertEquals(Integer.valueOf(5), name.handlers.get(1).code);

        FormLayout.Element article = layout.elements.get(2);
        assertEquals("Артикул", article.name); //$NON-NLS-1$
        assertEquals("Дополнительно", article.page); //$NON-NLS-1$
        assertNull(article.tooltip);
        assertTrue(article.handlers.isEmpty());

        FormLayout.Element button = layout.elements.get(3);
        assertEquals("Button", button.type); //$NON-NLS-1$
        assertEquals("Записать", button.title.get("ru")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Нажатие", button.handlers.get(0).event); //$NON-NLS-1$
        assertEquals("ЗаписатьНажатие", button.handlers.get(0).procedure); //$NON-NLS-1$

        FormLayout.Element panel = layout.elements.get(4);
        assertEquals("Panel", panel.type); //$NON-NLS-1$
        assertEquals(List.of("Общие", "Прочее"), List.of(panel.pages.get(0).name, panel.pages.get(1).name)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, panel.children.size());
        FormLayout.Element check = panel.children.get(0);
        assertEquals("CheckBox", check.type); //$NON-NLS-1$
        assertEquals("ФлагАктивен", check.name); //$NON-NLS-1$
        assertEquals("Активен", check.title.get("ru")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Прочее", check.page); //$NON-NLS-1$

        FormLayout.Element commands = layout.elements.get(5);
        assertEquals("CommandPanel", commands.type); //$NON-NLS-1$
        assertTrue(commands.handlers.isEmpty());
        assertEquals(2, commands.buttons.size());
        assertEquals("КнопкаОК", commands.buttons.get(0).name); //$NON-NLS-1$
        assertEquals("ОК", commands.buttons.get(0).caption.get("ru")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("КнопкаОКНажатие", commands.buttons.get(0).procedure); //$NON-NLS-1$
        assertEquals("КнопкаЗакрыть", commands.buttons.get(1).name); //$NON-NLS-1$
        assertNull(commands.buttons.get(1).procedure);

        FormLayout.Element group = layout.elements.get(6);
        assertEquals("Group", group.type); //$NON-NLS-1$
        assertEquals("Дополнительно", group.title.get("ru")); //$NON-NLS-1$ //$NON-NLS-2$

        // A record of a type the corpus did not hold is counted under its id, not guessed at.
        assertEquals(Integer.valueOf(1), layout.unread.get(UNKNOWN_TYPE));
        assertEquals(check, layout.elementNamed("флагактивен")); //$NON-NLS-1$
    }

    /**
     * A data path names a form attribute, a standard attribute by its code, a metadata object
     * by id resolved through the names, and an unresolved id as itself.
     */
    @Test
    public void theBindingsAreResolved()
    {
        FormLayout resolved = LayoutReader.read(BraceTree.parse(layoutText), "Catalog", NAMES); //$NON-NLS-1$
        assertEquals("Объект.Наименование", resolved.bindings.get("12")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Объект.Артикул", resolved.bindings.get("13")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Активен", resolved.bindings.get("16")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Объект.Наименование", resolved.elements.get(1).data); //$NON-NLS-1$
        assertEquals("Активен", resolved.elements.get(4).children.get(0).data); //$NON-NLS-1$
        assertEquals("CatalogObject.Products", resolved.attributes.get(0).type); //$NON-NLS-1$
        assertEquals(List.of(OBJECT_TYPE), resolved.producedTypeUuids());

        FormLayout bare = LayoutReader.read(BraceTree.parse(layoutText), "Document", LayoutNames.NONE); //$NON-NLS-1$
        assertEquals("Объект.Дата", bare.bindings.get("12")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Объект.uuid:" + ARTICLE_UUID, bare.bindings.get("13")); //$NON-NLS-1$ //$NON-NLS-2$
        FormLayout unknownKind = LayoutReader.read(BraceTree.parse(layoutText), null, null);
        assertEquals("Объект.standard(-3)", unknownKind.bindings.get("12")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** The events table answers both ways and knows the form's own events and the main attribute's. */
    @Test
    public void theEventsTableAnswersBothWays()
    {
        assertEquals("ПриИзменении", FormEvents.elementEvent("Field", 2147483647)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Integer.valueOf(47), FormEvents.elementCode("Table", "привыводестроки")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(FormEvents.elementEvent("Label", 5)); //$NON-NLS-1$
        assertNull(FormEvents.elementCode("Nowhere", "Нажатие")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("ПриОткрытии", FormEvents.formEvent(70001, false)); //$NON-NLS-1$
        assertEquals("ПослеЗаписи", FormEvents.formEvent(80002, false)); //$NON-NLS-1$
        assertEquals("ПослеВосстановленияЗначений", FormEvents.formEvent(80001, true)); //$NON-NLS-1$
        assertEquals("ПередНачаломДобавления", FormEvents.formEvent(40, false)); //$NON-NLS-1$
        assertEquals(Integer.valueOf(70000), FormEvents.formCode("ПередОткрытием", false)); //$NON-NLS-1$
        assertEquals(Integer.valueOf(80000), FormEvents.formCode("ПередСохранениемЗначений", true)); //$NON-NLS-1$
        assertNull(FormEvents.formCode("ПередСохранениемЗначений", false)); //$NON-NLS-1$
        assertEquals(9, FormEvents.elementEvents("SelectField").size()); //$NON-NLS-1$
    }

    /** The operation answers by FQN and by module address, and names what it cannot find. */
    @Test
    public void theOperationAnswersByFqnAndByAddress()
    {
        OrdinaryFormsTool tool = new OrdinaryFormsTool();
        String byFqn = tool.execute(Map.of("operation", "layout", "projectName", PROJECT, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "formFqn", "Catalog.Products.Form.ItemForm")); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject answer = JsonParser.parseString(byFqn).getAsJsonObject();
        assertTrue(byFqn, answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("Catalog.Products.Form.ItemForm", answer.get("form").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("src/Catalogs/Products/Forms/ItemForm/Form.oform", answer.get("container").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("27", answer.get("layoutVersion").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Карточка товара", answer.get("title").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(8, answer.get("elementCount").getAsInt()); //$NON-NLS-1$
        JsonArray elements = answer.getAsJsonArray("elements"); //$NON-NLS-1$
        assertEquals(7, elements.size());
        assertEquals(1, answer.getAsJsonObject("unread").get(UNKNOWN_TYPE).getAsInt()); //$NON-NLS-1$
        JsonObject field = elements.get(1).getAsJsonObject();
        assertEquals("Field", field.get("type").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Объект.Наименование", field.get("data").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Наименование позиции", field.get("tooltip").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(byFqn, field.has("title")); //$NON-NLS-1$
        assertEquals(20, field.getAsJsonObject("box").get("top").getAsInt()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Основная", field.get("page").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject handler = field.getAsJsonArray("handlers").get(0).getAsJsonObject(); //$NON-NLS-1$
        assertEquals("ПриИзменении", handler.get("event").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("НаименованиеПриИзменении", handler.get("procedure").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(2, answer.getAsJsonArray("pages").size()); //$NON-NLS-1$
        assertEquals(3, answer.getAsJsonArray("attributes").size()); //$NON-NLS-1$
        assertTrue(byFqn, answer.getAsJsonArray("attributes").get(0).getAsJsonObject().get("main").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject commands = elements.get(5).getAsJsonObject();
        assertEquals("КнопкаОКНажатие", commands.getAsJsonArray("buttons").get(0).getAsJsonObject().get("procedure").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        // No configuration in this runtime: the ids stay, and the answer says so.
        assertNotNull(answer.get("note")); //$NON-NLS-1$
        assertEquals("Объект.uuid:" + ARTICLE_UUID, elements.get(2).getAsJsonObject().get("data").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$

        String byAddress = tool.execute(Map.of("operation", "layout", "projectName", PROJECT, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "modulePath", "Catalogs/Products/Forms/ItemForm/Module.bsl")); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject second = JsonParser.parseString(byAddress).getAsJsonObject();
        assertEquals("Catalog.Products.Form.ItemForm", second.get("form").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(8, second.get("elementCount").getAsInt()); //$NON-NLS-1$

        String missing = tool.execute(Map.of("operation", "layout", "projectName", PROJECT, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "formFqn", "Catalog.Products.Form.Nowhere")); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject refused = JsonParser.parseString(missing).getAsJsonObject();
        assertFalse(missing, refused.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(missing, refused.get("error").getAsString().contains("No Form.oform for Catalog.Products.Form.Nowhere")); //$NON-NLS-1$ //$NON-NLS-2$

        String neither = tool.execute(Map.of("operation", "layout", "projectName", PROJECT)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(neither, neither.contains("needs formFqn")); //$NON-NLS-1$
    }

    /** The audit reads every layout too and counts what the reader does not know. */
    @Test
    public void theAuditReadsTheLayouts()
    {
        String audit = new OrdinaryFormsTool().execute(Map.of("operation", "audit", "projectName", PROJECT)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        JsonObject answer = JsonParser.parseString(audit).getAsJsonObject();
        assertTrue(audit, answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals(1, answer.get("forms").getAsInt()); //$NON-NLS-1$
        assertEquals(1, answer.get("layoutsRead").getAsInt()); //$NON-NLS-1$
        assertEquals(8, answer.get("elementsRead").getAsInt()); //$NON-NLS-1$
        assertEquals(1, answer.getAsJsonObject("unreadRecordTypes").get(UNKNOWN_TYPE).getAsInt()); //$NON-NLS-1$
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
