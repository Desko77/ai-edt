/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.common.util.BasicEList;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EObject;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmObject;

/**
 * A template lives in its owner's list, and the FQN index does not resolve it.
 * <p>
 * Measured on a stand: {@code getTopObjectByFqn(DataProcessor.X.Template.Y)} answered
 * <code>null</code> for a template declared in the owner's {@code .mdo} with its {@code .dcs} on
 * disk, so the restoration reported {@code no_template} for every template there is. The owner
 * is what the index resolves; the template is found in the owner's {@code templates} by name.
 * </p>
 */
public class TheTemplateIsFoundThroughItsOwnerTest
{
    private static final String OWNER = "DataProcessor.Pages"; //$NON-NLS-1$

    private static final String TEMPLATE = OWNER + ".Template.Batch"; //$NON-NLS-1$

    /** An owner with a templates list, as the model exposes one. */
    public interface Owner
        extends IBmObject
    {
        EList<EObject> getTemplates();
    }

    /** A template, as far as the lookup needs one. */
    public interface Template
        extends EObject
    {
        String getName();
    }

    @Test
    public void aTemplateTheIndexResolvesIsTakenAsIs()
    {
        IBmObject indexed = owner();
        List<String> asked = new ArrayList<>();
        Object found = DcsSchemaRestorer.resolveTemplate(TEMPLATE, fqn -> {
            asked.add(fqn);
            return TEMPLATE.equals(fqn) ? indexed : null;
        });
        assertSame(indexed, found);
        assertEquals("the owner is not asked when the index has the template", 1, asked.size()); //$NON-NLS-1$
    }

    @Test
    public void aTemplateTheIndexLacksIsTakenFromItsOwner()
    {
        Template batch = template("Batch"); //$NON-NLS-1$
        Owner owner = owner(template("Other"), batch); //$NON-NLS-1$
        List<String> asked = new ArrayList<>();
        Object found = DcsSchemaRestorer.resolveTemplate(TEMPLATE, fqn -> {
            asked.add(fqn);
            return OWNER.equals(fqn) ? owner : null;
        });
        assertSame(batch, found);
        assertEquals(List.of(TEMPLATE, OWNER), asked);
    }

    @Test
    public void theNameIsMatchedTheWayTheConfigurationMatchesNames()
    {
        Template batch = template("Batch"); //$NON-NLS-1$
        Owner owner = owner(batch);
        Object found = DcsSchemaRestorer.resolveTemplate(OWNER + ".Template.BATCH", //$NON-NLS-1$
            fqn -> OWNER.equals(fqn) ? owner : null);
        assertSame(batch, found);
    }

    @Test
    public void anOwnerWithoutTheTemplateGivesNothing()
    {
        Owner owner = owner(template("Other")); //$NON-NLS-1$
        assertNull(DcsSchemaRestorer.resolveTemplate(TEMPLATE, fqn -> OWNER.equals(fqn) ? owner : null));
    }

    @Test
    public void noOwnerGivesNothing()
    {
        assertNull(DcsSchemaRestorer.resolveTemplate(TEMPLATE, fqn -> null));
    }

    @Test
    public void anFqnWithoutATemplateSegmentIsAskedOfTheIndexOnly()
    {
        List<String> asked = new ArrayList<>();
        assertNull(DcsSchemaRestorer.resolveTemplate("CommonTemplate.Sales", fqn -> { //$NON-NLS-1$
            asked.add(fqn);
            return null;
        }));
        assertEquals(List.of("CommonTemplate.Sales"), asked); //$NON-NLS-1$
    }

    private static Owner owner(Template... templates)
    {
        EList<EObject> list = new BasicEList<>();
        for (Template t : templates)
        {
            list.add(t);
        }
        Map<String, Object> answers = new HashMap<>();
        answers.put("getTemplates", list); //$NON-NLS-1$
        return proxy(Owner.class, answers);
    }

    private static Template template(String name)
    {
        Map<String, Object> answers = new HashMap<>();
        answers.put("getName", name); //$NON-NLS-1$
        return proxy(Template.class, answers);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Map<String, Object> answers)
    {
        return (T)Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (self, method, args) -> {
            if (answers.containsKey(method.getName()))
            {
                return answers.get(method.getName());
            }
            switch (method.getName())
            {
                case "hashCode": //$NON-NLS-1$
                    return System.identityHashCode(self);
                case "equals": //$NON-NLS-1$
                    return self == args[0];
                case "toString": //$NON-NLS-1$
                    return type.getSimpleName();
                default:
                    return null;
            }
        });
    }
}
