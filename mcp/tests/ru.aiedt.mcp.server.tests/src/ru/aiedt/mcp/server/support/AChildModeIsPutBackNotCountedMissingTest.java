/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.UUID;

import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;

/**
 * A restore reaches the subordinate objects it recorded, or names its failure to.
 * <p>
 * The registry keeps a mode for an attribute, a form, a template, a command and a nested subsystem
 * as readily as for the object that owns them, and a snapshot copies those entries. The write used
 * to ask {@code bmGetFqn()} for each one first, which answers for top objects only and throws for
 * everything under one - so every subordinate came back unreachable and the restore counted it as
 * absent from a configuration that had it, with {@code applied} saying nothing about the half it
 * never wrote.
 * </p>
 */
public class AChildModeIsPutBackNotCountedMissingTest
{
    @SuppressWarnings("unchecked")
    private static Map<UUID, MdObject> indexObjects(Configuration configuration) throws Exception
    {
        Method method = BmSupportRegistryHelper.class.getDeclaredMethod("indexObjects", //$NON-NLS-1$
            Configuration.class);
        method.setAccessible(true);
        return (Map<UUID, MdObject>)method.invoke(null, configuration);
    }

    /**
     * A write transaction that gives its own instance on both routes, and gives different ones.
     * <p>
     * Both routes answer, so a comparison that takes the FQN one is caught by which instance comes
     * back rather than by a <code>null</code> that could as well mean the transaction holds nothing.
     * </p>
     *
     * @param byId the instance the identity route answers with.
     * @param byFqn the instance the fully qualified name route answers with.
     * @return the stand-in transaction
     */
    private static IBmTransaction transactionAnswering(MdObject byId, MdObject byFqn)
    {
        long identity = ((IBmObject)byId).bmGetId();
        return (IBmTransaction)Proxy.newProxyInstance(IBmTransaction.class.getClassLoader(),
            new Class<?>[] {IBmTransaction.class},
            (proxy, method, args) -> {
                if ("getObjectById".equals(method.getName()) && args != null && args.length == 1 //$NON-NLS-1$
                    && args[0] instanceof Long && ((Long)args[0]).longValue() == identity)
                {
                    return byId;
                }
                if ("getTopObjectByFqn".equals(method.getName())) //$NON-NLS-1$
                {
                    return byFqn;
                }
                // Only the two routes are read; any other call is a write this test does not make.
                return null;
            });
    }

    /**
     * The transaction's own instance of a subordinate object is reached by identity.
     * <p>
     * The transaction hands back its instance of what the object carries in {@code bmGetId()}, which
     * is the only route that answers for an attribute, a tabular section, a form or a command. The
     * FQN route answers for top objects only, and this test's transaction answers it with the object
     * that owns the attribute - so a write that took that route would put the mode on the owner.
     * </p>
     */
    @Test
    public void aSubordinateObjectIsReachedByIdentityNotByFqn()
    {
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName("Товары"); //$NON-NLS-1$
        CatalogAttribute attribute = MdClassFactory.eINSTANCE.createCatalogAttribute();
        attribute.setName("Автор"); //$NON-NLS-1$
        catalog.getAttributes().add(attribute);

        IBmTransaction tx = transactionAnswering(attribute, catalog);

        assertSame("the mode has to be written onto the instance the transaction holds of the"
            + " attribute, not onto the object its fully qualified name would answer",
            attribute, BmSupportRegistryHelper.attachedCopy(tx, attribute));
    }

    /** An attribute is a target reached through its owner, not an object that is not there. */
    @Test
    public void theAttributeOfAnObjectIsATargetReachedThroughItsOwner() throws Exception
    {
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName("Товары"); //$NON-NLS-1$
        CatalogAttribute attribute = MdClassFactory.eINSTANCE.createCatalogAttribute();
        attribute.setName("Автор"); //$NON-NLS-1$
        catalog.getAttributes().add(attribute);

        assertEquals(BmSupportRegistryHelper.RestoreTarget.CHILD_BY_OWNER,
            BmSupportRegistryHelper.restoreTargetFor(attribute));
    }

    /** A top object and the configuration itself are written directly. */
    @Test
    public void theOwningObjectAndTheConfigurationAreTheirOwnTargets()
    {
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName("Товары"); //$NON-NLS-1$
        assertEquals(BmSupportRegistryHelper.RestoreTarget.TOP,
            BmSupportRegistryHelper.restoreTargetFor(catalog));

        Configuration configuration = MdClassFactory.eINSTANCE.createConfiguration();
        assertEquals(BmSupportRegistryHelper.RestoreTarget.TOP,
            BmSupportRegistryHelper.restoreTargetFor(configuration));
    }

    /** Only an entry whose object is nowhere at all is the missing one. */
    @Test
    public void anObjectThatIsNotThereIsTheOnlyMissingTarget()
    {
        assertEquals(BmSupportRegistryHelper.RestoreTarget.MISSING,
            BmSupportRegistryHelper.restoreTargetFor(null));
    }

    /** The attribute the snapshot recorded is in the index the write reads from. */
    @Test
    public void subordinateObjectsAreIndexedForTheRestore() throws Exception
    {
        Configuration configuration = MdClassFactory.eINSTANCE.createConfiguration();
        configuration.setUuid(UUID.randomUUID());
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName("Товары"); //$NON-NLS-1$
        catalog.setUuid(UUID.randomUUID());
        configuration.getCatalogs().add(catalog);
        CatalogAttribute attribute = MdClassFactory.eINSTANCE.createCatalogAttribute();
        attribute.setName("Автор"); //$NON-NLS-1$
        attribute.setUuid(UUID.randomUUID());
        catalog.getAttributes().add(attribute);

        Map<UUID, MdObject> index = indexObjects(configuration);

        assertTrue("the attribute carries a mode of its own in the registry, so the restore reads"
            + " it from the index or counts it missing", index.containsKey(attribute.getUuid()));
    }

    /**
     * A subsystem nested one level down keeps a mode of its own, and the index has to reach it.
     * <p>
     * Nested subsystems do not arrive through {@code eAllContents} - the naming walk found the same
     * thing when every nested subsystem came back unnamed - so the index asks the typed accessor,
     * and a restore that did not would count two thirds of a configuration's subsystems absent.
     * </p>
     */
    @Test
    public void nestedSubsystemsAreIndexedForTheRestore() throws Exception
    {
        Configuration configuration = MdClassFactory.eINSTANCE.createConfiguration();
        configuration.setUuid(UUID.randomUUID());
        Subsystem outer = MdClassFactory.eINSTANCE.createSubsystem();
        outer.setName("Продажи"); //$NON-NLS-1$
        outer.setUuid(UUID.randomUUID());
        configuration.getSubsystems().add(outer);
        Subsystem nested = MdClassFactory.eINSTANCE.createSubsystem();
        nested.setName("Розница"); //$NON-NLS-1$
        nested.setUuid(UUID.randomUUID());
        outer.getSubsystems().add(nested);

        Map<UUID, MdObject> index = indexObjects(configuration);

        assertTrue("a nested subsystem is an ordinary entry of the registry and has to be in the"
            + " index a restore writes from", index.containsKey(nested.getUuid()));
    }

    /**
     * A subsystem cycle is indexed, not recursed into forever.
     * <p>
     * {@code Subsystem.subsystems} is a reference, not a containment, so a damaged model can point
     * two subsystems at each other. The index has to walk each of them once and stop, because the
     * alternative is a {@code StackOverflowError} in place of an answer.
     * </p>
     */
    @Test
    public void aSubsystemCycleIsIndexedOnceAndDoesNotBreakTheIndex() throws Exception
    {
        Configuration configuration = MdClassFactory.eINSTANCE.createConfiguration();
        configuration.setUuid(UUID.randomUUID());
        Subsystem first = MdClassFactory.eINSTANCE.createSubsystem();
        first.setName("Продажи"); //$NON-NLS-1$
        first.setUuid(UUID.randomUUID());
        configuration.getSubsystems().add(first);
        Subsystem second = MdClassFactory.eINSTANCE.createSubsystem();
        second.setName("Розница"); //$NON-NLS-1$
        second.setUuid(UUID.randomUUID());
        first.getSubsystems().add(second);
        second.getSubsystems().add(first);

        Map<UUID, MdObject> index = indexObjects(configuration);

        assertTrue("the cycle is walked once, not recursed into until the stack gives out",
            index.containsKey(first.getUuid()));
        assertTrue("every subsystem of the cycle keeps its own entry in the index",
            index.containsKey(second.getUuid()));
    }
}
