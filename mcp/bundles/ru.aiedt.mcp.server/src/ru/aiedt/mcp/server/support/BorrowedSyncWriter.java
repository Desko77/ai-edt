/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.md.extension.MdExtensionTypeUtil;
import com._1c.g5.v8.dt.md.resource.MdTypeUtil;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.ObjectExtension;
import com._1c.g5.v8.dt.metadata.mdclass.extension.TypeDescriptionExtension;
import com._1c.g5.v8.dt.metadata.mdclass.extension.TypeExtension;
import com._1c.g5.v8.dt.metadata.mdclass.extension.type.MdPropertyState;
import com._1c.g5.v8.dt.platform.version.Version;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.support.BorrowedSyncClassification.TypePlan;
import ru.aiedt.mcp.server.support.BorrowedSyncReader.Row;

/**
 * Writes the alignment {@code update_borrowed} decided on.
 * <p>
 * Two writes, two ways, because they are two different things. A controlled type is plain model
 * content the extension owns, so it is written directly, inside one object-model transaction per
 * owning object, and flushed to the {@code .mdo} the same way the borrow linkage flushes. The
 * EDT adopt service offers no property path for this - bytecode of {@code IModelObjectAdopter}
 * answers "not updatable" for every metadata object but a form - so the entries are assembled by
 * {@link MdExtensionTypeUtil} and set on the transaction object.
 * </p>
 * <p>
 * A form is the one thing the adopt service does update, through the same comparison merge the
 * EDT UI command runs, so the writer asks the service and writes nothing of the form itself.
 * </p>
 * <p>
 * Entries the extension added itself - state {@code Extended} - are carried over untouched in
 * every rewrite: they are the extension's own work, and aligning the controlled part is not a
 * license to drop it. Qualifiers belong to the composition as a whole and are replaced with the
 * base's, exactly what a fresh borrow of the same attribute would record.
 * </p>
 */
public final class BorrowedSyncWriter
{
    /** What one type alignment did. */
    public static final class TypeWrite
    {
        /** The address that was aligned. */
        public final String fqn;

        /** The controlled composition before the write, as text. */
        public final String was;

        /** The composition written, as text. */
        public final String now;

        /** Why nothing was written. Null when the write happened. */
        public String error;

        /** Set when the model took the write but the file did not confirm as saved. */
        public String flushNote;

        /**
         * Records one outcome.
         *
         * @param fqn the address
         * @param was the composition before
         * @param now the composition written
         */
        public TypeWrite(String fqn, String was, String now)
        {
            this.fqn = fqn;
            this.was = was;
            this.now = now;
        }
    }

    /** What one form update did. */
    public static final class FormWrite
    {
        /** The address that was updated. */
        public final String fqn;

        /** True when the adopt service updated the form. */
        public boolean updated;

        /** notUpdatable when the service declined; an error when the call failed. */
        public String status;

        /** Set when the update happened but the file did not confirm as saved. */
        public String flushNote;

        /**
         * Records one outcome.
         *
         * @param fqn the address
         */
        public FormWrite(String fqn)
        {
            this.fqn = fqn;
        }
    }

    private BorrowedSyncWriter()
    {
        // Static writer.
    }

    /**
     * Aligns the controlled types of the given rows to their bases.
     * <p>
     * All rows sharing an owning object are written in one transaction: a caller reading the
     * {@code .mdo} between two rows of one object would otherwise see the object half-aligned.
     * </p>
     *
     * @param extension the extension project
     * @param rows the typeConflict rows to write
     * @return one outcome per row, in order
     */
    public static List<TypeWrite> alignTypes(IProject extension, List<Row> rows)
    {
        List<TypeWrite> outcomes = new ArrayList<>();
        Map<String, List<Row>> byOwner = new LinkedHashMap<>();
        Map<String, String> wasByFqn = new LinkedHashMap<>();
        for (Row row : rows)
        {
            byOwner.computeIfAbsent(row.flushFqn, owner -> new ArrayList<>()).add(row);
            wasByFqn.put(row.fqn, row.controlledType);
        }
        IBmModelManager manager = Activator.getDefault().getBmModelManager();
        IBmModel model = manager == null ? null : manager.getModel(extension);
        if (model == null)
        {
            for (Row row : rows)
            {
                TypeWrite outcome = new TypeWrite(row.fqn, row.controlledType, null);
                outcome.error = "no object model for " + extension.getName(); //$NON-NLS-1$
                outcomes.add(outcome);
            }
            return outcomes;
        }
        for (Map.Entry<String, List<Row>> owner : byOwner.entrySet())
        {
            writeOwner(extension, model, manager, owner.getKey(), owner.getValue(), outcomes);
        }
        return outcomes;
    }

    /**
     * Writes one owning object's rows in one transaction and flushes the object's file.
     *
     * @param extension the extension project
     * @param model its object model
     * @param manager the model manager, for the flush
     * @param ownerFqn the owning object's address
     * @param rows the rows under it
     * @param outcomes the outcomes being collected, one entry per row
     */
    private static void writeOwner(IProject extension, IBmModel model, IBmModelManager manager,
        String ownerFqn, List<Row> rows, List<TypeWrite> outcomes)
    {
        Version version = runtimeVersion(extension);
        Map<Long, String> failedById = new LinkedHashMap<>();
        List<TypeWrite> written = new ArrayList<>();
        try
        {
            model.execute(new AbstractBmTask<Void>("updateBorrowedTypes") //$NON-NLS-1$
            {
                @Override
                public Void execute(IBmTransaction tx, IProgressMonitor monitor)
                {
                    for (Row row : rows)
                    {
                        writeRow(tx, row, version, failedById, written);
                    }
                    return null;
                }
            });
        }
        catch (Exception e)
        {
            // The transaction refused as a whole: every row of this owner reports it, and the
            // rows that claimed a write inside the failed transaction did not land.
            for (Row row : rows)
            {
                TypeWrite outcome = new TypeWrite(row.fqn, row.controlledType, null);
                outcome.error = "the transaction failed: " + e.getClass().getSimpleName() //$NON-NLS-1$
                    + ": " + e.getMessage(); //$NON-NLS-1$
                outcomes.add(outcome);
            }
            return;
        }
        List<TypeWrite> ownerOutcomes = new ArrayList<>();
        for (Row row : rows)
        {
            String error = failedById.get(Long.valueOf(row.targetId));
            TypeWrite outcome = written.stream()
                .filter(one -> one.fqn.equals(row.fqn)).findFirst().orElse(null);
            if (outcome == null)
            {
                outcome = new TypeWrite(row.fqn, row.controlledType, null);
                outcome.error = error == null ? "nothing was written and nothing said why" //$NON-NLS-1$
                    : error;
            }
            else if (error != null)
            {
                outcome.error = error;
            }
            ownerOutcomes.add(outcome);
        }
        outcomes.addAll(ownerOutcomes);
        if (written.isEmpty() || written.stream().anyMatch(one -> one.error != null))
        {
            return;
        }
        BmExportHelper.Result exported =
            BmExportHelper.forceExportAndWait(manager, extension, ownerFqn);
        if (exported == null || !exported.isOk())
        {
            for (TypeWrite outcome : ownerOutcomes)
            {
                outcome.flushNote = "written in the model, but the .mdo is not saved yet - read " //$NON-NLS-1$
                    + "it after the workspace settles"; //$NON-NLS-1$
            }
        }
        else if (exported.syncFlushPending)
        {
            for (TypeWrite outcome : ownerOutcomes)
            {
                outcome.flushNote = "written and the save is running, but it did not confirm " //$NON-NLS-1$
                    + "within the wait"; //$NON-NLS-1$
            }
        }
    }

    /**
     * Writes one row's composition inside the transaction.
     *
     * @param tx the transaction
     * @param row the row
     * @param version the runtime version the platform types are built for
     * @param failedById where a row's failure is recorded
     * @param written the outcomes of the rows that wrote
     */
    private static void writeRow(IBmTransaction tx, Row row, Version version,
        Map<Long, String> failedById, List<TypeWrite> written)
    {
        IBmObject target = tx.getObjectById(row.targetId);
        if (!(target instanceof MdObject))
        {
            failedById.put(Long.valueOf(row.targetId),
                "the attribute is not in the extension's object model"); //$NON-NLS-1$
            return;
        }
        ObjectExtension block = ((MdObject)target).getExtension();
        if (block == null)
        {
            failedById.put(Long.valueOf(row.targetId),
                "the attribute carries no extension block to write the type into"); //$NON-NLS-1$
            return;
        }
        EStructuralFeature feature =
            block.eClass().getEStructuralFeature("typeExtension"); //$NON-NLS-1$
        if (feature == null)
        {
            failedById.put(Long.valueOf(row.targetId),
                "a " + block.eClass().getName() + " keeps no type composition"); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        if (!(row.baseTypeDescription instanceof TypeDescription))
        {
            failedById.put(Long.valueOf(row.targetId),
                "the base's type description did not survive the walk"); //$NON-NLS-1$
            return;
        }
        TypeDescriptionExtension composition = buildComposition(
            (TypeDescription)row.baseTypeDescription, block, version,
            BorrowedSyncClassification.planFor(row.baseTypeSide));
        if (composition == null)
        {
            failedById.put(Long.valueOf(row.targetId),
                "the aligned composition could not be built"); //$NON-NLS-1$
            return;
        }
        block.eSet(feature, composition);
        if (block.eGet(feature) != composition)
        {
            failedById.put(Long.valueOf(row.targetId),
                "the composition did not stay on the attribute"); //$NON-NLS-1$
            return;
        }
        written.add(new TypeWrite(row.fqn, row.controlledType, rendered(row, composition)));
    }

    /**
     * Builds the composition that replaces the controlled part of a block.
     *
     * @param base the base's type description
     * @param block the extension block being rewritten
     * @param version the runtime version, for the platform's own AnyRef
     * @param plan what the base's shape calls for
     * @return the composition, or null when the base offers nothing to build from
     */
    private static TypeDescriptionExtension buildComposition(TypeDescription base,
        ObjectExtension block, Version version, TypePlan plan)
    {
        TypeDescriptionExtension composition = MdExtensionTypeUtil.newTypeDescriptionExtension();
        composition.getTypes().addAll(extendedEntries(currentComposition(block)));
        if (plan == TypePlan.ANY_REF)
        {
            composition.getTypes().add(MdExtensionTypeUtil.newTypeExtension(
                MdTypeUtil.createAnyRefType(version), MdPropertyState.CHECKED));
            return composition;
        }
        for (TypeItem item : base.getTypes())
        {
            composition.getTypes().add(MdExtensionTypeUtil.newTypeExtension(
                EcoreUtil.copy(item), MdPropertyState.CHECKED));
        }
        if (base.getStringQualifiers() != null)
        {
            composition.setStringQualifiers(EcoreUtil.copy(base.getStringQualifiers()));
        }
        if (base.getNumberQualifiers() != null)
        {
            composition.setNumberQualifiers(EcoreUtil.copy(base.getNumberQualifiers()));
        }
        if (base.getDateQualifiers() != null)
        {
            composition.setDateQualifiers(EcoreUtil.copy(base.getDateQualifiers()));
        }
        if (base.getBinaryQualifiers() != null)
        {
            composition.setBinaryQualifiers(EcoreUtil.copy(base.getBinaryQualifiers()));
        }
        return composition.getTypes().isEmpty() ? null : composition;
    }

    /**
     * The extension's own entries of a composition, as copies.
     * <p>
     * Copies, because the entries are contained by the composition: adding one to another
     * composition moves it out of this one, which shrinks the list being walked, so the entry after
     * it is skipped and lost once the old composition is replaced.
     * </p>
     *
     * @param current the composition the attribute carries
     * @return copies of its entries in the EXTENDED state, in order
     */
    static List<TypeExtension> extendedEntries(TypeDescriptionExtension current)
    {
        List<TypeExtension> entries = new ArrayList<>();
        for (Object entry : current.getTypes())
        {
            if (entry instanceof TypeExtension
                && ((TypeExtension)entry).getState() == MdPropertyState.EXTENDED)
            {
                entries.add(EcoreUtil.copy((TypeExtension)entry));
            }
        }
        return entries;
    }

    /**
     * The composition a block holds now, or an empty one when it holds none.
     *
     * @param block the extension block
     * @return the composition, never null
     */
    private static TypeDescriptionExtension currentComposition(ObjectExtension block)
    {
        Object current = block.eGet(
            block.eClass().getEStructuralFeature("typeExtension")); //$NON-NLS-1$
        return current instanceof TypeDescriptionExtension ? (TypeDescriptionExtension)current
            : MdExtensionTypeUtil.newTypeDescriptionExtension();
    }

    /**
     * Renders what the write put on the attribute, from the composition itself.
     *
     * @param row the row, for its base rendering
     * @param composition the composition written
     * @return the rendering
     */
    private static String rendered(Row row, TypeDescriptionExtension composition)
    {
        if (row.baseTypeSide != null
            && BorrowedSyncClassification.planFor(row.baseTypeSide) == TypePlan.ANY_REF)
        {
            return "AnyRef"; //$NON-NLS-1$
        }
        return row.baseType == null || row.baseType.isEmpty() ? "?" : row.baseType; //$NON-NLS-1$
    }

    /**
     * Updates one borrowed form through the EDT adopt service.
     *
     * @param extension the extension project
     * @param row the formOutOfDate row
     * @return what the update did
     */
    public static FormWrite updateForm(IProject extension, Row row)
    {
        FormWrite outcome = new FormWrite(row.fqn);
        Object adopter = BmExtensionHelper.resolveModelObjectAdopter();
        if (adopter == null)
        {
            outcome.status = "the EDT adopt service is not reachable, so the form was not " //$NON-NLS-1$
                + "updated - see the adoptServiceNotFound workaround"; //$NON-NLS-1$
            return outcome;
        }
        Object extProject = BmExtensionHelper.resolveExtensionProject(extension);
        if (extProject == null)
        {
            outcome.status = extension.getName() + " does not resolve to an extension project"; //$NON-NLS-1$
            return outcome;
        }
        if (!(row.baseForm instanceof EObject))
        {
            outcome.status = "the base form did not survive the walk"; //$NON-NLS-1$
            return outcome;
        }
        try
        {
            Method updatable = adopter.getClass().getMethod("isUpdatable", EObject.class, //$NON-NLS-1$
                Class.forName("com._1c.g5.v8.dt.core.platform.IExtensionProject")); //$NON-NLS-1$
            if (!Boolean.TRUE.equals(updatable.invoke(adopter, row.baseForm, extProject)))
            {
                outcome.status = "notUpdatable"; //$NON-NLS-1$
                return outcome;
            }
            Method update = adopter.getClass().getMethod("updateAdopted", EObject.class, //$NON-NLS-1$
                Class.forName("com._1c.g5.v8.dt.core.platform.IExtensionProject"), //$NON-NLS-1$
                IProgressMonitor.class);
            update.invoke(adopter, row.baseForm, extProject, new NullProgressMonitor());
        }
        catch (Exception e)
        {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            outcome.status = "updateAdopted failed: " + cause.getClass().getSimpleName() //$NON-NLS-1$
                + ": " + cause.getMessage(); //$NON-NLS-1$
            return outcome;
        }
        outcome.updated = true;
        IBmModelManager manager = Activator.getDefault().getBmModelManager();
        BmExportHelper.Result exported = manager == null ? null
            : BmExportHelper.forceExportAndWait(manager, extension, row.fqn + ".Form"); //$NON-NLS-1$
        if (exported == null || !exported.isOk())
        {
            outcome.flushNote = "updated in the model, but the form file is not saved yet"; //$NON-NLS-1$
        }
        else if (exported.syncFlushPending)
        {
            outcome.flushNote = "updated and the save is running, but it did not confirm " //$NON-NLS-1$
                + "within the wait"; //$NON-NLS-1$
        }
        return outcome;
    }

    /**
     * The extension's runtime version, for the platform's own type items.
     *
     * @param project the project
     * @return its runtime version, or the latest the platform knows
     */
    private static Version runtimeVersion(IProject project)
    {
        try
        {
            Version version = Activator.getDefault().getRuntimeVersionSupport()
                .getRuntimeVersion(project);
            return version == null ? Version.LATEST : version;
        }
        catch (RuntimeException | LinkageError unreachable)
        {
            // The version only picks which AnyRef instance the platform builds; the latest is
            // what a version-less runtime would answer anyway.
            return Version.LATEST;
        }
    }
}
