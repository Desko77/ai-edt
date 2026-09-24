/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support.modules;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A BSL module that has a module address but no {@code Module.bsl} behind it, provided by an
 * {@link IModuleSourceProvider} of another bundle.
 *
 * <p>The module tools of this server - reading, outlining, reading a method, writing, listing,
 * searching - resolve an address to a file first and to a module source second, and treat the
 * two alike from then on: the text is a list of lines, a write is a list of lines. What differs
 * is said in the answer: {@link #source()} names where the text lives, {@link #containerPath()}
 * the file that holds it, {@link #validationNote()} why EDT's validation does not apply, and
 * {@link #afterWrite} what happens to the written text next.</p>
 */
public interface IModuleSource
{
    /** What a write would do beyond writing, decided by the provider from the request. */
    final class WriteCheck
    {
        /** Fields for the answer's front matter, in order; may be empty. */
        public final Map<String, String> notes = new LinkedHashMap<>();

        /** A warning to show with the answer, or {@code null}. */
        public String warning;

        /** Why the write must not happen, or {@code null} when it may. */
        public String refusal;

        /**
         * A check that lets the write through with nothing to say.
         *
         * @return the check
         */
        public static WriteCheck clear()
        {
            return new WriteCheck();
        }
    }

    /** @return the kind the module lists under, in the style of {@code ObjectModule} */
    String kind();

    /** @return the tag the answers carry as {@code source}, naming where the text lives */
    String source();

    /** @return the module address, {@code src}-relative with forward slashes */
    String modulePath();

    /** @return the {@code src}-relative path of the file that holds the module, or {@code null} */
    String containerPath();

    /** @return the module's FQN in the style of {@code Catalog.Products.Form.ItemForm.Module}, or {@code null} */
    String fqn();

    /**
     * The text, split into lines with no line terminators.
     *
     * @return the lines, empty for an empty module
     * @throws IOException when the module cannot be read
     */
    List<String> lines() throws IOException;

    /**
     * Whether the module can be read only: a provider that does not write answers {@code true}
     * and the writer refuses before reading.
     *
     * @return {@code true} when writes are refused
     */
    default boolean readOnly()
    {
        return false;
    }

    /**
     * The lines of this module as another revision of its container holds them.
     *
     * <p>Asked when a module without a file of its own has to be compared with something: HEAD
     * holds the text of the file that carries it, not the module itself, so only the provider can
     * find its own module inside that revision. The bytes are the container file as that revision
     * has it, read the way any file of that revision would be - the same text a checkout of it
     * would leave on disk.</p>
     *
     * <p>A provider that does not read previous revisions leaves this implementation in place, and
     * the answer names that outcome instead of comparing against nothing. A provider that does read
     * them answers {@code null} when that revision holds no such module: the module is new.</p>
     *
     * @param containerRevision the container file as the previous revision holds it
     * @return the lines, no terminators; {@code null} when that revision does not hold the module
     * @throws IOException when the revision cannot be parsed into this module's text
     */
    default List<String> linesIn(byte[] containerRevision) throws IOException
    {
        throw new UnsupportedOperationException(kind() + " does not read previous revisions"); //$NON-NLS-1$
    }

    /**
     * Examines a write before it happens.
     *
     * @param before the lines as they are
     * @param after the lines the write would leave
     * @param options the request's arguments, for the provider's own options
     * @return what the provider has to say; never {@code null}
     */
    default WriteCheck check(List<String> before, List<String> after, Map<String, String> options)
    {
        return WriteCheck.clear();
    }

    /**
     * Writes the module.
     *
     * @param lines the lines, no terminators
     * @return {@code true} when something was written, {@code false} when the text was identical
     *         and nothing was touched
     * @throws IOException when the write fails
     */
    boolean write(List<String> lines) throws IOException;

    /**
     * What happens to the written module next, for the answer's {@code delivery} field.
     *
     * @param written whether {@link #write} wrote anything
     * @return a statement, or {@code null} when there is nothing to say
     */
    default String afterWrite(boolean written)
    {
        return null;
    }

    /**
     * Why EDT's validation of the module is not available.
     *
     * @return the note for the answer's {@code validation} field, or {@code null} when validation
     *         applies as to any module
     */
    default String validationNote()
    {
        return null;
    }

    /**
     * Fields every answer about this module carries, beyond {@code source} and {@code container}.
     *
     * @return the fields in order; may be empty
     */
    default Map<String, String> answerFields()
    {
        return Collections.emptyMap();
    }
}
