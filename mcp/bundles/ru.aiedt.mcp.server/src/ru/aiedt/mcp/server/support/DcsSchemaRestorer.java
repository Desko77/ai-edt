/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.bm.integration.IBmTask;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;

import ru.aiedt.mcp.server.Activator;

/**
 * Puts the schema a {@code .dcs} holds back into the model that lost it.
 * <p>
 * The file is the source and is never written. What the model holds decides what happens: nothing
 * there, and the file's schema is attached; the same bytes there, and nothing is done; something
 * else there, and the call is refused unless the caller said {@code overwriteModel}, in which case
 * the model's schema is serialized to a file beside the {@code .dcs} before it is replaced. The
 * reading, the comparison, the backup and the replacement happen inside one write transaction, and
 * the model's schema is serialized once - the same bytes serve the comparison and the backup.
 * </p>
 * <p>
 * The decision is separated from the model plumbing: {@link #restoreWithin} takes what the file
 * holds and a {@link ModelAccess}, and is what the tests exercise; {@link #restore} wires it to a
 * live model.
 * </p>
 */
public final class DcsSchemaRestorer
{
    /** How the call ended. */
    public enum Outcome
    {
        /** The model held no schema; the file's schema is attached now. */
        RESTORED,
        /** The model's schema serializes to exactly the bytes of the file; nothing was changed. */
        MATCHED,
        /** The model holds a different schema and the caller did not allow replacing it. */
        REFUSED_MODEL_DIFFERS,
        /** The model's schema was written to a backup file and replaced by the file's. */
        REPLACED,
        /** The template object the schema belongs to is not in the model. */
        NO_TEMPLATE,
        /** The {@code .dcs} changed between the read and the replacement; nothing was changed. */
        FILE_CHANGED,
        /** There is no {@code .dcs} to restore from. */
        NO_FILE,
        /** Something else stopped the call; {@link Result#error} says what. */
        FAILED
    }

    /** What the call found and did. */
    public static final class Result
    {
        public Outcome outcome = Outcome.FAILED;

        public String schemaFqn;

        public String templateFqn;

        public String filePath;

        public int fileBytes;

        /** Length of the model's serialization, or -1 when the model held no schema. */
        public int modelBytes = -1;

        /** Offset of the first byte where the model and the file disagree, or -1. */
        public int firstDifferenceAt = -1;

        public String backupPath;

        /** Whether the model, read back after the commit, serializes to the bytes of the file. */
        public boolean confirmed;

        /** Why the read-back did not confirm the restoration, or <code>null</code> when it did. */
        public String confirmation;

        /** Whether the {@code .dcs} changed while the call ran; what was restored is then stale. */
        public boolean fileChangedDuringRepair;

        /** Whether the call only decided and reported, changing nothing. */
        public boolean dryRun;

        /** A post-commit check that could not run; the outcome above stands. */
        public String warning;

        public String error;

        public long totalMs;
    }

    /**
     * The model, as the restoration needs it. Implemented over a live transaction, and by a test
     * over nothing at all.
     */
    public interface ModelAccess
    {
        /**
         * @return the template object the schema belongs to, or <code>null</code> when it is not
         *         declared
         */
        Object template();

        /**
         * @return the schema object the model holds under the schema FQN, or <code>null</code>
         */
        Object existingSchema();

        /**
         * Serializes a schema object the way the file is written.
         *
         * @param schema the schema object
         * @return the bytes
         * @throws Exception when the serializer refuses
         */
        byte[] serialize(Object schema) throws Exception;

        /**
         * Detaches a schema object from the model.
         *
         * @param schema the object the model holds
         */
        void detach(Object schema);

        /**
         * Attaches a schema object to the model under the schema FQN.
         *
         * @param schema the object to attach
         */
        void attach(Object schema);

        /**
         * Points the template at a schema object.
         *
         * @param template the template object
         * @param schema the schema object
         * @throws Exception when the template has no such setter
         */
        void setTemplate(Object template, Object schema) throws Exception;

        /**
         * Writes the model's schema aside before it is replaced.
         *
         * @param bytes the serialization of the schema about to be replaced
         * @return where it was written
         * @throws IOException when it could not be written, which cancels the replacement
         */
        Path writeBackup(byte[] bytes) throws IOException;

        /**
         * Reads the {@code .dcs} as it is right now, so a file that changed after the first read
         * is noticed before anything is attached from the stale bytes.
         *
         * @return the current bytes of the file
         * @throws IOException when the file cannot be read
         */
        byte[] fileNow() throws IOException;
    }

    /** What {@link #restoreWithin} decided and did, before the commit. */
    public static final class Step
    {
        public Outcome outcome;

        public int modelBytes = -1;

        public int firstDifferenceAt = -1;

        public Path backup;

        public String error;
    }

    private static final DateTimeFormatter BACKUP_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"); //$NON-NLS-1$

    private static final String SCHEMA_SUFFIX = ".Template"; //$NON-NLS-1$

    private DcsSchemaRestorer()
    {
        // Static entry points only.
    }

    /**
     * Decides and acts, given what the file holds and how the model looks.
     * <p>
     * Every branch reads the model at most once and serializes its schema at most once. There is
     * no second read to compare the first against: what was compared is what is replaced.
     * </p>
     *
     * @param fileBytes the bytes of the {@code .dcs}
     * @param fromFile the schema object read out of those bytes
     * @param overwriteModel whether a differing schema in the model may be replaced
     * @param model the model
     * @return what was decided and done
     */
    public static Step restoreWithin(byte[] fileBytes, Object fromFile, boolean overwriteModel, ModelAccess model)
    {
        Step step = new Step();
        Object template = model.template();
        if (template == null)
        {
            step.outcome = Outcome.NO_TEMPLATE;
            return step;
        }
        Object existing = model.existingSchema();
        try
        {
            if (existing == null)
            {
                if (!Arrays.equals(fileBytes, model.fileNow()))
                {
                    step.outcome = Outcome.FILE_CHANGED;
                    return step;
                }
                model.attach(fromFile);
                model.setTemplate(template, fromFile);
                step.outcome = Outcome.RESTORED;
                return step;
            }
            byte[] modelBytes = model.serialize(existing);
            step.modelBytes = modelBytes.length;
            if (Arrays.equals(modelBytes, fileBytes))
            {
                step.outcome = Outcome.MATCHED;
                return step;
            }
            step.firstDifferenceAt = Arrays.mismatch(modelBytes, fileBytes);
            if (!overwriteModel)
            {
                step.outcome = Outcome.REFUSED_MODEL_DIFFERS;
                return step;
            }
            if (!Arrays.equals(fileBytes, model.fileNow()))
            {
                step.outcome = Outcome.FILE_CHANGED;
                return step;
            }
            // The backup is these same bytes: what was compared is what is kept aside. A backup
            // that cannot be written cancels the replacement.
            step.backup = model.writeBackup(modelBytes);
            model.detach(existing);
            model.attach(fromFile);
            model.setTemplate(template, fromFile);
            step.outcome = Outcome.REPLACED;
            return step;
        }
        catch (Exception failed)
        {
            step.outcome = Outcome.FAILED;
            step.error = describe(failed);
            return step;
        }
    }

    /**
     * Restores a schema from its {@code .dcs} into the live model.
     *
     * @param manager the model manager
     * @param project the project
     * @param schemaFqn the schema FQN, {@code <Type>.<Object>.Template.<Name>.Template}
     * @param overwriteModel whether a differing schema in the model may be replaced
     * @return what was found and done
     */
    public static Result restore(IBmModelManager manager, IProject project, String schemaFqn, boolean overwriteModel)
    {
        return restore(manager, project, schemaFqn, overwriteModel, false);
    }

    /**
     * The FQN of the template object a schema belongs to.
     *
     * @param schemaFqn the schema FQN, {@code <Type>.<Object>.Template.<Name>.Template}
     * @return the template FQN, or <code>null</code> when the schema FQN has another shape
     */
    public static String templateFqnOf(String schemaFqn)
    {
        if (schemaFqn == null || !schemaFqn.endsWith(SCHEMA_SUFFIX)
            || schemaFqn.length() == SCHEMA_SUFFIX.length())
        {
            return null;
        }
        return schemaFqn.substring(0, schemaFqn.length() - SCHEMA_SUFFIX.length());
    }

    /**
     * Restores a schema from its {@code .dcs} into the live model, or only says what it would do.
     *
     * @param manager the model manager
     * @param project the project
     * @param schemaFqn the schema FQN, {@code <Type>.<Object>.Template.<Name>.Template}
     * @param overwriteModel whether a differing schema in the model may be replaced
     * @param dryRun whether to decide and report without changing the model or writing a backup
     * @return what was found and done
     */
    public static Result restore(IBmModelManager manager, IProject project, String schemaFqn, boolean overwriteModel,
        boolean dryRun)
    {
        Result r = new Result();
        long started = System.currentTimeMillis();
        r.schemaFqn = schemaFqn;
        r.dryRun = dryRun;
        Step[] step = new Step[1];
        try
        {
            if (manager == null || project == null || schemaFqn == null || schemaFqn.isEmpty())
            {
                r.error = "manager, project and schemaFqn are required"; //$NON-NLS-1$
                return r;
            }
            r.templateFqn = templateFqnOf(schemaFqn);
            if (r.templateFqn == null)
            {
                r.error = "schemaFqn has to end with .Template: " + schemaFqn; //$NON-NLS-1$
                return r;
            }
            IFile dcs = DcsExtensionExportHelper.locateDcsFile(project, schemaFqn);
            if (dcs == null || !dcs.exists())
            {
                r.outcome = Outcome.NO_FILE;
                r.filePath = dcs == null ? null : dcs.getFullPath().toOSString();
                r.error = "no .dcs on disk" + (dcs == null ? "" : " at " + r.filePath); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                return r;
            }
            r.filePath = dcs.getFullPath().toOSString();
            byte[] fileBefore = read(dcs);
            r.fileBytes = fileBefore.length;
            IBmModel model = manager.getModel(project);
            if (model == null)
            {
                r.error = "BM model not available for " + project.getName(); //$NON-NLS-1$
                return r;
            }
            try (DcsExtensionExportHelper.SchemaSerializer serializer =
                DcsExtensionExportHelper.SchemaSerializer.open(manager, project))
            {
                Object fromFile = serializer.deserialize(fileBefore);
                if (!(fromFile instanceof IBmObject))
                {
                    r.error = "the .dcs did not read as a model object"; //$NON-NLS-1$
                    return r;
                }
                Path backupTarget = backupPathBeside(dcs);
                AbstractBmTask<Void> task = new AbstractBmTask<Void>("repair_schema") //$NON-NLS-1$
                {
                    @Override
                    public Void execute(IBmTransaction tx, IProgressMonitor monitor)
                    {
                        ModelAccess live = new LiveModel(tx, r.templateFqn, schemaFqn, serializer, backupTarget, dcs);
                        step[0] = restoreWithin(fileBefore, fromFile, overwriteModel,
                            dryRun ? new PreviewModel(live) : live);
                        if (step[0].outcome == Outcome.FAILED)
                        {
                            // Rolls the transaction back: nothing half-done stays in the model.
                            throw new IllegalStateException(step[0].error);
                        }
                        return null;
                    }
                };
                if (dryRun)
                {
                    // Reads and decides; the preview model turns every mutation into a note.
                    model.executeReadonlyTask((IBmTask<Void>)task);
                }
                else
                {
                    // Through the global editing context, as every attach of a top object in this
                    // plugin: a schema attached in a plain task leaves its reference deferred and
                    // the commit fails with "Failed to persist reference value".
                    model.getGlobalContext().execute((IBmTask<Void>)task);
                }
                r.outcome = step[0].outcome;
                r.modelBytes = step[0].modelBytes;
                r.firstDifferenceAt = step[0].firstDifferenceAt;
                r.backupPath = step[0].backup == null ? null : step[0].backup.toString();
                // What happened is settled here; the checks below can only add to the answer. A
                // read that fails after the commit must not turn a committed change into FAILED.
                if (!dryRun && (r.outcome == Outcome.RESTORED || r.outcome == Outcome.REPLACED
                    || r.outcome == Outcome.MATCHED))
                {
                    r.confirmation = readsBackAs(model, schemaFqn, serializer, fileBefore);
                    r.confirmed = r.confirmation == null;
                }
                try
                {
                    byte[] fileAfter = read(dcs);
                    r.fileChangedDuringRepair = !Arrays.equals(fileBefore, fileAfter);
                }
                catch (Exception | LinkageError cannotReread)
                {
                    r.warning = "the .dcs could not be read back after the call: " + describe(cannotReread); //$NON-NLS-1$
                }
            }
        }
        catch (Exception | LinkageError failed)
        {
            r.outcome = Outcome.FAILED;
            r.error = describe(failed);
            if (step[0] != null && step[0].backup != null)
            {
                // Written before the replacement that then failed: it stays, and the answer says
                // where, rather than leaving a file nobody can find.
                r.backupPath = step[0].backup.toString();
            }
            Activator.logWarning("repair_schema failed for " + schemaFqn + ": " + r.error); //$NON-NLS-1$ //$NON-NLS-2$
        }
        finally
        {
            r.totalMs = System.currentTimeMillis() - started;
        }
        return r;
    }

    /**
     * Whether the model, read in a task of its own after the commit, serializes to the file.
     * <p>
     * A commit that returned is not a schema that is there: the confirmation reads what a later
     * caller will read. A serializer that refuses and a schema that differs are two different
     * answers, and the reason says which.
     * </p>
     *
     * @return <code>null</code> when the model serializes to the file, otherwise why not
     */
    private static String readsBackAs(IBmModel model, String schemaFqn,
        DcsExtensionExportHelper.SchemaSerializer serializer, byte[] expected)
    {
        try
        {
            String[] reason = new String[1];
            model.executeReadonlyTask(new AbstractBmTask<Void>("repair_schema.confirm") //$NON-NLS-1$
            {
                @Override
                public Void execute(IBmTransaction tx, IProgressMonitor monitor)
                {
                    try
                    {
                        IBmObject top = tx.getTopObjectByFqn(schemaFqn);
                        if (top == null)
                        {
                            reason[0] = "the model holds no schema under " + schemaFqn + " after the commit"; //$NON-NLS-1$ //$NON-NLS-2$
                            return null;
                        }
                        byte[] seen = serializer.serialize(top);
                        if (!Arrays.equals(seen, expected))
                        {
                            reason[0] = "the model read back after the commit serializes to " + seen.length //$NON-NLS-1$
                                + " bytes that differ from the file at byte " + Arrays.mismatch(seen, expected); //$NON-NLS-1$
                        }
                    }
                    catch (Exception | LinkageError failed)
                    {
                        reason[0] = "the model could not be serialized after the commit: " + describe(failed); //$NON-NLS-1$
                    }
                    return null;
                }
            });
            return reason[0];
        }
        catch (RuntimeException | LinkageError failed)
        {
            // Whatever stops the read-back is a reason the change is unconfirmed, never a reason to
            // call the committed change failed.
            return "the confirmation read failed: " + describe(failed); //$NON-NLS-1$
        }
    }

    private static Path backupPathBeside(IFile dcs)
    {
        Path onDisk = dcs.getLocation() == null ? null : dcs.getLocation().toFile().toPath();
        if (onDisk == null)
        {
            return null;
        }
        return onDisk.resolveSibling(onDisk.getFileName() + ".model-" //$NON-NLS-1$
            + LocalDateTime.now().format(BACKUP_STAMP) + ".bak"); //$NON-NLS-1$
    }

    private static byte[] read(IFile file) throws Exception
    {
        try (InputStream in = file.getContents(true))
        {
            ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) != -1)
            {
                out.write(chunk, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static String describe(Throwable failure)
    {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause)
        {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null || message.isEmpty() ? "" : ": " + message); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The live model behind a transaction.
     */
    private static final class LiveModel
        implements ModelAccess
    {
        private final IBmTransaction tx;

        private final String templateFqn;

        private final String schemaFqn;

        private final DcsExtensionExportHelper.SchemaSerializer serializer;

        private final Path backupTarget;

        private final IFile dcs;

        LiveModel(IBmTransaction tx, String templateFqn, String schemaFqn,
            DcsExtensionExportHelper.SchemaSerializer serializer, Path backupTarget, IFile dcs)
        {
            this.tx = tx;
            this.templateFqn = templateFqn;
            this.schemaFqn = schemaFqn;
            this.serializer = serializer;
            this.backupTarget = backupTarget;
            this.dcs = dcs;
        }

        @Override
        public Object template()
        {
            return tx.getTopObjectByFqn(templateFqn);
        }

        @Override
        public Object existingSchema()
        {
            return tx.getTopObjectByFqn(schemaFqn);
        }

        @Override
        public byte[] serialize(Object schema) throws Exception
        {
            return serializer.serialize(schema);
        }

        @Override
        public void detach(Object schema)
        {
            tx.detachTopObject((IBmObject)schema);
        }

        @Override
        public void attach(Object schema)
        {
            tx.attachTopObject((IBmObject)schema, schemaFqn);
        }

        @Override
        public void setTemplate(Object template, Object schema) throws Exception
        {
            for (Method candidate : template.getClass().getMethods())
            {
                if ("setTemplate".equals(candidate.getName()) && candidate.getParameterCount() == 1 //$NON-NLS-1$
                    && candidate.getParameterTypes()[0].isInstance(schema))
                {
                    candidate.invoke(template, schema);
                    return;
                }
            }
            throw new NoSuchMethodException(template.getClass().getSimpleName()
                + " has no setTemplate taking " + schema.getClass().getSimpleName()); //$NON-NLS-1$
        }

        @Override
        public Path writeBackup(byte[] bytes) throws IOException
        {
            if (backupTarget == null)
            {
                throw new IOException("the .dcs has no location on disk to put a backup beside"); //$NON-NLS-1$
            }
            Path target = backupTarget;
            for (int attempt = 1; attempt < 100; attempt++)
            {
                try
                {
                    Files.write(target, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                    return target;
                }
                catch (java.nio.file.FileAlreadyExistsException taken)
                {
                    target = backupTarget.resolveSibling(backupTarget.getFileName() + "-" + attempt); //$NON-NLS-1$
                }
            }
            throw new IOException("no free name for the backup beside " + backupTarget); //$NON-NLS-1$
        }

        @Override
        public byte[] fileNow() throws IOException
        {
            try
            {
                return read(dcs);
            }
            catch (IOException cannotRead)
            {
                throw cannotRead;
            }
            catch (Exception cannotRead)
            {
                throw new IOException(describe(cannotRead), cannotRead);
            }
        }
    }

    /**
     * A model that reads and decides but changes nothing: every mutation becomes a note of what
     * would have been done, and the backup is named but not written.
     */
    private static final class PreviewModel
        implements ModelAccess
    {
        private final ModelAccess live;

        PreviewModel(ModelAccess live)
        {
            this.live = live;
        }

        @Override
        public Object template()
        {
            return live.template();
        }

        @Override
        public Object existingSchema()
        {
            return live.existingSchema();
        }

        @Override
        public byte[] serialize(Object schema) throws Exception
        {
            return live.serialize(schema);
        }

        @Override
        public void detach(Object schema)
        {
            // Preview: not done.
        }

        @Override
        public void attach(Object schema)
        {
            // Preview: not done.
        }

        @Override
        public void setTemplate(Object template, Object schema)
        {
            // Preview: not done.
        }

        @Override
        public Path writeBackup(byte[] bytes)
        {
            return ((LiveModel)live).backupTarget;
        }

        @Override
        public byte[] fileNow() throws IOException
        {
            return live.fileNow();
        }
    }
}
