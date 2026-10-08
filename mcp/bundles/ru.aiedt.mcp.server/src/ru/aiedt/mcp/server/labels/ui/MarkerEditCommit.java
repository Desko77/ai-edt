/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.labels.ui;

/**
 * Applies an edit of one marker and says what became of it.
 * <p>
 * A rename to a name that is already taken is refused. Color and description from the same edit are
 * not part of that refusal: they are stored under the name the marker already had, and the caller is
 * told that the name was left as it was.
 * </p>
 */
public final class MarkerEditCommit
{
    private MarkerEditCommit()
    {
        // Static helpers.
    }

    /**
     * The storage the commit writes through. Tests supply their own; the dialog supplies the marker
     * service.
     */
    public interface Editor
    {
        /**
         * Updates one marker.
         *
         * @param oldName the current name
         * @param newName the requested name, or <code>null</code> to keep the current one
         * @param color the requested color, or <code>null</code> to keep it
         * @param description the requested description, or <code>null</code> to keep it
         * @return <code>true</code> when the update was stored
         */
        boolean update(String oldName, String newName, String color, String description);

        /**
         * Tells whether a marker with this name is defined.
         *
         * @param name the marker name
         * @return <code>true</code> when it is defined
         */
        boolean defined(String name);
    }

    /**
     * What an edit did: whether the name changed, whether anything was stored, and the message to show
     * when the name was refused.
     */
    public static final class Result
    {
        private final boolean nameChanged;

        private final boolean stored;

        private final String message;

        private Result(boolean nameChanged, boolean stored, String message)
        {
            this.nameChanged = nameChanged;
            this.stored = stored;
            this.message = message;
        }

        /**
         * Tells whether the marker's name changed.
         *
         * @return <code>true</code> when the new name was stored
         */
        public boolean nameChanged()
        {
            return nameChanged;
        }

        /**
         * Tells whether the edit was written.
         *
         * @return <code>true</code> when the name, the color, or the description was stored
         */
        public boolean stored()
        {
            return stored;
        }

        /**
         * Returns the message to show when the requested name was not stored.
         *
         * @return the message, or <code>null</code> when the edit was stored as asked
         */
        public String message()
        {
            return message;
        }
    }

    /**
     * Stores an edit. A taken name keeps the previous name and still stores the color and description.
     *
     * @param editor where the edit is written
     * @param oldName the marker's current name
     * @param newName the name the user asked for
     * @param color the color the user asked for
     * @param description the description the user asked for
     * @return what was stored, including a message when the name was refused
     */
    public static Result apply(Editor editor, String oldName, String newName, String color, String description)
    {
        if (editor.update(oldName, newName, color, description))
        {
            boolean renamed = newName != null && !newName.equals(oldName);
            return new Result(renamed, true, null);
        }
        boolean nameTaken = newName != null && !newName.equals(oldName) && editor.defined(oldName)
            && editor.defined(newName);
        if (nameTaken)
        {
            boolean stored = editor.update(oldName, null, color, description);
            // The message follows the second update's own outcome: claiming the color and
            // description were kept when that write was refused too would tell the user their
            // edit landed somewhere it never reached.
            return new Result(false, stored, stored
                ? "A marker named \"" + newName + "\" already exists. The color and description were kept." //$NON-NLS-1$ //$NON-NLS-2$
                : "A marker named \"" + newName + "\" already exists, and the color and description " //$NON-NLS-1$ //$NON-NLS-2$
                    + "could not be stored."); //$NON-NLS-1$
        }
        return new Result(false, false, "The marker could not be updated."); //$NON-NLS-1$
    }
}
