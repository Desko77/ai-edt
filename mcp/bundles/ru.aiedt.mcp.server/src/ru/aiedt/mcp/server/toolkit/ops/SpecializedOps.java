package ru.aiedt.mcp.server.toolkit.ops;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EObject;

import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.support.BmDefinedTypeHelper;
import ru.aiedt.mcp.server.support.BmEventSubscriptionHelper;
import ru.aiedt.mcp.server.support.BmExportHelper;
import ru.aiedt.mcp.server.support.BmHelpHelper;
import ru.aiedt.mcp.server.support.BmObjectHelper;
import ru.aiedt.mcp.server.support.ErrorTags;
import ru.aiedt.mcp.server.support.EventStubGenerator;
import ru.aiedt.mcp.server.support.MetadataGuards;
import ru.aiedt.mcp.server.support.MetadataTypeCatalog;
import ru.aiedt.mcp.server.support.PictureValidator;
import ru.aiedt.mcp.server.support.ProjectResolver;

/**
 * Specialized cluster of {@code edit_metadata}: register fields / enum values /
 * addressing attributes / accounting flags / recalculations / defined types /
 * event subscriptions / object commands / help pages / export-sync. Extracted
 * verbatim from {@link EditMetadataTool} (Inc4 god-class split); handlers are
 * package-visible and dispatched through the single-source op-registry. Shared
 * stateless helpers (synonym machinery, applyAttributeFeatureProperties, the
 * EMF/reflection setters, requireNonEmpty, formatResult, applyTags,
 * invokeListGetter) live on {@link EditMetadataTool} (qualified calls);
 * cluster-local helpers ({@link #addTypedCollectionChild}, {@link #collectNames},
 * {@link #getCommandsCollection}, {@link #formatGuardException}) are private here.
 */
final class SpecializedOps
{
    /**
     * 1.42 (RSV 4.2 parity): creates a {@code Command} attached to the owner
     * metadata object (Catalog/Document/etc.) - the same artefact the EDT
     * editor produces under "right-click owner -&gt; Add -&gt; Command".
     *
     * <p>Stores the command in {@code owner.getCommands()}, sets the supplied
     * properties, and validates the picture reference up-front via
     * {@link PictureValidator} so a typo'd {@code StdPicture.X} fails before
     * anything is written. The handler stub
     * ({@code Процедура ОбработкаКоманды(...) КонецПроцедуры}) is generated
     * by EDT itself when the {@code .mdo} is exported - the agent fills the
     * body afterwards via {@code write_module_source}.
     *
     * <p>For {@link com._1c.g5.v8.dt.metadata.mdclass.CommonCommand} use
     * {@code createObject objectName=CommonCommand.X} - that path already
     * exists.
     */
    String opCreateObjectCommand(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        String title = JsonUtils.extractStringArgument(params, "title"); //$NON-NLS-1$
        String commandParameterType = JsonUtils.extractStringArgument(params, "commandParameterType"); //$NON-NLS-1$
        String picture = JsonUtils.extractStringArgument(params, "picture"); //$NON-NLS-1$
        String tooltip = JsonUtils.extractStringArgument(params, "tooltip"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$

        String err = EditMetadataTool.requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(ownerFqn, "ownerFqn") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(name, "name"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }
        // 1.42 (B4): picture validation before any write hits disk.
        if (picture != null && !picture.isEmpty())
        {
            String pictureError = PictureValidator.validate(projectName, picture);
            if (pictureError != null)
            {
                return ToolResult.error(pictureError).toJson();
            }
        }

        final String titleFinal = title;
        final String paramTypeFinal = commandParameterType;
        final String pictureFinal = picture;
        final String tooltipFinal = tooltip;
        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, owner) -> {
                EList<MdObject> commands = getCommandsCollection(owner);
                if (commands == null)
                {
                    throw new RuntimeException("Unsupported owner type '" + owner.eClass().getName() //$NON-NLS-1$
                        + "' has no Commands collection. Object commands are supported on " //$NON-NLS-1$
                        + "Catalog, Document, ChartOfAccounts, ChartOfCharacteristicTypes, " //$NON-NLS-1$
                        + "ChartOfCalculationTypes, BusinessProcess, Task, ExchangePlan, " //$NON-NLS-1$
                        + "InformationRegister, AccumulationRegister, AccountingRegister, " //$NON-NLS-1$
                        + "CalculationRegister, DataProcessor, Report. For configuration-wide " //$NON-NLS-1$
                        + "commands use createObject objectName=CommonCommand.X."); //$NON-NLS-1$
                }
                if (BmObjectHelper.findByName(commands, name) != null)
                {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("name", name); //$NON-NLS-1$
                    data.put("ownerFqn", ownerFqn); //$NON-NLS-1$
                    data.put("kind", "command"); //$NON-NLS-1$ //$NON-NLS-2$
                    throw new MetadataGuards.BlockedGuardException(MetadataGuards.Verdict.block(
                        "Command already exists: " + name, //$NON-NLS-1$
                        "Use removeCommand first, or pick a different name.", //$NON-NLS-1$
                        new MetadataGuards.ErrorTag(ErrorTags.ALREADY_EXISTS.wire(), data)));
                }
                MdObject command = BmObjectHelper.createOwnerScopedObject(owner, "Command"); //$NON-NLS-1$
                if (command == null)
                {
                    throw new RuntimeException("Cannot create command under '" //$NON-NLS-1$
                        + owner.eClass().getName()
                        + "': no compatible MdClassFactory method found " //$NON-NLS-1$
                        + "(tried create" + owner.eClass().getName() + "Command, " //$NON-NLS-1$ //$NON-NLS-2$
                        + "MdClassPackage EClass lookup)."); //$NON-NLS-1$
                }
                command.setName(name);
                commands.add(command);
                // Auto-generate a synonym from the name when no title was supplied, via the same
                // path createObject uses: applyMdObjectSynonym resolves the configuration's default
                // language and strips the namePrefix, which a raw setProperty("synonym", ...) cannot
                // (it always writes under "ru"). An explicit title is honored as-is.
                EditMetadataTool.applyMdObjectSynonym(command, titleFinal, name, project);
                if (tooltipFinal != null && !tooltipFinal.isEmpty())
                {
                    BmObjectHelper.setProperty(command, "toolTip", tooltipFinal); //$NON-NLS-1$
                }
                EditMetadataTool.applyOptionalString(command, "setCommandParameterType", paramTypeFinal); //$NON-NLS-1$
                // Note: Command.picture is an EMF Picture object, not a String
                // setter. Applying it requires MdClassFactory.createPicture()
                // and a typed setter that is not stable across EDT versions.
                // The picture has been validated above via PictureValidator;
                // the agent applies it as a follow-up via setObjectProperty
                // propertyName=picture (which carries the same validation).
                return name;
            });
        if (picture != null && !picture.isEmpty() && r.ok)
        {
            r.tags.put("pictureValidated", picture); //$NON-NLS-1$
            r.tags.put("pictureFollowUp", //$NON-NLS-1$
                "setObjectProperty objectFqn=" + ownerFqn + "." + name //$NON-NLS-1$ //$NON-NLS-2$
                    + " propertyName=picture propertyValue=" + picture); //$NON-NLS-1$
        }
        return EditMetadataTool.formatResult(r, "create_object_command"); //$NON-NLS-1$
    }

    /**
     * 1.42 (RSV 4.2 parity): removes a {@code Command} from the owner's
     * {@code getCommands()} collection. The corresponding
     * {@code CommandModule.bsl} is wiped by EDT during the next export pass.
     *
     * <p>Note: callers should clear references to the command from form
     * command interfaces (via {@code removeFormCommandInterfaceItem}) before
     * deletion to avoid dangling FQN references; we do not auto-cascade
     * because that requires scanning every form on the configuration.
     */
    String opRemoveCommand(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$

        String err = EditMetadataTool.requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(ownerFqn, "ownerFqn") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(name, "name"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }
        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, owner) -> {
                EList<MdObject> commands = getCommandsCollection(owner);
                if (commands == null)
                {
                    throw new RuntimeException("Unsupported owner type '" + owner.eClass().getName() //$NON-NLS-1$
                        + "' has no Commands collection."); //$NON-NLS-1$
                }
                MdObject existing = BmObjectHelper.findByName(commands, name);
                if (existing == null)
                {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("name", name); //$NON-NLS-1$
                    data.put("ownerFqn", ownerFqn); //$NON-NLS-1$
                    data.put("kind", "command"); //$NON-NLS-1$ //$NON-NLS-2$
                    throw new MetadataGuards.BlockedGuardException(MetadataGuards.Verdict.block(
                        "Command not found: " + name, //$NON-NLS-1$
                        "List commands via get_metadata_details.", //$NON-NLS-1$
                        new MetadataGuards.ErrorTag(ErrorTags.NOT_FOUND.wire(), data)));
                }
                commands.remove(existing);
                return name;
            });
        return EditMetadataTool.formatResult(r, "remove_command"); //$NON-NLS-1$
    }

    /**
     * 1.42 helper: returns the {@code getCommands()} EList of an MdObject via
     * reflection. Returns {@code null} only when the type has no
     * {@code getCommands()} method at all (e.g. Constant, CommonModule,
     * Subsystem). When the method exists but throws at runtime the helper
     * rethrows so the failure surfaces as an action error - hiding it would
     * make the caller report a misleading "this type has no commands".
     */

    /**
     * 1.42 (RSV 4.2 parity): blocks until every pending BM export for the
     * project has hit disk. EDT lazily flushes {@code .mdo} / {@code .bsl}
     * writes - the resolver covers most cases automatically, but external
     * tooling (an outside git commit hook, a script reading the freshly
     * written file) may need an explicit "everything is on disk now" gate.
     *
     * <p>The actual wait is delegated to
     * {@link ru.aiedt.mcp.server.support.BmExportHelper#forceExportAndWait}
     * which is what every mutation operation uses internally; calling it
     * with the project's root FQN drains the project's export queue.
     *
     * <p>Returns the elapsed milliseconds and a boolean status so the agent
     * can decide whether to proceed with the read/commit it was waiting on.
     */
    String opSyncExport(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required").toJson(); //$NON-NLS-1$
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }
        IBmModelManager manager = Activator.getDefault().getBmModelManager();
        if (manager == null)
        {
            return ToolResult.error("object model manager is not published as a service").toJson(); //$NON-NLS-1$
        }
        long start = System.currentTimeMillis();
        ru.aiedt.mcp.server.support.BmExportHelper.Result r;
        // FQN-less drain: pass the configuration root FQN (for regular
        // projects) or the external root FQN (for .epf/.erf DT projects).
        String drainFqn;
        String externalKind = ru.aiedt.mcp.server.support
            .ExternalProjectResolver.detectExternalKind(project);
        if (externalKind != null)
        {
            drainFqn = ru.aiedt.mcp.server.support
                .ExternalProjectResolver.getRootFqn(project);
        }
        else
        {
            drainFqn = "Configuration"; //$NON-NLS-1$
        }
        try
        {
            r = ru.aiedt.mcp.server.support.BmExportHelper
                .forceExportAndWait(manager, project, drainFqn);
        }
        catch (Exception e)
        {
            return ToolResult.error("syncExport failed: " //$NON-NLS-1$
                + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()))
                .toJson();
        }
        long elapsed = System.currentTimeMillis() - start;
        boolean flushPending = r != null && r.syncFlushPending;
        boolean exportOk = r != null && r.forceExportOk;
        // forceExportOk=false: the export itself did not complete (not just the flush
        // still pending). sync_export exists to gate external commits / builds on a
        // settled disk, so a false success here would green-light them over stale
        // .mdo / .bsl - report an error instead of success.
        if (!exportOk)
        {
            return ToolResult.error("sync_export: force-export did not complete " //$NON-NLS-1$
                + "(forceExportOk=false). The project's export queue could not be drained; " //$NON-NLS-1$
                + "do NOT run an external commit / build yet. Re-run sync_export, or " //$NON-NLS-1$
                + "restart EDT if it keeps failing.") //$NON-NLS-1$
                .put("operation", "sync_export") //$NON-NLS-1$ //$NON-NLS-2$
                .put("projectName", projectName) //$NON-NLS-1$
                .put("drainFqn", drainFqn) //$NON-NLS-1$
                .put("forceExportOk", false) //$NON-NLS-1$
                .put("syncFlushPending", flushPending) //$NON-NLS-1$
                .put("elapsedMs", elapsed) //$NON-NLS-1$
                .toJson();
        }
        ToolResult out = ToolResult.success()
            .put("operation", "sync_export") //$NON-NLS-1$ //$NON-NLS-2$
            .put("projectName", projectName) //$NON-NLS-1$
            .put("drainFqn", drainFqn) //$NON-NLS-1$
            .put("forceExportOk", true) //$NON-NLS-1$
            .put("syncFlushPending", flushPending) //$NON-NLS-1$
            .put("elapsedMs", elapsed); //$NON-NLS-1$
        if (flushPending)
        {
            // Row 42: the drain did NOT finish within the budget - do NOT treat
            // this as "everything is on disk". This is the most dangerous false
            // success in the helper, since sync_export exists precisely to gate
            // external reads / commits / builds on a settled disk.
            out.put("hint", "DISK NOT CONFIRMED: the export queue did not drain within " //$NON-NLS-1$ //$NON-NLS-2$
                + "the wait budget (large or still-settling config). Freshly written " //$NON-NLS-1$
                + ".mdo / .bsl files may still be stale - do NOT run an external commit / " //$NON-NLS-1$
                + "build yet. Re-run once EDT settles; if it stays pending, restart EDT."); //$NON-NLS-1$
        }
        else
        {
            out.put("hint", "Returns when the project's export queue is empty. Use " //$NON-NLS-1$ //$NON-NLS-2$
                + "before reading freshly written .mdo / .bsl files from outside the " //$NON-NLS-1$
                + "plugin or before running an external commit / build."); //$NON-NLS-1$
        }
        return out.toJson();
    }

    private static EList<MdObject> getCommandsCollection(MdObject owner)
    {
        try
        {
            Object result = owner.getClass().getMethod("getCommands").invoke(owner); //$NON-NLS-1$
            if (result instanceof EList)
            {
                return (EList<MdObject>) result;
            }
            return null;
        }
        catch (NoSuchMethodException nsme)
        {
            return null;
        }
        catch (java.lang.reflect.InvocationTargetException ite)
        {
            Throwable cause = ite.getCause() != null ? ite.getCause() : ite;
            throw new RuntimeException("getCommands() failed on " //$NON-NLS-1$
                + owner.eClass().getName() + ": " //$NON-NLS-1$
                + (cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName()),
                cause);
        }
        catch (Exception e)
        {
            throw new RuntimeException("getCommandsCollection failed for " //$NON-NLS-1$
                + owner.eClass().getName() + ": " + e.getMessage(), e); //$NON-NLS-1$
        }
    }
    String opAddRegisterField(Map<String, String> params)
    {
        String kind = JsonUtils.extractStringArgument(params, "kind"); //$NON-NLS-1$
        if (kind == null || kind.isEmpty())
        {
            return ToolResult.error("kind is required (Dimension/Resource/Attribute)").toJson(); //$NON-NLS-1$
        }
        // Resolve the field kind up front so an invalid value fails fast (no
        // BM transaction is opened on bad input).
        final String collection;
        final String childType;
        switch (kind.toLowerCase())
        {
            case "dimension": case "измерение": //$NON-NLS-1$ //$NON-NLS-2$
                collection = "getDimensions"; childType = "Dimension"; break; //$NON-NLS-1$ //$NON-NLS-2$
            case "resource": case "ресурс": //$NON-NLS-1$ //$NON-NLS-2$
                collection = "getResources"; childType = "Resource"; break; //$NON-NLS-1$ //$NON-NLS-2$
            case "attribute": case "реквизит": //$NON-NLS-1$ //$NON-NLS-2$
                collection = "getAttributes"; childType = "Attribute"; break; //$NON-NLS-1$ //$NON-NLS-2$
            default:
                return ToolResult.error("kind must be Dimension/Resource/Attribute").toJson(); //$NON-NLS-1$
        }
        return addTypedCollectionChild(params, collection, childType, "add_register_field"); //$NON-NLS-1$
    }

    /**
     * 1.43.x (audit A3): adds an addressing attribute (реквизит адресации) to a
     * Task via {@code getAddressingAttributes}. Same typed-child machinery as
     * register fields - name + optional type/qualifiers, idempotent on name.
     * The MdClassFactory child class is the bare {@code AddressingAttribute}
     * (not type-prefixed), so {@code createOwnerScopedObject} resolves it via
     * its generic {@code create<childType>} strategy.
     */
    String opAddAddressingAttribute(Map<String, String> params)
    {
        return addTypedCollectionChild(params, "getAddressingAttributes", //$NON-NLS-1$
            "AddressingAttribute", "add_addressing_attribute"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * 1.43.x (audit A3): adds an accounting flag (признак учёта) to a
     * ChartOfAccounts via {@code getAccountingFlags}.
     */
    String opAddAccountingFlag(Map<String, String> params)
    {
        return addTypedCollectionChild(params, "getAccountingFlags", //$NON-NLS-1$
            "AccountingFlag", "add_accounting_flag"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * 1.43.x (audit A3): adds an ext-dimension accounting flag (признак учёта
     * субконто) to a ChartOfAccounts via {@code getExtDimensionAccountingFlags}.
     */
    String opAddExtDimensionAccountingFlag(Map<String, String> params)
    {
        return addTypedCollectionChild(params, "getExtDimensionAccountingFlags", //$NON-NLS-1$
            "ExtDimensionAccountingFlag", "add_ext_dimension_accounting_flag"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * 1.43.x (audit A3): adds a Recalculation (Перерасчёт) to a
     * CalculationRegister via {@code getRecalculations()}. A Recalculation is an
     * MdObject child (its own name + uuid); its dimensions are added separately
     * via add_recalculation_dimension. Idempotent on name.
     */
    String opAddRecalculation(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        String synonym = JsonUtils.extractStringArgument(params, "synonym"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$

        String err = EditMetadataTool.requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(ownerFqn, "ownerFqn") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(name, "name"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }

        final boolean[] idempotentSkip = { false };
        final EditMetadataTool.SynonymResult[] synOut = { null };

        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, owner) -> {
                EList<MdObject> recalcs = EditMetadataTool.invokeListGetter(owner, "getRecalculations"); //$NON-NLS-1$
                if (recalcs == null)
                {
                    throw new RuntimeException("Owner '" + owner.eClass().getName() //$NON-NLS-1$
                        + "' has no recalculations (only a CalculationRegister does)."); //$NON-NLS-1$
                }
                if (BmObjectHelper.findByName(recalcs, name) != null)
                {
                    idempotentSkip[0] = true;
                    return name;
                }
                MdObject recalc = BmObjectHelper.createGenericObject("Recalculation"); //$NON-NLS-1$
                if (recalc == null)
                {
                    throw new RuntimeException("Cannot create Recalculation - " //$NON-NLS-1$
                        + "MdClassFactory.createRecalculation() unavailable on this EDT runtime."); //$NON-NLS-1$
                }
                recalc.setName(name);
                synOut[0] = EditMetadataTool.applyMdObjectSynonym(recalc, synonym, name, project);
                recalcs.add(recalc);
                return name;
            });

        if (idempotentSkip[0])
        {
            Map<String, Object> idem = new LinkedHashMap<>();
            idem.put("name", name); //$NON-NLS-1$
            r.tags.put("idempotentSkip", idem); //$NON-NLS-1$
        }
        EditMetadataTool.addSynonymTags(r, synOut[0]);
        return EditMetadataTool.formatResult(r, "add_recalculation"); //$NON-NLS-1$
    }

    /**
     * 1.43.x (audit A3): adds a RecalculationDimension to a Recalculation. The
     * owner is the CalculationRegister; {@code recalculationName} selects the
     * recalc, {@code registerDimension} names an existing register dimension the
     * recalc dimension references (RecalculationDimension.setRegisterDimension).
     * Idempotent on the dimension name within the recalc.
     */
    String opAddRecalculationDimension(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String recalculationName = JsonUtils.extractStringArgument(params, "recalculationName"); //$NON-NLS-1$
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        String registerDimension = JsonUtils.extractStringArgument(params, "registerDimension"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$

        String err = EditMetadataTool.requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(ownerFqn, "ownerFqn") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(recalculationName, "recalculationName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(name, "name") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(registerDimension, "registerDimension"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }

        final boolean[] idempotentSkip = { false };

        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, owner) -> {
                EList<MdObject> recalcs = EditMetadataTool.invokeListGetter(owner, "getRecalculations"); //$NON-NLS-1$
                if (recalcs == null)
                {
                    throw new RuntimeException("Owner '" + owner.eClass().getName() //$NON-NLS-1$
                        + "' has no recalculations (only a CalculationRegister does)."); //$NON-NLS-1$
                }
                MdObject recalc = BmObjectHelper.findByName(recalcs, recalculationName);
                if (recalc == null)
                {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("recalculationName", recalculationName); //$NON-NLS-1$
                    data.put("available", collectNames(recalcs)); //$NON-NLS-1$
                    throw new MetadataGuards.BlockedGuardException(MetadataGuards.Verdict.block(
                        "Recalculation not found: " + recalculationName, //$NON-NLS-1$
                        "Create it first via add_recalculation.", //$NON-NLS-1$
                        new MetadataGuards.ErrorTag(ErrorTags.NOT_FOUND.wire(), data)));
                }
                EList<MdObject> regDims = EditMetadataTool.invokeListGetter(owner, "getDimensions"); //$NON-NLS-1$
                MdObject regDim = (regDims != null)
                    ? BmObjectHelper.findByName(regDims, registerDimension) : null;
                if (regDim == null)
                {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("registerDimension", registerDimension); //$NON-NLS-1$
                    data.put("available", regDims != null ? collectNames(regDims) : new ArrayList<>()); //$NON-NLS-1$
                    throw new MetadataGuards.BlockedGuardException(MetadataGuards.Verdict.block(
                        "Register dimension not found: " + registerDimension, //$NON-NLS-1$
                        "Add it first via add_register_field kind=Dimension, or pick an existing one.", //$NON-NLS-1$
                        new MetadataGuards.ErrorTag(ErrorTags.NOT_FOUND.wire(), data)));
                }
                EList<MdObject> dims = EditMetadataTool.invokeListGetter(recalc, "getDimensions"); //$NON-NLS-1$
                if (dims == null)
                {
                    throw new RuntimeException("Recalculation '" + recalculationName //$NON-NLS-1$
                        + "' has no dimensions collection."); //$NON-NLS-1$
                }
                if (BmObjectHelper.findByName(dims, name) != null)
                {
                    idempotentSkip[0] = true;
                    return recalculationName + "." + name; //$NON-NLS-1$
                }
                MdObject dim = BmObjectHelper.createGenericObject("RecalculationDimension"); //$NON-NLS-1$
                if (dim == null)
                {
                    throw new RuntimeException("Cannot create RecalculationDimension - " //$NON-NLS-1$
                        + "MdClassFactory.createRecalculationDimension() unavailable."); //$NON-NLS-1$
                }
                dim.setName(name);
                java.lang.reflect.Method setter =
                    EditMetadataTool.findSingleArgSetter(dim.getClass(), "setRegisterDimension"); //$NON-NLS-1$
                if (setter == null)
                {
                    throw new RuntimeException("RecalculationDimension has no setRegisterDimension."); //$NON-NLS-1$
                }
                EditMetadataTool.invokeSetterClearly(setter, dim, regDim, "registerDimension"); //$NON-NLS-1$
                dims.add(dim);
                return recalculationName + "." + name; //$NON-NLS-1$
            });

        if (idempotentSkip[0])
        {
            Map<String, Object> idem = new LinkedHashMap<>();
            idem.put("recalculationName", recalculationName); //$NON-NLS-1$
            idem.put("name", name); //$NON-NLS-1$
            r.tags.put("idempotentSkip", idem); //$NON-NLS-1$
        }
        return EditMetadataTool.formatResult(r, "add_recalculation_dimension"); //$NON-NLS-1$
    }

    /** Collects the {@code getName()} values of an MdObject list (for did-you-mean / available lists). */
    private static List<String> collectNames(EList<MdObject> list)
    {
        List<String> names = new ArrayList<>();
        if (list != null)
        {
            for (MdObject o : list)
            {
                if (o != null && o.getName() != null)
                {
                    names.add(o.getName());
                }
            }
        }
        return names;
    }

    /**
     * Shared core for "add a typed, named child to an owner's collection":
     * register fields (Dimension/Resource/Attribute), Task addressing
     * attributes, ChartOfAccounts accounting flags, etc. All such children
     * extend {@code BasicFeature} (an MdObject exposing a TypeDescription via
     * {@code getType()}), so the type is applied through the exact same path
     * object attributes use. Creates the child via the owner-scoped
     * MdClassFactory, applies a TypeDescription (with qualifiers), and surfaces
     * idempotency + typeApplication outcome tags. Idempotent on name: same name
     * + matching type is a no-op; a different type yields a propertyMismatch tag.
     *
     * @param collection no-arg getter on the owner returning the EList to add
     *                   to (e.g. {@code getAddressingAttributes})
     * @param childType  MdClassFactory child kind (e.g. {@code AddressingAttribute})
     * @param opLabel    operation name for the response envelope
     */
    private String addTypedCollectionChild(Map<String, String> params, String collection,
        String childType, String opLabel)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        // 1.43.x: previously add_register_field accepted only name/kind and
        // silently dropped any caller-supplied type/length, leaving the child
        // written to disk without a TypeDescription. That crashed EDT
        // sessions on save. Wire the same QualifierOptions path used by
        // opAddObjectAttribute so the child carries a real type out of the box.
        String type = JsonUtils.extractStringArgument(params, "type"); //$NON-NLS-1$
        String synonymArg = JsonUtils.extractStringArgument(params, "synonym"); //$NON-NLS-1$
        final String synonym = (synonymArg != null) ? synonymArg.trim() : null;
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$

        BmDefinedTypeHelper.QualifierOptions qualifiers = new BmDefinedTypeHelper.QualifierOptions();
        qualifiers.length = JsonUtils.extractIntegerArgument(params, "length"); //$NON-NLS-1$
        qualifiers.precision = JsonUtils.extractIntegerArgument(params, "precision"); //$NON-NLS-1$
        qualifiers.fractionDigits = JsonUtils.extractIntegerArgument(params, "fractionDigits"); //$NON-NLS-1$
        if (params != null && params.containsKey("nonNegative")) //$NON-NLS-1$
        {
            qualifiers.nonNegative = JsonUtils.extractBooleanArgument(params, "nonNegative", false); //$NON-NLS-1$
        }
        qualifiers.dateFractions = JsonUtils.extractStringArgument(params, "dateFractions"); //$NON-NLS-1$
        qualifiers.allowedLength = JsonUtils.extractStringArgument(params, "allowedLength"); //$NON-NLS-1$

        String err = EditMetadataTool.requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(ownerFqn, "ownerFqn") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(name, "name"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }

        // Capture configuration for TypeDescription application inside the BM
        // transaction. Use the IProject overload of BmDefinedTypeHelper.setTypes
        // - the IDtProject overload is marked transitional in the helper's
        // javadoc (1.42.5), and the IProject path resolves canonical Type
        // proxies via IRuntimeVersionSupport (closes the BUG-1424-A pattern
        // for register fields).
        IConfigurationProvider regCfgProvider = Activator.getDefault().getConfigurationProvider();
        final Configuration regConfig = regCfgProvider != null
            ? regCfgProvider.getConfiguration(project) : null;

        final boolean[] typeAppliedFlag = { false };
        final String[] typeApplyErrorRef = { null };
        final List<String> typeResolved = new ArrayList<>();
        final List<String> typeUnresolved = new ArrayList<>();
        final boolean[] idempotentSkipFlag = { false };
        // Synonym outcome (explicit value or auto-generated from the name).
        // add_object_attribute applies the synonym right after setName; register
        // fields expose the same MdObject.getSynonym(), so without this the .mdo
        // was written synonym-less even when the caller supplied one.
        final EditMetadataTool.SynonymResult[] synOut = { null };
        // Optional per-field feature properties (fillChecking / fullTextSearch /
        // indexing / toolTip / comment) applied in the same call, where the
        // field kind supports them - mirrors add_object_attribute. Collected for
        // the appliedProperties / failedProperties tags.
        final List<String> appliedFeatureProps = new ArrayList<>();
        final Map<String, String> failedFeatureProps = new LinkedHashMap<>();

        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, owner) -> {
                EList<MdObject> list = EditMetadataTool.invokeListGetter(owner, collection);
                if (list == null)
                {
                    throw new RuntimeException("Owner has no " + collection); //$NON-NLS-1$
                }
                MdObject existing = BmObjectHelper.findByName(list, name);
                if (existing != null)
                {
                    // 1.43.x: idempotency. Same name with no type or matching
                    // type => no-op success. Different type => propertyMismatch
                    // tag so the caller can pick a follow-up (removeRegisterField
                    // first, or pick a different name).
                    if (type == null || type.isEmpty())
                    {
                        idempotentSkipFlag[0] = true;
                        EditMetadataTool.applyAttributeFeatureProperties(existing, params, opLabel,
                            appliedFeatureProps, failedFeatureProps);
                        // Backfill an omitted synonym only when the field has none yet, so a retry
                        // against a synonym-less field still picks one up without overwriting a
                        // manually customized synonym. An explicit synonym is always applied.
                        if ((synonym != null && !synonym.trim().isEmpty())
                            || EditMetadataTool.synonymIsBlank(existing))
                        {
                            synOut[0] = EditMetadataTool.applyMdObjectSynonym(existing, synonym, name, project);
                        }
                        return name;
                    }
                    BmDefinedTypeHelper.TypeComparison cmp = BmDefinedTypeHelper
                        .compareTypeNames(existing, Collections.singletonList(type));
                    if (cmp == BmDefinedTypeHelper.TypeComparison.MATCH)
                    {
                        idempotentSkipFlag[0] = true;
                        EditMetadataTool.applyAttributeFeatureProperties(existing, params, opLabel,
                            appliedFeatureProps, failedFeatureProps);
                        if ((synonym != null && !synonym.trim().isEmpty())
                            || EditMetadataTool.synonymIsBlank(existing))
                        {
                            synOut[0] = EditMetadataTool.applyMdObjectSynonym(existing, synonym, name, project);
                        }
                        return name;
                    }
                    Set<String> existingTypes = BmDefinedTypeHelper
                        .readExistingTypeNames(existing);
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("name", name); //$NON-NLS-1$
                    data.put("ownerFqn", ownerFqn); //$NON-NLS-1$
                    data.put("kind", childType.toLowerCase()); //$NON-NLS-1$
                    data.put("requestedType", type); //$NON-NLS-1$
                    data.put("existingTypes", new ArrayList<>(existingTypes)); //$NON-NLS-1$
                    throw new MetadataGuards.BlockedGuardException(MetadataGuards.Verdict.block(
                        childType + " '" + name + "' already exists with a different type" //$NON-NLS-1$ //$NON-NLS-2$
                            + " (requested=" + type + ", existing=" + existingTypes + ")", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        "Use set_object_type (ownerFqn=" + ownerFqn + "." + childType + "." + name //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                            + ", type=" + type + ") to change the existing type, pick a different " //$NON-NLS-1$ //$NON-NLS-2$
                            + "name, or remove the existing child first.", //$NON-NLS-1$
                        new MetadataGuards.ErrorTag(ErrorTags.PROPERTY_MISMATCH.wire(), data)));
                }
                // The child class is created via the owner-scoped MdClassFactory.
                // Register fields are type-specific (createInformationRegisterDimension);
                // Task/ChartOfAccounts children are bare (createAddressingAttribute /
                // createAccountingFlag) - createOwnerScopedObject handles both.
                MdObject field = BmObjectHelper.createOwnerScopedObject(owner, childType);
                if (field == null)
                {
                    throw new RuntimeException("Cannot create " + childType //$NON-NLS-1$
                        + " under '" + owner.eClass().getName() //$NON-NLS-1$
                        + "': no compatible MdClassFactory method (tried " //$NON-NLS-1$
                        + "create" + owner.eClass().getName() + childType //$NON-NLS-1$
                        + ", create" + childType + ", MdClassPackage lookup)."); //$NON-NLS-1$ //$NON-NLS-2$
                }
                field.setName(name);
                synOut[0] = EditMetadataTool.applyMdObjectSynonym(field, synonym, name, project);
                list.add(field);
                // 1.43.x bug-fix: apply TypeDescription so the field is not
                // persisted as <name>-only. setTypes works on any MdObject
                // exposing TypeDescription via getType()/getTypes()/
                // getTypeDescription() - that includes Dimension/Resource/
                // Attribute across all four register kinds.
                if (type != null && !type.isEmpty() && regConfig != null)
                {
                    try
                    {
                        BmDefinedTypeHelper.TypesResult tr = BmDefinedTypeHelper.setTypes(
                            field, project, regConfig,
                            Collections.singletonList(type), qualifiers);
                        typeAppliedFlag[0] = tr.ok;
                        if (tr.resolved != null)
                        {
                            typeResolved.addAll(tr.resolved);
                        }
                        if (tr.unresolved != null)
                        {
                            typeUnresolved.addAll(tr.unresolved);
                        }
                        if (!tr.ok)
                        {
                            typeApplyErrorRef[0] = tr.error;
                            Activator.logWarning(opLabel + ": type='" + type //$NON-NLS-1$
                                + "' not applied: " + tr.error); //$NON-NLS-1$
                        }
                    }
                    catch (Exception typeEx)
                    {
                        typeApplyErrorRef[0] = typeEx.getClass().getSimpleName() + ": " //$NON-NLS-1$
                            + typeEx.getMessage();
                        Activator.logWarning(opLabel + ": type='" + type //$NON-NLS-1$
                            + "' threw: " + typeEx.getMessage()); //$NON-NLS-1$
                    }
                }
                EditMetadataTool.applyAttributeFeatureProperties(field, params, opLabel,
                    appliedFeatureProps, failedFeatureProps);
                return name;
            },
            owner -> {
                // Child name must not collide with the owner's standard
                // attributes (register Period/Recorder/Active/RecordType,
                // Task/ChartOfAccounts Ref/DeletionMark, etc.). The guard reads
                // the owner's actual standard-attribute set, so it is owner-aware.
                MetadataGuards.Verdict conflict = MetadataGuards
                    .checkStandardAttributeConflict(owner, name);
                if (conflict.blocked)
                {
                    throw new MetadataGuards.BlockedGuardException(conflict);
                }
            });
        if (idempotentSkipFlag[0])
        {
            Map<String, Object> idem = new LinkedHashMap<>();
            idem.put("name", name); //$NON-NLS-1$
            idem.put("ownerFqn", ownerFqn); //$NON-NLS-1$
            idem.put("kind", childType.toLowerCase()); //$NON-NLS-1$
            if (type != null && !type.isEmpty())
            {
                idem.put("type", type); //$NON-NLS-1$
            }
            r.tags.put("idempotentSkip", idem); //$NON-NLS-1$
        }
        if (type != null && !type.isEmpty() && !idempotentSkipFlag[0])
        {
            Map<String, Object> typeApply = new LinkedHashMap<>();
            typeApply.put("requested", type); //$NON-NLS-1$
            typeApply.put("applied", typeAppliedFlag[0]); //$NON-NLS-1$
            if (!typeResolved.isEmpty())
            {
                typeApply.put("resolved", typeResolved); //$NON-NLS-1$
            }
            if (!typeUnresolved.isEmpty())
            {
                typeApply.put("unresolved", typeUnresolved); //$NON-NLS-1$
            }
            if (typeApplyErrorRef[0] != null)
            {
                typeApply.put("error", typeApplyErrorRef[0]); //$NON-NLS-1$
            }
            r.tags.put("typeApplication", typeApply); //$NON-NLS-1$
        }
        // Surface feature-prop outcome only on a successful (or idempotent-success,
        // which also sets r.ok) result - never claim appliedProperties on an error
        // response whose transaction rolled back after the helper ran.
        if (r.ok)
        {
            EditMetadataTool.addSynonymTags(r, synOut[0]);
            if (!appliedFeatureProps.isEmpty())
            {
                r.tags.put("appliedProperties", appliedFeatureProps); //$NON-NLS-1$
            }
            if (!failedFeatureProps.isEmpty())
            {
                r.tags.put("failedProperties", failedFeatureProps); //$NON-NLS-1$
            }
        }
        return EditMetadataTool.formatResult(r, opLabel);
    }

    String opRemoveRegisterField(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return ToolResult.error("Project not found").toJson(); //$NON-NLS-1$
        }
        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, owner) -> {
                for (String coll : new String[] {
                    "getDimensions", "getResources", "getAttributes" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                })
                {
                    EList<MdObject> list = EditMetadataTool.invokeListGetter(owner, coll);
                    if (list != null)
                    {
                        MdObject existing = BmObjectHelper.findByName(list, name);
                        if (existing != null)
                        {
                            list.remove(existing);
                            return name;
                        }
                    }
                }
                throw BmObjectHelper.notFound(name, ownerFqn, "registerField"); //$NON-NLS-1$
            });
        return EditMetadataTool.formatResult(r, "remove_register_field"); //$NON-NLS-1$
    }

    String opAddEnumValue(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return ToolResult.error("Project not found").toJson(); //$NON-NLS-1$
        }
        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, owner) -> {
                EList<MdObject> values = EditMetadataTool.invokeListGetter(owner, "getEnumValues"); //$NON-NLS-1$
                if (values == null)
                {
                    throw new RuntimeException("Not an Enum or has no values collection"); //$NON-NLS-1$
                }
                if (BmObjectHelper.findByName(values, name) != null)
                {
                    return name + " (already exists)"; //$NON-NLS-1$
                }
                MdObject value = BmObjectHelper.createGenericObject("EnumValue"); //$NON-NLS-1$
                if (value == null)
                {
                    throw new RuntimeException("Cannot create EnumValue: " //$NON-NLS-1$
                        + "MdClassFactory.createEnumValue() and MdClassPackage " //$NON-NLS-1$
                        + "lookup both unavailable."); //$NON-NLS-1$
                }
                value.setName(name);
                values.add(value);
                return name;
            });
        return EditMetadataTool.formatResult(r, "add_enum_value"); //$NON-NLS-1$
    }

    /**
     * 1.43.x (RSV 5.0 M3): authors an object help page - the inverse of the
     * {@code mdo-help-page-missing-html} export linter. Writes
     * {@code Help/<lang>.html} (content from {@code format=html|markdown|text})
     * and declares the page in the object's {@code <help>} block via a BM
     * {@link BmHelpHelper#ensureHelpPage} mutation (forceExported into the .mdo).
     * The HTML file is written FIRST so a failed write never leaves the .mdo
     * referencing a missing page (the exact defect the linter catches);
     * {@code remove_help} drops the BM ref first, then deletes the file.
     *
     * @param removeMode {@code true} for the {@code remove_help} alias
     */
    String opSetHelp(Map<String, String> params, boolean removeMode)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String objectFqn = JsonUtils.extractStringArgument(params, "objectName"); //$NON-NLS-1$
        if (objectFqn == null || objectFqn.isEmpty())
        {
            objectFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        }
        String langArg = JsonUtils.extractStringArgument(params, "language"); //$NON-NLS-1$
        final String lang = (langArg == null || langArg.isEmpty()) ? BmHelpHelper.DEFAULT_LANG : langArg;
        String content = JsonUtils.extractStringArgument(params, "content"); //$NON-NLS-1$
        String format = JsonUtils.extractStringArgument(params, "format"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$

        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("set_help requires projectName").toJson(); //$NON-NLS-1$
        }
        if (objectFqn == null || objectFqn.isEmpty())
        {
            return ToolResult.error("set_help requires objectName (e.g. Catalog.Goods)").toJson(); //$NON-NLS-1$
        }
        // Guard the language code: it becomes a path segment (Help/<lang>.html), so
        // reject anything that could escape the object's Help/ folder.
        if (!lang.matches("[a-zA-Z0-9_-]{1,20}")) //$NON-NLS-1$
        {
            return ToolResult.error("language must be a short code like 'ru' or 'en' " //$NON-NLS-1$
                + "(letters/digits/_/-, max 20 chars)").toJson(); //$NON-NLS-1$
        }
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return ToolResult.error("Project not found").toJson(); //$NON-NLS-1$
        }
        final String normalized = MetadataTypeCatalog.normalizeFqn(objectFqn);
        String[] parts = normalized.split("\\.", 2); //$NON-NLS-1$
        if (parts.length < 2)
        {
            return ToolResult.error("objectName must be a metadata FQN like Catalog.Goods").toJson(); //$NON-NLS-1$
        }
        final String type = parts[0];
        String dirFolder = MetadataTypeCatalog.getDirectoryName(type);
        if (dirFolder == null)
        {
            return ToolResult.error("Unrecognised metadata type '" + type + "' in objectName").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        String objectName = parts[1];
        // Help lives on top-level objects only; a dotted remainder is a child FQN.
        if (objectName.contains(".")) //$NON-NLS-1$
        {
            return ToolResult.error("set_help targets a top-level object; '" + normalized //$NON-NLS-1$
                + "' looks like a child FQN").toJson(); //$NON-NLS-1$
        }

        if (removeMode)
        {
            // Drop the BM <help> ref first so we never leave a dangling reference,
            // then delete the Help/<lang>.html file.
            java.util.concurrent.atomic.AtomicBoolean pageRemoved =
                new java.util.concurrent.atomic.AtomicBoolean(false);
            BmObjectHelper.Result br = BmObjectHelper.executeWriteOnObject(project, normalized, dryRun,
                (tx, owner) -> {
                    if (!BmHelpHelper.hasHelpFeature(owner))
                    {
                        throw new RuntimeException(
                            "object type " + type + " does not support help pages"); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                    pageRemoved.set(BmHelpHelper.removeHelpPage(owner, lang));
                    return pageRemoved.get() ? "help page removed" : "no help page for language"; //$NON-NLS-1$ //$NON-NLS-2$
                });
            if (!br.ok)
            {
                return ToolResult.error(br.error != null ? br.error : "remove_help failed").toJson(); //$NON-NLS-1$
            }
            BmHelpHelper.FileResult fr =
                BmHelpHelper.removeHelpFile(project, dirFolder, objectName, lang, dryRun);
            if (!fr.ok)
            {
                return ToolResult.error(fr.error != null ? fr.error : "help file delete failed").toJson(); //$NON-NLS-1$
            }
            return ToolResult.success()
                .put("operation", "remove_help") //$NON-NLS-1$ //$NON-NLS-2$
                .put("objectName", normalized) //$NON-NLS-1$
                .put("language", lang) //$NON-NLS-1$
                .put("dryRun", dryRun) //$NON-NLS-1$
                .put("pageRefRemoved", pageRemoved.get()) //$NON-NLS-1$
                .put("fileRemoved", fr.fileRemoved) //$NON-NLS-1$
                .put("idempotentSkip", !pageRemoved.get() && fr.idempotent) //$NON-NLS-1$
                .put("persistedTo", fr.relPath) //$NON-NLS-1$
                .toJson();
        }

        if (content == null || content.isEmpty())
        {
            return ToolResult.error("set_help requires 'content' (use remove_help to delete a page)").toJson(); //$NON-NLS-1$
        }
        // Pre-validate (read-only) that the object exists and supports help, so a
        // non-help object (e.g. CommonModule) never gets an orphan Help/<lang>.html
        // written before the BM mutation would reject it. The supplier-lock guard
        // still runs inside executeWriteOnObject below.
        IConfigurationProvider helpCfgProvider = Activator.getDefault().getConfigurationProvider();
        Configuration helpConfig = helpCfgProvider != null ? helpCfgProvider.getConfiguration(project) : null;
        if (helpConfig == null)
        {
            return ToolResult.error("Configuration not available for project " + projectName).toJson(); //$NON-NLS-1$
        }
        MdObject helpTarget = MetadataTypeCatalog.findObject(helpConfig, type, objectName);
        if (helpTarget == null)
        {
            return ToolResult.error("No such object: " + normalized).toJson(); //$NON-NLS-1$
        }
        if (!BmHelpHelper.hasHelpFeature(helpTarget))
        {
            return ToolResult.error("object type " + type + " does not support help pages").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }

        // Build + write the HTML file first: if the write fails we abort before
        // declaring the page, so the .mdo never references a missing file.
        String html = BmHelpHelper.buildHelpHtml(content, format);
        BmHelpHelper.FileResult fr =
            BmHelpHelper.writeHelpFile(project, dirFolder, objectName, lang, html, dryRun);
        if (!fr.ok)
        {
            return ToolResult.error(fr.error != null ? fr.error : "help file write failed").toJson(); //$NON-NLS-1$
        }
        java.util.concurrent.atomic.AtomicBoolean pageAdded =
            new java.util.concurrent.atomic.AtomicBoolean(false);
        BmObjectHelper.Result br = BmObjectHelper.executeWriteOnObject(project, normalized, dryRun,
            (tx, owner) -> {
                if (!BmHelpHelper.hasHelpFeature(owner))
                {
                    throw new RuntimeException(
                        "object type " + type + " does not support help pages"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                pageAdded.set(BmHelpHelper.ensureHelpPage(owner, lang));
                return pageAdded.get() ? "help page declared" : "help page already declared"; //$NON-NLS-1$ //$NON-NLS-2$
            });
        if (!br.ok)
        {
            return ToolResult.error("Help file written but <help> ref update failed: " //$NON-NLS-1$
                + (br.error != null ? br.error : "unknown")).toJson(); //$NON-NLS-1$
        }
        return ToolResult.success()
            .put("operation", "set_help") //$NON-NLS-1$ //$NON-NLS-2$
            .put("objectName", normalized) //$NON-NLS-1$
            .put("language", lang) //$NON-NLS-1$
            .put("format", (format == null || format.isEmpty()) ? "auto" : format) //$NON-NLS-1$ //$NON-NLS-2$
            .put("dryRun", dryRun) //$NON-NLS-1$
            .put("pageRefAdded", pageAdded.get()) //$NON-NLS-1$
            .put("fileWritten", fr.fileWritten) //$NON-NLS-1$
            .put("idempotentSkip", !pageAdded.get() && fr.idempotent) //$NON-NLS-1$
            .put("bytes", fr.bytes) //$NON-NLS-1$
            .put("persistedTo", fr.relPath) //$NON-NLS-1$
            .toJson();
    }

    /**
     * 1.40.1: setDefinedTypeTypes - real mutation via
     * {@link BmDefinedTypeHelper#setTypes}. Probes
     * {@code MdClassUtil.getProducedTypes} and copies existing TypeItem
     * entries (avoiding EMF containment moves); falls back to a
     * {@code partialMutation} tag for primitive-only requests when the
     * platform factory is missing.
     */
    String opSetDefinedTypeTypes(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String typesCsv = JsonUtils.extractStringArgument(params, "types"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$
        if (ownerFqn == null || ownerFqn.isEmpty())
        {
            return ToolResult.error("setDefinedTypeTypes requires ownerFqn (DefinedType.X)").toJson();
        }
        if (typesCsv == null || typesCsv.isEmpty())
        {
            return ToolResult.error("setDefinedTypeTypes requires 'types' (CSV of FQNs)").toJson();
        }
        java.util.List<String> types = new java.util.ArrayList<>();
        for (String t : typesCsv.split("\\s*,\\s*"))
        {
            if (!t.isEmpty())
            {
                types.add(t);
            }
        }
        // Qualifier wiring so a DefinedType value type can carry String(150) /
        // Number(15,2) / Date(DateTime) / non-negative numbers in one call instead
        // of a follow-up step. Mirrors opAddObjectAttribute; passed to setTypes
        // below (previously null, so разрядность was silently dropped).
        BmDefinedTypeHelper.QualifierOptions qualifiers = new BmDefinedTypeHelper.QualifierOptions();
        qualifiers.length = JsonUtils.extractIntegerArgument(params, "length"); //$NON-NLS-1$
        qualifiers.precision = JsonUtils.extractIntegerArgument(params, "precision"); //$NON-NLS-1$
        qualifiers.fractionDigits = JsonUtils.extractIntegerArgument(params, "fractionDigits"); //$NON-NLS-1$
        if (params != null && params.containsKey("nonNegative")) //$NON-NLS-1$
        {
            qualifiers.nonNegative = JsonUtils.extractBooleanArgument(params, "nonNegative", false); //$NON-NLS-1$
        }
        qualifiers.dateFractions = JsonUtils.extractStringArgument(params, "dateFractions"); //$NON-NLS-1$
        qualifiers.allowedLength = JsonUtils.extractStringArgument(params, "allowedLength"); //$NON-NLS-1$
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return ToolResult.error("Project not found").toJson();
        }
        java.util.concurrent.atomic.AtomicReference<ru.aiedt.mcp.server.support.BmDefinedTypeHelper.TypesResult> ref
            = new java.util.concurrent.atomic.AtomicReference<>();
        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, owner) -> {
                Configuration config = Activator.getDefault().getConfigurationProvider()
                    .getConfiguration(project);
                IDtProject dtProject = Activator.getDefault().getDtProjectManager()
                    .getDtProject(project);
                ru.aiedt.mcp.server.support.BmDefinedTypeHelper.TypesResult tr
                    = ru.aiedt.mcp.server.support.BmDefinedTypeHelper.setTypes(
                        owner, dtProject, config, types, qualifiers);
                ref.set(tr);
                if (!tr.ok)
                {
                    throw new RuntimeException(tr.error != null ? tr.error : "setTypes failed");
                }
                if (tr.idempotentSkip)
                {
                    return "idempotentSkip: types already match the requested composition";
                }
                StringBuilder summary = new StringBuilder("Types applied: ") //$NON-NLS-1$
                    .append(tr.resolved.size());
                if (!tr.unresolved.isEmpty())
                {
                    summary.append(" (unresolved: ").append(tr.unresolved.size()).append(")"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                return summary.toString();
            });
        ToolResult tool = r.ok ? ToolResult.success() : ToolResult.error(r.error != null ? r.error : "setDefinedTypeTypes failed");
        tool.put("operation", "set_defined_type_types")
            .put("ownerFqn", ownerFqn)
            .put("requestedTypes", types);
        if (ref.get() != null)
        {
            ru.aiedt.mcp.server.support.BmDefinedTypeHelper.TypesResult tr = ref.get();
            tool.put("resolved", tr.resolved)
                .put("unresolved", tr.unresolved)
                .put("mutated", tr.mutated)
                .put("idempotentSkip", tr.idempotentSkip);
            if (!tr.unresolved.isEmpty())
            {
                tool.put("partialMutation", "Some FQNs could not be turned into TypeItems"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        EditMetadataTool.applyTags(tool, r.tags);
        return tool.toJson();
    }

    /**
     * 1.40: addEventSubscriptionHandler with handler auto-prefix
     * (defensive layer 3.8.1).
     * <p>
     * Accepts {@code handler} as either {@code "Module.Method"} or full
     * {@code "CommonModule.Module.Method"}; normalizes to the canonical full
     * form, validates the referenced common module exists in the project,
     * and only then generates the BSL stub.
     */
    String opAddEventHandler(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String eventName = JsonUtils.extractStringArgument(params, "eventName"); //$NON-NLS-1$
        String handlerName = JsonUtils.extractStringArgument(params, "handlerName"); //$NON-NLS-1$
        String handler = JsonUtils.extractStringArgument(params, "handler"); //$NON-NLS-1$
        String customSignature = JsonUtils.extractStringArgument(params, "customSignature"); //$NON-NLS-1$

        // 3.8.1: normalize handler if passed as full form (preferred form)
        BmEventSubscriptionHelper.NormalizationResult norm = null;
        String resolvedHandlerName = handlerName;
        String resolvedModuleName = null;
        if (handler != null && !handler.isEmpty())
        {
            norm = BmEventSubscriptionHelper.normalizeHandler(handler);
            if (norm == null)
            {
                try
                {
                    throw BmEventSubscriptionHelper.handlerInvalid(handler);
                }
                catch (MetadataGuards.BlockedGuardException blocked)
                {
                    return formatGuardException(blocked, "add_event_subscription_handler");
                }
            }
            resolvedHandlerName = norm.methodName;
            resolvedModuleName = norm.moduleName;
        }
        // Validate CommonModule exists when project is known
        if (resolvedModuleName != null && projectName != null && !projectName.isEmpty())
        {
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            if (project != null && project.exists() && project.isOpen())
            {
                Configuration config = Activator.getDefault().getConfigurationProvider()
                    .getConfiguration(project);
                if (!BmEventSubscriptionHelper.commonModuleExists(config, resolvedModuleName))
                {
                    try
                    {
                        throw BmEventSubscriptionHelper.commonModuleNotFound(resolvedModuleName);
                    }
                    catch (MetadataGuards.BlockedGuardException blocked)
                    {
                        return formatGuardException(blocked, "add_event_subscription_handler");
                    }
                }
            }
        }

        EventStubGenerator.Stub stub = EventStubGenerator.generateStub(eventName,
            resolvedHandlerName, customSignature);
        if (stub.code == null)
        {
            return ToolResult.error(stub.warning != null ? stub.warning : "stub generation failed") //$NON-NLS-1$
                .toJson();
        }
        ToolResult result = ToolResult.success()
            .put("operation", "add_event_subscription_handler") //$NON-NLS-1$ //$NON-NLS-2$
            .put("signatureSource", stub.signatureSource) //$NON-NLS-1$
            .put("stub", stub.code); //$NON-NLS-1$
        if (norm != null)
        {
            result.put("normalizedHandler", norm.normalized);
            result.put("commonModule", norm.moduleName);
            result.put("methodName", norm.methodName);
            if (norm.changed)
            {
                result.put("handlerNormalized", true);
            }
        }
        if (stub.warning != null)
        {
            result.put("warning", stub.warning); //$NON-NLS-1$
        }
        return result.toJson();
    }

    /**
     * Sets an EventSubscription's {@code source} (source value-types),
     * {@code event} and {@code handler} in one call - any subset may be given
     * (partial update). Closes the gap where create_object produced only a bare
     * name+synonym skeleton with no way to make the subscription functional
     * (and where the later background disk flush re-serialized that bare model
     * over a hand-edited .mdo). Because the whole payload is committed to BM in
     * one transaction, forceExport then writes the complete, correct .mdo.
     *
     * <p>{@code source} is a comma-separated list of source value-type FQNs
     * (e.g. {@code DocumentObject.X,InformationRegisterRecordSet.Y,ConstantValueManager.Z}).
     * {@code event} is a plain string (soft-validated: an unrecognized value is
     * set with a warning, not rejected - the platform event set is broader than
     * the known list). {@code handler} is normalized to the canonical
     * {@code CommonModule.<Module>.<Method>} shape and its module is verified to
     * exist before the write, failing fast with a guard error otherwise.
     */
    String opSetEventSubscription(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "objectFqn"); //$NON-NLS-1$
        if (ownerFqn == null || ownerFqn.isEmpty())
        {
            ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        }
        String source = JsonUtils.extractStringArgument(params, "source"); //$NON-NLS-1$
        String event = JsonUtils.extractStringArgument(params, "event"); //$NON-NLS-1$
        String handler = JsonUtils.extractStringArgument(params, "handler"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$
        if (ownerFqn == null || ownerFqn.isEmpty())
        {
            return ToolResult.error(
                "setEventSubscription requires objectFqn (EventSubscription.X)").toJson(); //$NON-NLS-1$
        }
        if ((source == null || source.isEmpty()) && (event == null || event.isEmpty())
            && (handler == null || handler.isEmpty()))
        {
            return ToolResult.error(
                "setEventSubscription requires at least one of source / event / handler").toJson(); //$NON-NLS-1$
        }
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return ToolResult.error("Project not found").toJson(); //$NON-NLS-1$
        }

        // Normalize the handler and verify its common module exists BEFORE the
        // write transaction, so a bad handler fails fast with a clear guard
        // error instead of a UpdateDBCfg failure minutes later.
        final String[] normalizedHandler = { handler };
        if (handler != null && !handler.isEmpty())
        {
            BmEventSubscriptionHelper.NormalizationResult norm =
                BmEventSubscriptionHelper.normalizeHandler(handler);
            if (norm == null)
            {
                try
                {
                    throw BmEventSubscriptionHelper.handlerInvalid(handler);
                }
                catch (MetadataGuards.BlockedGuardException blocked)
                {
                    return formatGuardException(blocked, "set_event_subscription"); //$NON-NLS-1$
                }
            }
            normalizedHandler[0] = norm.normalized;
            Configuration cfg = Activator.getDefault().getConfigurationProvider()
                .getConfiguration(project);
            if (!BmEventSubscriptionHelper.commonModuleExists(cfg, norm.moduleName))
            {
                try
                {
                    throw BmEventSubscriptionHelper.commonModuleNotFound(norm.moduleName);
                }
                catch (MetadataGuards.BlockedGuardException blocked)
                {
                    return formatGuardException(blocked, "set_event_subscription"); //$NON-NLS-1$
                }
            }
        }
        final boolean eventKnown = event == null || event.isEmpty()
            || BmEventSubscriptionHelper.isKnownEvent(event);
        final String eventFinal = event;
        final String sourceFinal = source;
        java.util.concurrent.atomic.AtomicReference<BmDefinedTypeHelper.TypesResult> srcRef =
            new java.util.concurrent.atomic.AtomicReference<>();

        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, owner) -> {
                if (!"EventSubscription".equals(owner.eClass().getName())) //$NON-NLS-1$
                {
                    throw new RuntimeException("objectFqn is not an EventSubscription (" //$NON-NLS-1$
                        + owner.eClass().getName() + ")"); //$NON-NLS-1$
                }
                Configuration config = Activator.getDefault().getConfigurationProvider()
                    .getConfiguration(project);
                StringBuilder applied = new StringBuilder();
                if (sourceFinal != null && !sourceFinal.isEmpty())
                {
                    java.util.List<String> fqns = new java.util.ArrayList<>();
                    for (String t : sourceFinal.split("\\s*,\\s*")) //$NON-NLS-1$
                    {
                        if (!t.isEmpty())
                        {
                            fqns.add(t);
                        }
                    }
                    BmDefinedTypeHelper.TypesResult tr =
                        BmDefinedTypeHelper.setEventSubscriptionSource(owner, project, config, fqns);
                    srcRef.set(tr);
                    if (!tr.ok)
                    {
                        throw new RuntimeException(tr.error != null ? tr.error : "setSource failed"); //$NON-NLS-1$
                    }
                    applied.append("source=").append(tr.resolved.size()).append(' '); //$NON-NLS-1$
                }
                if (eventFinal != null && !eventFinal.isEmpty())
                {
                    org.eclipse.emf.ecore.EStructuralFeature evF =
                        owner.eClass().getEStructuralFeature("event"); //$NON-NLS-1$
                    if (evF == null)
                    {
                        throw new RuntimeException("EventSubscription has no 'event' feature"); //$NON-NLS-1$
                    }
                    owner.eSet(evF, eventFinal);
                    applied.append("event=").append(eventFinal).append(' '); //$NON-NLS-1$
                }
                if (normalizedHandler[0] != null && !normalizedHandler[0].isEmpty())
                {
                    org.eclipse.emf.ecore.EStructuralFeature hF =
                        owner.eClass().getEStructuralFeature("handler"); //$NON-NLS-1$
                    if (hF == null)
                    {
                        throw new RuntimeException("EventSubscription has no 'handler' feature"); //$NON-NLS-1$
                    }
                    owner.eSet(hF, normalizedHandler[0]);
                    applied.append("handler set"); //$NON-NLS-1$
                }
                return applied.toString().trim();
            });

        ToolResult tool = r.ok ? ToolResult.success()
            : ToolResult.error(r.error != null ? r.error : "setEventSubscription failed"); //$NON-NLS-1$
        tool.put("operation", "set_event_subscription") //$NON-NLS-1$ //$NON-NLS-2$
            .put("objectFqn", ownerFqn); //$NON-NLS-1$
        if (source != null && !source.isEmpty())
        {
            tool.put("source", source); //$NON-NLS-1$
        }
        if (event != null && !event.isEmpty())
        {
            tool.put("event", event); //$NON-NLS-1$
            if (!eventKnown)
            {
                tool.put("eventWarning", "'" + event //$NON-NLS-1$ //$NON-NLS-2$
                    + "' is not a recognized standard event; setting it anyway. " //$NON-NLS-1$
                    + BmEventSubscriptionHelper.commonEventsHint());
            }
        }
        if (handler != null && !handler.isEmpty())
        {
            tool.put("handler", normalizedHandler[0]); //$NON-NLS-1$
        }
        if (srcRef.get() != null)
        {
            BmDefinedTypeHelper.TypesResult tr = srcRef.get();
            tool.put("sourceResolved", tr.resolved) //$NON-NLS-1$
                .put("sourceUnresolved", tr.unresolved) //$NON-NLS-1$
                .put("idempotentSkip", tr.idempotentSkip); //$NON-NLS-1$
            if (!tr.unresolved.isEmpty())
            {
                tool.put("partialSource", //$NON-NLS-1$
                    "Some source FQNs could not be resolved to a produced type"); //$NON-NLS-1$
            }
        }
        EditMetadataTool.applyTags(tool, r.tags);
        return tool.toJson();
    }

    /**
     * Helper: formats a {@link MetadataGuards.BlockedGuardException} into a
     * standard ToolResult JSON envelope.
     */
    private String formatGuardException(MetadataGuards.BlockedGuardException blocked, String op)
    {
        MetadataGuards.Verdict v = blocked.verdict;
        ToolResult result = ToolResult.error(v.error != null ? v.error : "blocked")
            .put("operation", op)
            .put("hint", v.hint != null ? v.hint : "");
        if (v.tag != null)
        {
            result.put(v.tag.name, v.tag.data);
        }
        return result.toJson();
    }
}
