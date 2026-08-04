/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.dt.mcore.QName;
import com._1c.g5.v8.dt.xdto.model.ObjectType;
import com._1c.g5.v8.dt.xdto.model.Package;
import com._1c.g5.v8.dt.xdto.model.Property;
import com._1c.g5.v8.dt.xdto.model.ValueType;

import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.BmXdtoHelper;
import ru.aiedt.mcp.server.support.ProjectResolver;

/**
 * XDTO package content constructor. Authors the {@code Package.xdto} schema
 * beside an {@code XDTOPackage} metadata object (create the XDTOPackage first
 * with {@code edit_metadata create_object objectType=XDTOPackage}). Content ops
 * auto-create {@code Package.xdto} on first mutation.
 *
 * <p>Operations: add_object_type, add_value_type, add_property,
 * remove_object_type, remove_value_type, remove_property, set_namespace, read.
 * The model is mutated and the file is written via {@link BmXdtoHelper} (the
 * file is the persistence; EDT re-imports it on the next workspace refresh).
 */
public class XdtoWorkshopTool implements IMcpTool
{
    public static final String NAME = "xdto_workshop"; //$NON-NLS-1$

    private static final Map<String, String> OPS = buildOpsCatalog();

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "XDTO package content constructor. Authors the " //$NON-NLS-1$
            + "Package.xdto schema beside an XDTOPackage. Create the XDTOPackage " //$NON-NLS-1$
            + "first via edit_metadata create_object objectType=XDTOPackage, then " //$NON-NLS-1$
            + "use add_object_type / add_value_type / add_property (+ remove_*, " //$NON-NLS-1$
            + "set_namespace, read). Pass ownerFqn=XDTOPackage.<name> (or " //$NON-NLS-1$
            + "packageName). Property type is a QName: type=<localName> + " //$NON-NLS-1$
            + "typeNamespace (default XSD); upperBound=-1 means unbounded."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("operation", //$NON-NLS-1$
                "add_object_type / add_value_type / add_property / remove_object_type / " //$NON-NLS-1$
                    + "remove_value_type / remove_property / set_namespace / read / help",
                true)
            .stringProperty("projectName", "Name of the EDT project to work in") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("ownerFqn", "XDTOPackage.<name> (or use packageName)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("packageName", "XDTOPackage name (alternative to ownerFqn)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("name", //$NON-NLS-1$
                "Name of the object type / value type / property to add or remove") //$NON-NLS-1$
            .stringProperty("objectType", //$NON-NLS-1$
                "add_property/remove_property: owning object type name (omit for a package-level property)") //$NON-NLS-1$
            .stringProperty("type", //$NON-NLS-1$
                "add_property: property type local name (e.g. 'string', 'tFoo')") //$NON-NLS-1$
            .stringProperty("typeNamespace", //$NON-NLS-1$
                "add_property: namespace URI of 'type' (default XSD http://www.w3.org/2001/XMLSchema)") //$NON-NLS-1$
            .integerProperty("lowerBound", "add_property: min occurrences (0 = optional)") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("upperBound", "add_property: max occurrences (-1 = unbounded)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("form", "add_property: Element (default) / Attribute / Text") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("nillable", "add_property: whether the property accepts xsi:nil") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("variety", "add_value_type: Atomic (default) / List / Union") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("itemType", "add_value_type (List variety): item type local name") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("itemTypeNamespace", "add_value_type: namespace of itemType (default XSD)") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("open", "add_object_type: open content model") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("abstract", "add_object_type: abstract type") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("mixed", "add_object_type: mixed content") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("namespace", "set_namespace: target namespace URI of the package") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("elementFormQualified", "set_namespace: elementFormQualified flag") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("attributeFormQualified", "set_namespace: attributeFormQualified flag") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("dryRun", "Preview without writing (default false)") //$NON-NLS-1$ //$NON-NLS-2$
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
        String op = JsonUtils.extractStringArgument(params, "operation"); //$NON-NLS-1$
        if (op == null || op.isEmpty())
        {
            return ToolResult.error("operation is required").toJson(); //$NON-NLS-1$
        }
        if ("help".equalsIgnoreCase(op)) //$NON-NLS-1$
        {
            return handleHelp();
        }
        if (!OPS.containsKey(op))
        {
            return ToolResult.error("Unknown operation: " + op //$NON-NLS-1$
                + ". Available: " + String.join(", ", OPS.keySet())).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (!BmXdtoHelper.xdtoApiAvailable())
        {
            return ToolResult.error("XDTO model API (com._1c.g5.v8.dt.xdto.model) is not " //$NON-NLS-1$
                + "reachable on this EDT runtime").toJson(); //$NON-NLS-1$
        }
        switch (op)
        {
            case "add_object_type": //$NON-NLS-1$
                return opAddObjectType(params);
            case "add_value_type": //$NON-NLS-1$
                return opAddValueType(params);
            case "add_property": //$NON-NLS-1$
                return opAddProperty(params);
            case "remove_object_type": //$NON-NLS-1$
                return opRemoveObjectType(params);
            case "remove_value_type": //$NON-NLS-1$
                return opRemoveValueType(params);
            case "remove_property": //$NON-NLS-1$
                return opRemoveProperty(params);
            case "set_namespace": //$NON-NLS-1$
                return opSetNamespace(params);
            case "read": //$NON-NLS-1$
                return opRead(params);
            default:
                return ToolResult.error("Unhandled op: " + op).toJson(); //$NON-NLS-1$
        }
    }

    private String opAddObjectType(Map<String, String> params)
    {
        Ctx c = resolve(params);
        if (c.error != null)
        {
            return c.error;
        }
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        Boolean open = boolArg(params, "open"); //$NON-NLS-1$
        Boolean isAbstract = boolArg(params, "abstract"); //$NON-NLS-1$
        Boolean mixed = boolArg(params, "mixed"); //$NON-NLS-1$
        String err = BmXdtoHelper.mutatePackage(c.project, c.packageName, defaultNs(c.packageName),
            pkg -> BmXdtoHelper.addObjectType(pkg, name, open, isAbstract, mixed), c.dryRun);
        return result(err, "add_object_type", c, "object type '" + name + "'"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private String opAddValueType(Map<String, String> params)
    {
        Ctx c = resolve(params);
        if (c.error != null)
        {
            return c.error;
        }
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        String variety = JsonUtils.extractStringArgument(params, "variety"); //$NON-NLS-1$
        String itemType = JsonUtils.extractStringArgument(params, "itemType"); //$NON-NLS-1$
        String itemTypeNs = JsonUtils.extractStringArgument(params, "itemTypeNamespace"); //$NON-NLS-1$
        String err = BmXdtoHelper.mutatePackage(c.project, c.packageName, defaultNs(c.packageName),
            pkg -> BmXdtoHelper.addValueType(pkg, name, variety, itemType, itemTypeNs), c.dryRun);
        return result(err, "add_value_type", c, "value type '" + name + "'"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private String opAddProperty(Map<String, String> params)
    {
        Ctx c = resolve(params);
        if (c.error != null)
        {
            return c.error;
        }
        String objectType = JsonUtils.extractStringArgument(params, "objectType"); //$NON-NLS-1$
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        String type = JsonUtils.extractStringArgument(params, "type"); //$NON-NLS-1$
        String typeNs = JsonUtils.extractStringArgument(params, "typeNamespace"); //$NON-NLS-1$
        Integer lower = intArg(params, "lowerBound"); //$NON-NLS-1$
        Integer upper = intArg(params, "upperBound"); //$NON-NLS-1$
        String form = JsonUtils.extractStringArgument(params, "form"); //$NON-NLS-1$
        Boolean nillable = boolArg(params, "nillable"); //$NON-NLS-1$
        String err = BmXdtoHelper.mutatePackage(c.project, c.packageName, defaultNs(c.packageName),
            pkg -> BmXdtoHelper.addProperty(pkg, objectType, name, type, typeNs, lower, upper, form,
                nillable),
            c.dryRun);
        String where = objectType != null && !objectType.isEmpty() ? objectType : "package"; //$NON-NLS-1$
        return result(err, "add_property", c, "property '" + name + "' on " + where); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private String opRemoveObjectType(Map<String, String> params)
    {
        Ctx c = resolve(params);
        if (c.error != null)
        {
            return c.error;
        }
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        if (name == null || name.isEmpty())
        {
            return ToolResult.error("name is required").toJson(); //$NON-NLS-1$
        }
        boolean[] removed = { false };
        String err = BmXdtoHelper.mutatePackage(c.project, c.packageName, defaultNs(c.packageName),
            pkg -> {
                removed[0] = BmXdtoHelper.removeObjectType(pkg, name);
                return removed[0] ? null : BmXdtoHelper.NO_CHANGE;
            }, c.dryRun);
        return removeResult(err, "remove_object_type", c, removed[0], "object type '" + name + "'"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private String opRemoveValueType(Map<String, String> params)
    {
        Ctx c = resolve(params);
        if (c.error != null)
        {
            return c.error;
        }
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        if (name == null || name.isEmpty())
        {
            return ToolResult.error("name is required").toJson(); //$NON-NLS-1$
        }
        boolean[] removed = { false };
        String err = BmXdtoHelper.mutatePackage(c.project, c.packageName, defaultNs(c.packageName),
            pkg -> {
                removed[0] = BmXdtoHelper.removeValueType(pkg, name);
                return removed[0] ? null : BmXdtoHelper.NO_CHANGE;
            }, c.dryRun);
        return removeResult(err, "remove_value_type", c, removed[0], "value type '" + name + "'"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private String opRemoveProperty(Map<String, String> params)
    {
        Ctx c = resolve(params);
        if (c.error != null)
        {
            return c.error;
        }
        String objectType = JsonUtils.extractStringArgument(params, "objectType"); //$NON-NLS-1$
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        if (name == null || name.isEmpty())
        {
            return ToolResult.error("name is required").toJson(); //$NON-NLS-1$
        }
        boolean[] removed = { false };
        String err = BmXdtoHelper.mutatePackage(c.project, c.packageName, defaultNs(c.packageName),
            pkg -> {
                removed[0] = BmXdtoHelper.removeProperty(pkg, objectType, name);
                return removed[0] ? null : BmXdtoHelper.NO_CHANGE;
            }, c.dryRun);
        return removeResult(err, "remove_property", c, removed[0], "property '" + name + "'"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private String opSetNamespace(Map<String, String> params)
    {
        Ctx c = resolve(params);
        if (c.error != null)
        {
            return c.error;
        }
        String namespace = JsonUtils.extractStringArgument(params, "namespace"); //$NON-NLS-1$
        Boolean elementFq = boolArg(params, "elementFormQualified"); //$NON-NLS-1$
        Boolean attributeFq = boolArg(params, "attributeFormQualified"); //$NON-NLS-1$
        if (namespace == null && elementFq == null && attributeFq == null)
        {
            return ToolResult
                .error("set_namespace requires namespace and/or elementFormQualified/attributeFormQualified") //$NON-NLS-1$
                .toJson();
        }
        String err = BmXdtoHelper.mutatePackage(c.project, c.packageName, defaultNs(c.packageName),
            pkg -> {
                if (namespace != null)
                {
                    pkg.setNsUri(namespace);
                }
                if (elementFq != null)
                {
                    pkg.setElementFormQualified(elementFq.booleanValue());
                }
                if (attributeFq != null)
                {
                    pkg.setAttributeFormQualified(attributeFq.booleanValue());
                }
                return null;
            }, c.dryRun);
        return result(err, "set_namespace", c, "namespace"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private String opRead(Map<String, String> params)
    {
        Ctx c = resolve(params);
        if (c.error != null)
        {
            return c.error;
        }
        Package pkg = BmXdtoHelper.readPackage(c.project, c.packageName);
        if (pkg == null)
        {
            return ToolResult.success()
                .put("operation", "read") //$NON-NLS-1$ //$NON-NLS-2$
                .put("packageName", c.packageName) //$NON-NLS-1$
                .put("message", "Package.xdto is absent or empty (no schema content yet)") //$NON-NLS-1$ //$NON-NLS-2$
                .toJson();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("namespace: ").append(pkg.getNsUri()).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("objectTypes (").append(pkg.getObjects().size()).append("):\n"); //$NON-NLS-1$ //$NON-NLS-2$
        for (ObjectType ot : pkg.getObjects())
        {
            sb.append("  - ").append(ot.getName()); //$NON-NLS-1$
            if (!ot.getProperties().isEmpty())
            {
                sb.append(" {"); //$NON-NLS-1$
                boolean first = true;
                for (Property p : ot.getProperties())
                {
                    sb.append(first ? "" : ", ").append(p.getName()) //$NON-NLS-1$ //$NON-NLS-2$
                        .append(": ").append(qnameStr(p.getType())); //$NON-NLS-1$
                    first = false;
                }
                sb.append("}"); //$NON-NLS-1$
            }
            sb.append("\n"); //$NON-NLS-1$
        }
        sb.append("valueTypes (").append(pkg.getTypes().size()).append("):\n"); //$NON-NLS-1$ //$NON-NLS-2$
        for (ValueType vt : pkg.getTypes())
        {
            sb.append("  - ").append(vt.getName()).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (!pkg.getProperties().isEmpty())
        {
            sb.append("package properties (").append(pkg.getProperties().size()).append("):\n"); //$NON-NLS-1$ //$NON-NLS-2$
            for (Property p : pkg.getProperties())
            {
                sb.append("  - ").append(p.getName()).append(": ").append(qnameStr(p.getType())) //$NON-NLS-1$ //$NON-NLS-2$
                    .append("\n"); //$NON-NLS-1$
            }
        }
        return ToolResult.success()
            .put("operation", "read") //$NON-NLS-1$ //$NON-NLS-2$
            .put("packageName", c.packageName) //$NON-NLS-1$
            .put("schema", sb.toString()) //$NON-NLS-1$
            .toJson();
    }

    // ---- helpers ----

    private static final class Ctx
    {
        IProject project;
        String packageName;
        boolean dryRun;
        String error;
    }

    private Ctx resolve(Map<String, String> params)
    {
        Ctx c = new Ctx();
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String packageName = JsonUtils.extractStringArgument(params, "packageName"); //$NON-NLS-1$
        c.dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$
        if (packageName == null && ownerFqn != null)
        {
            int dot = ownerFqn.indexOf('.');
            packageName = dot >= 0 ? ownerFqn.substring(dot + 1) : ownerFqn;
        }
        if (projectName == null || packageName == null || packageName.isEmpty())
        {
            c.error = ToolResult
                .error("projectName and ownerFqn (XDTOPackage.<name>) or packageName are required") //$NON-NLS-1$
                .toJson();
            return c;
        }
        c.project = ProjectResolver.resolve(projectName);
        if (c.project == null)
        {
            c.error = ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
            return c;
        }
        c.packageName = packageName;
        return c;
    }

    private static String defaultNs(String packageName)
    {
        return "http://www.example.org/" + packageName; //$NON-NLS-1$
    }

    private String result(String err, String op, Ctx c, String what)
    {
        if (err != null)
        {
            return ToolResult.error(op + " failed: " + err) //$NON-NLS-1$
                .put("operation", op).put("packageName", c.packageName).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return ToolResult.success()
            .put("operation", op) //$NON-NLS-1$
            .put("packageName", c.packageName) //$NON-NLS-1$
            .put("dryRun", c.dryRun) //$NON-NLS-1$
            .put("message", (c.dryRun ? "[dryRun] " : "") + what + " applied") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            .toJson();
    }

    private String removeResult(String err, String op, Ctx c, boolean removed, String what)
    {
        if (err != null)
        {
            return ToolResult.error(op + " failed: " + err) //$NON-NLS-1$
                .put("operation", op).put("packageName", c.packageName).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        ToolResult r = ToolResult.success()
            .put("operation", op) //$NON-NLS-1$
            .put("packageName", c.packageName) //$NON-NLS-1$
            .put("dryRun", c.dryRun) //$NON-NLS-1$
            .put("message", removed ? what + " removed" : "no " + what + " (idempotent skip)"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        if (!removed)
        {
            r.put("idempotentSkip", what); //$NON-NLS-1$
        }
        return r.toJson();
    }

    private static Boolean boolArg(Map<String, String> params, String key)
    {
        // Use the shared nullable extractor (accepts true/1/yes, false/0/no).
        return JsonUtils.extractBooleanArgumentNullable(params, key);
    }

    private static Integer intArg(Map<String, String> params, String key)
    {
        // Use the shared nullable extractor: Gson stringifies JSON integers via
        // Double.toString(), so a value of 5 arrives as "5.0" and -1 as "-1.0".
        // extractIntegerArgument parses through Double; a naive Integer.valueOf
        // would throw on "5.0" and silently drop lowerBound/upperBound.
        return JsonUtils.extractIntegerArgument(params, key);
    }

    private static String qnameStr(QName q)
    {
        if (q == null || q.getName() == null)
        {
            return "?"; //$NON-NLS-1$
        }
        // Keep the namespace visible so read output is unambiguous and can be
        // mapped back to add_property (type + typeNamespace).
        if (BmXdtoHelper.XSD_NS.equals(q.getNsUri()))
        {
            return "xs:" + q.getName(); //$NON-NLS-1$
        }
        return q.getNsUri() == null || q.getNsUri().isEmpty() ? q.getName()
            : q.getName() + "@" + q.getNsUri(); //$NON-NLS-1$
    }

    private String handleHelp()
    {
        StringBuilder sb = new StringBuilder("# xdto_workshop\n\n"); //$NON-NLS-1$
        sb.append("Authors the Package.xdto schema of an XDTOPackage.\n\n"); //$NON-NLS-1$
        sb.append("**Workflow:**\n"); //$NON-NLS-1$
        sb.append("1. edit_metadata create_object objectType=XDTOPackage name=MyPackage\n"); //$NON-NLS-1$
        sb.append("2. xdto_workshop set_namespace ownerFqn=XDTOPackage.MyPackage namespace=http://my/ns\n"); //$NON-NLS-1$
        sb.append("3. xdto_workshop add_object_type ownerFqn=XDTOPackage.MyPackage name=tOrder\n"); //$NON-NLS-1$
        sb.append("4. xdto_workshop add_property ownerFqn=XDTOPackage.MyPackage objectType=tOrder name=Number type=string\n"); //$NON-NLS-1$
        sb.append("5. xdto_workshop add_property ... objectType=tOrder name=Items type=tItem typeNamespace=http://my/ns upperBound=-1\n\n"); //$NON-NLS-1$
        sb.append("**Operations:** add_object_type, add_value_type, add_property, " //$NON-NLS-1$
            + "remove_object_type, remove_value_type, remove_property, set_namespace, read.\n"); //$NON-NLS-1$
        sb.append("Property type is a QName (type local name + typeNamespace, default XSD). " //$NON-NLS-1$
            + "upperBound=-1 = unbounded; lowerBound=0 = optional. Omit objectType for a " //$NON-NLS-1$
            + "package-level (global) property.\n"); //$NON-NLS-1$
        sb.append("XDTO API: ").append(BmXdtoHelper.xdtoApiAvailable() //$NON-NLS-1$
            ? "available" : "UNAVAILABLE on this runtime").append("\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return ToolResult.success().put("help", sb.toString()).toJson(); //$NON-NLS-1$
    }

    private static Map<String, String> buildOpsCatalog()
    {
        Map<String, String> m = new LinkedHashMap<>();
        for (String op : Arrays.asList("add_object_type", "add_value_type", "add_property", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "remove_object_type", "remove_value_type", "remove_property", "set_namespace", "read")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        {
            m.put(op, op);
        }
        return Collections.unmodifiableMap(m);
    }
}
