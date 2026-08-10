/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;

import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.util.McoreUtil;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.support.BmObjectCopyHelper;
import ru.aiedt.mcp.server.support.ErrorTags;
import ru.aiedt.mcp.server.support.ExternalProjectResolver;
import ru.aiedt.mcp.server.support.MetadataTypeCatalog;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.TextSuggest;
import ru.aiedt.mcp.server.support.UiSync;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * Replicates an existing metadata object into another project as an object of its own.
 * <p>
 * This is not borrowing. Borrowing adopts an object that the base configuration already has, so that
 * an extension can intercept it; there is nothing to adopt when the object does not exist in the base.
 * What is missing then is a way to take an object that has been built and debugged somewhere - a
 * sandbox project, another configuration - and have it in the target project as its own. Rebuilding it
 * operation by operation is the alternative, and it is where the forms and the conditional appearance
 * get quietly lost.
 * </p>
 * <p>
 * The copying itself is EDT's, through {@code IModelObjectCopySupport}: identifiers, name generation
 * against what the target already holds, subsystem membership and type descriptions are its business.
 * What this adds is the addressing an agent needs - resolve by FQN, name the target project - and the
 * check that the object is actually there afterwards.
 * </p>
 */
public class ObjectCopier
    implements IMcpTool
{
    /** The wire name. */
    public static final String NAME = "copy_object"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Copy a metadata object into another project as an object of ITS OWN (not adopted). " //$NON-NLS-1$
            + "For an object the base configuration already has, use extension_workshop " //$NON-NLS-1$
            + "borrow_object instead - that intercepts, this replicates. EDT does the copying, so " //$NON-NLS-1$
            + "identifiers, a non-colliding name, subsystem membership and type descriptions are " //$NON-NLS-1$
            + "handled; the name EDT picks is reported back because it need not be the source's. " //$NON-NLS-1$
            + "References to objects the target project does not have are NOT resolved - validate " //$NON-NLS-1$
            + "the copy afterwards."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("sourceProjectName", "Project holding the object to copy (required)", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("objectFqn", //$NON-NLS-1$
                "FQN of the object to copy, e.g. 'Document.SalesOrder'. Russian type names work too. " //$NON-NLS-1$
                    + "Top-level objects only. Required.", true) //$NON-NLS-1$
            .stringProperty("targetProjectName", //$NON-NLS-1$
                "Project to copy it into. May be the same project, which is how an object is " //$NON-NLS-1$
                    + "duplicated. Required.", true) //$NON-NLS-1$
            .booleanProperty("allowMissingReferences", //$NON-NLS-1$
                "true copies even when the object refers to types the target project does not have. " //$NON-NLS-1$
                    + "Off by default: EDT carries reference types across verbatim without resolving " //$NON-NLS-1$
                    + "them, so a copy made over missing types looks complete and is broken. Pass it " //$NON-NLS-1$
                    + "when the missing objects are about to be added.") //$NON-NLS-1$
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
        String sourceProjectName = JsonUtils.extractStringArgument(params, "sourceProjectName"); //$NON-NLS-1$
        String objectFqn = JsonUtils.extractStringArgument(params, "objectFqn"); //$NON-NLS-1$
        String targetProjectName = JsonUtils.extractStringArgument(params, "targetProjectName"); //$NON-NLS-1$

        if (sourceProjectName == null || sourceProjectName.isEmpty())
        {
            return ToolResult.error("sourceProjectName is required").toJson(); //$NON-NLS-1$
        }
        if (objectFqn == null || objectFqn.isEmpty())
        {
            return ToolResult.error("objectFqn is required - e.g. 'Document.SalesOrder'").toJson(); //$NON-NLS-1$
        }
        if (targetProjectName == null || targetProjectName.isEmpty())
        {
            return ToolResult.error("targetProjectName is required").toJson(); //$NON-NLS-1$
        }

        boolean allowMissing = JsonUtils.extractBooleanArgument(params, "allowMissingReferences", false); //$NON-NLS-1$
        try
        {
            return UiSync.call(() -> copy(sourceProjectName, objectFqn, targetProjectName, allowMissing));
        }
        catch (Exception e)
        {
            return ToolResult.error("Error: " + TextSuggest.safeMessage(e)).toJson(); //$NON-NLS-1$
        }
    }

    /**
     * Resolves both ends and copies. Runs on the UI thread.
     *
     * @param sourceProjectName the project holding the original
     * @param objectFqn the original's FQN
     * @param targetProjectName the project to copy into
     * @return the JSON answer
     */
    private static String copy(String sourceProjectName, String objectFqn, String targetProjectName,
        boolean allowMissing)
    {
        IProject sourceProject = ProjectResolver.resolve(sourceProjectName);
        if (sourceProject == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(sourceProjectName)).toJson();
        }
        IProject targetProject = ProjectResolver.resolve(targetProjectName);
        if (targetProject == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(targetProjectName)).toJson();
        }

        // An external-object project answers getConfiguration() with the base configuration it is
        // linked to - somebody else's model entirely - so every lookup here would silently be asked
        // of the wrong project: the source FQN would resolve against the base, and the copy would be
        // looked for where it was never put. Refused rather than half-supported, because the failure
        // mode is a copy that was made and reported missing.
        for (String[] side : new String[][] { { sourceProjectName, "source" }, //$NON-NLS-1$
            { targetProjectName, "target" } }) //$NON-NLS-1$
        {
            IProject project = "source".equals(side[1]) ? sourceProject : targetProject; //$NON-NLS-1$
            if (ExternalProjectResolver.isExternalProject(project))
            {
                return ToolResult.error("'" + side[0] + "' is an external-object project, and this " //$NON-NLS-1$ //$NON-NLS-2$
                    + "operation does not handle those: EDT may change the object's kind on the way " //$NON-NLS-1$
                    + "in, and the result could not be verified afterwards. Use " //$NON-NLS-1$
                    + "external_object_workshop for external data processors and reports.") //$NON-NLS-1$
                    .put("operation", NAME) //$NON-NLS-1$
                    .put("projectName", side[0]) //$NON-NLS-1$
                    .put("externalObjectProject", Boolean.TRUE) //$NON-NLS-1$
                    .toJson();
            }
        }

        Activator activator = Activator.getDefault();
        IConfigurationProvider configurationProvider =
            activator == null ? null : activator.getConfigurationProvider();
        if (configurationProvider == null)
        {
            return ToolResult.error("No configuration provider available").toJson(); //$NON-NLS-1$
        }
        Configuration sourceConfiguration = configurationProvider.getConfiguration(sourceProject);
        if (sourceConfiguration == null)
        {
            return ToolResult.error("Unable to load the configuration of '" + sourceProjectName //$NON-NLS-1$
                + "'").toJson(); //$NON-NLS-1$
        }

        MdObject source = resolve(sourceConfiguration, objectFqn);
        if (source == null)
        {
            return ToolResult.error("No such object in '" + sourceProjectName + "': " + objectFqn) //$NON-NLS-1$ //$NON-NLS-2$
                .put("operation", NAME) //$NON-NLS-1$
                .put(ErrorTags.NOT_FOUND.wire(), Boolean.TRUE)
                .toJson();
        }

        Configuration targetConfigurationBefore = configurationProvider.getConfiguration(targetProject);
        String ownFqn = typeOf(objectFqn) + "." + source.getName(); //$NON-NLS-1$
        List<String> missing = missingReferences(source, targetConfigurationBefore, ownFqn);
        if (!missing.isEmpty() && !allowMissing)
        {
            // Refused before anything is written. EDT carries the reference types across as they are
            // written and does not resolve them, so copying now would leave an object that looks
            // whole and points at nothing - and the caller would have been told it worked.
            return ToolResult.error("'" + objectFqn + "' refers to " + missing.size() //$NON-NLS-1$ //$NON-NLS-2$
                + " object(s) that '" + targetProjectName + "' does not have, and reference types are " //$NON-NLS-1$ //$NON-NLS-2$
                + "copied verbatim rather than resolved. Add them first, or pass " //$NON-NLS-1$
                + "allowMissingReferences=true to copy anyway.") //$NON-NLS-1$
                .put("operation", NAME) //$NON-NLS-1$
                .put("objectFqn", objectFqn) //$NON-NLS-1$
                .put("targetProjectName", targetProjectName) //$NON-NLS-1$
                .put("missingReferences", missing) //$NON-NLS-1$
                .put(ErrorTags.NOT_FOUND.wire(), Boolean.TRUE)
                .toJson();
        }

        BmObjectCopyHelper.CopyResult result = BmObjectCopyHelper.copyToProject(source, targetProject);
        if (!result.ok)
        {
            ToolResult error = ToolResult.error(result.error)
                .put("operation", NAME) //$NON-NLS-1$
                .put("objectFqn", objectFqn) //$NON-NLS-1$
                .put("targetProjectName", targetProjectName); //$NON-NLS-1$
            if (result.failureKind != null)
            {
                error.put(result.failureKind, Boolean.TRUE);
            }
            return error.toJson();
        }

        String copyFqn = typeOf(objectFqn) + "." + result.copyName; //$NON-NLS-1$
        // Asked of the target project's own configuration, not inferred from the call returning: a
        // copy that is not in the model is not a copy, whatever the copier handed back.
        Configuration targetConfiguration = configurationProvider.getConfiguration(targetProject);
        MdObject landed = targetConfiguration == null ? null : resolve(targetConfiguration, copyFqn);
        if (landed == null)
        {
            return ToolResult.error("The copier reported a copy named '" + result.copyName //$NON-NLS-1$
                + "', but '" + copyFqn + "' does not resolve in '" + targetProjectName + "'.") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                .put("operation", NAME) //$NON-NLS-1$
                .put(ErrorTags.OUTPUT_MISSING.wire(), Boolean.TRUE)
                .toJson();
        }

        return ToolResult.success()
            .put("operation", NAME) //$NON-NLS-1$
            .put("sourceFqn", objectFqn) //$NON-NLS-1$
            .put("copyFqn", copyFqn) //$NON-NLS-1$
            .put("targetProjectName", targetProjectName) //$NON-NLS-1$
            .put("hint", "EDT chose the name. Rights in roles, functional options and exchange plan " //$NON-NLS-1$ //$NON-NLS-2$
                + "contents live in OTHER objects and were not copied. Validate the target project " //$NON-NLS-1$
                + "before relying on the copy.") //$NON-NLS-1$
            .toJson();
    }

    /**
     * Lists the objects this one refers to by type that the target configuration does not have.
     * <p>
     * Read off the model rather than the files: every {@code TypeDescription} in the object's tree -
     * attribute types, tabular section field types, the types behind a form attribute - names the
     * objects it points at, and those names travel with the copy unchanged.
     * </p>
     * <p>
     * Types that are not references (a String, a Number, a Boolean) are skipped: they carry no
     * dependency. BSL is skipped too, and knowingly - a module names common modules in text, and
     * nothing short of parsing it would find them, so a clean answer here does not mean the modules
     * will resolve.
     * </p>
     *
     * @param source the object about to be copied, not <code>null</code>
     * @param target the configuration it would land in; may be <code>null</code>, which reports
     *            everything as missing
     * @return the FQNs that would dangle, in the order met, without repeats
     */
    private static List<String> missingReferences(MdObject source, Configuration target, String ownFqn)
    {
        Set<String> referenced = new LinkedHashSet<>();
        for (java.util.Iterator<EObject> it = source.eAllContents(); it.hasNext();)
        {
            EObject node = it.next();
            if (!(node instanceof TypeDescription))
            {
                continue;
            }
            for (TypeItem type : ((TypeDescription)node).getTypes())
            {
                String name = McoreUtil.getTypeName(type);
                // A dot is what separates a reference type from a primitive one: CatalogRef.X has
                // one, String does not. Which of them actually names an object is decided below.
                if (name != null && name.indexOf('.') > 0)
                {
                    referenced.add(name);
                }
            }
        }
        List<String> missing = new ArrayList<>();
        for (String reference : referenced)
        {
            // CatalogRef.X -> Catalog.X: the reference type names the object, with Ref stuck on the
            // type half of it.
            int dot = reference.indexOf('.');
            String fqn = metadataFqnOf(reference.substring(0, dot), reference.substring(dot));
            if (fqn == null)
            {
                continue;
            }
            // An object that refers to itself - a document whose "basis" attribute is its own type -
            // is not a missing dependency: it is about to exist. Reporting it would send the caller
            // looking for something they already have in hand.
            if (fqn.equals(ownFqn))
            {
                continue;
            }
            if (target == null || resolve(target, fqn) == null)
            {
                missing.add(fqn);
            }
        }
        return missing;
    }

    /**
     * The metadata suffixes a platform type name is built with.
     * <p>
     * A dependency is not only {@code CatalogRef.X}. The same object is named {@code CatalogObject.X}
     * by a form attribute, {@code CatalogManager.X} by a manager expression, {@code CatalogSelection.X}
     * by a query. Watching for {@code Ref} alone would let all the others through, and a check that
     * answers "nothing missing" while something is missing is worse than no check.
     * </p>
     */
    private static final String[] TYPE_SUFFIXES = { "Ref", "Object", "Manager", "Selection", "List", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        "RecordSet", "RecordKey", "RecordManager", "RecordSelection" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

    /**
     * Turns the type half of a platform type name into the FQN of the object behind it.
     *
     * @param kind the part before the dot, e.g. {@code CatalogRef} or {@code DefinedType}
     * @param dotAndName the rest, dot included
     * @return the object's FQN, or <code>null</code> when this type names no metadata object
     */
    private static String metadataFqnOf(String kind, String dotAndName)
    {
        for (String suffix : TYPE_SUFFIXES)
        {
            if (kind.length() > suffix.length() && kind.endsWith(suffix))
            {
                return kind.substring(0, kind.length() - suffix.length()) + dotAndName;
            }
        }
        // DefinedType.X and the like carry no suffix - the kind IS the metadata type. Anything that
        // is not a known metadata type falls out when the FQN fails to resolve as one.
        return MetadataTypeCatalog.toEnglishSingular(kind) != null ? kind + dotAndName : null;
    }

    private static String typeOf(String fqn)
    {
        int dot = fqn.indexOf('.');
        String type = dot > 0 ? fqn.substring(0, dot) : fqn;
        String english = MetadataTypeCatalog.toEnglishSingular(type);
        return english != null ? english : type;
    }

    /**
     * Finds a top-level object by FQN, accepting the Russian type name.
     *
     * @param configuration the configuration to look in, not <code>null</code>
     * @param fqn the fully qualified name
     * @return the object, or <code>null</code>
     */
    private static MdObject resolve(Configuration configuration, String fqn)
    {
        String[] parts = fqn.split("\\."); //$NON-NLS-1$
        if (parts.length < 2)
        {
            return null;
        }
        String type = parts[0];
        String english = MetadataTypeCatalog.toEnglishSingular(type);
        return MetadataTypeCatalog.findObject(configuration, english != null ? english : type, parts[1]);
    }
}
