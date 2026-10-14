/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.labels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ru.aiedt.mcp.server.labels.MarkerManager.PreviewRead;

/**
 * A preview of a project whose settings folder has no location on disk answers from the current
 * name, which is where a write looks there.
 * <p>
 * The folder of a project whose location the platform cannot name holds no file a read can fall
 * back to: the earlier build's file cannot be read there, and the save route starts from the
 * current name as well. A preview that still fell back to the file an earlier build wrote would
 * answer from a file the write never looks at - tags the write does not see, or a refusal it does
 * not carry - and that is the one thing a preview must not do.
 * </p>
 * <p>
 * The project is stood up as a proxy: an accessible project whose settings folder reports no
 * location, with no file under the current name and a document under the earlier name that the
 * preview must leave unread. The counter over the earlier file's contents is what tells the two
 * answers apart, since a read of it cannot be told from a read of nothing by the answer alone.
 * </p>
 */
public class ATagPreviewOfAProjectWithoutASettingsLocationTest
{
    private static final String PROJECT = "AiEdtTagPreviewNoLocation"; //$NON-NLS-1$

    private static final String EARLIER_DOCUMENT = "tags:" + "\n" //$NON-NLS-1$ //$NON-NLS-2$
        + "- name: Legacy" + "\n" //$NON-NLS-1$ //$NON-NLS-2$
        + "assignments: {}" + "\n"; //$NON-NLS-1$ //$NON-NLS-2$

    private static final Object NOT_OBJECT = new Object();

    private final AtomicInteger earlierReads = new AtomicInteger();

    /**
     * A fresh service, so one test cannot leave a cache or a sign for the next.
     */
    @Before
    public void aFreshService()
    {
        MarkerManager.dispose();
    }

    /**
     * Drops the service the test used.
     */
    @After
    public void theServiceStops()
    {
        MarkerManager.dispose();
    }

    /**
     * The preview answers no markers at all, the same empty state the write reads, and it never
     * touches the file the earlier build wrote.
     *
     * @throws Exception when the platform refuses the proxy
     */
    @Test
    public void aPreviewAnswersFromTheCurrentNameWhereTheSettingsHaveNoLocation() throws Exception
    {
        IProject project = aProjectWhoseSettingsHaveNoLocation();

        MarkerManager manager = MarkerManager.getInstance();
        PreviewRead previewed = manager.readMarkersForPreview(project);

        assertFalse("the preview refused a file the write never looks at", //$NON-NLS-1$
            previewed.unreadable() || previewed.readFailed());
        assertEquals("the preview answered no markers", //$NON-NLS-1$
            0, previewed.storage().getTags().size());
        assertEquals("the earlier file was left unread", 0, earlierReads.get()); //$NON-NLS-1$
        assertEquals("and the state the write reads is the same empty one", //$NON-NLS-1$
            0, manager.getMarkerStorage(project).getTags().size());
        assertEquals("the write's source left the earlier file unread as well", //$NON-NLS-1$
            0, earlierReads.get());
    }

    /**
     * A project whose settings folder reports no location, holding an earlier build's file on disk
     * and nothing under the current name.
     *
     * @return the project
     */
    private IProject aProjectWhoseSettingsHaveNoLocation()
    {
        ClassLoader loader = IProject.class.getClassLoader();
        IFile current = aFile(false, null);
        IFile earlier = aFile(true, EARLIER_DOCUMENT.getBytes(StandardCharsets.UTF_8));
        InvocationHandler folderHandler = (proxy, method, arguments) ->
        {
            Object objectAnswer = objectAnswer(proxy, method, arguments);
            if (objectAnswer != NOT_OBJECT)
            {
                return objectAnswer;
            }
            switch (method.getName())
            {
                case "getLocation": //$NON-NLS-1$
                    return null;
                case "getFile": //$NON-NLS-1$
                    return MarkerKeys.MARKERS_FILE.equals(arguments[0]) ? current : earlier;
                default:
                    return fallback(method);
            }
        };
        IFolder folder = (IFolder)Proxy.newProxyInstance(loader, new Class<?>[] {IFolder.class},
            folderHandler);
        InvocationHandler projectHandler = (proxy, method, arguments) ->
        {
            Object objectAnswer = objectAnswer(proxy, method, arguments);
            if (objectAnswer != NOT_OBJECT)
            {
                return objectAnswer;
            }
            switch (method.getName())
            {
                case "getName": //$NON-NLS-1$
                    return PROJECT;
                case "isAccessible": //$NON-NLS-1$
                    return Boolean.TRUE;
                case "getFolder": //$NON-NLS-1$
                    return folder;
                default:
                    return fallback(method);
            }
        };
        return (IProject)Proxy.newProxyInstance(loader, new Class<?>[] {IProject.class}, projectHandler);
    }

    /**
     * A marker file handle with no location on disk, which may be there or not.
     *
     * @param there whether the handle says a file is there
     * @param bytes the contents a read gets, or {@code null} for a handle no read may take
     * @return the handle
     */
    private IFile aFile(boolean there, byte[] bytes)
    {
        ClassLoader loader = IFile.class.getClassLoader();
        return (IFile)Proxy.newProxyInstance(loader, new Class<?>[] {IFile.class},
            (proxy, method, arguments) ->
            {
                Object objectAnswer = objectAnswer(proxy, method, arguments);
                if (objectAnswer != NOT_OBJECT)
                {
                    return objectAnswer;
                }
                switch (method.getName())
                {
                    case "getLocation": //$NON-NLS-1$
                        return null;
                    case "exists": //$NON-NLS-1$
                        return Boolean.valueOf(there);
                    case "isReadOnly": //$NON-NLS-1$
                        return Boolean.FALSE;
                    case "getContents": //$NON-NLS-1$
                        earlierReads.incrementAndGet();
                        return new ByteArrayInputStream(bytes == null ? new byte[0] : bytes);
                    default:
                        return fallback(method);
                }
            });
    }

    /**
     * The answers every proxy owes the object protocol.
     *
     * @param proxy the proxy the call came to
     * @param method the method called
     * @param arguments the arguments, if any
     * @return the answer, or {@link #NOT_OBJECT} when this is a call of the interface
     */
    private static Object objectAnswer(Object proxy, Method method, Object[] arguments)
    {
        if (method.getDeclaringClass() != Object.class)
        {
            return NOT_OBJECT;
        }
        if ("toString".equals(method.getName())) //$NON-NLS-1$
        {
            return "marker-project-proxy"; //$NON-NLS-1$
        }
        if ("hashCode".equals(method.getName())) //$NON-NLS-1$
        {
            return Integer.valueOf(System.identityHashCode(proxy));
        }
        if ("equals".equals(method.getName())) //$NON-NLS-1$
        {
            return Boolean.valueOf(proxy == arguments[0]);
        }
        return NOT_OBJECT;
    }

    /**
     * The answer of a call the proxy does not care about.
     *
     * @param method the method called
     * @return the empty value of its type
     */
    private static Object fallback(Method method)
    {
        Class<?> type = method.getReturnType();
        if (type == boolean.class)
        {
            return Boolean.FALSE;
        }
        if (type == int.class)
        {
            return Integer.valueOf(0);
        }
        if (type == void.class)
        {
            return null;
        }
        throw new UnsupportedOperationException(method.getName());
    }
}
