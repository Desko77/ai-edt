package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import org.junit.BeforeClass;
import org.junit.Test;
import org.osgi.framework.Bundle;

import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.metadata.common.AllowedLength;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogCodeType;
import com._1c.g5.v8.dt.metadata.mdclass.Document;
import com._1c.g5.v8.dt.metadata.mdclass.DocumentNumberPeriodicity;
import com._1c.g5.v8.dt.metadata.mdclass.DocumentNumberType;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.Posting;
import com._1c.g5.v8.dt.platform.version.Version;

/**
 * What a top-level object carries when the server creates it: the defaults the EDT wizard writes
 * into the {@code .mdo}, and not the Ecore defaults an uninitialized EClass carries.
 *
 * <p>The values asserted here are the wizard's master for a catalog and a document, read off the
 * per-type initializers the wizard's own creation route runs.
 */
public class ObjectCreationDefaultsTest
{
    /** How long the metadata model factory is waited for after its bundle is started. */
    private static final long FACTORY_WAIT_MILLIS = 30_000L;

    /**
     * Starts the metadata model bundle. Its services are published when it starts, and a runtime
     * that never starts it - the headless one this suite runs in - has no factory to ask.
     */
    @BeforeClass
    public static void startMetadataModelBundle()
    {
        Bundle md = org.eclipse.core.runtime.Platform.getBundle("com._1c.g5.v8.dt.md"); //$NON-NLS-1$
        if (md == null)
        {
            return;
        }
        try
        {
            if (md.getState() != Bundle.ACTIVE)
            {
                md.start();
            }
        }
        catch (Exception e)
        {
            throw new IllegalStateException("Cannot start com._1c.g5.v8.dt.md: " + e.getMessage(), e); //$NON-NLS-1$
        }
    }

    /**
     * A catalog the wizard creates carries the standard level count, code and description
     * lengths, code type, uniqueness and standard-command flags. A catalog that misses them is
     * a catalog the user has to correct by hand, and it is what the raw factory produces.
     */
    @Test
    public void aCatalogGetsTheDefaultsTheWizardWrites()
    {
        Catalog catalog = (Catalog) createInitialized("Catalog");
        assertNotNull(catalog.getUuid());
        assertEquals("level count", 2, catalog.getLevelCount()); //$NON-NLS-1$
        assertEquals("code length", 9, catalog.getCodeLength()); //$NON-NLS-1$
        assertEquals("description length", 25, catalog.getDescriptionLength()); //$NON-NLS-1$
        assertEquals("code type", CatalogCodeType.STRING, catalog.getCodeType()); //$NON-NLS-1$
        assertEquals("allowed code length", AllowedLength.VARIABLE, catalog.getCodeAllowedLength()); //$NON-NLS-1$
        assertTrue("unique codes", catalog.isCheckUnique()); //$NON-NLS-1$
        assertTrue("autonumbering", catalog.isAutonumbering()); //$NON-NLS-1$
        assertTrue("folders on top", catalog.isFoldersOnTop()); //$NON-NLS-1$
        assertTrue("standard commands", catalog.isUseStandardCommands()); //$NON-NLS-1$
        // The produced types are the reference the platform resolves a catalog through; a catalog
        // without them is one the rest of the model cannot point at.
        assertNotNull("produced types", catalog.getProducedTypes()); //$NON-NLS-1$
        assertNotNull("produced types reference", catalog.getProducedTypes().getRefType()); //$NON-NLS-1$
        assertNotNull("produced types object", catalog.getProducedTypes().getObjectType()); //$NON-NLS-1$
    }

    /**
     * The same for a document: numbering length and type, uniqueness, posting and standard
     * commands are the wizard's, not the Ecore defaults.
     */
    @Test
    public void aDocumentGetsTheDefaultsTheWizardWrites()
    {
        Document document = (Document) createInitialized("Document");
        assertNotNull(document.getUuid());
        assertEquals("number length", 9, document.getNumberLength()); //$NON-NLS-1$
        assertEquals("number type", DocumentNumberType.STRING, document.getNumberType()); //$NON-NLS-1$
        assertEquals("allowed number length", AllowedLength.VARIABLE, document.getNumberAllowedLength()); //$NON-NLS-1$
        assertEquals("number periodicity", DocumentNumberPeriodicity.NONPERIODICAL, //$NON-NLS-1$
            document.getNumberPeriodicity());
        assertTrue("unique numbers", document.isCheckUnique()); //$NON-NLS-1$
        assertTrue("autonumbering", document.isAutonumbering()); //$NON-NLS-1$
        assertEquals("posting", Posting.ALLOW, document.getPosting()); //$NON-NLS-1$
        assertTrue("standard commands", document.isUseStandardCommands()); //$NON-NLS-1$
    }

    /**
     * The route taken when the factory cannot be reached leaves every one of those features at its
     * Ecore default. This is what the server answered with before, and it is why the initialized
     * route exists - the difference is the defect, not the fallback's own quirk.
     */
    @Test
    public void theRawFactoryRouteCarriesNoWizardDefaults()
    {
        Catalog catalog = (Catalog) BmObjectHelper.createGenericObject("Catalog"); //$NON-NLS-1$
        assertNotNull(catalog);
        assertEquals("raw level count", 0, catalog.getLevelCount()); //$NON-NLS-1$
        assertEquals("raw code length", 0, catalog.getCodeLength()); //$NON-NLS-1$
        assertEquals("raw code type", CatalogCodeType.NUMBER, catalog.getCodeType()); //$NON-NLS-1$
        assertNull("raw produced types", catalog.getProducedTypes()); //$NON-NLS-1$
    }

    /**
     * Creates one top object through the server's own entry point, waiting for the metadata model
     * factory to publish itself - its registration is scheduled, so the first attempts answer
     * nothing even with the bundle already started.
     *
     * @param typeName English bare type name of the object to create.
     * @return the created object.
     */
    private static MdObject createInitialized(String typeName)
    {
        long deadline = System.currentTimeMillis() + FACTORY_WAIT_MILLIS;
        while (true)
        {
            MdObject created = BmObjectHelper.createInitializedObject(typeName, stubProject());
            if (created != null)
            {
                return created;
            }
            if (System.currentTimeMillis() > deadline)
            {
                throw new AssertionError("createInitializedObject(" + typeName //$NON-NLS-1$
                    + ") produced nothing in " + FACTORY_WAIT_MILLIS //$NON-NLS-1$
                    + " ms - the metadata model factory was not reachable."); //$NON-NLS-1$
            }
            try
            {
                Thread.sleep(200L);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for the factory", e); //$NON-NLS-1$
            }
        }
    }

    /**
     * A project that answers with a platform version and nothing else. The initializers read the
     * version and ask whether the project carries a configuration; this stand-in carries none, so
     * the configuration-driven branches stay out of the comparison.
     *
     * @return the stand-in project.
     */
    private static IV8Project stubProject()
    {
        InvocationHandler handler = new InvocationHandler()
        {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args)
            {
                if ("getVersion".equals(method.getName())) //$NON-NLS-1$
                {
                    return Version.LATEST;
                }
                if ("toString".equals(method.getName())) //$NON-NLS-1$
                {
                    return "ObjectCreationDefaultsTest project"; //$NON-NLS-1$
                }
                return null;
            }
        };
        return (IV8Project) Proxy.newProxyInstance(IV8Project.class.getClassLoader(),
            new Class<?>[] { IV8Project.class }, handler);
    }
}
