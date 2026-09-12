/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import ru.aiedt.mcp.server.toolkit.IMcpTool;

/**
 * What one operation of a facade takes, answered by the facade's own help.
 * <p>
 * A facade's schema declares the union of every operation's parameters, and says in prose which
 * ones belong to which. Asking about one operation is the question a caller actually has, and until
 * now the only answer was to read the union. The parameters come from the tool the operation routes
 * to, because that tool is what receives them.
 * </p>
 * <p>
 * Only for a facade that hands the call down unchanged. Where a facade rewrites the arguments on the
 * way - {@code code_search} renames five operations' worth - the delegate's schema names what it
 * receives rather than what the caller sends, and rendering it would advertise parameters the facade
 * refuses. Such a facade answers from the operation-parameter map instead, which is derived with the
 * rewrite in view.
 * </p>
 */
public final class FacadeParameterHelp
{
    private FacadeParameterHelp()
    {
    }

    /**
     * The help answer for a topic that may name an operation.
     *
     * @param topic the topic asked for, already normalized; may be <code>null</code>.
     * @param described operation name to the tool it routes to.
     * @param dispatched every operation the facade accepts, described or not.
     * @param namedTopics the facade's own topics, for the refusal - for example "workflow".
     * @return the parameters of that operation, or a refusal naming what can be asked for
     */
    public static String answer(String topic, Map<String, Supplier<IMcpTool>> described,
        Set<String> dispatched, String namedTopics, String facadeClass, String facadeSchema)
    {
        Supplier<IMcpTool> known = topic == null ? null : described.get(topic);
        if (known != null)
        {
            IMcpTool routed = known.get();
            // Rendered under the name the caller used. An operation and the tool behind it usually
            // share a name, and where they do not the caller asked by the operation's.
            return ParameterHelp.render(topic, routed.getInputSchema());
        }
        if (topic != null && dispatched != null && dispatched.contains(topic))
        {
            // An operation this facade handles itself rather than routing to a tool. Told apart
            // from a topic that names nothing, because a caller reading "unknown" about an
            // operation they just found in the catalog learns the wrong thing.
            return fromTheMap(topic, facadeClass, facadeSchema);
        }
        return "# Unknown topic '" + topic + "'.\n\nAvailable: " + namedTopics //$NON-NLS-1$ //$NON-NLS-2$
            + ", or the name of an operation for its parameters.\n"; //$NON-NLS-1$
    }

    /**
     * What an operation the facade handles itself takes, named by the map and described by the
     * facade's own schema.
     *
     * @param operation the operation asked about.
     * @param facadeClass the simple name of the facade class, as the map keys it.
     * @param facadeSchema the schema the facade declares.
     * @return the parameters, or a line saying they are not recorded
     */
    public static String fromTheMap(String operation, String facadeClass, String facadeSchema)
    {
        List<String> established = OperationParameters.establishedFor(facadeClass, operation);
        List<String> all = OperationParameters.of(facadeClass, operation);
        if (all.isEmpty())
        {
            // Said, because an empty answer and an unrecorded one read alike and mean opposite
            // things: one says the operation takes nothing, the other that nobody wrote down what
            // it takes.
            return "## " + operation + " - parameters\n\nThe parameters of this operation are " //$NON-NLS-1$ //$NON-NLS-2$
                + "not recorded. The schema this facade declares is what a call is validated " //$NON-NLS-1$
                + "against.\n"; //$NON-NLS-1$
        }
        Set<String> own = new LinkedHashSet<>();
        for (String entry : established)
        {
            own.add(OperationParameters.nameOf(entry));
        }
        Set<String> shared = new LinkedHashSet<>(all);
        shared.removeAll(own);
        return ParameterHelp.renderNamed(operation, facadeSchema, own, shared);
    }
}
