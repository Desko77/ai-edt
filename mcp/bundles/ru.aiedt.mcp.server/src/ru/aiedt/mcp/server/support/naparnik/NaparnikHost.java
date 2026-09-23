/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support.naparnik;

import java.util.List;

/**
 * The 1C:Naparnik installation this process can see.
 * <p>
 * The tool talks only to this seam. Tests hand it a fake, and the OSGi implementation is the one
 * place that reflects into {@code com.e1c.edt.ai}. A failure names the link it happened on and is
 * not swallowed.
 * </p>
 */
public interface NaparnikHost
{
    /** The probe step that opens the injector. */
    String LINK_INJECTOR = "injector"; //$NON-NLS-1$

    /** The probe step that reads the conversation facade. */
    String LINK_FACADE = "facade"; //$NON-NLS-1$

    /** The probe step that reads Naparnik's own tool names. */
    String LINK_TOOLS = "tools"; //$NON-NLS-1$

    /**
     * The probe step that loads one class out of a chosen bundle.
     *
     * @param className the binary name
     * @return the link id {@code status} publishes for that load
     */
    static String loadClassLink(String className)
    {
        return "loadClass:" + className; //$NON-NLS-1$
    }

    /**
     * Every installed copy of one symbolic name, in the host's order.
     *
     * @param symbolicName the bundle name
     * @return the copies; empty when none are installed, never {@code null}
     * @throws NaparnikAccessException when the host cannot be asked
     */
    List<BundleCopy> copiesOf(String symbolicName)
        throws NaparnikAccessException;

    /**
     * Loads a class from one chosen copy.
     *
     * @param bundle the copy to load from
     * @param className the binary name
     * @return the loaded class
     * @throws NaparnikAccessException when the bundle cannot load it; the link id names the class
     */
    Class<?> loadClass(BundleCopy bundle, String className)
        throws NaparnikAccessException;

    /**
     * Starts the UI bundle when it is only resolved, then reads its injector.
     * <p>
     * Starting is what {@code probe=true} warns about. A passive status call does not reach this
     * method.
     * </p>
     *
     * @param uiBundle the chosen {@code com.e1c.edt.ai.ui} copy
     * @param baseActivator {@code com.e1c.edt.ai.ui.BaseActivator}, loaded from the chosen
     *            {@code ui.common} bundle
     * @return the injector and the bundle that owns the activator instance
     * @throws NaparnikAccessException when the injector cannot be opened; the link id is
     *             {@link #LINK_INJECTOR}
     */
    InjectorDoor openInjector(BundleCopy uiBundle, Class<?> baseActivator)
        throws NaparnikAccessException;

    /**
     * Reads the conversation facade out of an injector and the bundle that defined its class.
     *
     * @param injector the injector from {@link #openInjector}
     * @param facadeType {@code com.e1c.edt.ai.IConversationFacade}, loaded from the chosen
     *            {@code com.e1c.edt.ai} bundle
     * @return the facade and the bundle that owns its runtime class
     * @throws NaparnikAccessException when the facade cannot be read; the link id is
     *             {@link #LINK_FACADE}
     */
    FacadeDoor openFacade(Object injector, Class<?> facadeType)
        throws NaparnikAccessException;

    /**
     * The tool names Naparnik currently publishes.
     *
     * @param injector the injector from {@link #openInjector}
     * @param mcpToolsType {@code com.e1c.edt.ai.IMcpTools}, loaded from the chosen
     *            {@code com.e1c.edt.ai} bundle
     * @return the names, in Naparnik's order
     * @throws NaparnikAccessException when the names cannot be read; the link id is
     *             {@link #LINK_TOOLS}
     */
    List<String> toolNames(Object injector, Class<?> mcpToolsType)
        throws NaparnikAccessException;

    /**
     * An injector and the bundle of the activator that produced it.
     */
    final class InjectorDoor
    {
        private final Object injector;

        private final BundleCopy activator;

        /**
         * @param injector the Guice injector
         * @param activator the bundle that owns the activator instance
         */
        public InjectorDoor(Object injector, BundleCopy activator)
        {
            this.injector = injector;
            this.activator = activator;
        }

        /**
         * @return the injector
         */
        public Object injector()
        {
            return injector;
        }

        /**
         * @return the activator's bundle
         */
        public BundleCopy activator()
        {
            return activator;
        }
    }

    /**
     * A conversation facade and the bundle that defined its runtime class.
     */
    final class FacadeDoor
    {
        private final Object facade;

        private final BundleCopy owner;

        /**
         * @param facade the facade instance
         * @param owner the bundle of {@code facade.getClass()}
         */
        public FacadeDoor(Object facade, BundleCopy owner)
        {
            this.facade = facade;
            this.owner = owner;
        }

        /**
         * @return the facade
         */
        public Object facade()
        {
            return facade;
        }

        /**
         * @return the owning bundle
         */
        public BundleCopy owner()
        {
            return owner;
        }
    }
}
