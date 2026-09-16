/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;

import org.eclipse.emf.common.util.BasicEList;
import org.eclipse.emf.common.util.EList;
import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;

/**
 * A reference is compared by the address it points at, not by the instance it points to.
 * <p>
 * The two sides of this comparison are two projects, and an object of one is never the same
 * instance as the object of the other. Compared by identity, every reference feature came back as
 * changed: on a pair of demonstration configurations, 250 objects were reported modified and twelve
 * of them had no difference at all - defaultListForm, defaultObjectForm, inputByString, content,
 * owners, basedOn.
 * </p>
 */
public class AReferenceIsComparedByAddressTest
{
    private static Catalog catalogNamed(String name)
    {
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName(name);
        return catalog;
    }

    private static boolean sameTarget(Object ours, Object theirs) throws Exception
    {
        Method method = MetadataDiffEngine.class.getDeclaredMethod("sameTarget", //$NON-NLS-1$
            Object.class, Object.class);
        method.setAccessible(true);
        return ((Boolean)method.invoke(null, ours, theirs)).booleanValue();
    }

    /** Two projects hold two objects of the same name: the reference has not changed. */
    @Test
    public void theSameNameInTwoProjectsIsTheSameTarget() throws Exception
    {
        assertTrue("one configuration copied is not one that differs", //$NON-NLS-1$
            sameTarget(catalogNamed("Товары"), catalogNamed("Товары"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A reference that really points elsewhere still reads as a change. */
    @Test
    public void adifferentNameIsADifferentTarget() throws Exception
    {
        assertFalse("a reference that moved is a change", //$NON-NLS-1$
            sameTarget(catalogNamed("Товары"), catalogNamed("Услуги"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Nothing on one side and something on the other is a change; nothing on both is not. */
    @Test
    public void absenceIsComparedToo() throws Exception
    {
        assertTrue("neither side points anywhere", sameTarget(null, null)); //$NON-NLS-1$
        assertFalse("one side started pointing somewhere", //$NON-NLS-1$
            sameTarget(null, catalogNamed("Товары"))); //$NON-NLS-1$
        assertFalse("and one side stopped", sameTarget(catalogNamed("Товары"), null)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<String> changesBetween(Object ours, Object theirs) throws Exception
    {
        Method method = MetadataDiffEngine.class.getDeclaredMethod("listChanges", //$NON-NLS-1$
            org.eclipse.emf.ecore.EObject.class, org.eclipse.emf.ecore.EObject.class);
        method.setAccessible(true);
        return (java.util.List<String>)method.invoke(null, ours, theirs);
    }

    /**
     * The engine itself asks about the address, not only the helper that can.
     * <p>
     * Written after the first version of this test passed with the engine comparing by identity
     * again: it exercised the helper, and the defect was that the engine did not call it.
     * </p>
     */
    @Test
    public void theEngineReportsNoChangeWhenOnlyTheInstanceDiffers() throws Exception
    {
        Catalog ours = catalogNamed("Товары"); //$NON-NLS-1$
        Catalog theirs = catalogNamed("Товары"); //$NON-NLS-1$
        ours.getOwners().add(catalogNamed("Контрагенты")); //$NON-NLS-1$
        theirs.getOwners().add(catalogNamed("Контрагенты")); //$NON-NLS-1$
        assertFalse(changesBetween(ours, theirs).toString(),
            changesBetween(ours, theirs).contains("owners")); //$NON-NLS-1$

        theirs.getOwners().clear();
        theirs.getOwners().add(catalogNamed("Организации")); //$NON-NLS-1$
        assertTrue("an owner that really changed is still a change", //$NON-NLS-1$
            changesBetween(ours, theirs).contains("owners")); //$NON-NLS-1$
    }

    /** A list of references is compared entry by entry, in order. */
    @Test
    public void aListIsComparedEntryByEntry() throws Exception
    {
        EList<Object> ours = new BasicEList<>();
        ours.add(catalogNamed("Товары")); //$NON-NLS-1$
        ours.add(catalogNamed("Склады")); //$NON-NLS-1$
        EList<Object> same = new BasicEList<>();
        same.add(catalogNamed("Товары")); //$NON-NLS-1$
        same.add(catalogNamed("Склады")); //$NON-NLS-1$
        assertTrue("the same two names in the same order", sameTarget(ours, same)); //$NON-NLS-1$

        EList<Object> reordered = new BasicEList<>();
        reordered.add(catalogNamed("Склады")); //$NON-NLS-1$
        reordered.add(catalogNamed("Товары")); //$NON-NLS-1$
        assertFalse("order is part of a list of references", sameTarget(ours, reordered)); //$NON-NLS-1$

        EList<Object> shorter = new BasicEList<>();
        shorter.add(catalogNamed("Товары")); //$NON-NLS-1$
        assertFalse("and so is length", sameTarget(ours, shorter)); //$NON-NLS-1$
    }
}
