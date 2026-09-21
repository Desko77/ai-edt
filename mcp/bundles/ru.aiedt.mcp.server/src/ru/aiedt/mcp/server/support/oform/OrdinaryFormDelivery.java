/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support.oform;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.eclipse.core.resources.IProject;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.support.SyncBaseline;

/**
 * Makes a written container reach the infobase at the next update.
 *
 * <p>EDT's incremental update exports the objects whose resources changed since the last sync,
 * mapping each changed path to the object that owns it. {@code Form.oform} is a path it does not
 * map ({@code QualifiedNameFilePathConverter} knows {@code form}, not {@code oform}), so a
 * container written here is dropped from the load list with "cannot find neither object, nor
 * owner", while the update still answers UPDATED. The object that owns the form, when it is in
 * the list, is exported with the container as {@code Ext/Form.bin}, byte for byte - so the owner
 * has to be in the list.</p>
 *
 * <p>The owner gets there through the baseline: a resource whose recorded signature does not
 * match the current one reads as changed. Blanking the signature of the owner's {@code .mdo} in
 * every baseline of the project's infobases says exactly what is true - the infobase's copy of
 * the object is behind - and EDT's cached copy of the baseline is dropped so the next update
 * reads the file.</p>
 */
public final class OrdinaryFormDelivery
{
    /** What the marking did, for an answer. */
    public static final class Marked
    {
        private final String ownerKey;

        private final List<String> markedInfobases = new ArrayList<>();

        private final List<String> alreadyMarked = new ArrayList<>();

        private final List<String> notes = new ArrayList<>();

        private int baselines;

        Marked(String ownerKey)
        {
            this.ownerKey = ownerKey;
        }

        /** @return the baseline key of the owner's {@code .mdo} */
        public String ownerKey()
        {
            return ownerKey;
        }

        /** @return the infobases whose baseline was written */
        public List<String> markedInfobases()
        {
            return markedInfobases;
        }

        /** @return the infobases whose baseline already read the owner as changed */
        public List<String> alreadyMarked()
        {
            return alreadyMarked;
        }

        /** @return how many baselines the project has in its workspace store */
        public int baselines()
        {
            return baselines;
        }

        /** @return what the cache drop and any failure said, one line each */
        public List<String> notes()
        {
            return notes;
        }

        /**
         * One sentence for the answer of the write.
         *
         * @return the statement
         */
        public String statement()
        {
            if (baselines == 0)
            {
                return "no infobase baseline in the workspace: the next update_database is the first load " //$NON-NLS-1$
                    + "of this project and carries the form with it"; //$NON-NLS-1$
            }
            int marked = markedInfobases.size() + alreadyMarked.size();
            if (marked == 0)
            {
                return "owner " + ownerKey + " is not in the " + baselines + " baseline(s) of the project; " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + "update_database will not carry the form until the owner changes"; //$NON-NLS-1$
            }
            return "owner " + ownerKey + " reads as changed in " + marked + " of " + baselines //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + " infobase baseline(s); the next update_database loads the object with the form"; //$NON-NLS-1$
        }
    }

    private OrdinaryFormDelivery()
    {
        // static utility
    }

    /**
     * The baseline key of the object that owns a form: the {@code .mdo} beside the form's folder.
     *
     * @param containerPath the container's {@code src}-relative path, forward slashes
     * @return the key, {@code src/<dir>/<object>/<object>.mdo}, or {@code null} when the path is
     *         not an ordinary form's
     */
    public static String ownerKeyOf(String containerPath)
    {
        String[] parts = containerPath.replace('\\', '/').split("/"); //$NON-NLS-1$
        if (parts.length == 3 && "CommonForms".equals(parts[0])) //$NON-NLS-1$
        {
            return "src/CommonForms/" + parts[1] + "/" + parts[1] + ".mdo"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        if (parts.length == 5 && "Forms".equals(parts[2])) //$NON-NLS-1$
        {
            return "src/" + parts[0] + "/" + parts[1] + "/" + parts[1] + ".mdo"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }
        return null;
    }

    /**
     * Marks the owner of a form as changed in every baseline of the project's infobases.
     *
     * @param project the project
     * @param module the form whose container was written
     * @return what was marked
     */
    public static Marked mark(IProject project, OrdinaryFormModule module)
    {
        String ownerKey = ownerKeyOf(module.containerPath());
        Marked marked = new Marked(ownerKey);
        if (ownerKey == null)
        {
            marked.notes.add("the container's path is not an ordinary form's; nothing marked"); //$NON-NLS-1$
            return marked;
        }
        List<Path> indexes = SyncBaseline.workspaceIndexes(project);
        marked.baselines = indexes.size();
        for (Path index : indexes)
        {
            String infobase = index.getParent().getFileName().toString();
            try
            {
                if (SyncBaseline.blankSignature(index, ownerKey))
                {
                    marked.markedInfobases.add(infobase);
                }
                else if (SyncBaseline.read(index).indexOf(ownerKey) >= 0)
                {
                    marked.alreadyMarked.add(infobase);
                }
                else
                {
                    marked.notes.add(infobase + ": owner not in the baseline"); //$NON-NLS-1$
                    continue;
                }
            }
            catch (IOException e)
            {
                Activator.logWarning("Baseline " + index + " was not marked: " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
                marked.notes.add(infobase + ": " + e.getMessage()); //$NON-NLS-1$
                continue;
            }
            try
            {
                marked.notes.add(infobase + ": cached state " + SyncBaseline.dropCachedHolder(UUID.fromString(infobase))); //$NON-NLS-1$ //$NON-NLS-2$
            }
            catch (IllegalArgumentException e)
            {
                marked.notes.add(infobase + ": directory name is not an infobase id, cached state left alone"); //$NON-NLS-1$
            }
        }
        return marked;
    }
}
