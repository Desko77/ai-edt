/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.EList;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.AccountingRegister;
import com._1c.g5.v8.dt.metadata.mdclass.AccumulationRegister;
import com._1c.g5.v8.dt.metadata.mdclass.BusinessProcess;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.ChartOfAccounts;
import com._1c.g5.v8.dt.metadata.mdclass.ChartOfCalculationTypes;
import com._1c.g5.v8.dt.metadata.mdclass.ChartOfCharacteristicTypes;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.DataProcessor;
import com._1c.g5.v8.dt.metadata.mdclass.Document;
import com._1c.g5.v8.dt.metadata.mdclass.ExchangePlan;
import com._1c.g5.v8.dt.metadata.mdclass.InformationRegister;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.Report;
import com._1c.g5.v8.dt.metadata.mdclass.Task;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.BmObjectHelper;
import ru.aiedt.mcp.server.support.ErrorTags;
import ru.aiedt.mcp.server.support.ExternalProjectResolver;
import ru.aiedt.mcp.server.support.MetadataGuards;
import ru.aiedt.mcp.server.support.MetadataTypeCatalog;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.TextSuggest;

/**
 * Adds a new attribute to a metadata object inside a BM write transaction. Supports thirteen parent
 * types; the attribute is created with default properties.
 */
public class AttributeAdder implements IMcpTool
{
    public static final String NAME = "add_metadata_attribute"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `edit_metadata` `operation=add_object_attribute`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Creates a new attribute on a metadata object with default properties. " //$NON-NLS-1$
            + "For new code prefer edit_metadata operation=add_object_attribute, which sets type and qualifiers in one call. " //$NON-NLS-1$
            + "Supported parents: Catalog, Document, ExchangePlan, ChartOfCharacteristicTypes, " //$NON-NLS-1$
            + "ChartOfAccounts, ChartOfCalculationTypes, BusinessProcess, Task, " //$NON-NLS-1$
            + "DataProcessor, Report, InformationRegister, AccumulationRegister, AccountingRegister. " //$NON-NLS-1$
            + "Russian parent type names are accepted."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", "Name of the target EDT project (required)", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("parentFqn", //$NON-NLS-1$
                "Fully qualified name of the parent object (for example 'Catalog.Products' or 'Document.SalesOrder'). Type names may be given in Russian.",
                true)
            .stringProperty("attributeName", "Name to assign to the new attribute (required)", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("synonym", //$NON-NLS-1$
                "Synonym for the new attribute - what a user reads on a form or in a report. " //$NON-NLS-1$
                    + "Omitted means generated from the name the way the editor does it " //$NON-NLS-1$
                    + "(DocumentCurrency becomes 'Document currency'), never left blank.") //$NON-NLS-1$
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
        String parentFqn = JsonUtils.extractStringArgument(params, "parentFqn"); //$NON-NLS-1$
        String attributeName = JsonUtils.extractStringArgument(params, "attributeName"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$

        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error(
                "projectName must be provided. Example call: {projectName: 'MyProject', parentFqn: 'Catalog.Products', attributeName: 'Weight'}")
                .toJson();
        }
        if (parentFqn == null || parentFqn.isEmpty())
        {
            return ToolResult.error(
                "parentFqn must be provided. For example 'Catalog.Products' or 'Document.SalesOrder'. Example call: {parentFqn: 'Catalog.Products', attributeName: 'Weight'}")
                .toJson();
        }
        if (attributeName == null || attributeName.isEmpty())
        {
            return ToolResult.error(
                "attributeName must be provided. Example call: {parentFqn: 'Catalog.Products', attributeName: 'Weight'}")
                .toJson();
        }

        AtomicReference<String> resultRef = new AtomicReference<>();
        Display display = PlatformUI.getWorkbench().getDisplay();
        display.syncExec(() -> {
            try
            {
                resultRef.set(executeInternal(projectName, parentFqn, attributeName,
                    JsonUtils.extractStringArgument(params, "synonym"), dryRun)); //$NON-NLS-1$
            }
            catch (Exception e)
            {
                Activator.logError("Unhandled error while running add_metadata_attribute", e); //$NON-NLS-1$
                resultRef.set(ToolResult.error(TextSuggest.safeMessage(e)).toJson());
            }
        });
        return resultRef.get();
    }

    private String executeInternal(String projectName, String parentFqn, String attributeName,
        String synonym, boolean dryRun)
    {
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ProjectResolver.notFound(projectName).toJson();
        }

        IConfigurationProvider configProvider = Activator.getDefault().getConfigurationProvider();
        if (configProvider == null)
        {
            return ToolResult.error("Configuration provider is unavailable").toJson();
        }

        if (ExternalProjectResolver.isExternalProject(project))
        {
            return ToolResult.error("add_metadata_attribute cannot be used on external-object projects ('" //$NON-NLS-1$
                + projectName
                + "' contains external data processors / reports instead). Call edit_metadata with operation=add_object_attribute and ownerFqn=ExternalDataProcessor.<Name> (or ExternalReport.<Name>) instead.")
                .toJson();
        }

        Configuration config = configProvider.getConfiguration(project);
        if (config == null)
        {
            return ToolResult.error("Unable to load configuration for project: " + projectName).toJson();
        }

        parentFqn = MetadataTypeCatalog.normalizeFqn(parentFqn);
        String[] parts = parentFqn.split("\\.", 2); //$NON-NLS-1$
        if (parts.length < 2)
        {
            return ToolResult.error("Not a valid FQN: " + parentFqn).toJson(); //$NON-NLS-1$
        }
        MdObject parentObject = MetadataTypeCatalog.findObject(config, parts[0], parts[1]);
        if (parentObject == null)
        {
            return ToolResult.error("Could not find parent object: " + parentFqn
                + ". Confirm the FQN follows 'Type.Name' (for example 'Catalog.Products', 'Document.SalesOrder')."
                + " The get_metadata_objects tool lists what is available.").toJson();
        }
        if (!supportsAttributes(parentObject))
        {
            return ToolResult.error("Unsupported object type '" + parentObject.eClass().getName()
                + "' does not accept attributes. Attributes can be added to: Catalog, Document, ExchangePlan,"
                + " ChartOfCharacteristicTypes, ChartOfAccounts, ChartOfCalculationTypes,"
                + " BusinessProcess, Task, DataProcessor, Report, InformationRegister,"
                + " AccumulationRegister, AccountingRegister.").toJson();
        }
        final String normalizedParentFqn = parentFqn;
        // The synonym is best-effort by design, so its outcome has to reach the
        // caller: an attribute created with no synonym is still a half-done job,
        // and reporting a bare success would hide it.
        final EditMetadataTool.SynonymResult[] synonymOut = { null };
        BmObjectHelper.Result result = BmObjectHelper.executeWriteOnObject(project,
            normalizedParentFqn, dryRun, (tx, parent) -> {
                MdObject newAttribute = createAttribute(parent);
                if (newAttribute == null)
                {
                    throw new RuntimeException("Do not know how to create an attribute for: " //$NON-NLS-1$
                        + parent.eClass().getName());
                }
                newAttribute.setName(attributeName);
                synonymOut[0] = EditMetadataTool.applyMdObjectSynonym(newAttribute, synonym,
                    attributeName, project);
                newAttribute.setUuid(UUID.randomUUID());
                addAttribute(parent, newAttribute);
                return dryRun ? "Dry run: attribute would be created." //$NON-NLS-1$
                    : "Attribute created."; //$NON-NLS-1$
            }, parent -> {
                MetadataGuards.Verdict conflict =
                    MetadataGuards.checkStandardAttributeConflict(parent, attributeName);
                if (conflict.blocked)
                {
                    throw new MetadataGuards.BlockedGuardException(conflict);
                }
                MetadataGuards.Verdict alphabet = MetadataGuards.checkOneAlphabet(attributeName);
                if (alphabet.blocked)
                {
                    throw new MetadataGuards.BlockedGuardException(alphabet);
                }
                if (hasAttribute(parent, attributeName))
                {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("name", attributeName); //$NON-NLS-1$
                    data.put("ownerFqn", normalizedParentFqn); //$NON-NLS-1$
                    data.put("kind", "attribute"); //$NON-NLS-1$ //$NON-NLS-2$
                    throw new MetadataGuards.BlockedGuardException(MetadataGuards.Verdict.block(
                        "An attribute with this name already exists: " + attributeName, //$NON-NLS-1$
                        "Choose a different name, or delete the existing attribute before retrying.", //$NON-NLS-1$
                        new MetadataGuards.ErrorTag(ErrorTags.ALREADY_EXISTS.wire(), data)));
                }
            });
        if (!result.ok)
        {
            ToolResult err = ToolResult.error("Could not add attribute: " + result.error) //$NON-NLS-1$
                .put("parentFqn", normalizedParentFqn) //$NON-NLS-1$
                .put("attributeName", attributeName); //$NON-NLS-1$
            EditMetadataTool.applyTags(err, result.tags);
            return err.toJson();
        }
        ToolResult ok = ToolResult.success()
            .put("parentFqn", normalizedParentFqn) //$NON-NLS-1$
            .put("attributeName", attributeName) //$NON-NLS-1$
            .put("dryRun", dryRun) //$NON-NLS-1$
            .put("message", //$NON-NLS-1$
                (dryRun ? "Dry run: attribute '" : "Attribute '") + attributeName //$NON-NLS-1$ //$NON-NLS-2$
                    + (dryRun ? " would be created on " : " was created on ") //$NON-NLS-1$ //$NON-NLS-2$
                    + normalizedParentFqn);
        // Same tag names the edit_metadata operations use, so a caller reads one
        // vocabulary whichever route it took to create the attribute.
        EditMetadataTool.SynonymResult sr = synonymOut[0];
        if (sr != null && sr.applied)
        {
            ok.put("synonym", sr.value) //$NON-NLS-1$
                .put("synonymApplied", true); //$NON-NLS-1$
        }
        else if (sr != null && sr.error != null)
        {
            Map<String, Object> reason = new LinkedHashMap<>();
            reason.put("reason", sr.error); //$NON-NLS-1$
            ok.put("synonymApplied", false) //$NON-NLS-1$
                .put("synonymNotSet", reason); //$NON-NLS-1$
        }
        return ok.toJson();
    }

    private boolean supportsAttributes(MdObject obj)
    {
        return obj instanceof Catalog
            || obj instanceof Document
            || obj instanceof ExchangePlan
            || obj instanceof ChartOfCharacteristicTypes
            || obj instanceof ChartOfAccounts
            || obj instanceof ChartOfCalculationTypes
            || obj instanceof BusinessProcess
            || obj instanceof Task
            || obj instanceof DataProcessor
            || obj instanceof Report
            || obj instanceof InformationRegister
            || obj instanceof AccumulationRegister
            || obj instanceof AccountingRegister;
    }

    @SuppressWarnings("unchecked")
    private boolean hasAttribute(MdObject parent, String name)
    {
        try
        {
            Method method = parent.getClass().getMethod("getAttributes"); //$NON-NLS-1$
            Object result = method.invoke(parent);
            if (result instanceof EList)
            {
                EList<? extends MdObject> attrs = (EList<? extends MdObject>)result;
                for (MdObject attr : attrs)
                {
                    if (name.equalsIgnoreCase(attr.getName()))
                    {
                        return true;
                    }
                }
            }
        }
        catch (Exception e)
        {
            // type may not have getAttributes — swallow
        }
        return false;
    }

    private MdObject createAttribute(MdObject parent)
    {
        MdClassFactory factory = MdClassFactory.eINSTANCE;
        if (parent instanceof Catalog)
        {
            return factory.createCatalogAttribute();
        }
        if (parent instanceof Document)
        {
            return factory.createDocumentAttribute();
        }
        if (parent instanceof ExchangePlan)
        {
            return factory.createExchangePlanAttribute();
        }
        if (parent instanceof ChartOfCharacteristicTypes)
        {
            return factory.createChartOfCharacteristicTypesAttribute();
        }
        if (parent instanceof ChartOfAccounts)
        {
            return factory.createChartOfAccountsAttribute();
        }
        if (parent instanceof ChartOfCalculationTypes)
        {
            return factory.createChartOfCalculationTypesAttribute();
        }
        if (parent instanceof BusinessProcess)
        {
            return factory.createBusinessProcessAttribute();
        }
        if (parent instanceof Task)
        {
            return factory.createTaskAttribute();
        }
        if (parent instanceof DataProcessor)
        {
            return factory.createDataProcessorAttribute();
        }
        if (parent instanceof Report)
        {
            return factory.createReportAttribute();
        }
        if (parent instanceof InformationRegister)
        {
            return factory.createInformationRegisterAttribute();
        }
        if (parent instanceof AccumulationRegister)
        {
            return factory.createAccumulationRegisterAttribute();
        }
        if (parent instanceof AccountingRegister)
        {
            return factory.createAccountingRegisterAttribute();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private void addAttribute(MdObject parent, MdObject attribute)
    {
        try
        {
            Method method = parent.getClass().getMethod("getAttributes"); //$NON-NLS-1$
            Object result = method.invoke(parent);
            if (result instanceof EList)
            {
                ((EList<MdObject>)result).add(attribute);
            }
            else
            {
                throw new RuntimeException("getAttributes() did not return an EList"); //$NON-NLS-1$
            }
        }
        catch (ReflectiveOperationException e)
        {
            throw new RuntimeException("Reflective attempt to add the attribute failed", e); //$NON-NLS-1$
        }
    }
}
