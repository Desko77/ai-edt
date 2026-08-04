/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.Map;

import org.eclipse.core.runtime.IProduct;
import org.eclipse.core.runtime.Platform;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.Version;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.toolkit.IMcpTool;

/**
 * Reports the version of the 1C:EDT that hosts the plugin.
 * <p>
 * The answer is a bare version string, delivered as markdown. It is the one fact the server-info and
 * health endpoints need too, so the work lives in a public static method they share.
 * </p>
 * <p>
 * There is no single source of truth for the version, so several are tried in turn. The build id the
 * platform is stamped with is the one a running EDT almost always carries; the rest are fallbacks for
 * the odd runtime that does not set it. Nothing here fails - the worst answer is {@code Unknown}.
 * </p>
 */
public class EdtVersionReader
    implements IMcpTool
{
    private static final String EDT_BUNDLE_PREFIX = "com._1c.g5.v8.dt"; //$NON-NLS-1$

    private static final String RCP_BUNDLE = "com._1c.g5.v8.dt.rcp"; //$NON-NLS-1$

    private static final String UNKNOWN = "Unknown"; //$NON-NLS-1$

    private static final String VERSION_QUALIFIER_MARK = ".v"; //$NON-NLS-1$

    private static final int QUALIFIER_MIN_LENGTH = 6;

    private static final int HALF_YEAR_LAST_MONTH = 6;

    @Override
    public String getName()
    {
        return "get_edt_version"; //$NON-NLS-1$
    }

    @Override
    public String getDescription()
    {
        return "Get 1C:EDT version"; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object().build();
    }

    @Override
    public String execute(Map<String, String> params)
    {
        return getEdtVersion();
    }

    /**
     * Determines the hosting EDT version, taking the first source that answers.
     * <p>
     * Public and static because the server info and health responses report the same version and must
     * not go through the tool-call path to get it. Runs on the calling thread.
     * </p>
     *
     * @return the version string, or {@code Unknown} when nothing could be determined
     */
    public static String getEdtVersion()
    {
        try
        {
            String buildId = System.getProperty("eclipse.buildId"); //$NON-NLS-1$
            if (buildId != null && !buildId.isEmpty())
            {
                return buildId;
            }

            IProduct product = Platform.getProduct();
            if (product != null)
            {
                String fromProduct = versionOf(product.getDefiningBundle());
                if (fromProduct != null)
                {
                    return fromProduct;
                }
            }

            String fromRcp = versionOf(Platform.getBundle(RCP_BUNDLE));
            if (fromRcp != null)
            {
                return fromRcp;
            }

            String fromEdt = versionOf(firstEdtBundle());
            if (fromEdt != null)
            {
                return fromEdt;
            }

            String productId = System.getProperty("eclipse.product"); //$NON-NLS-1$
            if (productId != null && !productId.isEmpty())
            {
                return productId;
            }

            return UNKNOWN;
        }
        catch (Exception e)
        {
            Activator.logError("Could not determine the EDT version", e); //$NON-NLS-1$
            return UNKNOWN;
        }
    }

    /**
     * Renders a bundle's version, preferring the marketing form when the qualifier yields one.
     *
     * @param bundle the bundle, or <code>null</code>
     * @return {@code marketing (raw)} when the qualifier converts, the raw version otherwise, or
     *         <code>null</code> when there is no bundle
     */
    private static String versionOf(Bundle bundle)
    {
        if (bundle == null)
        {
            return null;
        }
        Version version = bundle.getVersion();
        String raw = version.toString();
        String marketing = convertToMarketingVersion(raw);
        return marketing != null ? marketing + " (" + raw + ")" : raw; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Finds the first installed bundle whose symbolic name is EDT's.
     *
     * @return the bundle, or <code>null</code> when there is no bundle context or no EDT bundle
     */
    private static Bundle firstEdtBundle()
    {
        Bundle self = FrameworkUtil.getBundle(EdtVersionReader.class);
        if (self == null)
        {
            return null;
        }
        BundleContext context = self.getBundleContext();
        if (context == null)
        {
            return null;
        }
        for (Bundle bundle : context.getBundles())
        {
            String name = bundle.getSymbolicName();
            if (name != null && name.startsWith(EDT_BUNDLE_PREFIX))
            {
                return bundle;
            }
        }
        return null;
    }

    /**
     * Reads a marketing version out of an OSGi version qualifier.
     * <p>
     * The qualifier begins with a build date, and the release number is the half of the year the month
     * falls in: {@code 202512...} becomes {@code 2025.2.0}.
     * </p>
     *
     * @param version the OSGi version string
     * @return the marketing version, or <code>null</code> when the qualifier does not carry a date
     */
    private static String convertToMarketingVersion(String version)
    {
        int mark = version.indexOf(VERSION_QUALIFIER_MARK);
        if (mark < 0)
        {
            return null;
        }
        String qualifier = version.substring(mark + VERSION_QUALIFIER_MARK.length());
        if (qualifier.length() < QUALIFIER_MIN_LENGTH)
        {
            return null;
        }
        try
        {
            String year = qualifier.substring(0, 4);
            int month = Integer.parseInt(qualifier.substring(4, 6));
            Integer.parseInt(year);
            int release = month <= HALF_YEAR_LAST_MONTH ? 1 : 2;
            return year + "." + release + ".0"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }
}
