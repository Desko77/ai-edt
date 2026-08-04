/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.labels.refactoring;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.bm.core.IBmCrossReference;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.refactoring.core.IDeleteRefactoringContributor;
import com._1c.g5.v8.dt.refactoring.core.IRefactoringOperation;
import com._1c.g5.v8.dt.refactoring.core.IRefactoringPostProcessor;
import com._1c.g5.v8.dt.refactoring.core.RefactoringOperationDescriptor;
import com._1c.g5.v8.dt.refactoring.core.RefactoringSettings;
import com._1c.g5.v8.dt.refactoring.core.RefactoringStatus;

import ru.aiedt.mcp.server.labels.MarkerManager;
import ru.aiedt.mcp.server.labels.MarkerHelpers;

/**
 * Drops an object's marker assignments when EDT deletes it.
 * <p>
 * The removal is deferred to a post-process step so it runs only after the delete transaction has
 * committed, and only for objects that actually carried markers.
 * </p>
 */
public class MarkerDeleteRefactorHook
    implements IDeleteRefactoringContributor
{
    @Override
    public RefactoringOperationDescriptor createParticipatingOperation(EObject element,
        RefactoringSettings settings, RefactoringStatus status)
    {
        if (!(element instanceof IBmObject))
        {
            return null;
        }
        String fqn = MarkerHelpers.extractFqn((IBmObject)element);
        if (fqn == null)
        {
            return null;
        }
        IProject project = MarkerHelpers.extractProject(element);
        if (project == null)
        {
            return null;
        }
        if (MarkerManager.getInstance().getObjectMarkers(project, fqn).isEmpty())
        {
            return null;
        }
        return new RefactoringOperationDescriptor(new MarkerDeleteOperation(project, fqn));
    }

    @Override
    public RefactoringOperationDescriptor createCleanReferenceOperation(IBmObject source,
        IBmObject target, EStructuralFeature feature, RefactoringSettings settings,
        RefactoringStatus status)
    {
        return null;
    }

    @Override
    public boolean allowProhibitedReferenceEditing(IBmCrossReference reference)
    {
        return false;
    }

    /**
     * Removes an object's marker assignments once its deletion has committed.
     */
    private static final class MarkerDeleteOperation
        implements IRefactoringOperation, IRefactoringPostProcessor
    {
        private final IProject project;

        private final String fqn;

        MarkerDeleteOperation(IProject project, String fqn)
        {
            this.project = project;
            this.fqn = fqn;
        }

        @Override
        public void perform()
        {
            // The assignments are removed in postProcess, after the delete has committed.
        }

        @Override
        public void postProcess()
        {
            MarkerManager.getInstance().removeObject(project, fqn);
        }
    }
}
