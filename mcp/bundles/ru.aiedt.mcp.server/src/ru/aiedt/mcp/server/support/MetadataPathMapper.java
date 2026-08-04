/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

/**
 * Turns a metadata name into the file that holds it.
 * <p>
 * Only forms are resolved today. The name arrives as a 1C fully qualified name in whichever language
 * and case the agent chose - {@code Catalog.Products.Forms.ItemForm}, the same name with a plural or
 * lowercased segment, and the Russian spelling of it, all reach one file - and comes back as a path
 * relative to the project root, using forward slashes, as EDT lays it out on disk.
 * </p>
 */
public final class MetadataPathMapper
{
    /** Root of the sources of an EDT project. */
    private static final String SOURCE_ROOT = "src/"; //$NON-NLS-1$

    /** Directory of the common forms, which sit at the top level rather than under an owner. */
    private static final String COMMON_FORMS_DIR = "CommonForms"; //$NON-NLS-1$

    /** The segment between an owner and one of its forms, as EDT spells it on disk. */
    private static final String FORMS_SEGMENT = "Forms"; //$NON-NLS-1$

    /** File holding the form itself. */
    private static final String FORM_FILE = "Form.form"; //$NON-NLS-1$

    private MetadataPathMapper()
    {
        // utility
    }

    /**
     * Resolves the file of a form.
     * <p>
     * Two shapes are recognized: a common form, named by two segments
     * ({@code CommonForm.<name>}), and a form owned by another object, named by four
     * ({@code <type>.<owner>.Forms.<name>}). The type segment goes through the metadata registry, so a
     * type that has no directory there - a role, a subsystem - resolves to nothing, which is what
     * rejects {@code Role.Admin.Forms.X}: roles do not own forms.
     * </p>
     *
     * @param formPath the form's fully qualified name; may be <code>null</code>
     * @return the project-relative path of the form file, or <code>null</code> when the name is not a
     *         form this layout can place
     */
    public static String resolveFormFilePath(String formPath)
    {
        if (formPath == null || formPath.isEmpty())
        {
            return null;
        }
        String[] segments = formPath.split("\\."); //$NON-NLS-1$

        if (segments.length == 2)
        {
            // A common form: CommonForm.<name>. Any other two-segment name is an object, not a form.
            if (!COMMON_FORMS_DIR.equals(resolveMetadataDir(segments[0])))
            {
                return null;
            }
            return SOURCE_ROOT + COMMON_FORMS_DIR + '/' + segments[1] + '/' + FORM_FILE;
        }

        if (segments.length == 4)
        {
            // An owned form: <type>.<owner>.Forms.<name>. The keyword is matched loosely and written
            // back in EDT's spelling, so a lowercase "forms" from an agent still lands on disk.
            if (!FORMS_SEGMENT.equalsIgnoreCase(segments[2]))
            {
                return null;
            }
            String directory = resolveMetadataDir(segments[0]);
            if (directory == null)
            {
                return null;
            }
            return SOURCE_ROOT + directory + '/' + segments[1] + '/' + FORMS_SEGMENT + '/' + segments[3] + '/'
                + FORM_FILE;
        }

        return null;
    }

    /**
     * Resolves the directory a metadata type is stored in.
     *
     * @param metadataType the type name, in any recognized spelling; may be <code>null</code>
     * @return the directory under {@code src/}, or <code>null</code> for an unknown type and for a type
     *         that is not stored in one of its own directories
     */
    public static String resolveMetadataDir(String metadataType)
    {
        return MetadataTypeCatalog.getDirectoryName(metadataType);
    }
}
