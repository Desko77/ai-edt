package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EcoreFactory;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;
import org.osgi.framework.Bundle;

import com._1c.g5.v8.dt.core.model.IModelObjectFactory;
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
     * Puts the factory lookup back to the service registry, so a test that drove one of the
     * failure routes does not decide the route the next test runs.
     */
    @After
    public void restoreFactoryLookup()
    {
        BmObjectHelper.setFactorySupplier(null);
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
     * The route that reaches the factory reports a created object, no reason and nothing to warn
     * about. The warning belongs to the degraded route alone, so a client can read its absence as
     * a fully initialized object.
     */
    @Test
    public void theFactoryRouteReportsNoWarning()
    {
        BmObjectHelper.CreationOutcome outcome = createInitializedOutcome("Catalog"); //$NON-NLS-1$
        assertEquals(BmObjectHelper.CreationOutcome.Status.CREATED, outcome.getStatus());
        assertFalse("a created object is no factory failure", outcome.isFactoryFailure()); //$NON-NLS-1$
        assertNull("a created object has no reason", outcome.getReason()); //$NON-NLS-1$
        assertNull("a fully initialized object carries no warning", outcome.getDefaultsWarning()); //$NON-NLS-1$
    }

    /**
     * Without a registered factory the initializers cannot run, and the object the raw fallback
     * then builds is the one this test's counterpart above shows: Ecore defaults, no produced
     * types. The answer has to say which object the caller got and why.
     */
    @Test
    public void anUnavailableFactoryIsReportedAndWarnsAboutTheMissingDefaults()
    {
        BmObjectHelper.setFactorySupplier(() -> null);
        BmObjectHelper.CreationOutcome outcome =
            BmObjectHelper.createInitializedObjectWithReason("Catalog", stubProject()); //$NON-NLS-1$
        assertNull("no object without a factory", outcome.getObject()); //$NON-NLS-1$
        assertEquals(BmObjectHelper.CreationOutcome.Status.FACTORY_UNAVAILABLE, outcome.getStatus());
        assertTrue("the caller is told the defaults are missing", outcome.isFactoryFailure()); //$NON-NLS-1$
        String reason = outcome.getReason();
        assertNotNull("why nothing was created has to be readable", reason); //$NON-NLS-1$
        assertTrue("the reason names the factory: " + reason, reason.contains("MdObjectFactory")); //$NON-NLS-1$ //$NON-NLS-2$
        String warning = outcome.getDefaultsWarning();
        assertNotNull("a degraded object is reported", warning); //$NON-NLS-1$
        assertTrue("the warning names what is missing: " + warning, //$NON-NLS-1$
            warning.contains("EDT wizard defaults")); //$NON-NLS-1$
        assertTrue("the warning carries the reason: " + warning, warning.contains(reason)); //$NON-NLS-1$
    }

    /**
     * A factory that is there and throws is a different failure from one that is not registered:
     * the initializer ran half-way and the exception is the only thing that explains the object.
     */
    @Test
    public void aThrowingFactoryIsReportedWithItsOwnMessage()
    {
        BmObjectHelper.setFactorySupplier(() -> stubFactory(null, //$NON-NLS-1$
            new IllegalStateException("initializer exploded"))); //$NON-NLS-1$
        BmObjectHelper.CreationOutcome outcome =
            BmObjectHelper.createInitializedObjectWithReason("Catalog", stubProject()); //$NON-NLS-1$
        assertEquals(BmObjectHelper.CreationOutcome.Status.FACTORY_FAILED, outcome.getStatus());
        assertTrue("the caller is told the defaults are missing", outcome.isFactoryFailure()); //$NON-NLS-1$
        assertTrue("the failure text reaches the reason: " + outcome.getReason(), //$NON-NLS-1$
            outcome.getReason().contains("initializer exploded")); //$NON-NLS-1$
        assertTrue("and reaches the warning: " + outcome.getDefaultsWarning(), //$NON-NLS-1$
            outcome.getDefaultsWarning().contains("initializer exploded")); //$NON-NLS-1$
    }

    /**
     * A factory that answers an object of another kind is no better than one that throws: the
     * object the caller would then attach carries nothing the wizard writes. The answer names what
     * came back instead.
     */
    @Test
    public void aFactoryAnsweringSomethingElseIsARecordedFailure()
    {
        EObject foreign = EcoreFactory.eINSTANCE.createEObject();
        BmObjectHelper.setFactorySupplier(() -> stubFactory(foreign, null));
        BmObjectHelper.CreationOutcome outcome =
            BmObjectHelper.createInitializedObjectWithReason("Catalog", stubProject()); //$NON-NLS-1$
        assertNull("a foreign shape is not a metadata object", outcome.getObject()); //$NON-NLS-1$
        assertEquals(BmObjectHelper.CreationOutcome.Status.FACTORY_FAILED, outcome.getStatus());
        assertTrue("the answer says what came back: " + outcome.getReason(), //$NON-NLS-1$
            outcome.getReason().contains("instead of a metadata object")); //$NON-NLS-1$
        assertNotNull(outcome.getDefaultsWarning());
    }

    /**
     * A factory that answers nothing at all is a failure the same way, and the client hears about
     * it rather than about an object that never existed.
     */
    @Test
    public void aFactoryAnsweringNothingIsARecordedFailure()
    {
        BmObjectHelper.setFactorySupplier(() -> stubFactory(null, null));
        BmObjectHelper.CreationOutcome outcome =
            BmObjectHelper.createInitializedObjectWithReason("Catalog", stubProject()); //$NON-NLS-1$
        assertNull(outcome.getObject());
        assertEquals(BmObjectHelper.CreationOutcome.Status.FACTORY_FAILED, outcome.getStatus());
        assertTrue("the answer says that nothing came back: " + outcome.getReason(), //$NON-NLS-1$
            outcome.getReason().contains("answered no object")); //$NON-NLS-1$
        assertNotNull(outcome.getDefaultsWarning());
    }

    /**
     * The two factory failures are told apart by their reason, which is what the creating
     * operation reports: a missing service and a service that fails call for different repairs.
     */
    @Test
    public void theMissingAndTheFailingFactoryCarryDifferentReasons()
    {
        BmObjectHelper.setFactorySupplier(() -> null);
        BmObjectHelper.CreationOutcome missing =
            BmObjectHelper.createInitializedObjectWithReason("Catalog", stubProject()); //$NON-NLS-1$
        BmObjectHelper.setFactorySupplier(() -> stubFactory(null, //$NON-NLS-1$
            new IllegalStateException("initializer exploded"))); //$NON-NLS-1$
        BmObjectHelper.CreationOutcome failing =
            BmObjectHelper.createInitializedObjectWithReason("Catalog", stubProject()); //$NON-NLS-1$
        assertEquals(BmObjectHelper.CreationOutcome.Status.FACTORY_UNAVAILABLE, missing.getStatus());
        assertEquals(BmObjectHelper.CreationOutcome.Status.FACTORY_FAILED, failing.getStatus());
        assertFalse("a missing factory and a failing one read differently: " + missing.getReason(), //$NON-NLS-1$
            missing.getReason().equals(failing.getReason()));
    }

    /**
     * A project that could not be resolved leaves the initializers unrunnable, which is a
     * factory-side failure and not the caller's type name - so it warns like one.
     */
    @Test
    public void aMissingProjectIsReportedAsAnUnrunnableFactory()
    {
        BmObjectHelper.CreationOutcome outcome =
            BmObjectHelper.createInitializedObjectWithReason("Catalog", null); //$NON-NLS-1$
        assertTrue("no project means no initializers", outcome.isFactoryFailure()); //$NON-NLS-1$
        assertNotNull("and the client is told so", outcome.getDefaultsWarning()); //$NON-NLS-1$
    }

    /**
     * A type this runtime has no EClass for is the caller's mistake, not a broken runtime: the
     * call is refused rather than answered with a warning about an object nobody meant to build.
     */
    @Test
    public void anUnresolvableTypeIsNotAFactoryFailure()
    {
        BmObjectHelper.CreationOutcome outcome =
            BmObjectHelper.createInitializedObjectWithReason("NoSuchMetadataType", stubProject()); //$NON-NLS-1$
        assertEquals(BmObjectHelper.CreationOutcome.Status.TYPE_UNRESOLVED, outcome.getStatus());
        assertFalse("the name is wrong, the factory is not", outcome.isFactoryFailure()); //$NON-NLS-1$
        assertNotNull(outcome.getReason());
        assertNull("no defaults warning for a name that resolves to nothing", //$NON-NLS-1$
            outcome.getDefaultsWarning());
    }

    /**
     * A metadata model object factory stand-in. Only {@code create} is meaningful: it throws the
     * given failure, or answers the given object.
     *
     * @param answer what {@code create} returns when it does not throw.
     * @param failure the failure {@code create} throws, or {@code null} to answer instead.
     * @return the stand-in factory.
     */
    private static IModelObjectFactory stubFactory(final Object answer, final RuntimeException failure)
    {
        InvocationHandler handler = new InvocationHandler()
        {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args)
            {
                if (!"create".equals(method.getName())) //$NON-NLS-1$
                {
                    return null;
                }
                if (failure != null)
                {
                    throw failure;
                }
                return answer;
            }
        };
        return (IModelObjectFactory) Proxy.newProxyInstance(IModelObjectFactory.class.getClassLoader(),
            new Class<?>[] { IModelObjectFactory.class }, handler);
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
        return createInitializedOutcome(typeName).getObject();
    }

    /**
     * Drives the creation route until the factory answers an object.
     *
     * @param typeName English bare type name of the object to create.
     * @return the outcome of the attempt that produced the object.
     */
    private static BmObjectHelper.CreationOutcome createInitializedOutcome(String typeName)
    {
        long deadline = System.currentTimeMillis() + FACTORY_WAIT_MILLIS;
        while (true)
        {
            BmObjectHelper.CreationOutcome outcome =
                BmObjectHelper.createInitializedObjectWithReason(typeName, stubProject());
            if (outcome.getObject() != null)
            {
                return outcome;
            }
            if (System.currentTimeMillis() > deadline)
            {
                throw new AssertionError("createInitializedObject(" + typeName //$NON-NLS-1$
                    + ") produced nothing in " + FACTORY_WAIT_MILLIS //$NON-NLS-1$
                    + " ms - the metadata model factory was not reachable. Last reason: " //$NON-NLS-1$
                    + outcome.getReason());
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
