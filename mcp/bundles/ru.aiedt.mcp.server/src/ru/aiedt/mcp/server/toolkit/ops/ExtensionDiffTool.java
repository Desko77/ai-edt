/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;

import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.MetadataTypeCatalog;
import ru.aiedt.mcp.server.support.ProjectResolver;

/**
 * 1.43+: shallow diff of an adopted (borrowed) metadata object between an
 * extension project and its base configuration. Compares attributes,
 * tabular sections, forms, commands and templates by name; for attributes
 * also compares the {@code getType()} string representation when reachable.
 * <p>
 * Designed to give an AI agent a quick "what does this extension actually
 * change" snapshot without parsing .mdo XML by hand.
 * <p>
 * Limitations: shallow comparison only - method bodies, form layouts and
 * detailed property changes are NOT compared (use {@code diff_module} for
 * BSL diffs and {@code get_form_structure} for forms).
 */
public class ExtensionDiffTool implements IMcpTool
{
    public static final String NAME = "extension_diff"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `extension_workshop` `operation=extension_diff`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Shallow diff of an adopted metadata object between an extension and its base " //$NON-NLS-1$
            + "configuration. Compares attributes, tabular sections, forms, commands and templates " //$NON-NLS-1$
            + "by name (attributes also by type). Use to see what an extension actually overrides " //$NON-NLS-1$
            + "without reading .mdo files. For BSL diffs use diff_module; for form layout " //$NON-NLS-1$
            + "differences use get_form_structure on both sides."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "Extension project name (V8ExtensionNature). Required.", true) //$NON-NLS-1$
            .stringProperty("baseProjectName", //$NON-NLS-1$
                "Base configuration project name (V8ConfigurationNature). Required.", true) //$NON-NLS-1$
            .stringProperty("objectFqn", //$NON-NLS-1$
                "FQN of the adopted object (e.g. 'Catalog.Products', 'Document.SalesOrder'). " //$NON-NLS-1$
                    + "Russian type names supported.", true) //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String baseProjectName = JsonUtils.extractStringArgument(params, "baseProjectName"); //$NON-NLS-1$
        String objectFqn = JsonUtils.extractStringArgument(params, "objectFqn"); //$NON-NLS-1$
        if (projectName == null || projectName.isEmpty()
            || baseProjectName == null || baseProjectName.isEmpty()
            || objectFqn == null || objectFqn.isEmpty())
        {
            return ToolResult.error("projectName, baseProjectName and objectFqn are required.") //$NON-NLS-1$
                .toJson();
        }
        objectFqn = MetadataTypeCatalog.normalizeFqn(objectFqn);

        IProject ext = ProjectResolver.resolve(projectName);
        IProject base = ProjectResolver.resolve(baseProjectName);
        if (ext == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }
        if (base == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(baseProjectName)).toJson();
        }

        IConfigurationProvider cp = Activator.getDefault().getConfigurationProvider();
        if (cp == null)
        {
            return ToolResult.error("IConfigurationProvider is not available.").toJson(); //$NON-NLS-1$
        }
        Configuration extCfg = cp.getConfiguration(ext);
        Configuration baseCfg = cp.getConfiguration(base);
        if (extCfg == null || baseCfg == null)
        {
            return ToolResult.error("Configuration could not be loaded for one of the projects. " //$NON-NLS-1$
                + "Make sure both projects are open and indexed.").toJson(); //$NON-NLS-1$
        }

        String[] parts = objectFqn.split("\\.", 2); //$NON-NLS-1$
        if (parts.length != 2 || parts[1].isEmpty())
        {
            return ToolResult.error("Expected a two-segment FQN like 'Catalog.Products', got: " //$NON-NLS-1$
                + objectFqn).toJson();
        }
        MdObject extObj = MetadataTypeCatalog.findObject(extCfg, parts[0], parts[1]);
        MdObject baseObj = MetadataTypeCatalog.findObject(baseCfg, parts[0], parts[1]);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("objectFqn", objectFqn); //$NON-NLS-1$
        body.put("extension", projectName); //$NON-NLS-1$
        body.put("base", baseProjectName); //$NON-NLS-1$

        if (extObj == null && baseObj == null)
        {
            return ToolResult.error("Object " + objectFqn //$NON-NLS-1$
                + " not found in either project.").toJson(); //$NON-NLS-1$
        }
        if (extObj == null)
        {
            body.put("status", "missingInExtension"); //$NON-NLS-1$ //$NON-NLS-2$
            body.put("hint", "Object exists in base only - not adopted into the extension yet. " //$NON-NLS-1$ //$NON-NLS-2$
                + "Use extension_workshop borrow_object to bring it in.");
            return ToolResult.success().put("extensionDiff", body).toJson(); //$NON-NLS-1$
        }
        if (baseObj == null)
        {
            body.put("status", "newInExtension"); //$NON-NLS-1$ //$NON-NLS-2$
            body.put("hint", "Object exists in the extension only. Extensions can introduce new " //$NON-NLS-1$ //$NON-NLS-2$
                + "metadata; this object is a brand-new addition rather than an override.");
            return ToolResult.success().put("extensionDiff", body).toJson(); //$NON-NLS-1$
        }

        body.put("status", "compared"); //$NON-NLS-1$ //$NON-NLS-2$
        body.put("attributes", diffChildren(extObj, baseObj, "getAttributes", true)); //$NON-NLS-1$ //$NON-NLS-2$
        body.put("tabularSections", diffChildren(extObj, baseObj, "getTabularSections", false)); //$NON-NLS-1$ //$NON-NLS-2$
        body.put("forms", diffChildren(extObj, baseObj, "getForms", false)); //$NON-NLS-1$ //$NON-NLS-2$
        body.put("commands", diffChildren(extObj, baseObj, "getCommands", false)); //$NON-NLS-1$ //$NON-NLS-2$
        body.put("templates", diffChildren(extObj, baseObj, "getTemplates", false)); //$NON-NLS-1$ //$NON-NLS-2$
        body.put("hint", "Shallow comparison only. For BSL diffs use diff_module; for form layouts " //$NON-NLS-1$ //$NON-NLS-2$
            + "use get_form_structure on both projects and compare the JSON trees.");
        return ToolResult.success().put("extensionDiff", body).toJson(); //$NON-NLS-1$
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> diffChildren(MdObject ext, MdObject base, String getter,
        boolean compareTypes)
    {
        Map<String, Object> diff = new LinkedHashMap<>();
        List<? extends EObject> extKids = invokeListGetter(ext, getter);
        List<? extends EObject> baseKids = invokeListGetter(base, getter);
        if (extKids == null && baseKids == null)
        {
            diff.put("supported", false); //$NON-NLS-1$
            diff.put("hint", "Object type does not expose " + getter + "()"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return diff;
        }
        Map<String, EObject> extByName = indexByName(extKids);
        Map<String, EObject> baseByName = indexByName(baseKids);
        Set<String> only = new TreeSet<>(extByName.keySet());
        only.removeAll(baseByName.keySet());
        Set<String> missing = new TreeSet<>(baseByName.keySet());
        missing.removeAll(extByName.keySet());
        Set<String> common = new HashSet<>(extByName.keySet());
        common.retainAll(baseByName.keySet());

        diff.put("inExtensionOnly", new ArrayList<>(only)); //$NON-NLS-1$
        diff.put("missingFromExtension", new ArrayList<>(missing)); //$NON-NLS-1$
        diff.put("common", new ArrayList<>(new TreeSet<>(common))); //$NON-NLS-1$
        diff.put("extensionCount", extByName.size()); //$NON-NLS-1$
        diff.put("baseCount", baseByName.size()); //$NON-NLS-1$

        if (compareTypes && !common.isEmpty())
        {
            List<Map<String, Object>> typeDifferences = new ArrayList<>();
            for (String name : new TreeSet<>(common))
            {
                String extType = stringifyType(extByName.get(name));
                String baseType = stringifyType(baseByName.get(name));
                if (extType != null && baseType != null && !extType.equals(baseType))
                {
                    Map<String, Object> td = new LinkedHashMap<>();
                    td.put("name", name); //$NON-NLS-1$
                    td.put("extensionType", extType); //$NON-NLS-1$
                    td.put("baseType", baseType); //$NON-NLS-1$
                    typeDifferences.add(td);
                }
            }
            diff.put("typeChanges", typeDifferences); //$NON-NLS-1$
        }
        return diff;
    }

    private static List<? extends EObject> invokeListGetter(MdObject obj, String getterName)
    {
        if (obj == null)
        {
            return null;
        }
        try
        {
            Method m = obj.getClass().getMethod(getterName);
            Object value = m.invoke(obj);
            if (value instanceof List)
            {
                @SuppressWarnings("unchecked")
                List<? extends EObject> list = (List<? extends EObject>) value;
                return list;
            }
        }
        catch (NoSuchMethodException ignored)
        {
            // not all MdObject subclasses expose every collection - silent.
        }
        catch (Exception e)
        {
            Activator.logWarning(getterName + " on " + obj.eClass().getName() //$NON-NLS-1$
                + " failed: " + e.getMessage()); //$NON-NLS-1$
        }
        return null;
    }

    private static Map<String, EObject> indexByName(List<? extends EObject> kids)
    {
        Map<String, EObject> m = new LinkedHashMap<>();
        if (kids == null)
        {
            return m;
        }
        for (EObject k : kids)
        {
            String name = invokeNameGetter(k);
            if (name != null && !name.isEmpty())
            {
                m.put(name, k);
            }
        }
        return m;
    }

    private static String invokeNameGetter(Object obj)
    {
        try
        {
            Object v = obj.getClass().getMethod("getName").invoke(obj); //$NON-NLS-1$
            return v != null ? v.toString() : null;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static String stringifyType(Object attribute)
    {
        if (attribute == null)
        {
            return null;
        }
        try
        {
            Object type = attribute.getClass().getMethod("getType").invoke(attribute); //$NON-NLS-1$
            if (type == null)
            {
                return null;
            }
            // By name, never by toString. A TypeDescription answers toString with its identity, so
            // two instances describing the very same type render differently and compare unequal -
            // measured on an untouched extension, where every borrowed attribute came back as
            // having changed type. Sorted, so the same set of types always renders the same way.
            java.util.List<String> names = new java.util.ArrayList<>(
                ru.aiedt.mcp.server.support.BmDefinedTypeHelper.readTypeDescriptionNames(type));
            if (names.isEmpty())
            {
                return null;
            }
            java.util.Collections.sort(names);
            return String.join(", ", names); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            return null;
        }
    }
}
