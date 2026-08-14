/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.ui.editor.contentassist.ContentAssistContext;
import org.eclipse.xtext.ui.editor.contentassist.ICompletionProposalAcceptor;
import org.eclipse.xtext.ui.editor.contentassist.IContentProposalProvider;
import org.eclipse.xtext.ui.editor.model.XtextDocument;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.toolkit.ops.BslModuleAccess;

/**
 * Asks the language what could be typed at a position, without opening the module in an editor.
 * <p>
 * Content assist looks like an editor feature, and the way into it is written that way: a context is
 * built from a text viewer and an offset, then the language's proposal provider fills an acceptor. But
 * the two services are plain entries in the language's injector, and reading what they actually take
 * from the viewer shows the editor is not needed - see {@link OffScreenTextViewer}.
 * </p>
 * <p>
 * One thing does need care. BSL's proposal provider keeps a cache of document listeners keyed by the
 * viewer's text widget, and it reaches for that cache at the top of every call. A viewer with no widget
 * would send it to build a fresh entry, and building one registers a dispose listener on the widget
 * that is not there. The cache is an ordinary map, which accepts a missing widget as a key perfectly
 * well, so the entry is put there once up front and the provider finds it instead of building it. That
 * keeps the whole call off the display thread, where SWT would refuse it anyway.
 * </p>
 */
public final class BslProposalAccess
{
    /** A generous ceiling on how many proposals are collected before filtering. */
    static final int HARD_CEILING = 5000;

    private BslProposalAccess()
    {
    }

    /**
     * Collects the completion proposals the environment would offer at a position.
     *
     * @param text the module's text
     * @param resource the module's already-loaded resource
     * @param offset the character offset the question is asked at
     * @return the proposals, never <code>null</code>
     * @throws IllegalStateException when the language contributes no content assist
     */
    public static ICompletionProposal[] proposalsAt(String text, XtextResource resource, int offset)
    {
        IResourceServiceProvider services = IResourceServiceProvider.Registry.INSTANCE
            .getResourceServiceProvider(BslModuleAccess.BSL_LOOKUP_URI);
        if (services == null)
        {
            throw new IllegalStateException("The BSL language services are not registered"); //$NON-NLS-1$
        }
        ContentAssistContext.Factory factory = services.get(ContentAssistContext.Factory.class);
        IContentProposalProvider provider = services.get(IContentProposalProvider.class);
        if (factory == null || provider == null)
        {
            throw new IllegalStateException("The BSL language contributes no content assist"); //$NON-NLS-1$
        }

        ITextViewer viewer = new OffScreenTextViewer(languageDocument(services, text, resource), offset);
        seedWidgetKeyedCache(provider, viewer);

        ContentAssistContext[] contexts = factory.create(viewer, offset, resource);
        if (contexts == null || contexts.length == 0)
        {
            return new ICompletionProposal[0];
        }

        Collector collector = new Collector();
        for (ContentAssistContext context : contexts)
        {
            if (!collector.canAcceptMoreProposals())
            {
                break;
            }
            provider.createProposals(context, collector);
        }
        return collector.collected();
    }

    /**
     * Builds the document the language expects to be handed back.
     * <p>
     * Not any document will do. BSL's proposal provider casts the context's document to its own
     * concrete class while working out which keyword closes the block around the caret, so a document
     * that merely implements the Xtext interface fails that cast on every position inside an
     * {@code Если}, {@code Пока}, {@code Для} or {@code Попытка} - which is most of the interesting
     * positions in a module. The language's own document is bound in its injector, so it is asked for
     * one rather than given a substitute.
     * </p>
     * <p>
     * The resource handed to it is the one this plugin loaded, from its own resource set - not the one
     * an open editor owns - so pointing a document at it does not disturb anybody's editing session.
     * If the environment refuses the pairing, the text alone still answers most of the question, so
     * that is not made fatal.
     * </p>
     *
     * @param services the language's services
     * @param text the module's text
     * @param resource the module's already-loaded resource
     * @return the document to ask through
     */
    private static IDocument languageDocument(IResourceServiceProvider services, String text,
        XtextResource resource)
    {
        XtextDocument document = services.get(XtextDocument.class);
        if (document == null)
        {
            return new ReadOnlyModuleDocument(text, resource);
        }
        document.set(text);
        try
        {
            document.setInput(resource);
        }
        catch (RuntimeException e)
        {
            Activator.logWarning("The module could not be paired with its document: " + e.getMessage()); //$NON-NLS-1$
        }
        return document;
    }

    /**
     * Puts an entry under the absent widget so the provider never tries to build one.
     * <p>
     * Found by shape rather than by name: a map on the provider whose keys are text widgets. Naming the
     * field would tie this to today's spelling of a private detail; the shape is what the behaviour
     * depends on. When nothing matches - because the environment stopped keeping such a cache - there is
     * nothing to seed and nothing to worry about, so this returns quietly.
     * </p>
     *
     * @param provider the language's proposal provider
     * @param viewer the viewer the proposals will be asked through
     * @return <code>true</code> when a cache was found and seeded
     */
    static boolean seedWidgetKeyedCache(Object provider, ITextViewer viewer)
    {
        for (Class<?> type = provider.getClass(); type != null && type != Object.class; type =
            type.getSuperclass())
        {
            for (Field field : type.getDeclaredFields())
            {
                if (!Map.class.isAssignableFrom(field.getType()) || !keyedByWidget(field))
                {
                    continue;
                }
                return seed(provider, field, viewer);
            }
        }
        return false;
    }

    /**
     * @param field the candidate field
     * @return <code>true</code> when the map's keys are SWT text widgets
     */
    private static boolean keyedByWidget(Field field)
    {
        Type generic = field.getGenericType();
        if (!(generic instanceof ParameterizedType))
        {
            return false;
        }
        Type[] arguments = ((ParameterizedType)generic).getActualTypeArguments();
        return arguments.length == 2 && arguments[0] instanceof Class
            && "org.eclipse.swt.custom.StyledText".equals(((Class<?>)arguments[0]).getName()); //$NON-NLS-1$
    }

    /**
     * @param provider the language's proposal provider
     * @param field the widget-keyed cache
     * @param viewer the viewer the cached value is built for
     * @return <code>true</code> when the cache now holds an entry for the absent widget
     */
    @SuppressWarnings("unchecked")
    private static boolean seed(Object provider, Field field, ITextViewer viewer)
    {
        try
        {
            field.setAccessible(true);
            Map<Object, Object> cache = (Map<Object, Object>)field.get(provider);
            if (cache == null)
            {
                return false;
            }
            if (cache.containsKey(null))
            {
                return true;
            }
            Class<?> valueType = (Class<?>)((ParameterizedType)field.getGenericType()).getActualTypeArguments()[1];
            Object value = buildCacheValue(valueType, provider, viewer);
            if (value == null)
            {
                return false;
            }
            cache.put(null, value);
            return true;
        }
        catch (Exception e)
        {
            // Worth saying out loud: without the seed the call will fail on the missing widget, and
            // the reason will be a bare NPE from inside the environment.
            Activator.logWarning(
                "Could not prepare BSL content assist for a call without an editor: " + e.getMessage()); //$NON-NLS-1$
            return false;
        }
    }

    /**
     * Builds the value the cache would have built for itself.
     *
     * @param valueType the cache's value class
     * @param provider the proposal provider, which the value is an inner class of
     * @param viewer the viewer
     * @return the value, or <code>null</code> when it cannot be built
     * @throws Exception when construction fails
     */
    private static Object buildCacheValue(Class<?> valueType, Object provider, ITextViewer viewer)
        throws Exception
    {
        for (Constructor<?> constructor : valueType.getDeclaredConstructors())
        {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length != 2 || !parameters[0].isInstance(provider)
                || !parameters[1].isAssignableFrom(ITextViewer.class))
            {
                continue;
            }
            constructor.setAccessible(true);
            return constructor.newInstance(provider, viewer);
        }
        return null;
    }

    /**
     * Gathers proposals up to a ceiling.
     * <p>
     * The ceiling is not the caller's page size: filtering by substring happens afterwards, so cutting
     * at the page size would leave nothing to filter. It is there because a position in a large module
     * can offer thousands of proposals, and a long series of such calls is what once walked the heap to
     * its ceiling.
     * </p>
     */
    static final class Collector
        implements ICompletionProposalAcceptor
    {
        private final List<ICompletionProposal> proposals = new ArrayList<>();

        @Override
        public void accept(ICompletionProposal proposal)
        {
            if (proposal != null && this.proposals.size() < HARD_CEILING)
            {
                this.proposals.add(proposal);
            }
        }

        @Override
        public boolean canAcceptMoreProposals()
        {
            return this.proposals.size() < HARD_CEILING;
        }

        ICompletionProposal[] collected()
        {
            return this.proposals.toArray(new ICompletionProposal[0]);
        }
    }
}
