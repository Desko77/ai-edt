/**
 * AI-EDT Ordinary Forms
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform.session;

import java.util.Objects;

import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.operations.AbstractOperation;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;

/**
 * One recorded change of a {@link FormEditingSession}: the layout text and the module text as
 * they were before the change and as the change left them. The session has already fixed the
 * change when the history keeps this record, so {@link #execute} does not apply it again.
 *
 * <p>Undo and redo run under the file's lock of the registry and change only an entry this
 * record touched, and only when that entry's current text is the one this record expects. A
 * file the session has not seen is reconciled first; the record is not applied over a text that
 * no longer matches. A {@code null} side of the module is the entry's absence, not the absence
 * of a change; it is restored and saved as an absent entry.</p>
 */
public final class LayoutChangeOperation extends AbstractOperation
{
    private static final String BUNDLE = "ru.aiedt.mcp.oform"; //$NON-NLS-1$

    private final FormEditingSession session;

    private final String formBefore;

    private final String formAfter;

    private final boolean formTouched;

    private final String moduleBefore;

    private final String moduleAfter;

    private final boolean moduleTouched;

    LayoutChangeOperation(FormEditingSession session, String formBefore, String formAfter, boolean formTouched,
        String moduleBefore, String moduleAfter, boolean moduleTouched)
    {
        super("Ordinary form layout"); //$NON-NLS-1$
        this.session = session;
        this.formBefore = formTouched ? formBefore : null;
        this.formAfter = formTouched ? formAfter : null;
        this.formTouched = formTouched;
        this.moduleBefore = moduleTouched ? moduleBefore : null;
        this.moduleAfter = moduleTouched ? moduleAfter : null;
        this.moduleTouched = moduleTouched;
    }

    /** @return whether this record changed the module text */
    public boolean touchesModule()
    {
        return moduleTouched;
    }

    /**
     * Whether each touched entry's current text is the side this undo or redo expects.
     *
     * @param form the session's current layout text
     * @param module the session's current module text
     * @param undoing {@code true} for undo, which expects the text the record left
     * @return whether the record may be applied
     */
    boolean matches(String form, String module, boolean undoing)
    {
        if (formTouched && !Objects.equals(form, undoing ? formAfter : formBefore))
        {
            return false;
        }
        return !moduleTouched || Objects.equals(module, undoing ? moduleAfter : moduleBefore);
    }

    /**
     * Puts the other side of each touched entry into the session.
     *
     * @param undoing {@code true} to restore the text from before the record
     */
    void apply(boolean undoing)
    {
        if (formTouched)
        {
            session.holdForm(undoing ? formBefore : formAfter);
        }
        if (moduleTouched)
        {
            session.holdModule(undoing ? moduleBefore : moduleAfter);
        }
    }

    /**
     * Does not apply the change. The session fixed it before the history kept this record, and
     * executing it again would apply the same change twice.
     */
    @Override
    public IStatus execute(IProgressMonitor monitor, IAdaptable info) throws ExecutionException
    {
        return Status.OK_STATUS;
    }

    @Override
    public IStatus redo(IProgressMonitor monitor, IAdaptable info) throws ExecutionException
    {
        return revert(monitor, false);
    }

    @Override
    public IStatus undo(IProgressMonitor monitor, IAdaptable info) throws ExecutionException
    {
        return revert(monitor, true);
    }

    /**
     * Undo or redo. A caller that already holds the session lock applies the record on that
     * hold. Any other caller follows the file the session is bound to now, or refuses when the
     * container was removed.
     */
    private IStatus revert(IProgressMonitor monitor, boolean undoing)
    {
        if (monitor != null && monitor.isCanceled())
        {
            return Status.CANCEL_STATUS;
        }
        OpenFormSessions.SessionLock looked = OpenFormSessions.lockOf(session.file());
        try
        {
            if (looked.heldByCurrentThread())
            {
                return session.revertRecorded(this, undoing);
            }
        }
        finally
        {
            OpenFormSessions.releasePin(looked);
        }
        return session.revertGuarded(this, undoing);
    }

    /** A status the history does not treat as done, so a disposed record is not pushed back onto a stack. */
    static IStatus notDone(int severity, String message)
    {
        return new Status(severity, BUNDLE, message);
    }
}
