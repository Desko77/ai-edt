/**
 * AI-EDT Ordinary Forms
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform.ops;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import ru.aiedt.mcp.server.wire.SchemaComposer;

/**
 * Every operation of {@code ordinary_forms}, gathered from the theme catalogs in a fixed order.
 * <p>
 * A theme catalog is one class with one static {@code specs()} list; a theme that grows adds to
 * its own list. Two specs with one name are a programming error and fail the catalog at load.
 * The arguments of every operation merge into one schema by name: an argument two operations
 * share must have one type, and its description names each operation's meaning.
 * </p>
 */
public final class OperationsCatalog
{
    /** The arguments every operation may take, described once. */
    private static final List<ParamSpec> COMMON = List.of(
        ParamSpec.string("projectName", "Name of the EDT project whose ordinary forms to read or write."), //$NON-NLS-1$ //$NON-NLS-2$
        ParamSpec.string("formFqn", //$NON-NLS-1$
            "The form, Type.Object.Form.Name or CommonForm.Name (Catalog.Products.Form.ItemForm). Alternatively modulePath."), //$NON-NLS-1$
        ParamSpec.string("modulePath", "The form by the address of its module, <dir>/<object>/Forms/<form>/Module.bsl."), //$NON-NLS-1$ //$NON-NLS-2$
        ParamSpec.bool("dryRun", "Writing operations: report what the write would do and write nothing (default false)."), //$NON-NLS-1$ //$NON-NLS-2$
        ParamSpec.string("language", "Writing operations: the language code of new texts (default ru).")); //$NON-NLS-1$ //$NON-NLS-2$

    private static final Map<String, OperationSpec> BY_NAME = new LinkedHashMap<>();

    static
    {
        List<OperationSpec> all = new ArrayList<>();
        all.addAll(CoreOperations.specs());
        all.addAll(ColumnControlOperations.specs());
        all.addAll(ComposerOperations.specs());
        all.addAll(ImportOperations.specs());
        all.addAll(CommandPanelOperations.specs());
        all.addAll(MoveOperations.specs());
        all.addAll(StyleOperations.specs());
        // Reading first, then creating, then editing, help last - the order a reader of the
        // schema and the help meets the operations in, whatever catalog declares them.
        List<OperationSpec> ordered = new ArrayList<>();
        for (OperationSpec.Kind kind : new OperationSpec.Kind[] { OperationSpec.Kind.QUERY, OperationSpec.Kind.CREATION,
            OperationSpec.Kind.LAYOUT })
        {
            for (OperationSpec spec : all)
            {
                if (spec.kind == kind && !spec.name.equals("help")) //$NON-NLS-1$
                {
                    ordered.add(spec);
                }
            }
        }
        for (OperationSpec spec : all)
        {
            if (spec.name.equals("help")) //$NON-NLS-1$
            {
                ordered.add(spec);
            }
        }
        for (OperationSpec spec : ordered)
        {
            String key = spec.name.toLowerCase(Locale.ROOT);
            if (BY_NAME.containsKey(key))
            {
                throw new IllegalStateException("Two catalogs declare the operation " + spec.name); //$NON-NLS-1$
            }
            BY_NAME.put(key, spec);
        }
    }

    private OperationsCatalog()
    {
        // static catalog
    }

    /** @return the spec of an operation, or {@code null}; case does not matter */
    public static OperationSpec find(String name)
    {
        return name == null ? null : BY_NAME.get(name.trim().toLowerCase(Locale.ROOT));
    }

    /** @return the names in declaration order */
    public static List<String> knownNames()
    {
        List<String> out = new ArrayList<>();
        for (OperationSpec spec : BY_NAME.values())
        {
            out.add(spec.name);
        }
        return out;
    }

    /** @return the names of the operations that write */
    public static List<String> writingNames()
    {
        List<String> out = new ArrayList<>();
        for (OperationSpec spec : BY_NAME.values())
        {
            if (spec.writes)
            {
                out.add(spec.name);
            }
        }
        return out;
    }

    /** @return the help lines, one per operation, in declaration order */
    public static List<String> helpLines()
    {
        List<String> out = new ArrayList<>();
        for (OperationSpec spec : BY_NAME.values())
        {
            out.add(spec.name + " - " + spec.help); //$NON-NLS-1$
        }
        return out;
    }

    /**
     * The tool's input schema: {@code operation}, the common arguments, then every argument any
     * operation declares, merged by name.
     *
     * @return the schema as JSON
     */
    public static String schema()
    {
        SchemaComposer composer = SchemaComposer.object()
            .stringProperty("operation", String.join(" | ", knownNames()) + " (required)"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Map<String, ParamSpec.Type> types = new LinkedHashMap<>();
        Map<String, List<String>> descriptions = new LinkedHashMap<>();
        for (ParamSpec common : COMMON)
        {
            types.put(common.name, common.type);
            descriptions.computeIfAbsent(common.name, k -> new ArrayList<>()).add(common.description());
        }
        for (OperationSpec spec : BY_NAME.values())
        {
            for (ParamSpec param : spec.params)
            {
                ParamSpec.Type known = types.get(param.name);
                if (known != null && known != param.type)
                {
                    throw new IllegalStateException("The argument " + param.name + " is a " + known + " for one operation and a " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        + param.type + " for " + spec.name); //$NON-NLS-1$
                }
                types.put(param.name, param.type);
                descriptions.computeIfAbsent(param.name, k -> new ArrayList<>()).add(spec.name + ": " + param.description()); //$NON-NLS-1$
            }
        }
        for (Map.Entry<String, ParamSpec.Type> entry : types.entrySet())
        {
            String description = String.join(" ", descriptions.get(entry.getKey())); //$NON-NLS-1$
            switch (entry.getValue())
            {
            case INTEGER:
                composer.integerProperty(entry.getKey(), description);
                break;
            case BOOLEAN:
                composer.booleanProperty(entry.getKey(), description);
                break;
            default:
                composer.stringProperty(entry.getKey(), description);
                break;
            }
        }
        return composer.build();
    }

    /** @return the names of the schema's arguments with their types, for the tests */
    public static Map<String, ParamSpec.Type> argumentTypes()
    {
        Map<String, ParamSpec.Type> types = new LinkedHashMap<>();
        types.put("operation", ParamSpec.Type.STRING); //$NON-NLS-1$
        for (ParamSpec common : COMMON)
        {
            types.put(common.name, common.type);
        }
        for (OperationSpec spec : BY_NAME.values())
        {
            for (ParamSpec param : spec.params)
            {
                types.put(param.name, param.type);
            }
        }
        return types;
    }
}
