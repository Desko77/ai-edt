/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.junit.Test;

/**
 * A form root holds handlers in two containers, and the event decides which one.
 * <p>
 * Measured over 1157 forms of a working configuration: the split is clean, and no event stands in
 * both. Six of the eight events a caller is most likely to attach to a form root live in the
 * extInfo - and every one of them used to be written onto the form instead, which leaves the event
 * bound twice on a form EDT had already touched.
 * </p>
 */
public class AFormEventKnowsItsContainerTest
{
    /** A form root: carries handlers, and carries an extInfo that carries its own. */
    public interface FakeForm
    {
        Collection<Object> getHandlers();

        Object getExtInfo();
    }

    /** The extInfo: a handler container of its own. */
    public interface FakeExtInfo
    {
        Collection<Object> getHandlers();
    }

    /** A handler, as far as reflection needs one. */
    public interface FakeHandler
    {
        Object getEvent();

        String getName();
    }

    /** An event, which carries the name the containers are matched on. */
    public interface FakeEvent
    {
        String getName();
    }

    // ---- the measured table -------------------------------------------------------------------

    @Test
    public void theEventsMeasuredInTheExtInfoAreClassifiedThere()
    {
        for (String event : Arrays.asList("OnWriteAtServer", "BeforeWriteAtServer", //$NON-NLS-1$ //$NON-NLS-2$
            "AfterWriteAtServer", "OnReadAtServer", "BeforeWrite", "AfterWrite", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "OnLoadUserSettingsAtServer", "OnSaveVariantAtServer")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            assertTrue(event, FormEventContainers.belongsToExtInfo(event));
        }
    }

    @Test
    public void theEventsMeasuredOnTheFormAreNotClassifiedInTheExtInfo()
    {
        // FillCheckProcessingAtServer is here on purpose: it was assumed to belong to the extInfo
        // and the census put it on the form, 68 times.
        for (String event : Arrays.asList("OnCreateAtServer", "OnOpen", "NotificationProcessing", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "BeforeClose", "OnClose", "FillCheckProcessingAtServer", "OnChange", "URLProcessing")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        {
            assertFalse(event, FormEventContainers.belongsToExtInfo(event));
        }
    }

    @Test
    public void theNameIsMatchedWhateverItsCase()
    {
        assertTrue(FormEventContainers.belongsToExtInfo("onwriteatserver")); //$NON-NLS-1$
        assertTrue(FormEventContainers.belongsToExtInfo("ONWRITEATSERVER")); //$NON-NLS-1$
    }

    @Test
    public void nothingIsClassifiedForNothing()
    {
        assertFalse(FormEventContainers.belongsToExtInfo(null));
        assertFalse(FormEventContainers.belongsToExtInfo("")); //$NON-NLS-1$
    }

    // ---- choosing the container -----------------------------------------------------------------

    @Test
    public void anExtInfoEventGoesToTheExtInfo()
    {
        Object extInfo = extInfo();
        Object form = form(extInfo);

        assertSame(extInfo, FormEventContainers.containerFor(form, "OnWriteAtServer")); //$NON-NLS-1$
    }

    @Test
    public void aFormEventGoesToTheForm()
    {
        Object form = form(extInfo());

        assertSame(form, FormEventContainers.containerFor(form, "OnCreateAtServer")); //$NON-NLS-1$
    }

    @Test
    public void aFormWithoutAnExtInfoKeepsTheHandlerOnItself()
    {
        // A handler in the second-best place still runs; a handler nowhere does not.
        Object form = form(null);

        assertSame(form, FormEventContainers.containerFor(form, "OnWriteAtServer")); //$NON-NLS-1$
    }

    @Test
    public void bothContainersAreListedWhenBothExist()
    {
        Object extInfo = extInfo();
        Object form = form(extInfo);

        assertEquals(Arrays.asList(form, extInfo), FormEventContainers.containersOf(form));
    }

    @Test
    public void onlyTheFormIsListedWhenItHasNoExtInfo()
    {
        Object form = form(null);

        assertEquals(1, FormEventContainers.containersOf(form).size());
    }

    // ---- finding a duplicate in either container --------------------------------------------------

    @Test
    public void aHandlerAlreadyBoundInTheExtInfoIsFound()
    {
        Object extInfo = extInfo(handler("OnWriteAtServer", "ПриЗаписиНаСервере")); //$NON-NLS-1$ //$NON-NLS-2$
        Object form = form(extInfo);

        String bound = FormEventContainers.boundHandlerFor(form, "OnWriteAtServer"); //$NON-NLS-1$

        assertNotNull(bound);
        assertTrue(bound, bound.contains("ПриЗаписиНаСервере")); //$NON-NLS-1$
        assertTrue(bound, bound.contains("extInfo")); //$NON-NLS-1$
    }

    @Test
    public void aHandlerAlreadyBoundOnTheFormIsFound()
    {
        Object form = form(extInfo(), handler("OnOpen", "ПриОткрытии")); //$NON-NLS-1$ //$NON-NLS-2$

        String bound = FormEventContainers.boundHandlerFor(form, "OnOpen"); //$NON-NLS-1$

        assertNotNull(bound);
        assertTrue(bound, bound.contains("ПриОткрытии")); //$NON-NLS-1$
        assertTrue(bound, bound.contains("the form")); //$NON-NLS-1$
    }

    @Test
    public void anEventNobodyHandlesIsFree()
    {
        Object form = form(extInfo(handler("OnWriteAtServer", "A")), handler("OnOpen", "B")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertNull(FormEventContainers.boundHandlerFor(form, "OnReadAtServer")); //$NON-NLS-1$
    }

    @Test
    public void theEventIsFoundInTheOtherContainerThanTheOneItBelongsTo()
    {
        // The case the defect produced: an event of the extInfo standing on the form. The search
        // has to see it, or the caller is allowed to bind it a second time.
        Object form = form(extInfo(), handler("OnWriteAtServer", "СтарыйОбработчик")); //$NON-NLS-1$ //$NON-NLS-2$

        String bound = FormEventContainers.boundHandlerFor(form, "OnWriteAtServer"); //$NON-NLS-1$

        assertNotNull("a handler in the wrong container is still a handler", bound); //$NON-NLS-1$
        assertTrue(bound, bound.contains("СтарыйОбработчик")); //$NON-NLS-1$
    }

    @Test
    public void aHandlerWhoseEventCannotBeReadIsNotTakenForTheEventAskedAbout()
    {
        Object form = form(extInfo(), handler(null, "Поврежденный")); //$NON-NLS-1$

        assertNull(FormEventContainers.boundHandlerFor(form, "OnOpen")); //$NON-NLS-1$
    }

    // ---- fakes ------------------------------------------------------------------------------------

    private static Object form(Object extInfo, Object... handlers)
    {
        List<Object> list = new ArrayList<>(Arrays.asList(handlers));
        return Proxy.newProxyInstance(FakeForm.class.getClassLoader(),
            new Class<?>[] { FakeForm.class }, (self, method, args) -> {
                switch (method.getName())
                {
                    case "getHandlers": //$NON-NLS-1$
                        return list;
                    case "getExtInfo": //$NON-NLS-1$
                        return extInfo;
                    case "toString": //$NON-NLS-1$
                        return "form"; //$NON-NLS-1$
                    case "hashCode": //$NON-NLS-1$
                        return System.identityHashCode(self);
                    case "equals": //$NON-NLS-1$
                        return self == args[0];
                    default:
                        return null;
                }
            });
    }

    private static Object extInfo(Object... handlers)
    {
        List<Object> list = new ArrayList<>(Arrays.asList(handlers));
        return Proxy.newProxyInstance(FakeExtInfo.class.getClassLoader(),
            new Class<?>[] { FakeExtInfo.class }, (self, method, args) -> {
                switch (method.getName())
                {
                    case "getHandlers": //$NON-NLS-1$
                        return list;
                    case "toString": //$NON-NLS-1$
                        return "extInfo"; //$NON-NLS-1$
                    case "hashCode": //$NON-NLS-1$
                        return System.identityHashCode(self);
                    case "equals": //$NON-NLS-1$
                        return self == args[0];
                    default:
                        return null;
                }
            });
    }

    private static Object handler(String eventName, String handlerName)
    {
        Object event = eventName == null ? null
            : Proxy.newProxyInstance(FakeEvent.class.getClassLoader(),
                new Class<?>[] { FakeEvent.class },
                (self, method, args) -> "getName".equals(method.getName()) ? eventName : null); //$NON-NLS-1$
        return Proxy.newProxyInstance(FakeHandler.class.getClassLoader(),
            new Class<?>[] { FakeHandler.class }, (self, method, args) -> {
                switch (method.getName())
                {
                    case "getEvent": //$NON-NLS-1$
                        return event;
                    case "getName": //$NON-NLS-1$
                        return handlerName;
                    case "hashCode": //$NON-NLS-1$
                        return System.identityHashCode(self);
                    case "equals": //$NON-NLS-1$
                        return self == args[0];
                    default:
                        return null;
                }
            });
    }
}
