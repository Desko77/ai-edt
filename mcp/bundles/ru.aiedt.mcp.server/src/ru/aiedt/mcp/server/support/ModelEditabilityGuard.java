/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;

import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com.e1c.g5.v8.dt.distribution.IDistributionSupportManager;
import com.e1c.g5.v8.dt.distribution.model.DistributionSupport;
import com.e1c.g5.v8.dt.distribution.model.UserSupportMode;

import ru.aiedt.mcp.server.Activator;

/**
 * Refuses a write into a metadata object the support registry closed for changes.
 * <p>
 * An object taken from a vendor configuration and left in the mode EDT calls "changes are not
 * allowed" is read-only: the environment offers no way to change it, and a write reached through
 * this server used to go ahead anyway. The change landed in the in-memory model, the module file
 * was rewritten, and the next vendor update overwrote it - with nothing in the answer saying so.
 * </p>
 * <p>
 * This guard is the one place the question is asked, and it is asked on the way into every write
 * path: {@link BmObjectHelper#executeWriteOnObject} for the metadata operations, the form writes
 * through {@code BmFormHelper} and the module writes through {@code ModuleSourceWriter}. It runs
 * before the model is touched and before the transaction opens, a preview included, so a dry run
 * answers the same refusal a real call would.
 * </p>
 * <p>
 * The answer comes from the support registry itself - {@link IDistributionSupportManager}, the same
 * service {@code SupportRegistryTool} reads through - rather than from anything this server keeps.
 * The environment's own judgement is reported next to the recorded mode: a refusal names the object,
 * the mode the registry holds for it, and what lifts it.
 * </p>
 * <p>
 * Three states pass, and each of them deliberately. An object with no supplier is not on support at
 * all; an object whose mode is {@code ChangesAllowed} is one the user has already opened for
 * editing; and an environment that cannot be asked - no support subsystem installed, a project whose
 * configuration is not loaded - is never a reason to refuse a write, since the guard would then
 * block work it knows nothing about. Only the two answers together, a registry record that forbids
 * changes and an environment that confirms it, produce a refusal.
 * </p>
 */
public final class ModelEditabilityGuard
{
    /**
     * The name this guard is known by inside the server. It is not a tool and not an operation: no
     * visible name of a tool, an operation or a preference key changes because of it.
     */
    public static final String NAME = "model_editability_guard"; //$NON-NLS-1$

    /** The one mode the registry writes for an object whose changes are not allowed. */
    private static final String CHANGES_NOT_ALLOWED = "ChangesNotAllowed"; //$NON-NLS-1$

    /** The mode an object carries once the user has opened it for editing. */
    private static final String CHANGES_ALLOWED = "ChangesAllowed"; //$NON-NLS-1$

    /**
     * What the environment says about one object. Every field is filled in by
     * {@link #askSupportService}, and a test fills them in by hand.
     */
    public static final class Editability
    {
        /** Whether the environment answered at all. */
        public boolean answered;

        /** Whether the environment would let this object be changed. */
        public boolean canEdit;

        /** The support mode the registry records, as the model spells it; <code>null</code> for none. */
        public String userMode;

        /** How the support service was reached, for the answer. */
        public String route;

        /** Why nothing could be said, when {@link #answered} is <code>false</code>. */
        public String cannotTell;

        /**
         * Whether the registry holds a record for this object that forbids changing it.
         *
         * @return <code>true</code> only for the mode that closes the object for changes
         */
        public boolean recordedAsNotAllowed()
        {
            return CHANGES_NOT_ALLOWED.equals(userMode);
        }

        /**
         * Whether the registry holds a record that opens this object for changes.
         *
         * @return <code>true</code> for the mode a user's own decision puts on an object
         */
        public boolean recordedAsAllowed()
        {
            return CHANGES_ALLOWED.equals(userMode);
        }
    }

    /**
     * Answers the editability question for one object.
     * <p>
     * The production implementation reads the support service. A test substitutes its own, which is
     * the only way this question can be asked without a project on support: the acceptance of a
     * support mode cannot be arranged from a unit test.
     * </p>
     */
    public interface Probe
    {
        /**
         * Asks about one object.
         *
         * @param project the project the object belongs to; may be <code>null</code>
         * @param top the metadata object that carries the support record; never <code>null</code>
         * @return what the environment says; never <code>null</code>
         */
        Editability ask(IProject project, MdObject top);
    }

    /** Where the answer comes from. Replaced only by a test, and put back by it. */
    private static volatile Probe probe = ModelEditabilityGuard::askSupportService;

    private ModelEditabilityGuard()
    {
        // utility
    }

    /**
     * Substitutes the source of answers, for a test.
     *
     * @param replacement the answers to use; <code>null</code> puts the support service back
     */
    public static void useProbeForTest(Probe replacement)
    {
        probe = replacement != null ? replacement : ModelEditabilityGuard::askSupportService;
    }

    /**
     * Judges a write into the object a caller holds.
     *
     * @param project the project being written to; may be <code>null</code>
     * @param any the object about to be changed, or any object inside it
     * @return a verdict that blocks the write, or one that passes
     */
    public static MetadataGuards.Verdict checkObject(IProject project, EObject any)
    {
        MdObject top = topOwnerOf(any);
        if (top == null)
        {
            // No metadata owner to judge: the write is aimed at something the registry has no
            // record for - a configuration-level object, a form model, an external object root.
            return MetadataGuards.Verdict.pass();
        }
        return decide(fqnOf(top), probe.ask(project, top));
    }

    /**
     * Judges a write into the object an address names.
     * <p>
     * Used where the write path has an address rather than the object - the module writer knows the
     * module path, the form writer the form's address. An address that does not resolve to a
     * metadata object is not judged: the write is refused or accepted further along by the code that
     * does resolve it.
     * </p>
     *
     * @param project the project being written to
     * @param fqn the address of the object or of anything it contains
     * @return a verdict that blocks the write, or one that passes
     */
    public static MetadataGuards.Verdict checkFqn(IProject project, String fqn)
    {
        MdObject object = resolve(project, ownerFqnOf(fqn));
        if (object == null)
        {
            return MetadataGuards.Verdict.pass();
        }
        return checkObject(project, object);
    }

    /**
     * The metadata object an address belongs to, as the address spells it: the first two segments of
     * {@code Type.Name}.
     * <p>
     * Every address this server takes - {@code Catalog.Goods}, {@code Catalog.Goods.Form.ItemForm},
     * {@code Catalogs/Goods/ObjectModule.bsl}, {@code Subsystem.A.Subsystem.B} - carries the owning
     * object's name in its first two segments. Where an address is one segment long it is returned
     * unchanged, since there is no name in it to shorten.
     * </p>
     *
     * @param fqn the address; may be <code>null</code>
     * @return the owning object's address, or the input when it has no second segment
     */
    public static String ownerFqnOf(String fqn)
    {
        if (fqn == null)
        {
            return null;
        }
        String trimmed = fqn.trim();
        int firstDot = trimmed.indexOf('.');
        if (firstDot <= 0)
        {
            return trimmed;
        }
        int secondDot = trimmed.indexOf('.', firstDot + 1);
        return secondDot < 0 ? trimmed : trimmed.substring(0, secondDot);
    }

    /**
     * The outermost metadata object an object sits in.
     * <p>
     * A write is aimed at an attribute, a tabular section or a nested subsystem, while the support
     * record belongs to the object that holds it. The walk stops below the configuration root: the
     * configuration is not one of the objects this guard judges, so a configuration-level write is
     * passed through rather than judged by a record that belongs to the configuration as a whole.
     * </p>
     *
     * @param any the object to walk up from; may be <code>null</code>
     * @return the outermost metadata object below the configuration, or <code>null</code> when there
     *         is none
     */
    public static MdObject topOwnerOf(EObject any)
    {
        EObject current = any;
        MdObject outermost = null;
        while (current != null)
        {
            if (current instanceof Configuration)
            {
                return outermost;
            }
            if (current instanceof MdObject)
            {
                outermost = (MdObject)current;
            }
            current = current.eContainer();
        }
        return outermost;
    }

    /**
     * Names a metadata object as the rest of this server names it.
     *
     * @param object the object; may be <code>null</code>
     * @return the address, or <code>null</code> when there is no object to name
     */
    public static String fqnOf(MdObject object)
    {
        if (object == null)
        {
            return null;
        }
        String name = object.getName();
        return object.eClass().getName() + "." + (name == null ? "?" : name); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Turns what the environment said into a verdict.
     * <p>
     * The refusal names the object as the caller knows it, the mode the registry holds and what
     * lifts it, so that the caller can act without asking the registry tool separately. The check
     * runs only where both answers agree: the registry records the object as closed for changes and
     * the environment confirms it will not edit the object.
     * </p>
     *
     * @param fqn the object, as the answer should name it
     * @param answer what the environment said; may be <code>null</code>
     * @return a verdict that blocks the write, or one that passes
     */
    public static MetadataGuards.Verdict decide(String fqn, Editability answer)
    {
        if (answer == null || !answer.answered || answer.canEdit || !answer.recordedAsNotAllowed())
        {
            return MetadataGuards.Verdict.pass();
        }
        String hint = "Open the object for changes in the support settings of the project, or make " //$NON-NLS-1$
            + "the change in a configuration extension."; //$NON-NLS-1$
        MetadataGuards.ErrorTag tag = new MetadataGuards.ErrorTag(ErrorTags.SUPPORT_LOCK.wire())
            .put("object", fqn) //$NON-NLS-1$
            .put("userSupportMode", answer.userMode) //$NON-NLS-1$
            .put("canEdit", Boolean.valueOf(answer.canEdit)) //$NON-NLS-1$
            .put("guard", NAME) //$NON-NLS-1$
            .put("hint", hint); //$NON-NLS-1$
        return MetadataGuards.Verdict.block(
            "Object '" + fqn + "' is on vendor support and changes to it are not allowed (mode " //$NON-NLS-1$ //$NON-NLS-2$
                + answer.userMode + "). Nothing was changed.", //$NON-NLS-1$
            hint, tag);
    }

    /**
     * Asks the support service about one object.
     *
     * @param project the project the object belongs to
     * @param top the metadata object carrying the support record
     * @return what the service said; {@link Editability#answered} is <code>false</code> when it could
     *         not be asked
     */
    private static Editability askSupportService(IProject project, MdObject top)
    {
        Editability answer = new Editability();
        if (project == null || top == null)
        {
            answer.cannotTell = "no project or no object to ask about"; //$NON-NLS-1$
            return answer;
        }
        try
        {
            BmSupportRegistryHelper.Service service = BmSupportRegistryHelper.findService();
            if (service.manager == null)
            {
                answer.cannotTell = service.failure;
                return answer;
            }
            answer.route = service.route;
            DistributionSupport support = service.manager.getDistributionSupport(project);
            if (support == null)
            {
                // Not on support: no object of this project has a mode, so nothing is closed.
                answer.cannotTell = project.getName()
                    + " is not on support, so no object of it is closed for changes"; //$NON-NLS-1$
                return answer;
            }
            UserSupportMode mode = service.manager.getUserSupportMode(top);
            answer.userMode = mode == null ? null : mode.getName();
            answer.canEdit = service.manager.canEdit(top);
            answer.answered = true;
        }
        catch (RuntimeException | LinkageError cannotAsk)
        {
            // Named rather than swallowed, but not fatal: a support subsystem this server cannot
            // reach is not evidence that the object is closed.
            answer.cannotTell = "the support service could not be asked: " + cannotAsk; //$NON-NLS-1$
            Activator.logDebug("model_editability_guard: " + answer.cannotTell); //$NON-NLS-1$
        }
        return answer;
    }

    /**
     * Resolves an address to the metadata object it names.
     *
     * @param project the project holding the object
     * @param fqn the address, already shortened to {@code Type.Name}
     * @return the object, or <code>null</code> when the address names nothing this project holds
     */
    private static MdObject resolve(IProject project, String fqn)
    {
        if (project == null || fqn == null)
        {
            return null;
        }
        try
        {
            Configuration configuration = SubsystemMembership.configurationOf(project);
            if (configuration == null)
            {
                return null;
            }
            String normalized = MetadataTypeCatalog.normalizeFqn(fqn);
            String[] parts = normalized.split("\\.", 2); //$NON-NLS-1$
            if (parts.length < 2)
            {
                return null;
            }
            return MetadataTypeCatalog.findObject(configuration, parts[0], parts[1]);
        }
        catch (RuntimeException | LinkageError cannotResolve)
        {
            Activator.logDebug("model_editability_guard: could not resolve " + fqn + ": " //$NON-NLS-1$ //$NON-NLS-2$
                + cannotResolve);
            return null;
        }
    }
}
