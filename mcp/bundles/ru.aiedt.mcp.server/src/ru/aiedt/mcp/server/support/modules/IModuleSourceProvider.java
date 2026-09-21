/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support.modules;

import java.io.IOException;
import java.util.Collection;
import java.util.List;

import org.eclipse.core.resources.IProject;

/**
 * A source of BSL modules that have an address but no {@code Module.bsl} file - modules EDT
 * keeps somewhere it does not open, provided to this server by another bundle.
 *
 * <p>A provider is published as an OSGi service of this interface. {@link ModuleSources} tracks
 * the services and asks each of them in turn: the module tools resolve an address through
 * {@link ModuleSources#locate}, the listing and the text search walk {@link #list}, and the
 * answers built from the BSL index end with what {@link #coverage} says about the modules the
 * index does not hold.</p>
 */
public interface IModuleSourceProvider
{
    /** @return the kind the provider's modules list under, in the style of {@code ObjectModule} */
    String kind();

    /**
     * Resolves a module address.
     *
     * @param project the project
     * @param modulePath the address, {@code src}-relative with forward slashes, possibly with a
     *        leading {@code src/}; an FQN a caller may also pass has been turned into an address
     *        already
     * @return the module, or {@code null} when the address is not one of this provider's
     */
    IModuleSource locate(IProject project, String modulePath);

    /**
     * Every module of the provider in a project.
     *
     * @param project the project
     * @return the modules, possibly none
     * @throws IOException when the project cannot be read
     */
    List<IModuleSource> list(IProject project) throws IOException;

    /**
     * What an answer built from the BSL index has to say about this provider's modules, which
     * the index does not hold.
     *
     * @param projects the projects the answer spans
     * @param what what the answer is about, e.g. {@code callers and callees}
     * @return one line for the answer, or {@code null} when the projects hold no such modules
     */
    default String coverage(Collection<IProject> projects, String what)
    {
        return null;
    }
}
