package ru.aiedt.mcp.server.toolkit.ops;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EObject;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.support.BmFormHelper;
import ru.aiedt.mcp.server.support.FormEventRegistry;
import ru.aiedt.mcp.server.support.BmFormGeneratorHelper;
import ru.aiedt.mcp.server.support.MetadataTypeCatalog;
import ru.aiedt.mcp.server.support.ProjectResolver;

/**
 * Form-event cluster of {@code edit_metadata}: attach / detach a BSL event handler
 * to a form or form item. Extracted verbatim from {@link EditMetadataTool} (Inc4
 * god-class split); handlers are package-visible and dispatched through the
 * single-source op-registry. Shared stateless helpers live on {@link EditMetadataTool}
 * (qualified calls); cluster-local helpers ({@link #attachFormEventHandler},
 * {@link #resolveAllowedFormEvent}, {@link #detachFormEventHandler}) are private here.
 */
final class FormEventOps
{
    /**
     * 1.42 (RSV 4.2 parity): assigns an event handler to a form or to a form
     * item (field, table, button) - the same artefact you set via the EDT
     * "Events" panel. Resolves the canonical signature and compilation
     * directive ({@code &НаКлиенте} / {@code &НаСервере}) from
     * {@link FormEventRegistry} so the agent gets a working stub even
     * without knowing the platform's per-event contract.
     *
     * <p>Parameters:
     * <ul>
     *   <li>{@code projectName}, {@code formFqn} - required</li>
     *   <li>{@code itemName} - optional. Empty/missing = handler on the form
     *       root (e.g. ПриОткрытии). Otherwise the handler attaches to the
     *       named form element (field / table / etc).</li>
     *   <li>{@code event} - required. Russian or English event name (e.g.
     *       OnOpen / ПриОткрытии, OnChange / ПриИзменении).</li>
     *   <li>{@code handlerName} - optional. Default:
     *       {@code <itemName><event>} for items, just {@code <event>} for
     *       the form root.</li>
     * </ul>
     *
     * <p>Bound through the {@code getHandlers} / {@code getEventHandlers}
     * EList found on the target via reflection so the call works across
     * EDT versions that use slightly different accessor names. The
     * generated BSL stub is returned in the response payload - the agent
     * pastes it into the form module via {@code write_module_source}
     * (the platform itself cannot autogenerate a procedure body in the
     * middle of a BM transaction).
     */
    String opAddFormEventHandler(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String formFqn = JsonUtils.extractStringArgument(params, "formFqn"); //$NON-NLS-1$
        String itemName = JsonUtils.extractStringArgument(params, "itemName"); //$NON-NLS-1$
        String event = JsonUtils.extractStringArgument(params, "event"); //$NON-NLS-1$
        String handlerNameParam = JsonUtils.extractStringArgument(params, "handlerName"); //$NON-NLS-1$

        String err = EditMetadataTool.requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(formFqn, "formFqn") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(event, "event"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        FormEventRegistry.EventSpec spec = FormEventRegistry.lookup(event);
        if (spec == null)
        {
            return ToolResult.error("Unknown event '" + event //$NON-NLS-1$
                + "'. Pass either an English form event (OnOpen, OnCreateAtServer, " //$NON-NLS-1$
                + "BeforeWriteAtServer, OnChange, ChoiceProcessing, ...) or its " //$NON-NLS-1$
                + "Russian equivalent (ПриОткрытии, ПриСозданииНаСервере, ...). " //$NON-NLS-1$
                + "For event subscriptions on metadata objects (not on forms) use " //$NON-NLS-1$
                + "addEventSubscriptionHandler.").toJson(); //$NON-NLS-1$
        }
        String handlerName = (handlerNameParam != null && !handlerNameParam.isEmpty())
            ? handlerNameParam
            : FormEventRegistry.defaultHandlerName(event, itemName);

        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }
        BmFormHelper helper = new BmFormHelper();
        if (!helper.init())
        {
            return ToolResult.error("EDT form model unavailable in this runtime").toJson(); //$NON-NLS-1$
        }

        final String finalItemName = itemName;
        // Use the canonical English event name (e.g. ПриИзменении -> OnChange):
        // mcore.Event.getName() is English, so the getAllowedEvents match in
        // attachFormEventHandler needs the English form, not the raw RU input.
        final String finalEvent = spec.englishName;
        final String finalHandlerName = handlerName;
        final boolean formDryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$
        String execResult = helper.executeFormOperation(project, formFqn, formDryRun, (tx, form) -> {
            try
            {
                Object target = (finalItemName == null || finalItemName.isEmpty())
                    ? form
                    : helper.findItemByName(form, finalItemName);
                if (target == null)
                {
                    return "Error: Form item not found: " + finalItemName; //$NON-NLS-1$
                }
                return attachFormEventHandler(target, finalEvent, finalHandlerName);
            }
            catch (Exception e)
            {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                return "Error: addEventHandler failed - " //$NON-NLS-1$
                    + (cause.getMessage() != null ? cause.getMessage()
                        : cause.getClass().getSimpleName());
            }
        });
        if (execResult != null && execResult.startsWith("Error:")) //$NON-NLS-1$
        {
            return ToolResult.error(execResult.substring("Error:".length()).trim()).toJson(); //$NON-NLS-1$
        }

        String stub = FormEventRegistry.generateBslStub(handlerName, spec);
        return ToolResult.success()
            .put("operation", "add_form_event_handler") //$NON-NLS-1$ //$NON-NLS-2$
            .put("formFqn", formFqn) //$NON-NLS-1$
            .put("event", event) //$NON-NLS-1$
            .put("handlerName", handlerName) //$NON-NLS-1$
            .put("directive", spec.directive) //$NON-NLS-1$
            .put("signature", spec.signature) //$NON-NLS-1$
            .put("scope", spec.scope.name()) //$NON-NLS-1$
            .put("itemName", itemName != null ? itemName : "") //$NON-NLS-1$ //$NON-NLS-2$
            .put("stub", stub) //$NON-NLS-1$
            .put("hint", //$NON-NLS-1$
                "Handler attached to the form's event list. Append the stub to the " //$NON-NLS-1$
                    + "form's Module.bsl via write_module_source mode=append - the " //$NON-NLS-1$
                    + "platform cannot generate procedure bodies inside a BM " //$NON-NLS-1$
                    + "transaction.") //$NON-NLS-1$
            .toJson();
    }

    /**
     * 1.42 helper: attaches a {@code FormItemEventHandler} (or whatever the
     * EDT runtime calls it) to the target item via reflection. Probes a
     * handful of accessor / factory names so the call works across EDT
     * versions. Returns a status string consumed by the form transaction
     * dispatcher.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static String attachFormEventHandler(Object target, String event, String handlerName)
    {
        // Probe the handler container - EDT 2024+ uses getHandlers, older
        // builds expose getEventHandlers. The list itself is an EList.
        Object handlers = null;
        for (String accessor : new String[] { "getHandlers", "getEventHandlers" }) //$NON-NLS-1$ //$NON-NLS-2$
        {
            try
            {
                handlers = target.getClass().getMethod(accessor).invoke(target);
                if (handlers != null)
                {
                    break;
                }
            }
            catch (NoSuchMethodException nsme)
            {
                // Try next accessor.
            }
            catch (Exception e)
            {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                return "Error: form item handlers accessor failed - " //$NON-NLS-1$
                    + (cause.getMessage() != null ? cause.getMessage()
                        : cause.getClass().getSimpleName());
            }
        }
        if (handlers == null)
        {
            return "Error: formEventApiNotFound - no handlers EList exposed on " //$NON-NLS-1$
                + target.getClass().getSimpleName() //$NON-NLS-1$
                + ". Use the EDT GUI 'Events' panel for this element."; //$NON-NLS-1$
        }
        // EDT 2026.1: EventHandler.event is an EReference to an mcore.Event (NOT
        // a String). Resolve the real Event allowed for this item; without it
        // getEvent() stays null and the platform form export (update_database)
        // NPEs in SymbolicNameService.generateSymbolicName. Fail loudly rather
        // than create an export-crashing handler.
        java.util.List<String> allowedEvents = new java.util.ArrayList<>();
        Object mcoreEvent = resolveAllowedFormEvent(target, event, allowedEvents);
        if (mcoreEvent == null)
        {
            if (allowedEvents.isEmpty())
            {
                return "Error: formEventApiNotFound - cannot resolve form events on this " //$NON-NLS-1$
                    + "EDT runtime. Add the handler via the EDT 'Events' panel."; //$NON-NLS-1$
            }
            return "Error: event '" + event + "' is not valid for this item. " //$NON-NLS-1$ //$NON-NLS-2$
                + "Allowed events: " + String.join(", ", allowedEvents); //$NON-NLS-1$ //$NON-NLS-2$
        }
        // De-dupe: skip when a handler for the same event + procedure exists.
        for (Object existing : (Iterable<Object>) handlers)
        {
            try
            {
                Object existingEvent = existing.getClass().getMethod("getEvent").invoke(existing); //$NON-NLS-1$
                Object existingName = existing.getClass().getMethod("getName").invoke(existing); //$NON-NLS-1$
                String n = existingName != null ? existingName.toString() : ""; //$NON-NLS-1$
                // Identity first; fall back to event-name match in case getAllowedEvents
                // hands back a non-canonical Event instance per call.
                boolean sameEvent = existingEvent == mcoreEvent;
                if (!sameEvent && existingEvent != null)
                {
                    Object en = existingEvent.getClass().getMethod("getName").invoke(existingEvent); //$NON-NLS-1$
                    sameEvent = en != null && event.equalsIgnoreCase(en.toString());
                }
                if (sameEvent && handlerName.equals(n))
                {
                    return "handler '" + handlerName + "' already attached to event '" //$NON-NLS-1$ //$NON-NLS-2$
                        + event + "'"; //$NON-NLS-1$
                }
            }
            catch (Exception ignored)
            {
                // Older EDT - cannot dedupe, fall through and add.
            }
        }
        // Create the handler EObject through FormFactory.
        Object newHandler;
        try
        {
            Class<?> ffClass = Class.forName("com._1c.g5.v8.dt.form.model.FormFactory"); //$NON-NLS-1$
            Object ff = ffClass.getField("eINSTANCE").get(null); //$NON-NLS-1$
            Object created = null;
            for (String factory : new String[] {
                "createFormItemEventHandler", //$NON-NLS-1$
                "createEventHandler", //$NON-NLS-1$
                "createFormEventHandler" //$NON-NLS-1$
            })
            {
                try
                {
                    created = ffClass.getMethod(factory).invoke(ff);
                    if (created != null)
                    {
                        break;
                    }
                }
                catch (NoSuchMethodException ignored)
                {
                    // Try next factory.
                }
            }
            if (created == null)
            {
                return "Error: formEventApiNotFound - no FormFactory.create*EventHandler() " //$NON-NLS-1$
                    + "method on this EDT runtime. Use the EDT GUI Events panel."; //$NON-NLS-1$
            }
            newHandler = created;
        }
        catch (Exception e)
        {
            return "Error: FormFactory access failed - " + e.getMessage(); //$NON-NLS-1$
        }
        // Wire the resolved mcore.Event reference + the BSL procedure name.
        try
        {
            boolean eventSet = false;
            for (java.lang.reflect.Method m : newHandler.getClass().getMethods())
            {
                if ("setEvent".equals(m.getName()) && m.getParameterCount() == 1 //$NON-NLS-1$
                    && m.getParameterTypes()[0].isInstance(mcoreEvent))
                {
                    m.invoke(newHandler, mcoreEvent);
                    eventSet = true;
                    break;
                }
            }
            if (!eventSet)
            {
                return "Error: EventHandler.setEvent(mcore.Event) not found on this runtime."; //$NON-NLS-1$
            }
            newHandler.getClass().getMethod("setName", String.class) //$NON-NLS-1$
                .invoke(newHandler, handlerName);
        }
        catch (Exception e)
        {
            return "Error: cannot set event/name on handler - " + e.getMessage(); //$NON-NLS-1$
        }
        // Guard: never persist a handler whose event reference did not take -
        // that null event is exactly what crashes the platform form export.
        try
        {
            if (newHandler.getClass().getMethod("getEvent").invoke(newHandler) == null) //$NON-NLS-1$
            {
                return "Error: event '" + event + "' did not bind (getEvent()==null); " //$NON-NLS-1$ //$NON-NLS-2$
                    + "not adding it, to avoid a form-export crash on update_database."; //$NON-NLS-1$
            }
        }
        catch (Exception ignored)
        {
            // getEvent() absent - cannot verify; proceed on this older runtime.
        }
        // EMF EList implements java.util.Collection on every supported EDT
        // build, but we still guard the cast so a hypothetical custom EList
        // implementation surfaces formEventApiNotFound instead of a bare CCE.
        if (!(handlers instanceof java.util.Collection))
        {
            return "Error: formEventApiNotFound - handlers accessor returned " //$NON-NLS-1$
                + handlers.getClass().getSimpleName() //$NON-NLS-1$
                + " which is not a java.util.Collection. Use the EDT GUI Events panel."; //$NON-NLS-1$
        }
        try
        {
            ((java.util.Collection) handlers).add(newHandler);
        }
        catch (Exception e)
        {
            return "Error: cannot add handler to the EList - " //$NON-NLS-1$
                + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
        return "added handler " + handlerName + " for " + event; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Resolves the {@code mcore.Event} matching {@code eventName} among the
     * events EDT allows for the given form item, via
     * {@code FormItemInformationService.getAllowedEvents}. Fills {@code allowedOut}
     * with the available event names (for a helpful error). Returns {@code null}
     * when the service is unavailable or no event matches the requested name.
     */
    private static Object resolveAllowedFormEvent(Object item, String eventName,
        java.util.List<String> allowedOut)
    {
        Object svc = BmFormGeneratorHelper.resolveFormService(
            "com._1c.g5.v8.dt.form.service.FormItemInformationService"); //$NON-NLS-1$
        if (svc == null)
        {
            return null;
        }
        try
        {
            java.lang.reflect.Method getAllowed = null;
            for (java.lang.reflect.Method m : svc.getClass().getMethods())
            {
                if ("getAllowedEvents".equals(m.getName()) && m.getParameterCount() == 1 //$NON-NLS-1$
                    && m.getParameterTypes()[0].isInstance(item))
                {
                    getAllowed = m;
                    break;
                }
            }
            if (getAllowed == null)
            {
                return null;
            }
            Object events = getAllowed.invoke(svc, item);
            if (!(events instanceof Iterable))
            {
                return null;
            }
            for (Object ev : (Iterable<?>) events)
            {
                Object nm = ev.getClass().getMethod("getName").invoke(ev); //$NON-NLS-1$
                String n = nm != null ? nm.toString() : ""; //$NON-NLS-1$
                if (!n.isEmpty())
                {
                    allowedOut.add(n);
                }
                if (n.equalsIgnoreCase(eventName))
                {
                    return ev;
                }
            }
        }
        catch (Exception e)
        {
            Activator.logWarning("resolveAllowedFormEvent failed: " //$NON-NLS-1$
                + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
        return null;
    }

    /**
     * Removes a form event handler from a form item (or the form root). Pass
     * {@code event} (EN/RU via FormEventRegistry) and/or {@code handlerName}: with
     * both, the named handler for that event; event alone removes every handler
     * for it; handlerName alone removes every handler with that procedure name -
     * including a corrupt null-event handler (the cleanup path for a form whose
     * export crashed). At least one selector is required. Idempotent: reports
     * when nothing matched.
     */
    String opRemoveFormEventHandler(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String formFqn = JsonUtils.extractStringArgument(params, "formFqn"); //$NON-NLS-1$
        String itemName = JsonUtils.extractStringArgument(params, "itemName"); //$NON-NLS-1$
        String event = JsonUtils.extractStringArgument(params, "event"); //$NON-NLS-1$
        String handlerNameParam = JsonUtils.extractStringArgument(params, "handlerName"); //$NON-NLS-1$

        String err = EditMetadataTool.requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(formFqn, "formFqn"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        boolean hasEvent = event != null && !event.isEmpty();
        boolean hasHandler = handlerNameParam != null && !handlerNameParam.isEmpty();
        if (!hasEvent && !hasHandler)
        {
            return ToolResult.error("Pass event and/or handlerName to select the handler(s) " //$NON-NLS-1$
                + "to remove (handlerName alone removes a corrupt null-event handler).").toJson(); //$NON-NLS-1$
        }
        String englishEvent = null;
        if (hasEvent)
        {
            FormEventRegistry.EventSpec spec = FormEventRegistry.lookup(event);
            if (spec == null)
            {
                return ToolResult.error("Unknown event '" + event //$NON-NLS-1$
                    + "'. Pass an English form event (OnChange, OnOpen, ...) or its Russian " //$NON-NLS-1$
                    + "equivalent (ПриИзменении, ПриОткрытии, ...).").toJson(); //$NON-NLS-1$
            }
            englishEvent = spec.englishName;
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }
        BmFormHelper helper = new BmFormHelper();
        if (!helper.init())
        {
            return ToolResult.error("EDT form model unavailable in this runtime").toJson(); //$NON-NLS-1$
        }
        final String finalItemName = itemName;
        final String finalEvent = englishEvent;
        final String finalHandlerName = hasHandler ? handlerNameParam : null;
        final boolean formDryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$
        String execResult = helper.executeFormOperation(project, formFqn, formDryRun, (tx, form) -> {
            try
            {
                Object target = (finalItemName == null || finalItemName.isEmpty())
                    ? form
                    : helper.findItemByName(form, finalItemName);
                if (target == null)
                {
                    return "Error: Form item not found: " + finalItemName; //$NON-NLS-1$
                }
                return detachFormEventHandler(target, finalEvent, finalHandlerName);
            }
            catch (Exception e)
            {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                return "Error: removeEventHandler failed - " //$NON-NLS-1$
                    + (cause.getMessage() != null ? cause.getMessage()
                        : cause.getClass().getSimpleName());
            }
        });
        if (execResult != null && execResult.startsWith("Error:")) //$NON-NLS-1$
        {
            return ToolResult.error(execResult.substring("Error:".length()).trim()).toJson(); //$NON-NLS-1$
        }
        return ToolResult.success()
            .put("operation", "remove_form_event_handler") //$NON-NLS-1$ //$NON-NLS-2$
            .put("formFqn", formFqn) //$NON-NLS-1$
            .put("event", hasEvent ? event : "") //$NON-NLS-1$ //$NON-NLS-2$
            .put("itemName", itemName != null ? itemName : "") //$NON-NLS-1$ //$NON-NLS-2$
            .put("result", execResult) //$NON-NLS-1$
            .toJson();
    }

    /**
     * Removes matching event handlers from a form item's handler EList by
     * reflection. Match rules: with {@code handlerName} given - by procedure name
     * (event must also match when the existing handler has a non-null event, so a
     * null-event corrupt handler is removed by name alone); without it - every
     * handler bound to {@code englishEvent}. Returns a status string.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static String detachFormEventHandler(Object target, String englishEvent, String handlerName)
    {
        Object handlers = null;
        for (String accessor : new String[] { "getHandlers", "getEventHandlers" }) //$NON-NLS-1$ //$NON-NLS-2$
        {
            try
            {
                handlers = target.getClass().getMethod(accessor).invoke(target);
                if (handlers != null)
                {
                    break;
                }
            }
            catch (NoSuchMethodException nsme)
            {
                // try next accessor
            }
            catch (Exception e)
            {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                return "Error: form item handlers accessor failed - " //$NON-NLS-1$
                    + (cause.getMessage() != null ? cause.getMessage()
                        : cause.getClass().getSimpleName());
            }
        }
        if (!(handlers instanceof java.util.Collection))
        {
            return "Error: formEventApiNotFound - no handlers EList exposed on " //$NON-NLS-1$
                + target.getClass().getSimpleName();
        }
        java.util.Iterator it = ((java.util.Collection) handlers).iterator();
        int removed = 0;
        while (it.hasNext())
        {
            Object existing = it.next();
            try
            {
                Object existingEvent = existing.getClass().getMethod("getEvent").invoke(existing); //$NON-NLS-1$
                String evName = null;
                if (existingEvent != null)
                {
                    Object en = existingEvent.getClass().getMethod("getName").invoke(existingEvent); //$NON-NLS-1$
                    evName = en != null ? en.toString() : null;
                }
                Object nm = existing.getClass().getMethod("getName").invoke(existing); //$NON-NLS-1$
                String hName = nm != null ? nm.toString() : ""; //$NON-NLS-1$
                boolean nameMatch = handlerName != null && handlerName.equalsIgnoreCase(hName);
                boolean eventMatch =
                    evName != null && englishEvent != null && evName.equalsIgnoreCase(englishEvent);
                boolean match;
                if (englishEvent == null)
                {
                    // event omitted: match purely by handler name (any / no event) -
                    // removes a corrupt null-event handler the caller names.
                    match = nameMatch;
                }
                else if (handlerName != null)
                {
                    // name given: match by name; require event match only when the
                    // handler has an event (a null-event corrupt handler is removed
                    // by name alone - the export-crash cleanup case).
                    match = nameMatch && (existingEvent == null || eventMatch);
                }
                else
                {
                    match = eventMatch;
                }
                if (match)
                {
                    it.remove();
                    removed++;
                }
            }
            catch (Exception ignored)
            {
                // skip entries we cannot introspect
            }
        }
        String sel = (englishEvent != null ? "event '" + englishEvent + "'" : "") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + (handlerName != null
                ? (englishEvent != null ? " / " : "") + "'" + handlerName + "'" : ""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        if (removed == 0)
        {
            return "no matching handler for " + sel; //$NON-NLS-1$
        }
        return "removed " + removed + " handler(s) for " + sel; //$NON-NLS-1$ //$NON-NLS-2$
    }
}
