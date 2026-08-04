/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.md.refactoring.core.IMdRefactoringService;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.refactoring.core.CleanReferenceProblem;
import com._1c.g5.v8.dt.refactoring.core.IRefactoring;
import com._1c.g5.v8.dt.refactoring.core.IRefactoringItem;
import com._1c.g5.v8.dt.refactoring.core.IRefactoringProblem;
import com._1c.g5.v8.dt.refactoring.core.RefactoringStatus;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.ExternalProjectResolver;
import ru.aiedt.mcp.server.support.MetadataTypeCatalog;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.TextSuggest;

/**
 * Removes a metadata object - or one of its nested children - and lets EDT's refactoring clean up
 * everything that pointed at it: BSL code, forms, other metadata.
 * <p>
 * It runs in two beats. Without {@code confirm} it only reports what the deletion would touch, so a
 * caller can look before it leaps; with {@code confirm=true} it carries the deletion out. Either way
 * the work happens on the SWT thread, because EDT's refactoring machinery is not safe off it.
 * </p>
 */
public class MetadataObjectDeleter implements IMcpTool
{
    public static final String NAME = "delete_metadata_object"; //$NON-NLS-1$

    /** Caps how far down a wrapped cause-chain to look for the stale-reference marker. */
    private static final int CAUSE_CHAIN_DEPTH = 24;

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `edit_metadata` `operation=delete_metadata_object`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Removes a metadata object or attribute using EDT's built-in refactoring engine. " //$NON-NLS-1$
            + "All referencing BSL code, forms, and other metadata are cleaned up automatically. " //$NON-NLS-1$
            + "Call once without confirm to see what would be affected, then call again with confirm=true to apply it. " //$NON-NLS-1$
            + "Accepts FQNs such as 'Catalog.Products', 'Document.SalesOrder.Attribute.Amount'. " //$NON-NLS-1$
            + "Object type names may also be given in Russian."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", "Name of the EDT project (required)", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("objectFqn", //$NON-NLS-1$
                "FQN identifying the object to remove " //$NON-NLS-1$
                    + "(for example 'Catalog.Products', 'Document.SalesOrder.Attribute.Amount'). " //$NON-NLS-1$
                    + "Russian type names are accepted too.", true) //$NON-NLS-1$
            .booleanProperty("confirm", //$NON-NLS-1$
                "Pass true to actually perform the deletion. " //$NON-NLS-1$
                    + "Defaults to false, which only previews the effect.") //$NON-NLS-1$
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
        String objectFqn = JsonUtils.extractStringArgument(params, "objectFqn"); //$NON-NLS-1$
        boolean confirm = JsonUtils.extractBooleanArgument(params, "confirm", false); //$NON-NLS-1$ //$NON-NLS-2$

        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName must be provided. " //$NON-NLS-1$
                + "Example: {projectName: 'MyProject', objectFqn: 'Catalog.Products'}").toJson(); //$NON-NLS-1$
        }
        if (objectFqn == null || objectFqn.isEmpty())
        {
            return ToolResult.error("objectFqn must be provided. " //$NON-NLS-1$
                + "For instance: 'Catalog.Products' (removes the entire catalog), " //$NON-NLS-1$
                + "'Document.SalesOrder.Attribute.Amount' (removes an attribute), " //$NON-NLS-1$
                + "'Catalog.Products.TabularSection.Prices' (removes a tabular section)").toJson(); //$NON-NLS-1$
        }

        AtomicReference<String> outcome = new AtomicReference<>();
        Display display = PlatformUI.getWorkbench().getDisplay();
        display.syncExec(() ->
        {
            try
            {
                outcome.set(run(projectName, objectFqn, confirm));
            }
            catch (Exception e)
            {
                Activator.logError("Failure in delete_metadata_object", e); //$NON-NLS-1$
                outcome.set(ToolResult.error(TextSuggest.safeMessage(e)).toJson());
            }
        });
        return outcome.get();
    }

    /**
     * Resolves the object, builds EDT's delete refactoring, and either previews or applies it. Runs on
     * the SWT thread.
     *
     * @param projectName the owning project
     * @param objectFqn the object FQN (may be RU-normalized here)
     * @param confirm whether to apply rather than preview
     * @return a JSON result body
     */
    private String run(String projectName, String objectFqn, boolean confirm)
    {
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }

        IConfigurationProvider configProvider = Activator.getDefault().getConfigurationProvider();
        if (configProvider == null)
        {
            return ToolResult.error("Configuration provider is unavailable").toJson(); //$NON-NLS-1$
        }
        // An external-object project answers getConfiguration() with ITS parent configuration. Deleting
        // by name there would look the object up in a foreign config and, on a collision, delete the
        // wrong thing. Refuse.
        if (ExternalProjectResolver.isExternalProject(project))
        {
            return ToolResult
                .error("delete_metadata_object cannot yet operate on external-object projects ('" //$NON-NLS-1$
                    + projectName + "' contains external data processors / reports). Remove the object " //$NON-NLS-1$
                    + "through the EDT UI instead, or delete the whole project.") //$NON-NLS-1$
                .toJson();
        }

        Configuration config = configProvider.getConfiguration(project);
        if (config == null)
        {
            return ToolResult.error("Unable to resolve configuration for project: " + projectName).toJson(); //$NON-NLS-1$
        }

        IMdRefactoringService refactoringService = Activator.getDefault().getMdRefactoringService();
        if (refactoringService == null)
        {
            return ToolResult.error("IMdRefactoringService is unavailable").toJson(); //$NON-NLS-1$
        }

        objectFqn = MetadataTypeCatalog.normalizeFqn(objectFqn);
        MdObject target = resolveObject(config, objectFqn);
        if (target == null)
        {
            return ToolResult.error("No such object: " + objectFqn + ". " //$NON-NLS-1$ //$NON-NLS-2$
                + "Expected FQN format: 'Type.Name' for top-level objects (for example 'Catalog.Products'), " //$NON-NLS-1$
                + "'Type.Name.ChildType.ChildName' for nested objects (for example 'Document.Order.Attribute.Amount'). " //$NON-NLS-1$
                + "Recognized child types: Attribute, TabularSection, Dimension, Resource, Form, " //$NON-NLS-1$
                + "Template, Command, EnumValue, Recalculation, AccountingFlag, " //$NON-NLS-1$
                + "ExtDimensionAccountingFlag, AddressingAttribute, Operation, Parameter, " //$NON-NLS-1$
                + "URLTemplate, Method (nested paths are supported).").toJson(); //$NON-NLS-1$
        }

        IRefactoring refactoring = refactoringService.createMdObjectDeleteRefactoring(Collections.singletonList(target));
        if (refactoring == null)
        {
            return ToolResult.error("Could not build the delete refactoring for: " + objectFqn).toJson(); //$NON-NLS-1$
        }

        return confirm ? apply(objectFqn, refactoring) : preview(objectFqn, refactoring);
    }

    /**
     * Builds the preview: the refactoring items EDT would touch, and the references it would clean.
     *
     * @param objectFqn the object being deleted
     * @param refactoring the prepared delete refactoring
     * @return a JSON preview body
     */
    private String preview(String objectFqn, IRefactoring refactoring)
    {
        List<Map<String, Object>> items = new ArrayList<>();
        Collection<IRefactoringItem> refactoringItems = refactoring.getItems();
        if (refactoringItems != null)
        {
            for (IRefactoringItem item : refactoringItems)
            {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", item.getName()); //$NON-NLS-1$
                row.put("optional", item.isOptional()); //$NON-NLS-1$
                row.put("checked", item.isChecked()); //$NON-NLS-1$
                items.add(row);
            }
        }

        List<Map<String, Object>> references = collectReferenceProblems(refactoring);

        return ToolResult.success()
            .put("action", "preview") //$NON-NLS-1$ //$NON-NLS-2$
            .put("objectFqn", objectFqn) //$NON-NLS-1$
            .put("refactoringTitle", refactoring.getTitle()) //$NON-NLS-1$
            .put("items", items) //$NON-NLS-1$
            .put("affectedReferences", references) //$NON-NLS-1$
            .put("affectedReferencesCount", references.size()) //$NON-NLS-1$
            .put("message", "This is a preview of the delete refactoring. " //$NON-NLS-1$ //$NON-NLS-2$
                + "The references shown above would be cleaned up. " //$NON-NLS-1$
                + "Re-call with confirm=true to actually apply it.") //$NON-NLS-1$
            .toJson();
    }

    /**
     * Walks the refactoring's problems into plain rows. A clean-reference problem names the object
     * that holds the reference and the feature it used; every problem also names its own target.
     *
     * @param refactoring the prepared delete refactoring
     * @return one map per problem that yielded anything to report
     */
    private List<Map<String, Object>> collectReferenceProblems(IRefactoring refactoring)
    {
        List<Map<String, Object>> references = new ArrayList<>();
        RefactoringStatus status = refactoring.getStatus();
        if (status == null)
        {
            return references;
        }

        Collection<IRefactoringProblem> problems = status.getProblems();
        if (problems == null)
        {
            return references;
        }

        for (IRefactoringProblem problem : problems)
        {
            Map<String, Object> row = new LinkedHashMap<>();

            if (problem instanceof CleanReferenceProblem cleanRef)
            {
                EObject holder = cleanRef.getReferencingObject();
                if (holder instanceof IBmObject bm)
                {
                    row.put("referencingObject", topFqn(bm)); //$NON-NLS-1$
                }
                EStructuralFeature feature = cleanRef.getReference();
                if (feature != null)
                {
                    row.put("reference", feature.getName()); //$NON-NLS-1$
                }
            }

            EObject target = problem.getObject();
            if (target instanceof IBmObject bm)
            {
                row.put("targetObject", topFqn(bm)); //$NON-NLS-1$
            }

            if (!row.isEmpty())
            {
                references.add(row);
            }
        }
        return references;
    }

    /**
     * Applies the deletion. The one failure worth translating is EDT's dangling-inverse-reference bug:
     * a referencer that was already deleted leaves a stale entry in the BM reference index, and EDT's
     * delete throws a {@code NullPointerException} on a null EMF proxy when it walks into it. That entry
     * does not clear on retry or restart, so the caller gets an actionable explanation rather than the
     * raw NPE.
     *
     * @param objectFqn the object being deleted
     * @param refactoring the prepared delete refactoring
     * @return a JSON success body, or a JSON error
     */
    private String apply(String objectFqn, IRefactoring refactoring)
    {
        try
        {
            refactoring.perform();
            return ToolResult.success()
                .put("action", "executed") //$NON-NLS-1$ //$NON-NLS-2$
                .put("objectFqn", objectFqn) //$NON-NLS-1$
                .put("message", "The delete refactoring finished successfully.") //$NON-NLS-1$ //$NON-NLS-2$
                .toJson();
        }
        catch (Exception e)
        {
            Activator.logError("Delete refactoring failed", e); //$NON-NLS-1$
            if (isStaleReferenceFailure(e))
            {
                return ToolResult
                    .error("Could not delete '" + objectFqn + "': it is still " //$NON-NLS-1$ //$NON-NLS-2$
                        + "referenced by another object that was already removed (a leftover entry " //$NON-NLS-1$
                        + "in EDT's internal reference index - usually a ChartOfAccounts / " //$NON-NLS-1$
                        + "ChartOfCalculationTypes referencing this object through extDimensionTypes). EDT's " //$NON-NLS-1$
                        + "delete operation cannot resolve a reference whose owner no longer exists, so it raises an " //$NON-NLS-1$
                        + "internal NullPointerException. This leftover entry sticks around - it does " //$NON-NLS-1$
                        + "NOT go away after retrying, running clean_project, or restarting EDT. Avoid it by " //$NON-NLS-1$
                        + "removing a referenced object BEFORE the objects that point to it (delete " //$NON-NLS-1$
                        + "this one first, then whatever references it). To clear it now, delete this " //$NON-NLS-1$
                        + "object's 'src/<Type>/<Name>' folder plus its Configuration.mdo entry on " //$NON-NLS-1$
                        + "disk and reimport the project.") //$NON-NLS-1$
                    .put("objectFqn", objectFqn) //$NON-NLS-1$
                    .put("cause", "staleReferenceIndex") //$NON-NLS-1$ //$NON-NLS-2$
                    .put("recovery", //$NON-NLS-1$
                        "avoid by deleting the referenced object before its referencers; the leftover entry requires manual filesystem cleanup") //$NON-NLS-1$
                    .toJson();
            }
            return ToolResult.error("Deletion failed: " + TextSuggest.safeMessage(e)).toJson(); //$NON-NLS-1$
        }
    }

    /**
     * Tells the dangling-inverse-reference failure apart from any other delete exception.
     * <p>
     * EDT 2026.1 runs on JDK 17, so helpful-NPE messages are on; the null receiver means the
     * {@code eProxyURI} accessor appears in the exception <em>message</em>, not as a stack frame. Keying
     * off "an NPE with zero clean-reference problems" would be wrong: that is also the ordinary case
     * for a leaf object with no incoming references, and would mislabel unrelated NPEs as this bug.
     * </p>
     *
     * @param error the failure thrown by {@code perform()}
     * @return whether it is the stale-reference-index signature
     */
    private static boolean isStaleReferenceFailure(Throwable error)
    {
        Throwable current = error;
        for (int guard = 0; current != null && guard < CAUSE_CHAIN_DEPTH; guard++)
        {
            String message = current.getMessage();
            if (message != null && message.contains("eProxyURI")) //$NON-NLS-1$
            {
                return true;
            }
            Throwable cause = current.getCause();
            current = (cause == current) ? null : cause;
        }
        return false;
    }

    /**
     * EDT 2026.1 restricts {@code bmGetFqn()} to top objects - on a nested one (an attribute, a form
     * item) it throws - so this returns the FQN directly for a top object and the owning top object's
     * FQN otherwise.
     *
     * @param bmObject a referenced BM object
     * @return its own FQN, its top object's FQN, or {@code null}
     */
    private static String topFqn(IBmObject bmObject)
    {
        if (bmObject == null)
        {
            return null;
        }
        try
        {
            return bmObject.bmGetFqn();
        }
        catch (Exception topObjectsOnly)
        {
            try
            {
                IBmObject top = bmObject.bmGetTopObject();
                return top != null ? top.bmGetFqn() : null;
            }
            catch (Exception e)
            {
                return null;
            }
        }
    }

    /**
     * Resolves an FQN to a metadata object, top-level or nested.
     *
     * @param config the project configuration
     * @param fqn the FQN
     * @return the object, or {@code null} when the path does not resolve
     */
    private MdObject resolveObject(Configuration config, String fqn)
    {
        if (fqn == null || fqn.isEmpty())
        {
            return null;
        }
        String[] segments = fqn.split("\\."); //$NON-NLS-1$
        if (segments.length < 2)
        {
            return null;
        }

        MdObject current = MetadataTypeCatalog.findObject(config, segments[0], segments[1]);
        if (current == null || segments.length == 2)
        {
            return current;
        }

        for (int i = 2; i + 1 < segments.length; i += 2)
        {
            current = findChild(current, segments[i], segments[i + 1]);
            if (current == null)
            {
                return null;
            }
        }
        return current;
    }

    /**
     * Finds a named child of a parent metadata object by its collection type. The type token accepts
     * English singular/plural and the common Russian forms.
     *
     * @param parent the owning metadata object
     * @param childType the collection type token
     * @param childName the name to find (case-insensitive)
     * @return the child, or {@code null} when the type is unknown or no child matches
     */
    @SuppressWarnings("unchecked")
    private MdObject findChild(MdObject parent, String childType, String childName)
    {
        String getter = childCollectionGetter(childType);
        if (getter == null)
        {
            return null;
        }

        try
        {
            Method method = parent.getClass().getMethod(getter);
            Object result = method.invoke(parent);
            if (result instanceof EList)
            {
                EList<? extends MdObject> children = (EList<? extends MdObject>)result;
                for (MdObject child : children)
                {
                    if (childName.equalsIgnoreCase(child.getName()))
                    {
                        return child;
                    }
                }
            }
        }
        catch (Exception e)
        {
            Activator.logError("Failed to find child " + childType + "." + childName, e); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return null;
    }

    /**
     * Maps a child-type token to its EMF collection getter on the parent.
     *
     * @param childType the token from the FQN
     * @return the getter name, or {@code null} when the token is not a deletable child type
     */
    private static String childCollectionGetter(String childType)
    {
        // ROOT locale: a default-locale toLowerCase would fold ASCII I to dotless ı on a Turkish
        // JVM and quietly miss a case label. The type tokens are ASCII; ROOT keeps them stable.
        String token = childType.toLowerCase(Locale.ROOT);
        switch (token)
        {
            case "attribute": //$NON-NLS-1$
            case "attributes": //$NON-NLS-1$
            case "реквизит": //$NON-NLS-1$
            case "реквизиты": //$NON-NLS-1$
                return "getAttributes"; //$NON-NLS-1$
            case "tabularsection": //$NON-NLS-1$
            case "tabularsections": //$NON-NLS-1$
            case "табличнаячасть": //$NON-NLS-1$
            case "табличныечасти": //$NON-NLS-1$
                return "getTabularSections"; //$NON-NLS-1$
            case "dimension": //$NON-NLS-1$
            case "dimensions": //$NON-NLS-1$
            case "измерение": //$NON-NLS-1$
            case "измерения": //$NON-NLS-1$
                return "getDimensions"; //$NON-NLS-1$
            case "resource": //$NON-NLS-1$
            case "resources": //$NON-NLS-1$
            case "ресурс": //$NON-NLS-1$
            case "ресурсы": //$NON-NLS-1$
                return "getResources"; //$NON-NLS-1$
            case "form": //$NON-NLS-1$
            case "forms": //$NON-NLS-1$
            case "форма": //$NON-NLS-1$
            case "формы": //$NON-NLS-1$
                return "getForms"; //$NON-NLS-1$
            case "template": //$NON-NLS-1$
            case "templates": //$NON-NLS-1$
            case "макет": //$NON-NLS-1$
            case "макеты": //$NON-NLS-1$
                return "getTemplates"; //$NON-NLS-1$
            case "command": //$NON-NLS-1$
            case "commands": //$NON-NLS-1$
            case "команда": //$NON-NLS-1$
            case "команды": //$NON-NLS-1$
                return "getCommands"; //$NON-NLS-1$
            case "enumvalue": //$NON-NLS-1$
            case "enumvalues": //$NON-NLS-1$
            case "значениеперечисления": //$NON-NLS-1$
            case "значенияперечисления": //$NON-NLS-1$
                return "getEnumValues"; //$NON-NLS-1$
            case "recalculation": //$NON-NLS-1$
            case "recalculations": //$NON-NLS-1$
            case "перерасчет": //$NON-NLS-1$
            case "перерасчеты": //$NON-NLS-1$
                return "getRecalculations"; //$NON-NLS-1$
            case "accountingflag": //$NON-NLS-1$
            case "accountingflags": //$NON-NLS-1$
            case "признакучета": //$NON-NLS-1$
                return "getAccountingFlags"; //$NON-NLS-1$
            case "extdimensionaccountingflag": //$NON-NLS-1$
            case "extdimensionaccountingflags": //$NON-NLS-1$
            case "признакучетасубконто": //$NON-NLS-1$
                return "getExtDimensionAccountingFlags"; //$NON-NLS-1$
            case "addressingattribute": //$NON-NLS-1$
            case "addressingattributes": //$NON-NLS-1$
            case "реквизитаадресации": //$NON-NLS-1$
                return "getAddressingAttributes"; //$NON-NLS-1$
            case "operation": //$NON-NLS-1$
            case "operations": //$NON-NLS-1$
            case "операция": //$NON-NLS-1$
            case "операции": //$NON-NLS-1$
                return "getOperations"; //$NON-NLS-1$
            case "parameter": //$NON-NLS-1$
            case "parameters": //$NON-NLS-1$
            case "параметр": //$NON-NLS-1$
            case "параметры": //$NON-NLS-1$
                return "getParameters"; //$NON-NLS-1$
            case "urltemplate": //$NON-NLS-1$
            case "urltemplates": //$NON-NLS-1$
            case "шаблонurl": //$NON-NLS-1$
                return "getUrlTemplates"; //$NON-NLS-1$
            case "method": //$NON-NLS-1$
            case "methods": //$NON-NLS-1$
            case "метод": //$NON-NLS-1$
            case "методы": //$NON-NLS-1$
                return "getMethods"; //$NON-NLS-1$
            default:
                return null;
        }
    }
}
