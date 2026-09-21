/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support.oform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code Form.oform} of an ordinary form: a container with two text entries, {@code form}
 * (the brace text of the layout) and {@code module} (the BSL of the form module), each written
 * with a UTF-8 BOM.
 *
 * <p>EDT does not read this file - it moves it as it is and hands it to the platform - so the
 * file is the only place the form lives, and a write here is what the platform sees on the next
 * database update. The container keeps every entry it has, known or not, and the layout text is
 * read into a {@link BraceTree} only when asked, so a module edit never touches the layout's
 * bytes.</p>
 */
public final class OrdinaryFormFile
{
    /** The entry that holds the layout. */
    public static final String FORM_ENTRY = "form"; //$NON-NLS-1$

    /** The entry that holds the module. */
    public static final String MODULE_ENTRY = "module"; //$NON-NLS-1$

    private static final byte[] BOM = { (byte)0xEF, (byte)0xBB, (byte)0xBF };

    private final Path path;

    private final V8Container container;

    private OrdinaryFormFile(Path path, V8Container container)
    {
        this.path = path;
        this.container = container;
    }

    /**
     * Reads a form file.
     *
     * @param path the {@code Form.oform}
     * @return the file
     * @throws IOException when the file cannot be read or is not a container
     */
    public static OrdinaryFormFile read(Path path) throws IOException
    {
        byte[] bytes = Files.readAllBytes(path);
        try
        {
            return new OrdinaryFormFile(path, V8Container.read(bytes));
        }
        catch (IllegalArgumentException e)
        {
            throw new IOException(path + ": " + e.getMessage(), e); //$NON-NLS-1$
        }
    }

    /**
     * Wraps a container that is not on disk yet.
     *
     * @param path where the file will be written
     * @param container the container
     * @return the file
     */
    public static OrdinaryFormFile of(Path path, V8Container container)
    {
        return new OrdinaryFormFile(path, container);
    }

    /** @return the path of the file */
    public Path getPath()
    {
        return path;
    }

    /** @return the container, for what the two named entries do not cover */
    public V8Container getContainer()
    {
        return container;
    }

    /** @return the names of the entries, in table order */
    public List<String> entryNames()
    {
        List<String> names = new ArrayList<>();
        for (V8Container.Entry entry : container.getEntries())
        {
            names.add(entry.getName());
        }
        return names;
    }

    /**
     * The layout text, BOM stripped.
     *
     * @return the text, or {@code null} when the container has no {@code form} entry
     */
    public String formText()
    {
        return textOf(FORM_ENTRY);
    }

    /**
     * The module text, BOM stripped.
     *
     * @return the text, or {@code null} when the container has no {@code module} entry
     */
    public String moduleText()
    {
        return textOf(MODULE_ENTRY);
    }

    /**
     * The layout as a tree.
     *
     * @return the root list
     * @throws IllegalArgumentException when the layout text does not parse
     * @throws IllegalStateException when there is no layout entry
     */
    public BraceTree.ListNode formTree()
    {
        String text = formText();
        if (text == null)
        {
            throw new IllegalStateException("No 'form' entry in " + path); //$NON-NLS-1$
        }
        return BraceTree.parse(text);
    }

    /**
     * The version the layout text opens with - the number the platform that last saved the form
     * wrote first.
     *
     * @return the version token, or {@code null} when there is no layout or it does not start
     *         with one
     */
    public String formVersion()
    {
        String text = formText();
        if (text == null || !text.startsWith("{")) //$NON-NLS-1$
        {
            return null;
        }
        int end = 1;
        while (end < text.length() && Character.isDigit(text.charAt(end)))
        {
            end++;
        }
        return end > 1 ? text.substring(1, end) : null;
    }

    /**
     * Replaces the module text.
     *
     * @param module the new module source
     */
    public void setModuleText(String module)
    {
        setText(MODULE_ENTRY, module);
    }

    /**
     * Replaces the layout text.
     *
     * @param form the new layout text, as {@link BraceTree#serialize} writes it
     */
    public void setFormText(String form)
    {
        setText(FORM_ENTRY, form);
    }

    /**
     * Replaces the layout with a tree.
     *
     * @param root the root list
     */
    public void setFormTree(BraceTree.ListNode root)
    {
        setFormText(BraceTree.serialize(root));
    }

    /** @return whether anything was changed since the file was read */
    public boolean isDirty()
    {
        return container.isDirty();
    }

    /**
     * Writes the file back. Nothing is written when nothing changed.
     *
     * @throws IOException when the file cannot be written
     */
    public void save() throws IOException
    {
        if (!container.isDirty())
        {
            return;
        }
        Files.write(path, container.write());
    }

    /**
     * The bytes the file would hold now, changed or not.
     *
     * @return the container bytes
     */
    public byte[] toBytes()
    {
        return container.write();
    }

    private String textOf(String entryName)
    {
        V8Container.Entry entry = container.find(entryName);
        if (entry == null)
        {
            return null;
        }
        byte[] data = entry.getData();
        int offset = startsWithBom(data) ? BOM.length : 0;
        return new String(data, offset, data.length - offset, StandardCharsets.UTF_8);
    }

    private void setText(String entryName, String text)
    {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        byte[] data = new byte[BOM.length + body.length];
        System.arraycopy(BOM, 0, data, 0, BOM.length);
        System.arraycopy(body, 0, data, BOM.length, body.length);
        V8Container.Entry entry = container.find(entryName);
        if (entry == null)
        {
            container.add(V8Container.Entry.of(entryName, data));
        }
        else
        {
            entry.setData(data);
        }
    }

    private static boolean startsWithBom(byte[] data)
    {
        return data.length >= BOM.length && data[0] == BOM[0] && data[1] == BOM[1] && data[2] == BOM[2];
    }
}
