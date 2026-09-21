/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support.oform;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.Test;

/**
 * An ordinary form survives a read and a write: the container comes back byte for byte when
 * nothing changed, the layout text comes back byte for byte through the tree, and a changed
 * module goes into a container the reader reads again.
 *
 * <p>The formatting rule the writer follows was measured on every ordinary form of a whole
 * configuration - six layout versions, forms up to two megabytes - and held on each; the
 * samples here are the shapes that rule has to reproduce: nesting, a scalar after a nested
 * list, a list ending in a list, doubled quotes, a line break inside a string, a base64 blob
 * with the platform's own line separators.</p>
 */
public class AnOrdinaryFormSurvivesAReadAndAWriteTest
{
    private static final String CRLF = "\r\n"; //$NON-NLS-1$

    /** A layout in the platform's own formatting. */
    private static final String LAYOUT = "{27," + CRLF //$NON-NLS-1$
        + "{18," + CRLF //$NON-NLS-1$
        + "{" + CRLF //$NON-NLS-1$
        + "{1,1," + CRLF //$NON-NLS-1$
        + "{\"ru\",\"Заголовок \"\"в кавычках\"\"\"}" + CRLF //$NON-NLS-1$
        + "},9,4294967295}," + CRLF //$NON-NLS-1$
        + "{09ccdc77-ea1a-4a6d-ab1c-3435eada2433,1,-22,278.0000000000001," + CRLF //$NON-NLS-1$
        + "{3,\"ПередОткрытием\"," + CRLF //$NON-NLS-1$
        + "{1,\"ПередОткрытием\"}" + CRLF //$NON-NLS-1$
        + "}," + CRLF //$NON-NLS-1$
        + "{\"строка" + CRLF + "с переносом\"}," + CRLF //$NON-NLS-1$ //$NON-NLS-2$
        + "{#base64:AgFTS2/0iI3BTqDV67a9oKcN1VI7TgMxEKWgCsodItPakmc8/uwtaDhAsrspKaJQ\r\r\nRStFUHIBGoTECSIkRESAXME5\r\r\n},0}" + CRLF //$NON-NLS-1$
        + "},1}"; //$NON-NLS-1$

    /**
     * The layout text comes back byte for byte, and its shape is what was written.
     */
    @Test
    public void theLayoutTextRoundTripsByteForByte()
    {
        BraceTree.ListNode root = BraceTree.parse(LAYOUT);
        assertEquals(LAYOUT, BraceTree.serialize(root));
        assertEquals("27", ((BraceTree.Token)root.get(0)).text()); //$NON-NLS-1$
        BraceTree.ListNode inner = (BraceTree.ListNode)root.get(1);
        assertEquals("18", ((BraceTree.Token)inner.get(0)).text()); //$NON-NLS-1$
        BraceTree.ListNode title = (BraceTree.ListNode)((BraceTree.ListNode)inner.get(1)).get(0);
        BraceTree.Token caption = (BraceTree.Token)((BraceTree.ListNode)title.get(2)).get(1);
        assertTrue(caption.isString());
        assertEquals("Заголовок \"в кавычках\"", caption.value()); //$NON-NLS-1$
        BraceTree.ListNode element = (BraceTree.ListNode)inner.get(2);
        assertEquals("09ccdc77-ea1a-4a6d-ab1c-3435eada2433", ((BraceTree.Token)element.get(0)).text()); //$NON-NLS-1$
        assertEquals("-22", ((BraceTree.Token)element.get(2)).text()); //$NON-NLS-1$
        BraceTree.ListNode blobList = (BraceTree.ListNode)element.get(6);
        assertTrue(blobList.get(0) instanceof BraceTree.Blob);
        assertTrue(((BraceTree.Blob)blobList.get(0)).raw().startsWith("#base64:")); //$NON-NLS-1$
    }

    /**
     * A string token is written with its quotes doubled and read back to its value.
     */
    @Test
    public void aStringTokenDoublesItsQuotes()
    {
        BraceTree.Token token = BraceTree.Token.string("a \"b\" c"); //$NON-NLS-1$
        assertEquals("\"a \"\"b\"\" c\"", token.text()); //$NON-NLS-1$
        assertEquals("a \"b\" c", token.value()); //$NON-NLS-1$
        assertFalse(new BraceTree.Token("42").isString()); //$NON-NLS-1$
    }

    /**
     * Text that is not one list is refused with the reason, not read as something else.
     */
    @Test
    public void malformedTextIsRefused()
    {
        for (String bad : new String[] { "{1,2", "{1}}", "{\"open", "1,2", "{1}{2}" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        {
            try
            {
                BraceTree.parse(bad);
                throw new AssertionError("accepted: " + bad); //$NON-NLS-1$
            }
            catch (IllegalArgumentException expected)
            {
                assertNotNull(expected.getMessage());
            }
        }
    }

    /**
     * A container written by this server is read back with the same entries, and the file
     * built from it reads as a form with the two texts.
     */
    @Test
    public void aContainerOfOurOwnReadsBack() throws IOException
    {
        V8Container container = V8Container.empty();
        byte[] form = withBom(LAYOUT);
        byte[] module = withBom("Процедура ПередОткрытием(Отказ)" + CRLF + "КонецПроцедуры" + CRLF); //$NON-NLS-1$ //$NON-NLS-2$
        container.add(V8Container.Entry.of("form", form)); //$NON-NLS-1$
        container.add(V8Container.Entry.of("module", module)); //$NON-NLS-1$
        byte[] bytes = container.write();
        assertEquals((byte)0xFF, bytes[0]);
        assertEquals((byte)0x7F, bytes[3]);

        V8Container again = V8Container.read(bytes);
        assertEquals(List.of("form", "module"), //$NON-NLS-1$ //$NON-NLS-2$
            List.of(again.getEntries().get(0).getName(), again.getEntries().get(1).getName()));
        assertArrayEquals(form, again.find("form").getData()); //$NON-NLS-1$
        assertArrayEquals(module, again.find("module").getData()); //$NON-NLS-1$
        assertFalse(again.isDirty());
        assertArrayEquals(bytes, again.write());

        Path file = Files.createTempFile("aiedt-form", ".oform"); //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            Files.write(file, bytes);
            OrdinaryFormFile read = OrdinaryFormFile.read(file);
            assertEquals("27", read.formVersion()); //$NON-NLS-1$
            assertEquals(LAYOUT, read.formText());
            assertTrue(read.moduleText().startsWith("Процедура")); //$NON-NLS-1$
            assertEquals(LAYOUT, BraceTree.serialize(read.formTree()));
        }
        finally
        {
            Files.deleteIfExists(file);
        }
    }

    /**
     * A changed module is written into a fresh layout the reader reads; the layout entry keeps
     * its bytes; nothing is written to disk while nothing changed.
     */
    @Test
    public void aChangedModuleIsWrittenAndTheLayoutIsNot() throws IOException
    {
        V8Container container = V8Container.empty();
        container.add(V8Container.Entry.of("form", withBom(LAYOUT))); //$NON-NLS-1$
        container.add(V8Container.Entry.of("module", withBom("// empty"))); //$NON-NLS-1$ //$NON-NLS-2$
        Path file = Files.createTempFile("aiedt-form", ".oform"); //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            Files.write(file, container.write());
            long stamp = Files.getLastModifiedTime(file).toMillis();
            OrdinaryFormFile read = OrdinaryFormFile.read(file);
            read.save();
            assertEquals(stamp, Files.getLastModifiedTime(file).toMillis());

            read.setModuleText("Процедура Новая()" + CRLF + "КонецПроцедуры"); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(read.isDirty());
            read.save();
            OrdinaryFormFile again = OrdinaryFormFile.read(file);
            assertTrue(again.moduleText().startsWith("Процедура Новая()")); //$NON-NLS-1$
            assertEquals(LAYOUT, again.formText());
            assertFalse(again.getContainer().find("form").isDirty()); //$NON-NLS-1$
        }
        finally
        {
            Files.deleteIfExists(file);
        }
    }

    /**
     * Bytes that are not a container are refused by name, not read as an empty form.
     */
    @Test
    public void notAContainerIsRefused() throws IOException
    {
        Path file = Files.createTempFile("aiedt-notaform", ".oform"); //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            Files.write(file, "just text, long enough to pass the size check of the reader".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
            try
            {
                OrdinaryFormFile.read(file);
                throw new AssertionError("read as a form"); //$NON-NLS-1$
            }
            catch (IOException expected)
            {
                assertTrue(expected.getMessage(), expected.getMessage().contains("Not a container")); //$NON-NLS-1$
            }
        }
        finally
        {
            Files.deleteIfExists(file);
        }
    }

    /**
     * The FQN of a form is read off its path, and a path that is not a form's answers nothing.
     */
    @Test
    public void theFqnIsReadOffThePath()
    {
        Path src = Path.of("E:", "ws", "Project", "src"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertEquals("Catalog.Products.Form.ItemForm", OrdinaryFormLocator.fqnOf(src, //$NON-NLS-1$
            src.resolve("Catalogs").resolve("Products").resolve("Forms").resolve("ItemForm").resolve("Form.oform"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        assertEquals("CommonForm.Selection", OrdinaryFormLocator.fqnOf(src, //$NON-NLS-1$
            src.resolve("CommonForms").resolve("Selection").resolve("Form.oform"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNull(OrdinaryFormLocator.fqnOf(src,
            src.resolve("Unknown").resolve("X").resolve("Forms").resolve("F").resolve("Form.oform"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        assertNull(OrdinaryFormLocator.fqnOf(src, src.resolve("Catalogs").resolve("Form.oform"))); //$NON-NLS-1$ //$NON-NLS-2$
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
