/**
 * AI-EDT - 1C AI tools for EDT
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

/**
 * What kind of container an FQN names, decided by the shape of the path.
 * <p>
 * The universal item operations have to send the caller to the operation that can actually do the
 * work, and to do that they first have to know what they are looking at. Two of them worked this
 * out by searching the FQN for a substring, and they disagreed with each other: one treated
 * {@code CommonForm.X} as a form, the other did not, so the same container answered differently
 * depending on which operation was asked.
 * </p>
 * <p>
 * A substring is also wrong on its own terms. {@code Catalog.TemplateSettings} contains
 * {@code .Template} and was read as a composition schema, so a caller removing an attribute of that
 * catalogue was sent to the schema workshop. The name of an object is not a statement about its
 * kind.
 * </p>
 * <p>
 * Kinds sit at the even indices of a metadata FQN - {@code Type.Name.Kind.Name.Kind.Name} - so this
 * reads them by position. A catalogue whose NAME is "TemplateSettings" carries nothing at an index
 * where a kind would be, and a form whose name happens to be "Template" is still a form.
 * </p>
 */
public enum ContainerScope
{
    /** A managed form, either owned by an object or a common one. */
    FORM,

    /** A template - a spreadsheet document or a composition schema; the FQN does not say which. */
    TEMPLATE,

    /** Anything else: the metadata object itself, and its attributes and tabular sections. */
    METADATA_OBJECT;

    /** Root types that decide the answer on their own, without a kind segment. */
    private static final String COMMON_FORM = "CommonForm"; //$NON-NLS-1$

    private static final String COMMON_TEMPLATE = "CommonTemplate"; //$NON-NLS-1$

    /**
     * What the FQN names.
     *
     * @param containerFqn the container's FQN; may be <code>null</code>
     * @return the scope, {@link #METADATA_OBJECT} when nothing says otherwise - including for an
     *         absent or unparsable FQN, because a guess of "form" would send the caller to an
     *         operation that cannot resolve it
     */
    public static ContainerScope of(String containerFqn)
    {
        if (containerFqn == null || containerFqn.isEmpty())
        {
            return METADATA_OBJECT;
        }
        String[] segments = MetadataTypeCatalog.normalizeFqn(containerFqn).split("\\."); //$NON-NLS-1$
        if (segments.length == 0)
        {
            return METADATA_OBJECT;
        }
        if (COMMON_FORM.equals(segments[0]))
        {
            return FORM;
        }
        if (COMMON_TEMPLATE.equals(segments[0]))
        {
            return TEMPLATE;
        }
        for (int i = 2; i < segments.length; i += 2)
        {
            if (isFormKind(segments[i]))
            {
                return FORM;
            }
            if (isTemplateKind(segments[i]))
            {
                return TEMPLATE;
            }
        }
        return METADATA_OBJECT;
    }

    /**
     * Whether the segment names the form kind.
     * <p>
     * The plural is accepted because a caller that built the FQN from a source path writes
     * {@code Forms} - the directory is named that way - and refusing it would reject a container
     * that exists.
     * </p>
     *
     * @param segment one segment of the FQN
     * @return <code>true</code> when it names a form
     */
    private static boolean isFormKind(String segment)
    {
        return "Form".equals(segment) //$NON-NLS-1$
            || "Forms".equals(segment) //$NON-NLS-1$
            || "Форма".equals(segment); //$NON-NLS-1$
    }

    /**
     * Whether the segment names the template kind.
     *
     * @param segment one segment of the FQN
     * @return <code>true</code> when it names a template
     */
    private static boolean isTemplateKind(String segment)
    {
        return "Template".equals(segment) //$NON-NLS-1$
            || "Templates".equals(segment) //$NON-NLS-1$
            || "Макет".equals(segment); //$NON-NLS-1$
    }
}
