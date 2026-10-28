/**
 * AI-EDT Ordinary Forms
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform.session;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.operations.IOperationHistory;
import org.eclipse.core.commands.operations.IUndoContext;
import org.eclipse.core.commands.operations.IUndoableOperation;
import org.eclipse.core.commands.operations.ObjectUndoContext;
import org.eclipse.core.commands.operations.OperationHistoryFactory;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceRuleFactory;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;

import ru.aiedt.mcp.oform.BraceTree;
import ru.aiedt.mcp.oform.BraceTree.ListNode;
import ru.aiedt.mcp.oform.ConfigurationNames;
import ru.aiedt.mcp.oform.LayoutOperations;
import ru.aiedt.mcp.oform.LayoutOperations.Applied;
import ru.aiedt.mcp.oform.LayoutOperations.Target;
import ru.aiedt.mcp.oform.OrdinaryFormFile;
import ru.aiedt.mcp.oform.OrdinaryFormLocator;
import ru.aiedt.mcp.oform.OrdinaryFormModule;
import ru.aiedt.mcp.oform.OwnerModel;
import ru.aiedt.mcp.oform.OwnerModelReader;
import ru.aiedt.mcp.oform.Pacing;
import ru.aiedt.mcp.oform.V8Container;
import ru.aiedt.mcp.oform.layout.ElementKinds;
import ru.aiedt.mcp.oform.layout.FormEvents;
import ru.aiedt.mcp.oform.layout.FormLayout;
import ru.aiedt.mcp.oform.layout.LayoutEditor;
import ru.aiedt.mcp.oform.layout.LayoutNames;
import ru.aiedt.mcp.oform.layout.LayoutReader;
import ru.aiedt.mcp.oform.layout.TypePattern;
import ru.aiedt.mcp.oform.ops.DataPaths;
import ru.aiedt.mcp.oform.ops.OperationSpec;
import ru.aiedt.mcp.oform.ops.OperationsCatalog;

/**
 * One editing session over one {@code Form.oform}, shared by every editor that has the file
 * open ({@link OpenFormSessions} hands it out and counts the editors).
 *
 * <p>The session keeps a <b>baseline</b> - the file's bytes as they were read, with the two
 * texts - and a <b>current</b> state: the layout text and the module text it holds, each
 * {@code null} while no edit has moved it off the baseline. Every state change - an edit, a
 * save, a reconciliation - runs under the file's lock of the registry and over a fresh copy of
 * the tree, so a refusal or a throw leaves the current state exactly as it was, and a save
 * never lands over bytes the session has not seen: the file is compared with the baseline in
 * full first, and what a write left - its bytes, its modification stamp - becomes the next
 * baseline inside the same lock.</p>
 *
 * <p>An edit is the operation's own handler out of {@link OperationsCatalog}, the same one the
 * MCP tool calls - the session only builds the target from its current texts, so the stubs of
 * earlier edits are in the module the next handler reads. A layout version outside the measured
 * set, or a container that will not read, puts the session into read-only: edits and saves are
 * refused until a reconciliation finds a readable container again.</p>
 *
 * <p>Each edit that changes a text is one record of the workspace operation history, on this
 * session's own undo context. Undo and redo run under the same lock and put back only the
 * entries that record touched. The history is disposed when the last editor closes, when the
 * layout is reread, and when a foreign module write meets a record that touched the module.</p>
 */
public final class FormEditingSession
{
    /** How many operations one session's undo history keeps. */
    public static final int HISTORY_LIMIT = 100;

    /** The line shown when a foreign module write drops a history that touched the module. */
    static final String HISTORY_CLEARED = "история отмены очищена: модуль изменен вне редактора"; //$NON-NLS-1$

    /** The standard attribute names a data path can name under the form's main attribute. */
    private static final List<String> STANDARD_ATTRIBUTE_NAMES = List.of("Код", "Наименование", "Родитель", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "Владелец", "Номер", "Дата", "Выполнена", "НомерОтправленного", "НомерПринятого", "ТипЗначения", "Период"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$

    /** The kinds {@code add_element} refuses a data path for. */
    private static final Set<String> KINDS_WITHOUT_DATA = Set.of("TableField", "FieldHtml", "TextDocumentField", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "Chart"); //$NON-NLS-1$

    private final String id;

    /** This session's undo context. The history keeps {@link #HISTORY_LIMIT} records of it. */
    private final IUndoContext undoContext;

    /** The reconciliation an undo or redo in progress reached; {@code null} when the baseline held. */
    private Reconciliation reconciledDuringStep;

    private final IFile file;

    private final OrdinaryFormModule module;

    private final String fqn;

    private final String ownerKind;

    /** The file's bytes as the session last saw them written. */
    private byte[] baseBytes;

    /** The modification stamp that went with those bytes. */
    private long baseStamp;

    private String baseForm;

    private String baseModule;

    /** The session's layout text, or {@code null} while no edit moved it off the baseline. */
    private String formText;

    /** The session's module text, or {@code null} while no edit moved it off the baseline. */
    private String moduleText;

    private boolean readOnly;

    private String problem;

    FormEditingSession(IFile file)
    {
        this.id = UUID.randomUUID().toString();
        this.undoContext = new ObjectUndoContext(this);
        OperationHistoryFactory.getOperationHistory().setLimit(undoContext, HISTORY_LIMIT);
        this.file = file;
        IProject project = file.getProject();
        String relative = file.getProjectRelativePath().toString().replace('\\', '/');
        OrdinaryFormModule located = OrdinaryFormModule.locate(project, OrdinaryFormModule.moduleAddressOf(relative));
        this.module = located;
        this.fqn = located == null ? fqnOf(file) : located.fqn();
        String[] parts = fqn == null ? new String[0] : fqn.split("\\."); //$NON-NLS-1$
        this.ownerKind = parts.length == 4 ? parts[0] : null;
        try
        {
            this.baseBytes = readBytes();
            OrdinaryFormFile read = OrdinaryFormFile.of(null, V8Container.read(baseBytes), baseBytes);
            this.baseForm = read.formText();
            this.baseModule = read.moduleText();
            this.baseStamp = file.getModificationStamp();
            if (baseForm == null)
            {
                refuse("the container of " + file.getProjectRelativePath() + " holds no layout entry."); //$NON-NLS-1$ //$NON-NLS-2$
            }
            else if (!versionMeasured(baseForm))
            {
                refuse("the layout version " + OrdinaryFormFile.formVersionOf(baseForm) //$NON-NLS-1$
                    + " is outside the measured set 25, 26, 27."); //$NON-NLS-1$
            }
        }
        catch (IOException | IllegalArgumentException e)
        {
            this.baseBytes = new byte[0];
            refuse(e.getMessage());
        }
    }

    /** @return the session's identifier, which a write path takes as its initiator */
    public String id()
    {
        return id;
    }

    /** @return the container the session edits */
    public IFile file()
    {
        return file;
    }

    /** @return the form's FQN, or {@code null} when its path is not a form's */
    public String fqn()
    {
        return fqn;
    }

    /** @return whether the session is read-only: no edit and no save while it is */
    public boolean readOnly()
    {
        return readOnly;
    }

    /** @return why the session is read-only, or {@code null} when it is not */
    public String problem()
    {
        return problem;
    }

    /** @return whether the session's texts differ from the baseline */
    public boolean isDirty()
    {
        return formChanged() || moduleChanged();
    }

    /** @return whether the session's layout text differs from the baseline */
    public boolean formChanged()
    {
        return formText != null && !formText.equals(baseForm);
    }

    /** @return whether the session's module text differs from the baseline */
    public boolean moduleChanged()
    {
        return moduleText != null && !moduleText.equals(baseModule);
    }

    // ---- the edit --------------------------------------------------------------------------

    /**
     * Applies a layout operation to the session. The handler is the operation's own, from
     * {@link OperationsCatalog}; it runs over a fresh copy of the current tree, so a refusal or a
     * throw leaves the session exactly as it was. A file the session has not seen - a write that
     * landed with no event processed yet - is not edited over: the session reconciles itself and
     * answers with what that found.
     *
     * @param operation the operation's name, one of the writing layout operations
     * @param params the operation's arguments, as the tool takes them
     * @return what the edit did
     */
    public Edit edit(String operation, Map<String, String> params)
    {
        OperationSpec spec = OperationsCatalog.find(operation);
        if (spec == null || spec.kind != OperationSpec.Kind.LAYOUT)
        {
            return Edit.refused(operation + " is not a layout operation of ordinary_forms."); //$NON-NLS-1$
        }
        OpenFormSessions.SessionLock lock = OpenFormSessions.lockOf(file);
        lock.lock();
        try
        {
            if (readOnly)
            {
                return Edit.refused(problem);
            }
            if (!baselineHolds())
            {
                Reconciliation reconciliation = reconcileLocked();
                String what = reconciliation.message != null ? reconciliation.message : reconciliation.outcome;
                return new Edit(false, "the form changed outside the editor: " + what, null, null, //$NON-NLS-1$
                    reconciliation.outcome);
            }
            Applied applied;
            try
            {
                applied = LayoutOperations.applyTo(spec, target(), params);
            }
            catch (IOException e)
            {
                return Edit.refused(e.getMessage());
            }
            catch (RuntimeException e)
            {
                return Edit.refused(e.getMessage());
            }
            if (applied.refusedVersion != null || !applied.done)
            {
                return new Edit(false, applied.message, applied.refusedVersion, null, null);
            }
            String beforeForm = currentFormText();
            String beforeModule = currentModuleText();
            Pacing.pauseAt(Pacing.BEFORE_EDIT_FIXATION);
            formText = applied.layoutText;
            if (applied.moduleText != null)
            {
                moduleText = applied.moduleText;
            }
            record(beforeForm, beforeModule);
            return new Edit(true, applied.message, null, applied.elementId, null);
        }
        finally
        {
            lock.unlock();
        }
    }

    /**
     * Undoes the newest record of this session. Nothing moves when the session is read-only or
     * the history has no record to undo. A file the session has not seen is reconciled under the
     * same lock before the record is applied, and the record is not applied when the current
     * text is no longer the one it left.
     *
     * @return whether the record was undone, and the reconciliation when one ran
     */
    public Reversal undo()
    {
        return step(true);
    }

    /**
     * Redoes the newest undone record of this session. Nothing moves when the session is
     * read-only or the history has no record to redo.
     *
     * @return whether the record was redone, and the reconciliation when one ran
     */
    public Reversal redo()
    {
        return step(false);
    }

    /**
     * Applies several layout operations as one change: one baseline check, one fixation and one
     * history record. A refusal or a throw of any step leaves the session exactly as it was, and
     * no record is kept.
     *
     * @param steps the operations, in order; each is one {@link OperationsCatalog} layout operation
     * @return what the last step did, when every step was fixed
     */
    public Edit gesture(List<Step> steps)
    {
        if (steps == null || steps.isEmpty())
        {
            return Edit.refused("a gesture names no operation."); //$NON-NLS-1$
        }
        OpenFormSessions.SessionLock lock = OpenFormSessions.lockOf(file);
        lock.lock();
        try
        {
            if (readOnly)
            {
                return Edit.refused(problem);
            }
            if (!baselineHolds())
            {
                Reconciliation reconciliation = reconcileLocked();
                String what = reconciliation.message != null ? reconciliation.message : reconciliation.outcome;
                return new Edit(false, "the form changed outside the editor: " + what, null, null, //$NON-NLS-1$
                    reconciliation.outcome);
            }
            String beforeForm = currentFormText();
            String beforeModule = currentModuleText();
            Target target;
            try
            {
                target = target();
            }
            catch (IOException e)
            {
                return Edit.refused(e.getMessage());
            }
            Applied last = null;
            String moduleKept = null;
            for (Step step : steps)
            {
                OperationSpec spec = OperationsCatalog.find(step.operation);
                if (spec == null || spec.kind != OperationSpec.Kind.LAYOUT)
                {
                    return Edit.refused(step.operation + " is not a layout operation of ordinary_forms."); //$NON-NLS-1$
                }
                Applied applied;
                try
                {
                    applied = LayoutOperations.applyTo(spec, target, step.params);
                }
                catch (RuntimeException e)
                {
                    return Edit.refused(e.getMessage());
                }
                if (applied.refusedVersion != null || !applied.done)
                {
                    return new Edit(false, applied.message, applied.refusedVersion, null, null);
                }
                last = applied;
                if (applied.moduleText != null)
                {
                    moduleKept = applied.moduleText;
                }
            }
            if (last == null)
            {
                return Edit.refused("a gesture names no operation."); //$NON-NLS-1$
            }
            Pacing.pauseAt(Pacing.BEFORE_EDIT_FIXATION);
            formText = last.layoutText;
            if (moduleKept != null)
            {
                moduleText = moduleKept;
            }
            record(beforeForm, beforeModule);
            return new Edit(true, last.message, null, last.elementId, null);
        }
        finally
        {
            lock.unlock();
        }
    }

    /** @return this session's undo context */
    public IUndoContext undoContext()
    {
        return undoContext;
    }

    /** @return whether this session has a record to undo */
    boolean canUndo()
    {
        return history().canUndo(undoContext);
    }

    /** @return whether this session has a record to redo */
    boolean canRedo()
    {
        return history().canRedo(undoContext);
    }

    /**
     * The records waiting to be undone, oldest first.
     *
     * @return the records
     */
    LayoutChangeOperation[] undoRecords()
    {
        return records(history().getUndoHistory(undoContext));
    }

    /**
     * The records waiting to be redone, oldest first.
     *
     * @return the records
     */
    LayoutChangeOperation[] redoRecords()
    {
        return records(history().getRedoHistory(undoContext));
    }

    /** The history's records of this context, when every one of them is a layout change. */
    private static LayoutChangeOperation[] records(IUndoableOperation[] operations)
    {
        LayoutChangeOperation[] records = new LayoutChangeOperation[operations.length];
        for (int index = 0; index < operations.length; index++)
        {
            if (!(operations[index] instanceof LayoutChangeOperation record))
            {
                throw new IllegalStateException(operations[index].getClass().getName());
            }
            records[index] = record;
        }
        return records;
    }

    /** Undo or redo under the registry lock, through the operation history. */
    private Reversal step(boolean undoing)
    {
        OpenFormSessions.SessionLock lock = OpenFormSessions.lockOf(file);
        lock.lock();
        try
        {
            if (readOnly)
            {
                return new Reversal(false, problem, null);
            }
            IOperationHistory history = history();
            boolean available = undoing ? history.canUndo(undoContext) : history.canRedo(undoContext);
            if (!available)
            {
                return new Reversal(false, null, null);
            }
            reconciledDuringStep = null;
            IStatus status;
            try
            {
                status = undoing ? history.undo(undoContext, new NullProgressMonitor(), null)
                    : history.redo(undoContext, new NullProgressMonitor(), null);
            }
            catch (ExecutionException e)
            {
                return new Reversal(false, e.getMessage(), null);
            }
            Reconciliation seen = reconciledDuringStep;
            String outcome = seen == null ? null : seen.outcome;
            if (status.isOK())
            {
                return new Reversal(true, seen == null ? null : seen.message, outcome);
            }
            String message = status.getMessage();
            if ((message == null || message.isEmpty()) && seen != null)
            {
                message = seen.message != null ? seen.message : seen.outcome;
            }
            return new Reversal(false, message, outcome);
        }
        finally
        {
            lock.unlock();
        }
    }

    /**
     * What a preview of an operation would do, without fixing anything: the operation runs over
     * a fresh copy of the current tree and the elements it would take away and those it would
     * change are named - the cascade of a removal, the panels a cascade clears.
     *
     * @param operation the operation's name
     * @param params the operation's arguments
     * @return the preview; nothing of the session moves
     */
    public Preview preview(String operation, Map<String, String> params)
    {
        OperationSpec spec = OperationsCatalog.find(operation);
        if (spec == null || spec.kind != OperationSpec.Kind.LAYOUT)
        {
            return Preview.refused(operation + " is not a layout operation of ordinary_forms.", null); //$NON-NLS-1$
        }
        OpenFormSessions.SessionLock lock = OpenFormSessions.lockOf(file);
        lock.lock();
        try
        {
            if (readOnly)
            {
                return Preview.refused(problem, null);
            }
            if (!baselineHolds())
            {
                return Preview.refused("the form changed outside the editor; reconcile it first.", null); //$NON-NLS-1$
            }
            FormLayout before = currentLayout();
            Applied applied;
            try
            {
                applied = LayoutOperations.applyTo(spec, target(), params);
            }
            catch (IOException e)
            {
                return Preview.refused(e.getMessage(), null);
            }
            catch (RuntimeException e)
            {
                return Preview.refused(e.getMessage(), null);
            }
            if (applied.refusedVersion != null || !applied.done)
            {
                return Preview.refused(applied.message, applied.refusedVersion);
            }
            FormLayout after = LayoutReader.read(BraceTree.parse(applied.layoutText), ownerKind);
            return changedAgainst(before, after, applied.message);
        }
        finally
        {
            lock.unlock();
        }
    }

    /** Names the elements one tree holds that the other does not, and those both hold altered. */
    private static Preview changedAgainst(FormLayout before, FormLayout after, String message)
    {
        Map<String, String> signatures = new LinkedHashMap<>();
        for (FormLayout.Element element : after.allElements())
        {
            signatures.put(element.id, signature(element));
        }
        List<String> removed = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        for (FormLayout.Element element : before.allElements())
        {
            String name = element.name != null ? element.name : element.id;
            String signature = signatures.get(element.id);
            if (signature == null)
            {
                removed.add(name);
            }
            else if (!signature.equals(signature(element)))
            {
                changed.add(name);
            }
        }
        return new Preview(true, message, null, List.copyOf(removed), List.copyOf(changed));
    }

    /** Everything of an element the reading yields, as one comparable string. */
    private static String signature(FormLayout.Element element)
    {
        StringBuilder out = new StringBuilder(element.type);
        out.append('|').append(element.name).append('|').append(element.title).append('|').append(element.tooltip)
            .append('|').append(Arrays.toString(element.box)).append('|').append(element.page).append('|') //$NON-NLS-1$
            .append(element.data).append('|').append(element.valueType);
        for (FormLayout.Handler handler : element.handlers)
        {
            out.append("|h:").append(handler.procedure); //$NON-NLS-1$
        }
        for (FormLayout.Button button : element.buttons)
        {
            out.append("|b:").append(button.name).append(':').append(button.procedure); //$NON-NLS-1$
        }
        for (FormLayout.Page page : element.pages)
        {
            out.append("|p:").append(page.name); //$NON-NLS-1$
        }
        for (FormLayout.Column column : element.columns)
        {
            out.append("|c:").append(column.name).append(':').append(column.width).append(':').append(column.control); //$NON-NLS-1$
        }
        out.append("|d:").append(element.details); //$NON-NLS-1$
        for (FormLayout.Element child : element.children)
        {
            out.append("|[").append(signature(child)).append(']'); //$NON-NLS-1$
        }
        return out.toString();
    }

    // ---- the save --------------------------------------------------------------------------

    /**
     * Saves the session's texts into the container. The file is compared with the baseline in
     * full first - a save never lands over bytes the session has not seen - and what the write
     * left, its bytes and its modification stamp, becomes the next baseline inside the same
     * lock, so no reader can observe the file between the write and the baseline. Nothing is
     * written and no owner is marked when the session is clean, and the write goes through
     * {@link OrdinaryFormModule#writeEntriesReturning} under the workspace's rule on the file,
     * the rule every other write path takes as well.
     *
     * @return what the save did
     */
    public Save save()
    {
        if (readOnly)
        {
            return new Save(false, null, problem);
        }
        if (module == null)
        {
            return new Save(false, null, "the container does not lie at a form module's address."); //$NON-NLS-1$
        }
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        IResourceRuleFactory rules = workspace.getRuleFactory();
        Save[] out = new Save[1];
        IWorkspaceRunnable work = monitor -> {
            OpenFormSessions.SessionLock lock = OpenFormSessions.lockOf(file);
            lock.lock();
            try
            {
                out[0] = saveLocked();
            }
            catch (IOException e)
            {
                out[0] = new Save(false, null, e.getMessage());
            }
            finally
            {
                lock.unlock();
            }
        };
        try
        {
            workspace.run(work, rules.modifyRule(file), IResource.NONE, new NullProgressMonitor());
        }
        catch (CoreException e)
        {
            return new Save(false, null, e.getMessage());
        }
        Save save = out[0];
        if (save.written)
        {
            String delivery = module.afterWrite(true);
            return new Save(true, delivery, save.reconciliation);
        }
        return save;
    }

    private Save saveLocked() throws IOException
    {
        if (!baselineHolds())
        {
            Reconciliation reconciliation = reconcileLocked();
            return new Save(false, null, reconciliation.outcome);
        }
        if (!isDirty())
        {
            return new Save(false, null, null);
        }
        String formOut = formChanged() ? formText : null;
        String moduleOut = moduleChanged() ? moduleText : null;
        OrdinaryFormModule.WriteOutcome outcome = module.writeEntriesReturning(baselineFile(), formOut, moduleOut,
            id(), true);
        if (!outcome.written)
        {
            return new Save(false, null, null);
        }
        baseBytes = outcome.bytes;
        baseStamp = outcome.modificationStamp;
        if (formOut != null)
        {
            baseForm = formOut;
            formText = null;
        }
        if (moduleOut != null)
        {
            baseModule = moduleOut;
            moduleText = null;
        }
        return new Save(true, null, null);
    }

    // ---- the reconciliation ----------------------------------------------------------------

    /**
     * Reads the file under the lock and settles the session against what it holds now:
     *
     * <table border="1">
     * <caption>the reconciliation</caption>
     * <tr><th>the file against the baseline</th><th>what happens</th></tr>
     * <tr><td>the bytes equal the baseline</td><td>nothing</td></tr>
     * <tr><td>other bytes, both texts equal</td><td>the byte baseline is taken over; the edits stay</td></tr>
     * <tr><td>the layout equal, the module not, the session holds no module edit</td><td>the baseline takes the module; the layout edits stay</td></tr>
     * <tr><td>the layout equal, the module not, the session holds a module edit</td><td>as a changed layout below</td></tr>
     * <tr><td>the layout changed, the session clean</td><td>reread</td></tr>
     * <tr><td>the layout changed, the session holds edits</td><td>the edits are discarded, the form is reread</td></tr>
     * <tr><td>no container, or a version outside the measured set</td><td>read-only; saving is off until a readable container is back</td></tr>
     * </table>
     *
     * @return what the reconciliation found and did
     */
    public Reconciliation reconcile()
    {
        OpenFormSessions.SessionLock lock = OpenFormSessions.lockOf(file);
        lock.lock();
        try
        {
            return reconcileLocked();
        }
        finally
        {
            lock.unlock();
        }
    }

    private Reconciliation reconcileLocked()
    {
        byte[] bytes;
        try
        {
            bytes = readBytes();
        }
        catch (IOException e)
        {
            return refuse("the container could not be read: " + e.getMessage()); //$NON-NLS-1$
        }
        long stamp = file.getModificationStamp();
        if (Arrays.equals(bytes, baseBytes))
        {
            baseStamp = stamp;
            if (baselineEditable())
            {
                // the baseline is a layout this session can edit, so a file back at it is
                // editable again after a read-only spell over something else
                readOnly = false;
                problem = null;
            }
            return new Reconciliation(Reconciliation.NOTHING, null);
        }
        OrdinaryFormFile read;
        try
        {
            read = OrdinaryFormFile.of(null, V8Container.read(bytes), bytes);
        }
        catch (IllegalArgumentException e)
        {
            return refuse("the file is no longer a form container: " + e.getMessage()); //$NON-NLS-1$
        }
        String form = read.formText();
        if (form == null)
        {
            return refuse("the container holds no layout entry."); //$NON-NLS-1$
        }
        if (!versionMeasured(form))
        {
            return refuse("the layout version " + OrdinaryFormFile.formVersionOf(form) //$NON-NLS-1$
                + " is outside the measured set 25, 26, 27."); //$NON-NLS-1$
        }
        readOnly = false;
        problem = null;
        String module = read.moduleText();
        boolean formSame = Objects.equals(form, baseForm);
        if (formSame && Objects.equals(module, baseModule))
        {
            baseBytes = bytes;
            baseStamp = stamp;
            return new Reconciliation(Reconciliation.REPACKED, null);
        }
        if (formSame && !moduleChanged())
        {
            baseBytes = bytes;
            baseStamp = stamp;
            baseModule = module;
            if (historyTouchesModule())
            {
                disposeHistory();
                return new Reconciliation(Reconciliation.MODULE_ACCEPTED, HISTORY_CLEARED);
            }
            return new Reconciliation(Reconciliation.MODULE_ACCEPTED, null);
        }
        boolean dirty = isDirty();
        baseBytes = bytes;
        baseStamp = stamp;
        baseForm = form;
        baseModule = module;
        formText = null;
        moduleText = null;
        disposeHistory();
        if (dirty)
        {
            return new Reconciliation(Reconciliation.CHANGES_DISCARDED,
                "Форма изменена вне редактора; несохраненные правки отброшены."); //$NON-NLS-1$
        }
        return new Reconciliation(Reconciliation.REREAD, null);
    }

    // ---- the snapshot and the reading ------------------------------------------------------

    /**
     * The session's model as its own types: the elements with their render branch and their
     * kind's applicable properties, the pages, the handlers, the attributes, the unread records.
     * Built afresh from the current layout text; a text that will not read gives a snapshot with
     * its {@code problem}.
     *
     * @return the snapshot
     */
    public FormSnapshot snapshot()
    {
        if (readOnly || baseForm == null)
        {
            return FormSnapshot.of(null, null, problem);
        }
        FormLayout layout;
        try
        {
            layout = currentLayout();
        }
        catch (RuntimeException e)
        {
            return FormSnapshot.of(null, null, e.getMessage());
        }
        return FormSnapshot.of(layout, layout.version, null);
    }

    /** @return the kinds an editor operation creates, sorted */
    public List<String> kindsForCreation()
    {
        List<String> kinds = new ArrayList<>(ElementKinds.WRITABLE);
        kinds.sort(String::compareTo);
        return kinds;
    }

    /**
     * The events a kind was measured on.
     *
     * @param kind the element kind as the reader names it
     * @return the event names, in the order measured; empty for a kind without measured events
     */
    public List<String> elementEventsOf(String kind)
    {
        return new ArrayList<>(FormEvents.elementEvents(ElementKinds.canonical(kind)).values());
    }

    /** @return the form's own event names, in the order measured */
    public List<String> formEvents()
    {
        return new ArrayList<>(FormEvents.formEvents().values());
    }

    /** @return the event names of the form's main attribute, in the order measured */
    public List<String> mainAttributeEvents()
    {
        return new ArrayList<>(FormEvents.mainAttributeEvents(settingsHolder()).values());
    }

    /**
     * The owner's own words - its attributes, tabular sections, standard attributes and list
     * columns - read the way {@code describe_owner} reads them, as the session's own types for
     * the dialogs of the editor.
     *
     * @return the words; {@code null} when the form has no owner this reads or the model is not
     *         loaded in this environment
     */
    public OwnerWords ownerAttributes()
    {
        String ownerFqn = ownerFqn();
        if (ownerFqn == null || module == null)
        {
            return null;
        }
        String[] parts = ownerFqn.split("\\."); //$NON-NLS-1$
        OwnerModelReader.Read read = OwnerModelReader.read(module.getProject(), parts[0], parts[1], null);
        return read.model == null ? null : OwnerWords.of(read.model);
    }

    /**
     * The data paths a kind of element can show, in words: every form attribute alone, and the
     * main attribute with each standard attribute its owner was measured on. Each path named
     * here is one {@code DataPaths} resolves on this very form - what it does not resolve is not
     * named.
     *
     * @param kind the element kind as the reader names it
     * @return the paths, each with the type of the value it ends at; empty for a kind that shows
     *         no data
     */
    public List<DataPath> dataPathsFor(String kind)
    {
        String canonical = ElementKinds.canonical(kind);
        if (canonical == null || KINDS_WITHOUT_DATA.contains(canonical) || readOnly)
        {
            return List.of();
        }
        FormLayout layout;
        try
        {
            layout = currentLayout();
        }
        catch (RuntimeException e)
        {
            return List.of();
        }
        LayoutNames names = module == null ? LayoutNames.NONE
            : ConfigurationNames.of(module.getProject(), layout.producedTypeUuids());
        layout.resolve(names);
        List<DataPath> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (FormLayout.Attribute attribute : layout.attributes)
        {
            acceptPath(out, seen, layout, names, attribute.name);
            if (attribute.id.equals(layout.mainAttributeId))
            {
                for (String standard : STANDARD_ATTRIBUTE_NAMES)
                {
                    acceptPath(out, seen, layout, names, attribute.name + "." + standard); //$NON-NLS-1$
                }
            }
        }
        return out;
    }

    /** Adds a path when the resolver accepts it on this layout, once. */
    private void acceptPath(List<DataPath> out, Set<String> seen, FormLayout layout, LayoutNames names, String data)
    {
        if (!seen.add(data.toLowerCase(Locale.ROOT)))
        {
            return;
        }
        List<FormLayout.Segment> path = new ArrayList<>();
        ListNode[] patternOut = new ListNode[1];
        if (DataPaths.resolve(layout, names, ownerKind, data, path, patternOut) != null)
        {
            return;
        }
        String valueType = patternOut[0] == null ? null : TypePattern.of(patternOut[0]).describe(names);
        out.add(new DataPath(data, valueType));
    }

    // ---- the plumbing ----------------------------------------------------------------------

    /** The workspace operation history. */
    private static IOperationHistory history()
    {
        return OperationHistoryFactory.getOperationHistory();
    }

    /**
     * Keeps one history record when a fixed edit changed a text. A success that leaves both
     * texts as they were keeps nothing, and a new record clears the redo of this context.
     */
    private void record(String beforeForm, String beforeModule)
    {
        String afterForm = currentFormText();
        String afterModule = currentModuleText();
        boolean formTouched = !Objects.equals(beforeForm, afterForm);
        boolean moduleTouched = !Objects.equals(beforeModule, afterModule);
        if (!formTouched && !moduleTouched)
        {
            return;
        }
        LayoutChangeOperation operation = new LayoutChangeOperation(this, beforeForm, afterForm, formTouched,
            beforeModule, afterModule, moduleTouched);
        operation.addContext(undoContext);
        history().add(operation);
    }

    /**
     * Applies one recorded undo or redo. The caller holds the file's registry lock, and so does
     * the history's own call into the operation. A stale baseline is reconciled first; the
     * record is applied only when every touched entry still holds the text the record expects,
     * and a reconciliation that disposed the history answers with a status the history does not
     * push back onto a stack.
     *
     * @param operation the record
     * @param undoing {@code true} for undo
     * @return {@link Status#OK_STATUS} when the record was applied
     */
    IStatus revertRecorded(LayoutChangeOperation operation, boolean undoing)
    {
        reconciledDuringStep = null;
        if (readOnly)
        {
            return LayoutChangeOperation.notDone(IStatus.ERROR,
                problem != null ? problem : "the session is read-only"); //$NON-NLS-1$
        }
        if (!baselineHolds())
        {
            reconciledDuringStep = reconcileLocked();
        }
        if (readOnly)
        {
            return LayoutChangeOperation.notDone(IStatus.CANCEL, reversalMessage(reconciledDuringStep));
        }
        if (!operation.matches(currentFormText(), currentModuleText(), undoing))
        {
            if (reconciledDuringStep != null)
            {
                return LayoutChangeOperation.notDone(IStatus.CANCEL, reversalMessage(reconciledDuringStep));
            }
            String which = undoing ? "undo" : "redo"; //$NON-NLS-1$ //$NON-NLS-2$
            return LayoutChangeOperation.notDone(IStatus.ERROR,
                which + " is not available: the text is not the one this operation left"); //$NON-NLS-1$
        }
        Pacing.pauseAt(Pacing.BEFORE_EDIT_FIXATION);
        operation.apply(undoing);
        return Status.OK_STATUS;
    }

    /** The line a reversal reports for a reconciliation, the outcome when it has no line of its own. */
    private static String reversalMessage(Reconciliation reconciliation)
    {
        if (reconciliation == null)
        {
            return "the session is read-only"; //$NON-NLS-1$
        }
        return reconciliation.message != null ? reconciliation.message : reconciliation.outcome;
    }

    /** Drops this session's undo and redo. Called when the last editor closes and when a reread drops the records. */
    void disposeHistory()
    {
        history().dispose(undoContext, true, true, true);
    }

    /** @return whether any record still on this session's undo or redo changed the module */
    private boolean historyTouchesModule()
    {
        return touchesModule(history().getUndoHistory(undoContext))
            || touchesModule(history().getRedoHistory(undoContext));
    }

    /** @return whether any of the records changed the module */
    private static boolean touchesModule(IUndoableOperation[] operations)
    {
        for (IUndoableOperation operation : operations)
        {
            if (operation instanceof LayoutChangeOperation layout && layout.touchesModule())
            {
                return true;
            }
        }
        return false;
    }

    /** @return the session's layout text, the baseline while no edit holds another */
    String currentFormText()
    {
        return formText == null ? baseForm : formText;
    }

    /** @return the session's module text, the baseline while no edit holds another */
    String currentModuleText()
    {
        return moduleText == null ? baseModule : moduleText;
    }

    /**
     * Holds a layout text. A text equal to the baseline is held as {@code null}, so the session
     * is clean when both texts are back on the baseline.
     *
     * @param text the layout text to hold
     */
    void holdForm(String text)
    {
        formText = Objects.equals(text, baseForm) ? null : text;
    }

    /**
     * Holds a module text. A text equal to the baseline is held as {@code null}.
     *
     * @param text the module text to hold
     */
    void holdModule(String text)
    {
        moduleText = Objects.equals(text, baseModule) ? null : text;
    }

    /**
     * Whether the file still holds the baseline. A file that was rewritten with the very same
     * bytes still holds it; the stamp is taken over then.
     */
    private boolean baselineHolds()
    {
        try
        {
            byte[] bytes = readBytes();
            if (Arrays.equals(bytes, baseBytes))
            {
                baseStamp = file.getModificationStamp();
                return true;
            }
            return false;
        }
        catch (IOException e)
        {
            return false;
        }
    }

    /** The operation's target: the baseline container with the session's texts in. */
    private Target target() throws IOException
    {
        if (module == null)
        {
            throw new IOException("the container does not lie at a form module's address."); //$NON-NLS-1$
        }
        OrdinaryFormFile current = currentFile();
        return Target.of(module, fqn, current);
    }

    /** The baseline container with the session's texts put in; the baseline stays what a write compares against. */
    private OrdinaryFormFile currentFile()
    {
        OrdinaryFormFile current = baselineFile();
        String form = formText == null ? baseForm : formText;
        if (form != null)
        {
            current.setFormText(form);
        }
        if (moduleText != null)
        {
            current.setModuleText(moduleText);
        }
        return current;
    }

    /** The baseline container, its observed texts the write compares the file with. */
    private OrdinaryFormFile baselineFile()
    {
        return OrdinaryFormFile.of(null, V8Container.read(baseBytes), baseBytes);
    }

    /** The current layout as the reader reads it. */
    private FormLayout currentLayout()
    {
        return LayoutReader.read(BraceTree.parse(formText == null ? baseForm : formText), ownerKind);
    }

    private boolean settingsHolder()
    {
        if (readOnly || baseForm == null)
        {
            return false;
        }
        try
        {
            return currentLayout().settingsHolder;
        }
        catch (RuntimeException e)
        {
            return false;
        }
    }

    /** The owner of the form, {@code Type.Object}; a common form has none. */
    private String ownerFqn()
    {
        if (fqn == null)
        {
            return null;
        }
        String[] parts = fqn.split("\\."); //$NON-NLS-1$
        return parts.length == 4 ? parts[0] + "." + parts[1] : null; //$NON-NLS-1$
    }

    /**
     * Whether the baseline holds a layout a session can edit. A session opened over a
     * container of an unmeasured version keeps that baseline while the file stands at it, and
     * a reconciliation over unchanged bytes must not read the session back into edit.
     *
     * @return whether the baseline layout opens with a measured version
     */
    private boolean baselineEditable()
    {
        return versionMeasured(baseForm);
    }

    /**
     * @param formText a layout text
     * @return whether it opens with a version the writers are measured on
     */
    private static boolean versionMeasured(String formText)
    {
        String version = OrdinaryFormFile.formVersionOf(formText);
        return version != null && LayoutEditor.SUPPORTED_VERSIONS.contains(version);
    }

    /**
     * Puts the session into read-only and answers the reconciliation outcome that says so.
     *
     * @param why what the file holds that cannot be edited
     * @return the read-only outcome with that reason
     */
    private Reconciliation refuse(String why)
    {
        readOnly = true;
        problem = why;
        return new Reconciliation(Reconciliation.READ_ONLY, why);
    }

    /** The form's FQN by where its file lies, for a container the module locator does not reach. */
    private static String fqnOf(IFile file)
    {
        IPath location = file.getLocation();
        IPath project = file.getProject().getLocation();
        if (location == null || project == null)
        {
            return null;
        }
        try
        {
            return OrdinaryFormLocator.fqnOf(project.append("src").toFile().toPath(), location.toFile().toPath()); //$NON-NLS-1$
        }
        catch (IllegalArgumentException e)
        {
            return null;
        }
    }

    /** The file's bytes, the workspace copy first and the disk when the workspace has none. */
    private byte[] readBytes() throws IOException
    {
        try (InputStream stream = file.getContents(true))
        {
            return stream.readAllBytes();
        }
        catch (CoreException primary)
        {
            IPath location = file.getLocation();
            java.io.File osFile = location == null ? null : location.toFile();
            if (osFile == null || !osFile.isFile())
            {
                throw new IOException(primary.getMessage(), primary);
            }
            return Files.readAllBytes(osFile.toPath());
        }
    }

    // ---- the answers -----------------------------------------------------------------------

    /** What an edit did. */
    public static final class Edit
    {
        /** Whether the edit was fixed into the session. */
        public final boolean done;

        /** The handler's outcome when done, the refusal otherwise. */
        public final String message;

        /** The layout version a refusal on the version names; {@code null} otherwise. */
        public final String refusedVersion;

        /** The id of an element the edit created, or {@code null}. */
        public final String elementId;

        /** The outcome the reconciliation reached - one of {@link Reconciliation} constants - when the file had changed; {@code null} otherwise. */
        public final String reconciliation;

        Edit(boolean done, String message, String refusedVersion, String elementId, String reconciliation)
        {
            this.done = done;
            this.message = message;
            this.refusedVersion = refusedVersion;
            this.elementId = elementId;
            this.reconciliation = reconciliation;
        }

        static Edit refused(String message)
        {
            return new Edit(false, message, null, null, null);
        }
    }

    /** What a preview found. */
    public static final class Preview
    {
        /** Whether the operation would run; a refusal names why not. */
        public final boolean done;

        /** The handler's outcome, or the refusal. */
        public final String message;

        /** The layout version a refusal on the version names; {@code null} otherwise. */
        public final String refusedVersion;

        /** The elements the operation would take away, outermost first. */
        public final List<String> removed;

        /** The elements the operation would leave altered. */
        public final List<String> changed;

        Preview(boolean done, String message, String refusedVersion, List<String> removed, List<String> changed)
        {
            this.done = done;
            this.message = message;
            this.refusedVersion = refusedVersion;
            this.removed = removed;
            this.changed = changed;
        }

        static Preview refused(String message, String refusedVersion)
        {
            return new Preview(false, message, refusedVersion, List.of(), List.of());
        }
    }

    /** What a save did. */
    public static final class Save
    {
        /** Whether the container was written. */
        public final boolean written;

        /** The delivery note of the owner's mark, or {@code null} when nothing was written. */
        public final String delivery;

        /** The outcome the reconciliation reached - one of {@link Reconciliation} constants - when the file had changed; {@code null} otherwise. */
        public final String reconciliation;

        Save(boolean written, String delivery, String reconciliation)
        {
            this.written = written;
            this.delivery = delivery;
            this.reconciliation = reconciliation;
        }
    }

    /** What a reconciliation found and did. */
    public static final class Reconciliation
    {
        /** The file equals the baseline. */
        public static final String NOTHING = "nothing"; //$NON-NLS-1$

        /** Other bytes, the same texts: the byte baseline was taken over. */
        public static final String REPACKED = "repacked"; //$NON-NLS-1$

        /** The module changed outside and the session held no module edit: the baseline took it. */
        public static final String MODULE_ACCEPTED = "moduleAccepted"; //$NON-NLS-1$

        /** The layout changed outside while the session held edits: they were discarded. */
        public static final String CHANGES_DISCARDED = "changesDiscarded"; //$NON-NLS-1$

        /** The layout changed outside and the session was clean: the form was reread. */
        public static final String REREAD = "reread"; //$NON-NLS-1$

        /** The file holds nothing a session can edit: no container, or a version outside the set. */
        public static final String READ_ONLY = "readOnly"; //$NON-NLS-1$

        /** The outcome; one of the constants above. */
        public final String outcome;

        /** The line the editor shows; {@code null} when there is nothing to show. */
        public final String message;

        Reconciliation(String outcome, String message)
        {
            this.outcome = outcome;
            this.message = message;
        }
    }

    /** A data path a kind of element can show, in words. */
    public static final class DataPath
    {
        /** The path in words, {@code <form attribute>} or {@code <form attribute>.<member>}. */
        public final String path;

        /** The type of the value the path ends at, in words, or {@code null}. */
        public final String valueType;

        DataPath(String path, String valueType)
        {
            this.path = path;
            this.valueType = valueType;
        }
    }

    /** What an undo or a redo did. */
    public static final class Reversal
    {
        /** Whether the record was applied. */
        public final boolean done;

        /** The reconciliation's line, or why the record was not applied; {@code null} when there was nothing to do. */
        public final String message;

        /** The outcome the reconciliation reached, one of {@link Reconciliation}'s constants; {@code null} when none ran. */
        public final String reconciliation;

        Reversal(boolean done, String message, String reconciliation)
        {
            this.done = done;
            this.message = message;
            this.reconciliation = reconciliation;
        }
    }

    /** One operation of a {@link #gesture}. */
    public static final class Step
    {
        /** The operation's name, one of the writing layout operations. */
        public final String operation;

        /** The operation's arguments, as the tool takes them. */
        public final Map<String, String> params;

        /**
         * @param operation the operation's name
         * @param params the operation's arguments
         */
        public Step(String operation, Map<String, String> params)
        {
            this.operation = operation;
            this.params = params;
        }
    }
}
