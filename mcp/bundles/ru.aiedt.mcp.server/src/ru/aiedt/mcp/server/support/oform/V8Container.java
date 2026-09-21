/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support.oform;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The container format the platform keeps binary metadata in - a {@code .cf}, an {@code .epf},
 * and the {@code Form.oform} an EDT project holds for an ordinary form.
 *
 * <p>A container is a 16-byte header, then a chain of blocks. Every block opens with a 31-byte
 * text header - {@code \r\n<doc size> <block size> <next block> \r\n}, three lowercase
 * eight-digit hex numbers - followed by the block's bytes; {@code 7fffffff} as the next block
 * ends a chain. The first document, at offset 16, is the table of contents: for every entry, the
 * offset of its attribute document and the offset of its data document, each pair closed by
 * {@code 7fffffff}. An attribute document is two 100-microsecond timestamps since year 1, a
 * reserved int and the entry name in UTF-16LE followed by four zero bytes. A data document is the
 * entry's bytes, possibly deflated without a zlib header - a form file keeps both of its entries
 * ({@code form} and {@code module}) as plain text.</p>
 *
 * <p>Reading keeps the original bytes: a container written back with no entry changed is the
 * original file, byte for byte, whatever chain the platform grew while it wrote the file. A
 * container with a changed entry is laid out afresh - one block per document, the table of
 * contents padded to the platform's 512-byte page - which is the layout the platform accepts
 * from every other builder of these files.</p>
 */
public final class V8Container
{
    /** The marker that ends a chain and pads the table of contents. */
    static final int END_MARKER = 0x7fffffff;

    private static final int HEADER_SIZE = 16;

    private static final int BLOCK_HEADER_SIZE = 31;

    private static final int PAGE_SIZE = 0x200;

    /** One entry of the container. */
    public static final class Entry
    {
        private final String name;

        private long created;

        private long modified;

        private final int reserved;

        private final byte[] nameTail;

        private byte[] data;

        private boolean dirty;

        Entry(String name, long created, long modified, int reserved, byte[] nameTail, byte[] data)
        {
            this.name = name;
            this.created = created;
            this.modified = modified;
            this.reserved = reserved;
            this.nameTail = nameTail;
            this.data = data;
        }

        /**
         * Creates an entry that did not exist before.
         *
         * @param name the entry name
         * @param data the bytes
         * @return the entry, timestamped now
         */
        public static Entry of(String name, byte[] data)
        {
            long now = nowAsPlatformTime();
            Entry entry = new Entry(name, now, now, 0, new byte[4], data.clone());
            entry.dirty = true;
            return entry;
        }

        /** @return the entry name, as the platform names it ({@code form}, {@code module}, ...) */
        public String getName()
        {
            return name;
        }

        /** @return the bytes of the entry, as stored - a caller decodes them */
        public byte[] getData()
        {
            return data;
        }

        /**
         * Replaces the bytes of the entry and stamps it modified now.
         *
         * @param bytes the new bytes
         */
        public void setData(byte[] bytes)
        {
            this.data = bytes.clone();
            this.modified = nowAsPlatformTime();
            this.dirty = true;
        }

        /** @return whether the entry was changed since the container was read */
        public boolean isDirty()
        {
            return dirty;
        }
    }

    private final List<Entry> entries;

    private final byte[] original;

    private V8Container(List<Entry> entries, byte[] original)
    {
        this.entries = entries;
        this.original = original;
    }

    /**
     * Starts an empty container.
     *
     * @return a container with no entries
     */
    public static V8Container empty()
    {
        return new V8Container(new ArrayList<>(), null);
    }

    /**
     * Reads a container from its bytes.
     *
     * @param bytes the file
     * @return the container with every entry's bytes read
     * @throws IllegalArgumentException when the bytes are not a container
     */
    public static V8Container read(byte[] bytes)
    {
        if (bytes.length < HEADER_SIZE + BLOCK_HEADER_SIZE)
        {
            throw new IllegalArgumentException("Not a container: " + bytes.length + " bytes"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        ByteBuffer header = ByteBuffer.wrap(bytes, 0, HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        int endMarker = header.getInt();
        int pageSize = header.getInt();
        if (endMarker != END_MARKER || pageSize <= 0)
        {
            throw new IllegalArgumentException("Not a container: header " //$NON-NLS-1$
                + Integer.toHexString(endMarker) + " page " + pageSize); //$NON-NLS-1$
        }
        byte[] toc = readDocument(bytes, HEADER_SIZE);
        List<Entry> entries = new ArrayList<>();
        ByteBuffer index = ByteBuffer.wrap(toc).order(ByteOrder.LITTLE_ENDIAN);
        while (index.remaining() >= 12)
        {
            int attributeOffset = index.getInt();
            int dataOffset = index.getInt();
            int marker = index.getInt();
            if (attributeOffset == END_MARKER || attributeOffset == 0)
            {
                break;
            }
            byte[] attributes = readDocument(bytes, attributeOffset);
            ByteBuffer attr = ByteBuffer.wrap(attributes).order(ByteOrder.LITTLE_ENDIAN);
            long created = attr.getLong();
            long modified = attr.getLong();
            int reserved = attr.getInt();
            byte[] nameBytes = Arrays.copyOfRange(attributes, 20, attributes.length);
            int nameEnd = 0;
            while (nameEnd + 1 < nameBytes.length && (nameBytes[nameEnd] != 0 || nameBytes[nameEnd + 1] != 0))
            {
                nameEnd += 2;
            }
            String name = new String(nameBytes, 0, nameEnd, StandardCharsets.UTF_16LE);
            byte[] nameTail = Arrays.copyOfRange(nameBytes, nameEnd, nameBytes.length);
            byte[] data = dataOffset == END_MARKER ? new byte[0] : readDocument(bytes, dataOffset);
            entries.add(new Entry(name, created, modified, reserved, nameTail, data));
            if (marker != END_MARKER)
            {
                // The platform closes every pair with the marker; a different value would mean
                // a table this reader does not understand, and reading on would misplace entries.
                throw new IllegalArgumentException("Table of contents not understood at entry " //$NON-NLS-1$
                    + entries.size());
            }
        }
        return new V8Container(entries, bytes.clone());
    }

    /** @return the entries in table order, read-only */
    public List<Entry> getEntries()
    {
        return Collections.unmodifiableList(entries);
    }

    /**
     * Finds an entry by name.
     *
     * @param name the entry name
     * @return the entry, or {@code null}
     */
    public Entry find(String name)
    {
        for (Entry entry : entries)
        {
            if (entry.name.equals(name))
            {
                return entry;
            }
        }
        return null;
    }

    /**
     * Adds an entry at the end of the table.
     *
     * @param entry the entry
     */
    public void add(Entry entry)
    {
        entries.add(entry);
    }

    /** @return whether any entry was changed or added since the container was read */
    public boolean isDirty()
    {
        if (original == null)
        {
            return true;
        }
        for (Entry entry : entries)
        {
            if (entry.dirty)
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Serializes the container.
     *
     * @return the original bytes when nothing changed, otherwise a fresh layout
     */
    public byte[] write()
    {
        if (!isDirty())
        {
            return original.clone();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(END_MARKER).putInt(PAGE_SIZE).putInt(entries.size()).putInt(0);
        out.write(header.array(), 0, HEADER_SIZE);
        // The table of contents takes one page right after the header; its offsets point past
        // it, so the documents are written first into a separate buffer whose base is known.
        int base = HEADER_SIZE + BLOCK_HEADER_SIZE + PAGE_SIZE;
        ByteArrayOutputStream documents = new ByteArrayOutputStream();
        ByteBuffer toc = ByteBuffer.allocate(12 * entries.size()).order(ByteOrder.LITTLE_ENDIAN);
        for (Entry entry : entries)
        {
            byte[] nameBytes = entry.name.getBytes(StandardCharsets.UTF_16LE);
            ByteBuffer attr = ByteBuffer.allocate(20 + nameBytes.length + entry.nameTail.length)
                .order(ByteOrder.LITTLE_ENDIAN);
            attr.putLong(entry.created).putLong(entry.modified).putInt(entry.reserved)
                .put(nameBytes).put(entry.nameTail);
            int attributeOffset = base + documents.size();
            writeDocument(documents, attr.array(), attr.array().length);
            int dataOffset = base + documents.size();
            writeDocument(documents, entry.data, Math.max(entry.data.length, PAGE_SIZE));
            toc.putInt(attributeOffset).putInt(dataOffset).putInt(END_MARKER);
        }
        if (toc.capacity() > PAGE_SIZE)
        {
            // More entries than one page holds: a form file has two, a .cf has thousands and is
            // not what this writer is for.
            throw new IllegalStateException("Too many entries for a single-page table: " + entries.size()); //$NON-NLS-1$
        }
        writeDocument(out, toc.array(), PAGE_SIZE);
        byte[] body = documents.toByteArray();
        out.write(body, 0, body.length);
        return out.toByteArray();
    }

    private static byte[] readDocument(byte[] bytes, int offset)
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int at = offset;
        long left = -1;
        while (at != END_MARKER && at >= 0 && at + BLOCK_HEADER_SIZE <= bytes.length)
        {
            String head = new String(bytes, at, BLOCK_HEADER_SIZE, StandardCharsets.US_ASCII);
            String[] parts = head.trim().split(" "); //$NON-NLS-1$
            if (parts.length != 3)
            {
                throw new IllegalArgumentException("Block header not understood at " + at); //$NON-NLS-1$
            }
            long docSize = Long.parseLong(parts[0], 16);
            long blockSize = Long.parseLong(parts[1], 16);
            long next = Long.parseLong(parts[2], 16);
            if (left < 0)
            {
                left = docSize;
            }
            long take = Math.min(blockSize, left);
            int start = at + BLOCK_HEADER_SIZE;
            if (start + take > bytes.length)
            {
                throw new IllegalArgumentException("Block runs past the end of the file at " + at); //$NON-NLS-1$
            }
            out.write(bytes, start, (int)take);
            left -= take;
            if (left <= 0 || next == END_MARKER)
            {
                break;
            }
            at = (int)next;
        }
        return out.toByteArray();
    }

    private static void writeDocument(ByteArrayOutputStream out, byte[] data, int blockSize)
    {
        String head = String.format(Locale.ROOT, "\r\n%08x %08x %08x \r\n", //$NON-NLS-1$
            Integer.valueOf(data.length), Integer.valueOf(blockSize), Integer.valueOf(END_MARKER));
        byte[] headBytes = head.getBytes(StandardCharsets.US_ASCII);
        out.write(headBytes, 0, headBytes.length);
        out.write(data, 0, data.length);
        for (int i = data.length; i < blockSize; i++)
        {
            out.write(0);
        }
    }

    /**
     * The platform's timestamp: 100-microsecond intervals since 0001-01-01. Measured: a form
     * written in 2026 carries 637311959090000, which is that unit and not a finer one.
     *
     * @return now, in that unit
     */
    static long nowAsPlatformTime()
    {
        // 62135596800 seconds separate 0001-01-01 from the Unix epoch; 10 units per millisecond.
        long unixMillis = System.currentTimeMillis();
        return (unixMillis + 62135596800000L) * 10L;
    }
}
