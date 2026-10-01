/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.Charset;

import org.eclipse.core.resources.IFile;
import org.junit.Test;

/**
 * A module saved in a single-byte Cyrillic charset is read in that charset on every path of the
 * symbol reader.
 * <p>
 * The main path asks the file for its charset; the EMF fallback decoded the same bytes as UTF-8
 * whatever the workspace said, and every offset it computed over the mojibake pointed at the wrong
 * character. Both paths now read through one method, and this pins what that method does with a
 * windows-1251 file.
 * </p>
 */
public class ANonUtf8ModuleIsReadInItsOwnCharsetTest
{
    /**
     * A file whose bytes are windows-1251 and whose workspace charset says so.
     *
     * @param bytes the file's content
     * @param charset the charset the workspace declares for it
     * @return the file stand-in
     */
    private static IFile fileWith(byte[] bytes, String charset)
    {
        return (IFile)Proxy.newProxyInstance(ANonUtf8ModuleIsReadInItsOwnCharsetTest.class
            .getClassLoader(), new Class<?>[] { IFile.class }, (proxy, method, args) ->
            {
                switch (method.getName())
                {
                    case "getContents": //$NON-NLS-1$
                        return new ByteArrayInputStream(bytes);
                    case "getCharset": //$NON-NLS-1$
                        return charset;
                    case "getLocation": //$NON-NLS-1$
                        return null;
                    default:
                        throw new UnsupportedOperationException(method.getName());
                }
            });
    }

    /** Cyrillic text in a declared single-byte charset decodes back to itself. */
    @Test
    public void aWindows1251ModuleDecodesAsWindows1251() throws Exception
    {
        String russian = "Процедура ИсполняемыеСценарии(СписокТестов)"; //$NON-NLS-1$
        byte[] bytes = russian.getBytes(Charset.forName("windows-1251")); //$NON-NLS-1$

        String text = SymbolInfoReader.readModuleContent(fileWith(bytes, "windows-1251")); //$NON-NLS-1$

        assertEquals(russian, text);
    }
}
