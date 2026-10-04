/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Resolves one member of a metadata collection directory and refuses a name that would leave it.
 * <p>
 * An XDTO package name and a common picture name are single path segments. Joining either onto its
 * collection with {@link Path#resolve} keeps an absolute name as itself and walks {@code ..} out of
 * the project. The name is refused before that join. The joined path is then normalized and has to
 * stay inside the collection, so a form the name check does not recognise still cannot escape.
 * </p>
 */
public final class CollectionMemberPath
{
    private CollectionMemberPath()
    {
        // utility
    }

    /**
     * A member directory, or the reason the name was refused.
     */
    public static final class Place
    {
        private final Path path;

        private final String refusal;

        private Place(Path path, String refusal)
        {
            this.path = path;
            this.refusal = refusal;
        }

        /**
         * @param path the member directory
         * @return a place that may be used
         */
        static Place accepted(Path path)
        {
            return new Place(path, null);
        }

        /**
         * @param refusal the refusal, naming the argument and the reason
         * @return a place that must not be used
         */
        static Place refused(String refusal)
        {
            return new Place(null, refusal);
        }

        /**
         * @return the member directory, or {@code null} when the name was refused
         */
        public Path path()
        {
            return path;
        }

        /**
         * @return the refusal naming the argument and the reason, or {@code null} when {@link #path()}
         *         is usable
         */
        public String refusal()
        {
            return refusal;
        }
    }

    /**
     * Resolves {@code memberName} as one member of {@code collectionDir}.
     *
     * @param collectionDir the directory that holds the members, such as {@code src/XDTOPackages}
     * @param argument the caller argument the refusal names, such as {@code packageName} or
     *            {@code name}
     * @param memberName the member name as the caller gave it
     * @return the member directory, or a refusal
     */
    public static Place member(Path collectionDir, String argument, String memberName)
    {
        String label = label(argument);
        if (collectionDir == null)
        {
            return Place.refused(label + " has no collection directory"); //$NON-NLS-1$
        }
        String nameProblem = nameRefusal(label, memberName);
        if (nameProblem != null)
        {
            return Place.refused(nameProblem);
        }
        Path collection = collectionDir.toAbsolutePath().normalize();
        Path resolved = collection.resolve(memberName).normalize();
        String confined = confinementRefusal(label, collection, resolved);
        if (confined != null)
        {
            return Place.refused(confined);
        }
        return Place.accepted(resolved);
    }

    /**
     * Why a resolved path may not be used as a member of the collection.
     * <p>
     * Both paths are normalized first. A path that equals the collection, or that does not start
     * with it, is outside: {@code .} lands on the collection itself, and {@code ..} lands beside it.
     * </p>
     *
     * @param argument the caller argument the refusal names
     * @param collectionDir the collection directory; a relative path is made absolute
     * @param resolved the member path
     * @return the refusal, or {@code null} when the member stays strictly inside the collection
     */
    static String confinementRefusal(String argument, Path collectionDir, Path resolved)
    {
        String label = label(argument);
        if (collectionDir == null || resolved == null)
        {
            return label + " resolves outside the collection directory"; //$NON-NLS-1$
        }
        Path collection = collectionDir.toAbsolutePath().normalize();
        Path candidate = resolved.toAbsolutePath().normalize();
        if (candidate.equals(collection) || !candidate.startsWith(collection))
        {
            return label + " resolves outside the collection directory"; //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Why {@code memberName} is not a single member name.
     *
     * @param argument the caller argument the refusal names; already defaulted
     * @param memberName the candidate
     * @return the refusal, or {@code null} when the name is one relative segment
     */
    private static String nameRefusal(String argument, String memberName)
    {
        if (memberName == null || memberName.isBlank())
        {
            return argument + " is empty"; //$NON-NLS-1$
        }
        Path asPath;
        try
        {
            asPath = Path.of(memberName);
        }
        catch (InvalidPathException invalid)
        {
            String reason = invalid.getReason() == null ? invalid.getClass().getSimpleName()
                : invalid.getReason();
            return argument + " is not a usable path (" + reason + ")"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (asPath.isAbsolute())
        {
            return argument + " must not be an absolute path"; //$NON-NLS-1$
        }
        if (memberName.indexOf('/') >= 0)
        {
            return argument + " must not contain '/'"; //$NON-NLS-1$
        }
        if (memberName.indexOf('\\') >= 0)
        {
            return argument + " must not contain '\\'"; //$NON-NLS-1$
        }
        if (memberName.contains("..")) //$NON-NLS-1$
        {
            return argument + " must not contain '..'"; //$NON-NLS-1$
        }
        if (memberName.indexOf(':') >= 0)
        {
            return argument + " must not contain ':'"; //$NON-NLS-1$
        }
        return null;
    }

    /**
     * The argument name a refusal quotes.
     *
     * @param argument the caller argument; may be blank
     * @return {@code argument}, or {@code name} when none was given
     */
    private static String label(String argument)
    {
        return argument == null || argument.isBlank() ? "name" : argument; //$NON-NLS-1$
    }
}
