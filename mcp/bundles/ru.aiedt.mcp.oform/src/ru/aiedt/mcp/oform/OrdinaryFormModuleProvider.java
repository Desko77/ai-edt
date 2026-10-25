/**
 * AI-EDT Ordinary Forms
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.eclipse.core.resources.IProject;

import ru.aiedt.mcp.server.support.modules.IModuleSource;
import ru.aiedt.mcp.server.support.modules.IModuleSourceProvider;

/**
 * The module source provider this bundle publishes: the modules of the ordinary forms of a
 * project, by the address every form module has, read and written through the container.
 *
 * <p>Published as an OSGi service; the plugin's whiteboard hands it to the module tools for as
 * long as the service is registered.</p>
 */
public final class OrdinaryFormModuleProvider
    implements IModuleSourceProvider
{
    @Override
    public String kind()
    {
        return OrdinaryFormModule.KIND;
    }

    @Override
    public IModuleSource locate(IProject project, String modulePath)
    {
        return OrdinaryFormModule.locate(project, modulePath);
    }

    /**
     * Every ordinary form's module of a project: the container without a {@code Module.bsl}
     * beside it, addressed as that file.
     *
     * @param project the project
     * @return the modules, in path order
     * @throws IOException when the tree cannot be walked
     */
    @Override
    public List<IModuleSource> list(IProject project) throws IOException
    {
        List<IModuleSource> out = new ArrayList<>();
        if (project == null || project.getLocation() == null)
        {
            return out;
        }
        Path src = project.getLocation().toFile().toPath().resolve("src"); //$NON-NLS-1$
        for (OrdinaryFormLocator.Located located : OrdinaryFormLocator.locate(project))
        {
            String containerPath = src.relativize(located.file()).toString().replace('\\', '/');
            IModuleSource module = OrdinaryFormModule.locate(project, OrdinaryFormModule.moduleAddressOf(containerPath));
            if (module != null)
            {
                out.add(module);
            }
        }
        return out;
    }

    @Override
    public String coverage(Collection<IProject> projects, String what)
    {
        return OrdinaryFormCoverage.statement(projects, what);
    }
}
