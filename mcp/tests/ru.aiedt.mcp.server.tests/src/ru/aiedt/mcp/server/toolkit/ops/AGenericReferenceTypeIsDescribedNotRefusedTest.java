/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.xtext.resource.IEObjectDescription;
import org.junit.Test;

import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeSet;
import com._1c.g5.v8.dt.platform.IEObjectProvider;
import com._1c.g5.v8.dt.platform.version.Version;

/**
 * A name that stands for a set of types is answered about, not refused.
 * <p>
 * {@code СправочникСсылка} and {@code ДокументСсылка} are the names an agent reaches for when it
 * writes code against an arbitrary reference, and the call refused both with "unable to locate type"
 * while the same answer printed them further down under "Known types". The register keeps such a name
 * as a type set, and the lookup accepted only a type - so it collected a name, printed it, and then
 * threw it away one line later. The evidence is two answers from a live server naming those two names
 * in the list of what the call knows.
 * </p>
 * <p>
 * What a set can answer is what it holds: a set has no members and no context of its own, so the
 * answer says which types it covers and how many of them there are. Asking it for a member is refused
 * for that reason, not as a name the platform does not have.
 * </p>
 */
public class AGenericReferenceTypeIsDescribedNotRefusedTest
{
    /** The version whose bundle the test launch pulls in; the register is empty for any other. */
    private static final Version PRESENT = Version.V8_3_22;

    private static IEObjectProvider register()
    {
        return IEObjectProvider.Registry.INSTANCE.get(McorePackage.Literals.TYPE_ITEM, PRESENT);
    }

    private static EObject resolved(String name)
    {
        IEObjectDescription desc = register().getEObjectDescription(name);
        assertNotNull("the register does not hold " + name, desc); //$NON-NLS-1$
        return PlatformDocReader.resolveTypeItem(desc);
    }

    private static TypeSet setOf(String name, String... members)
    {
        TypeSet set = McoreFactory.eINSTANCE.createTypeSet();
        set.setName(name);
        for (String member : members)
        {
            Type type = McoreFactory.eINSTANCE.createType();
            type.setName(member);
            set.getBaseTypes().add(type);
        }
        return set;
    }

    /** The premise of the finding, measured on the register rather than quoted from the report. */
    @Test
    public void aGenericReferenceNameIsASetInTheRegister()
    {
        assertTrue("the register answers " + resolved("СправочникСсылка") + " for СправочникСсылка", //$NON-NLS-1$ //$NON-NLS-2$
            resolved("СправочникСсылка") instanceof TypeSet); //$NON-NLS-1$
        assertTrue(resolved("ДокументСсылка") instanceof TypeSet); //$NON-NLS-1$
    }

    /** An ordinary type is still a type, so the answer a caller reads today does not change. */
    @Test
    public void anOrdinaryTypeIsNotASet()
    {
        assertTrue(resolved("Массив") instanceof Type); //$NON-NLS-1$
    }

    /**
     * One question decides both what the list prints and what the lookup accepts.
     * <p>
     * The list used to be filled from every entry the register hands over while the lookup accepted
     * only a type, and the two disagreed on exactly the names that started this. The sign of a type or
     * a set is a class judgement and needs no model, which is why it can be pinned here.
     * </p>
     */
    @Test
    public void theSignOfATypeOrASetIsReadFromTheClassAlone()
    {
        assertTrue(PlatformDocReader.isDocumentable(McorePackage.Literals.TYPE));
        assertTrue(PlatformDocReader.isDocumentable(McorePackage.Literals.TYPE_SET));
        assertFalse(PlatformDocReader.isDocumentable(McorePackage.Literals.METHOD));
        assertFalse(PlatformDocReader.isDocumentable(null));
    }

    /** Everything the list prints is something the lookup can answer about. */
    @Test
    public void everyNameTheListPrintsResolvesToSomethingTheCallAnswersAbout()
    {
        List<IEObjectDescription> accepted = new ArrayList<>();
        for (IEObjectDescription desc : register().getEObjectDescriptions(null))
        {
            if (PlatformDocReader.isDocumentable(desc.getEClass()))
            {
                accepted.add(desc);
            }
        }
        assertFalse("the register answered nothing at all", accepted.isEmpty()); //$NON-NLS-1$

        int checked = 0;
        for (IEObjectDescription desc : accepted)
        {
            if (checked >= 30)
            {
                break;
            }
            checked++;
            EObject object = PlatformDocReader.resolveTypeItem(desc);
            assertTrue(desc.getName() + " is printed as known and resolves to " + object, //$NON-NLS-1$
                object instanceof Type || object instanceof TypeSet);
        }
        assertEquals("the register holds fewer names than the list prints", 30, checked); //$NON-NLS-1$
    }

    /** A set is answered about, with how many types it covers and which ones. */
    @Test
    public void aSetIsDescribedWithTheTypesItCovers()
    {
        String answer = PlatformDocReader.describeTypeSet(
            setOf("CatalogRef", "CatalogRef.Products", "CatalogRef.Services"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            null, 50, false, PRESENT);

        assertFalse("a set the register holds is not a refusal: " + answer, //$NON-NLS-1$
            answer.startsWith("Error:")); //$NON-NLS-1$
        assertTrue(answer.contains("CatalogRef")); //$NON-NLS-1$
        assertTrue("the answer does not say how many types the set holds: " + answer, //$NON-NLS-1$
            answer.contains("(2)")); //$NON-NLS-1$
        assertTrue(answer.contains("CatalogRef.Products")); //$NON-NLS-1$
        assertTrue(answer.contains("CatalogRef.Services")); //$NON-NLS-1$
        assertTrue("the answer does not say the set has no members of its own: " + answer, //$NON-NLS-1$
            answer.contains("no members")); //$NON-NLS-1$
    }

    /** A member asked of a set is refused for being a set, not as a name the platform lacks. */
    @Test
    public void aMemberRequestedFromASetIsRefusedForBeingASet()
    {
        String answer = PlatformDocReader.describeTypeSet(setOf("CatalogRef", "CatalogRef.Products"), //$NON-NLS-1$ //$NON-NLS-2$
            "Add", 50, false, PRESENT); //$NON-NLS-1$

        assertTrue("a member of a set has to be refused: " + answer, answer.startsWith("Error:")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the reason does not say the name is a set of types: " + answer, //$NON-NLS-1$
            answer.contains("set of types")); //$NON-NLS-1$
        assertFalse("the reason is not that the name is unknown: " + answer, //$NON-NLS-1$
            answer.contains("unable to locate type")); //$NON-NLS-1$
    }

    /** The bound holds for the names of the set too, and the count of the rest is still named. */
    @Test
    public void aSetPrintsNoMoreTypesThanTheBoundAllows()
    {
        String answer = PlatformDocReader.describeTypeSet(
            setOf("AnyRef", "AnyRef1", "AnyRef2", "AnyRef3"), null, 1, false, PRESENT); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertTrue(answer.contains("AnyRef1")); //$NON-NLS-1$
        assertFalse("the bound of one printed another name too: " + answer, //$NON-NLS-1$
            answer.contains("AnyRef2")); //$NON-NLS-1$
        assertTrue("the answer does not say how many types it left out: " + answer, //$NON-NLS-1$
            answer.contains("2 more not shown")); //$NON-NLS-1$
    }

    /** A set with nothing in it is still a description of a set, not a refusal or an empty answer. */
    @Test
    public void anEmptySetSaysSoInsteadOfPrintingNothing()
    {
        String answer = PlatformDocReader.describeTypeSet(setOf("EmptyRef"), null, 50, false, PRESENT); //$NON-NLS-1$

        assertTrue(answer.contains("(0)")); //$NON-NLS-1$
        assertTrue(answer.contains("no types")); //$NON-NLS-1$
    }
}
