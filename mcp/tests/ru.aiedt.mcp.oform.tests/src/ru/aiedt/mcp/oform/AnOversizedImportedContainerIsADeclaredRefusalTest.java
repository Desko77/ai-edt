/**
 * AI-EDT Ordinary Forms - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.junit.Test;

/**
 * A container too big for the writer's single-page table is a declared refusal of the import,
 * not a runtime exception out of the container writer.
 * <p>
 * The writer lays the table of contents into one 512-byte page, which holds 42 entries, so a
 * form the infobase filled with more - its layout, its module and unknown entries after them -
 * is not one this bundle can write back. The import says so itself: the assembly answers an
 * {@link IOException} that names the single-page table and the entry count, so the refusal
 * reaches the caller as a refusal and not as an internal error. The container here is built
 * byte by byte - header, table, attribute and data documents - because the writer cannot build
 * the very bytes that put it over the limit.
 * </p>
 */
public class AnOversizedImportedContainerIsADeclaredRefusalTest
{
    /** One entry over what a single 512-byte table page holds: 42 pairs of 12 bytes. */
    private static final int ENTRIES = 43;

    private static final int HEADER_SIZE = 16;

    private static final int BLOCK_HEADER_SIZE = 31;

    private static final int PAGE_SIZE = 0x200;

    private static final int TOC_CAPACITY = 0x400;

    private static final int END_MARKER = 0x7fffffff;

    private static final long STAMP = 637311959090000L;

    private static final String CRLF = "\r\n"; //$NON-NLS-1$

    private static final String LAYOUT = "{27," + CRLF //$NON-NLS-1$
        + "{1}" + CRLF //$NON-NLS-1$
        + "}"; //$NON-NLS-1$

    private static final String ORIGINAL_MODULE = "Процедура ПриОткрытии()" + CRLF //$NON-NLS-1$
        + "КонецПроцедуры" + CRLF; //$NON-NLS-1$

    private static final String OTHER_MODULE = "Процедура Нажатие(Элемент)" + CRLF //$NON-NLS-1$
        + "КонецПроцедуры" + CRLF; //$NON-NLS-1$

    /**
     * The hand-built container is a real one: it reads with every one of its entries, its module
     * is the module it was built with, and a write of it unchanged returns the original bytes.
     */
    @Test
    public void aHandBuiltContainerReadsWithAllItsEntries()
    {
        byte[] original = oversized();
        V8Container read = V8Container.read(original);
        assertEquals("the container reads with all its entries", ENTRIES, read.getEntries().size()); //$NON-NLS-1$
        assertEquals("the module entry carries the module it was built with", ORIGINAL_MODULE, //$NON-NLS-1$
            OrdinaryFormFile.of(null, read).moduleText());
        assertArrayEquals("an unchanged container writes back byte for byte", original, read.write()); //$NON-NLS-1$
    }

    /**
     * Assembling an imported container of 43 entries around a different module is a declared
     * refusal: an {@link IOException} naming the single-page table and the count. An
     * {@link IllegalStateException} out of the writer is not a refusal, and the original bytes
     * come out of the attempt untouched.
     */
    @Test
    public void anOversizedContainerWithANewModuleIsRefusedByAnIOException()
    {
        byte[] original = oversized();
        byte[] before = original.clone();
        try
        {
            byte[] out = OrdinaryFormImport.assembled(original, OTHER_MODULE);
            fail("a container of " + ENTRIES + " entries is refused, not assembled into " //$NON-NLS-1$ //$NON-NLS-2$
                + out.length + " bytes"); //$NON-NLS-1$
        }
        catch (IllegalStateException notADeclaredRefusal)
        {
            fail("the refusal must be declared as an IOException, not thrown as the writer's " //$NON-NLS-1$
                + "IllegalStateException: " + notADeclaredRefusal.getMessage()); //$NON-NLS-1$
        }
        catch (IOException refusal)
        {
            assertTrue("the refusal names the single-page table: " + refusal.getMessage(), //$NON-NLS-1$
                refusal.getMessage().contains("single-page table")); //$NON-NLS-1$
            assertTrue("the refusal names the entry count: " + refusal.getMessage(), //$NON-NLS-1$
                refusal.getMessage().contains(String.valueOf(ENTRIES)));
        }
        assertArrayEquals("the refusal leaves the imported bytes untouched", before, original); //$NON-NLS-1$
    }

    /**
     * The same oversized container with no module to replace needs no re-pack: the assembly
     * answers the original bytes and refuses nothing.
     */
    @Test
    public void theSameContainerWithoutAModuleChangeComesBackByteForByte() throws IOException
    {
        byte[] original = oversized();
        assertArrayEquals("no module change means the export's own bytes", original, //$NON-NLS-1$
            OrdinaryFormImport.assembled(original, null));
    }

    /**
     * Builds the bytes of a valid container of {@value #ENTRIES} entries: the layout, the module
     * and unknown entries after them, laid out the way the platform lays one out - a 16-byte
     * header, the table of contents as the first document, then every entry's attribute and data
     * document in table order. The table block carries a capacity of its own, so the page the
     * writer would use plays no part here.
     *
     * @return the container bytes
     */
    private static byte[] oversized()
    {
        String[] names = names();
        byte[][] datas = datas(names);
        byte[][] attributes = new byte[names.length][];
        int[] attributeOffsets = new int[names.length];
        int[] dataOffsets = new int[names.length];
        int at = HEADER_SIZE + BLOCK_HEADER_SIZE + TOC_CAPACITY;
        for (int i = 0; i < names.length; i++)
        {
            attributes[i] = attributeOf(names[i]);
            attributeOffsets[i] = at;
            at += BLOCK_HEADER_SIZE + attributes[i].length;
            dataOffsets[i] = at;
            at += BLOCK_HEADER_SIZE + datas[i].length;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(END_MARKER).putInt(PAGE_SIZE).putInt(names.length).putInt(0);
        out.write(header.array(), 0, HEADER_SIZE);
        block(out, tableOf(attributeOffsets, dataOffsets), TOC_CAPACITY);
        for (int i = 0; i < names.length; i++)
        {
            block(out, attributes[i], attributes[i].length);
            block(out, datas[i], datas[i].length);
        }
        return out.toByteArray();
    }

    /**
     * The entry names: the layout, the module and an unknown tail, all distinct.
     *
     * @return the names in table order
     */
    private static String[] names()
    {
        String[] names = new String[ENTRIES];
        names[0] = "form"; //$NON-NLS-1$
        names[1] = "module"; //$NON-NLS-1$
        for (int i = 2; i < ENTRIES; i++)
        {
            names[i] = String.format(Locale.ROOT, "aux%02d", Integer.valueOf(i)); //$NON-NLS-1$
        }
        return names;
    }

    /**
     * The entry data: the layout and the module as byte-order-marked UTF-8, the unknown entries
     * as plain ASCII of their own.
     *
     * @param names the entry names in table order
     * @return the data of each entry, in table order
     */
    private static byte[][] datas(String[] names)
    {
        byte[][] datas = new byte[names.length][];
        for (int i = 0; i < names.length; i++)
        {
            if ("form".equals(names[i])) //$NON-NLS-1$
            {
                datas[i] = withBom(LAYOUT);
            }
            else if ("module".equals(names[i])) //$NON-NLS-1$
            {
                datas[i] = withBom(ORIGINAL_MODULE);
            }
            else
            {
                datas[i] = names[i].getBytes(StandardCharsets.US_ASCII);
            }
        }
        return datas;
    }

    /**
     * One attribute document: two platform timestamps, a reserved int, the name in UTF-16LE and
     * four zero bytes closing it.
     *
     * @param name the entry name
     * @return the document bytes
     */
    private static byte[] attributeOf(String name)
    {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_16LE);
        ByteBuffer attribute = ByteBuffer.allocate(20 + nameBytes.length + 4).order(ByteOrder.LITTLE_ENDIAN);
        attribute.putLong(STAMP).putLong(STAMP).putInt(0).put(nameBytes);
        // The four bytes the buffer was allocated with are still zero and close the name.
        return attribute.array();
    }

    /**
     * The table of contents: for every entry the offset of its attribute document, the offset of
     * its data document and the end marker.
     *
     * @param attributeOffsets the attribute offsets, in table order
     * @param dataOffsets the data offsets, in table order
     * @return the table bytes
     */
    private static byte[] tableOf(int[] attributeOffsets, int[] dataOffsets)
    {
        ByteBuffer table = ByteBuffer.allocate(12 * attributeOffsets.length).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < attributeOffsets.length; i++)
        {
            table.putInt(attributeOffsets[i]).putInt(dataOffsets[i]).putInt(END_MARKER);
        }
        return table.array();
    }

    /**
     * Writes one block: the 31-byte text header of the document size, the block capacity and the
     * end marker, then the data padded with zeros out to the capacity.
     *
     * @param out the output the block goes to
     * @param data the document's bytes
     * @param capacity the block's capacity in bytes
     */
    private static void block(ByteArrayOutputStream out, byte[] data, int capacity)
    {
        String head = String.format(Locale.ROOT, "\r\n%08x %08x %08x \r\n", //$NON-NLS-1$
            Integer.valueOf(data.length), Integer.valueOf(capacity), Integer.valueOf(END_MARKER));
        out.write(head.getBytes(StandardCharsets.US_ASCII), 0, BLOCK_HEADER_SIZE);
        out.write(data, 0, data.length);
        for (int i = data.length; i < capacity; i++)
        {
            out.write(0);
        }
    }

    /**
     * Prefixes the UTF-8 bytes of a text with the byte-order mark.
     *
     * @param text the text
     * @return its bytes with the mark in front
     */
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
