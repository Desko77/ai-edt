/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * A member name is one segment of its collection directory. Each refusal names the argument, and a
 * name that stays inside resolves to the normalized member path, Cyrillic included.
 */
public class CollectionMemberPathTest
{
    private Path root;

    /** A throwaway root. The collection used by the acceptance tests still contains {@code ..}. */
    @Before
    public void aRoot() throws IOException
    {
        root = Files.createTempDirectory("xdto-member-"); //$NON-NLS-1$
    }

    /** Removes the throwaway root. */
    @After
    public void theRootGoes() throws IOException
    {
        if (root == null)
        {
            return;
        }
        try (var walk = Files.walk(root))
        {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try
                {
                    Files.deleteIfExists(path);
                }
                catch (IOException ioe)
                {
                    throw new IllegalStateException(ioe);
                }
            });
        }
    }

    /** An empty name is refused and the refusal names the argument. */
    @Test
    public void anEmptyNameIsRefused()
    {
        String refusal = refusal("packageName", ""); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(refusal, refusal.contains("packageName")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("empty")); //$NON-NLS-1$
    }

    /** A blank name is the same refusal as an empty one. */
    @Test
    public void aBlankNameIsRefused()
    {
        String refusal = refusal("packageName", "   "); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(refusal, refusal.contains("packageName")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("empty")); //$NON-NLS-1$
    }

    /** A missing name is empty. */
    @Test
    public void aNullNameIsRefused()
    {
        String refusal = refusal("name", null); //$NON-NLS-1$
        assertTrue(refusal, refusal.startsWith("name ")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("empty")); //$NON-NLS-1$
    }

    /** A slash is a second segment and is refused. */
    @Test
    public void aSlashIsRefused()
    {
        String refusal = refusal("packageName", "a/b"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(refusal, refusal.contains("packageName")); //$NON-NLS-1$
        assertTrue(refusal, refusal.indexOf('/') >= 0);
    }

    /** A backslash is a second segment and is refused. */
    @Test
    public void aBackslashIsRefused()
    {
        String refusal = refusal("name", "a\\b"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(refusal, refusal.startsWith("name ")); //$NON-NLS-1$
        assertTrue(refusal, refusal.indexOf('\\') >= 0);
    }

    /** {@code ..} is refused by name, before the path is joined. */
    @Test
    public void aParentSegmentIsRefused()
    {
        String refusal = refusal("packageName", ".."); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(refusal, refusal.contains("packageName")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("..")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("must not contain")); //$NON-NLS-1$
    }

    /** A colon is refused even where the file system would keep the name inside the directory. */
    @Test
    public void aColonIsRefused()
    {
        String refusal = refusal("packageName", "a:b"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(refusal, refusal.contains("packageName")); //$NON-NLS-1$
        assertTrue(refusal, refusal.indexOf(':') >= 0);
        assertTrue(refusal, !refusal.contains("absolute")); //$NON-NLS-1$
    }

    /** An absolute path is refused as absolute, ahead of the separators it also carries. */
    @Test
    public void anAbsolutePathIsRefused() throws IOException
    {
        Path outside = Files.createTempDirectory("xdto-outside-"); //$NON-NLS-1$
        try
        {
            String refusal = refusal("packageName", outside.toString()); //$NON-NLS-1$
            assertTrue(refusal, refusal.contains("packageName")); //$NON-NLS-1$
            assertTrue(refusal, refusal.contains("absolute")); //$NON-NLS-1$
        }
        finally
        {
            Files.deleteIfExists(outside);
        }
    }

    /**
     * {@code .} survives the name check and normalizes onto the collection itself, which is not a
     * member.
     */
    @Test
    public void aDotResolvesOntoTheCollectionAndIsRefused()
    {
        String refusal = refusal("packageName", "."); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(refusal, refusal.contains("packageName")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("resolves outside")); //$NON-NLS-1$
    }

    /** A path that normalizes beside the collection is refused, and a path inside it is not. */
    @Test
    public void aPathThatNormalizesOutsideTheCollectionIsRefused() throws IOException
    {
        Path collection = root.resolve("packages"); //$NON-NLS-1$
        Files.createDirectories(collection);
        Path escaped = collection.resolve("..").resolve("outside").normalize(); //$NON-NLS-1$ //$NON-NLS-2$
        String refusal = CollectionMemberPath.confinementRefusal("packageName", collection, escaped); //$NON-NLS-1$
        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains("packageName")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("resolves outside")); //$NON-NLS-1$

        Path sibling = collection.resolveSibling(collection.getFileName().toString() + "Evil"); //$NON-NLS-1$
        String siblingRefusal = CollectionMemberPath.confinementRefusal("packageName", collection, //$NON-NLS-1$
            sibling);
        assertNotNull(siblingRefusal);
        assertTrue(siblingRefusal, siblingRefusal.contains("packageName")); //$NON-NLS-1$

        assertNull(CollectionMemberPath.confinementRefusal("packageName", collection, //$NON-NLS-1$
            collection.resolve("Ok"))); //$NON-NLS-1$
    }

    /** A plain name stays inside, and the path is normalized even when the collection was not. */
    @Test
    public void aPlainNameStaysInsideTheCollection() throws IOException
    {
        Path collection = root.resolve("nested").resolve("..").resolve("packages"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Files.createDirectories(collection);
        CollectionMemberPath.Place place = CollectionMemberPath.member(collection, "packageName", //$NON-NLS-1$
            "MyPackage"); //$NON-NLS-1$
        assertNull(place.refusal());
        assertEquals(root.resolve("packages").resolve("MyPackage").toAbsolutePath().normalize(), //$NON-NLS-1$ //$NON-NLS-2$
            place.path());
    }

    /** A Cyrillic name is a member name. It is not a path. */
    @Test
    public void aCyrillicNameStaysInsideTheCollection() throws IOException
    {
        Path collection = root.resolve("nested").resolve("..").resolve("packages"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Files.createDirectories(collection);
        String name = "\u041f\u0430\u043a\u0435\u0442\u041e\u0431\u043c\u0435\u043d\u0430"; //$NON-NLS-1$
        CollectionMemberPath.Place place = CollectionMemberPath.member(collection, "packageName", name); //$NON-NLS-1$
        assertNull(place.refusal());
        assertEquals(root.resolve("packages").resolve(name).toAbsolutePath().normalize(), place.path()); //$NON-NLS-1$
    }

    private String refusal(String argument, String memberName)
    {
        CollectionMemberPath.Place place = CollectionMemberPath.member(root.resolve("packages"), //$NON-NLS-1$
            argument, memberName);
        assertNull(place.path());
        assertNotNull(place.refusal());
        return place.refusal();
    }
}
