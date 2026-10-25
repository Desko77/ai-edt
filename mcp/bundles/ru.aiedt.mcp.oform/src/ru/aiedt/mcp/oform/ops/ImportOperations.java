/**
 * AI-EDT Ordinary Forms
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform.ops;

import java.util.ArrayList;
import java.util.List;

import ru.aiedt.mcp.oform.OrdinaryFormImport;
import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * Forms that arrive in the project from its infobase: {@code import_from_infobase}.
 */
public final class ImportOperations
{
    private ImportOperations()
    {
        // static catalog
    }

    /** @return the specs of this catalog */
    public static List<OperationSpec> specs()
    {
        List<OperationSpec> out = new ArrayList<>();
        out.add(OperationSpec.creation("import_from_infobase", //$NON-NLS-1$
            "formFqn (or modulePath), module (project | infobase, default project), applicationId: the " //$NON-NLS-1$
                + "form's container pulled out of the project's infobase into the project. The layout " //$NON-NLS-1$
                + "and every other entry of the container come from the infobase, the module entry from " //$NON-NLS-1$
                + "the chosen source; a handler the infobase layout binds that the chosen module does " //$NON-NLS-1$
                + "not declare stops the write and is named in the answer", //$NON-NLS-1$
            List.of(ParamSpec.string("module", "where the module entry comes from - " //$NON-NLS-1$ //$NON-NLS-2$
                + "project (default: the module of the project's current container; refused when " //$NON-NLS-1$
                + "the project holds no container, with module=infobase named as the source that " //$NON-NLS-1$
                + "has one) or infobase (the exported container's own module)."), //$NON-NLS-1$
                ParamSpec.string("applicationId", "the infobase to export the form " //$NON-NLS-1$ //$NON-NLS-2$
                    + "from, when the project has more than one; the default infobase when omitted.")), //$NON-NLS-1$
            (project, params) -> ToolResult.toJsonStatic(OrdinaryFormImport.run(project, params))));
        return out;
    }
}
