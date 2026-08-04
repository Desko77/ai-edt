/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.labels.refactoring;

import java.util.Collection;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.ltk.core.refactoring.Change;

import com._1c.g5.v8.bm.core.IBmCrossReference;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.refactoring.core.IRenameRefactoringContributor;
import com._1c.g5.v8.dt.refactoring.core.RefactoringOperationDescriptor;
import com._1c.g5.v8.dt.refactoring.core.RefactoringSettings;
import com._1c.g5.v8.dt.refactoring.core.RefactoringStatus;

import ru.aiedt.mcp.server.labels.MarkerManager;
import ru.aiedt.mcp.server.labels.MarkerHelpers;

/**
 * Keeps marker assignments attached to an object when EDT renames it.
 * <p>
 * The plugin does not touch the rename itself. It only contributes an undoable change that runs after
 * the rename has committed and moves the object's marker assignments from its old FQN to its new one.
 * The change is contributed only when the renamed object actually carries markers and its FQN really
 * changes.
 * </p>
 */
public class MarkerRenameRefactorHook
    implements IRenameRefactoringContributor
{
    @Override
    public RefactoringOperationDescriptor createParticipatingOperation(EObject element,
        RefactoringSettings settings, RefactoringStatus status)
    {
        return null;
    }

    @Override
    public RefactoringOperationDescriptor createPreReferenceUpdateParticipatingOperation(
        IBmObject element, RefactoringSettings settings, RefactoringStatus status)
    {
        return null;
    }

    @Override
    public Collection<Change> createNativePreChanges(EObject element, String newName,
        RefactoringSettings settings, RefactoringStatus status)
    {
        return null;
    }

    @Override
    public Collection<Change> createNativePostChanges(EObject element, String newName,
        RefactoringSettings settings, RefactoringStatus status)
    {
        if (!(element instanceof IBmObject))
        {
            return null;
        }
        String oldFqn = MarkerHelpers.extractFqn((IBmObject)element);
        if (oldFqn == null)
        {
            return null;
        }
        IProject project = MarkerHelpers.extractProject(element);
        if (project == null)
        {
            return null;
        }
        if (MarkerManager.getInstance().getObjectMarkers(project, oldFqn).isEmpty())
        {
            return null;
        }
        String newFqn = MarkerHelpers.buildNewFqn(oldFqn, newName);
        if (newFqn == null || newFqn.equals(oldFqn))
        {
            return null;
        }
        return List.of(new MarkerFqnRenameChange(project, oldFqn, newFqn));
    }

    @Override
    public boolean allowProhibitedReferenceEditing(IBmCrossReference reference)
    {
        return false;
    }

    /**
     * The change that moves an object's marker assignments after a rename, and undoes itself by moving
     * them back.
     */
    private static final class MarkerFqnRenameChange
        extends Change
    {
        private final IProject project;

        private final String oldFqn;

        private final String newFqn;

        MarkerFqnRenameChange(IProject project, String oldFqn, String newFqn)
        {
            this.project = project;
            this.oldFqn = oldFqn;
            this.newFqn = newFqn;
        }

        @Override
        public String getName()
        {
            return "Update marker assignments: " + oldFqn + " -> " + newFqn; //$NON-NLS-1$ //$NON-NLS-2$
        }

        @Override
        public void initializeValidationData(IProgressMonitor monitor)
        {
            // Nothing to validate.
        }

        @Override
        public org.eclipse.ltk.core.refactoring.RefactoringStatus isValid(IProgressMonitor monitor)
        {
            return new org.eclipse.ltk.core.refactoring.RefactoringStatus();
        }

        @Override
        public Change perform(IProgressMonitor monitor)
        {
            MarkerManager.getInstance().renameObject(project, oldFqn, newFqn);
            return new MarkerFqnRenameChange(project, newFqn, oldFqn);
        }

        @Override
        public Object getModifiedElement()
        {
            return null;
        }
    }
}
