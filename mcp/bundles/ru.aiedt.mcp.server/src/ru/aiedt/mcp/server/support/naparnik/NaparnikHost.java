/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support.naparnik;

import java.util.List;
import java.util.Set;

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

    /** The step that sends one question. */
    String LINK_ASK = "ask"; //$NON-NLS-1$

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
     * Sends one question through an already opened facade.
     * <p>
     * The future completes on another thread. {@code maxToolRounds} of {@code 0} or less is no
     * limit. A name in {@code allowedTools} that the installation does not publish fails before
     * the future starts, with {@code Unknown tools in allowed-tools:} in the message. {@code null}
     * allowed tools do not filter. The installation's own cancellation token is what
     * {@link RunningQuestion#cancel()} moves, and the loop polls that token.
     * </p>
     *
     * @param facade the facade from {@link #openFacade}
     * @param source the chosen {@code com.e1c.edt.ai} bundle, which is where the request types live
     * @param question the request
     * @return the running question
     * @throws NaparnikAccessException when the request cannot be started; the link id is
     *             {@link #LINK_ASK}
     */
    RunningQuestion ask(Object facade, BundleCopy source, Question question)
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

    /**
     * Told when a Naparnik tool call is about to run.
     */
    interface ToolStart
    {
        /**
         * @param names the tools about to run, in Naparnik's order
         * @return why this question must stop after the current call, or {@code null} to continue
         */
        String onStart(List<String> names);
    }

    /**
     * One question, as the 9-argument request of 1.0.7 carries it.
     * <p>
     * A new conversation has a null session and {@code forceNew}. A continuation carries the
     * conversation and the message it replies to, and is not forced new. {@code skillName} and
     * {@code chat} are the values the 7-argument constructor writes before it calls the
     * 9-argument one: {@code custom} and {@code true}. {@code allowedTools} null is no filter.
     * </p>
     */
    final class Question
    {
        private final Object project;

        private final String text;

        private final String conversationId;

        private final String replyTo;

        private final boolean forceNew;

        private final String skillName;

        private final Boolean chat;

        private final int maxToolRounds;

        private final Set<String> allowedTools;

        private final ToolStart onToolStart;

        /**
         * @param project the open workspace project
         * @param text the question
         * @param conversationId the conversation to continue; {@code null} starts one
         * @param replyTo the message to reply to; only with a conversation
         * @param forceNew whether this starts a conversation
         * @param skillName the skill name the 9-argument constructor receives
         * @param chat whether the request is a chat
         * @param maxToolRounds the round limit; a positive value is a limit
         * @param allowedTools the names to allow, or {@code null} for no filter
         * @param onToolStart told as each tool call starts; may be {@code null}
         */
        public Question(Object project, String text, String conversationId, String replyTo,
            boolean forceNew, String skillName, Boolean chat, int maxToolRounds,
            Set<String> allowedTools, ToolStart onToolStart)
        {
            this.project = project;
            this.text = text;
            this.conversationId = conversationId;
            this.replyTo = replyTo;
            this.forceNew = forceNew;
            this.skillName = skillName;
            this.chat = chat;
            this.maxToolRounds = maxToolRounds;
            this.allowedTools = allowedTools;
            this.onToolStart = onToolStart;
        }

        /**
         * @return the project handle
         */
        public Object project()
        {
            return project;
        }

        /**
         * @return the question text
         */
        public String text()
        {
            return text;
        }

        /**
         * @return the conversation to continue, or {@code null} for a new one
         */
        public String conversationId()
        {
            return conversationId;
        }

        /**
         * @return the message this replies to
         */
        public String replyTo()
        {
            return replyTo;
        }

        /**
         * @return whether the request starts a conversation
         */
        public boolean forceNew()
        {
            return forceNew;
        }

        /**
         * @return the skill name
         */
        public String skillName()
        {
            return skillName;
        }

        /**
         * @return whether the request is a chat
         */
        public Boolean chat()
        {
            return chat;
        }

        /**
         * @return the round limit passed in the request
         */
        public int maxToolRounds()
        {
            return maxToolRounds;
        }

        /**
         * @return the allowed names, or {@code null} when the request does not filter
         */
        public Set<String> allowedTools()
        {
            return allowedTools;
        }

        /**
         * @return the start notice
         */
        public ToolStart onToolStart()
        {
            return onToolStart;
        }
    }

    /**
     * A question whose future completes on another thread.
     */
    interface RunningQuestion
    {
        /**
         * Waits until the future completes or the wait elapses.
         *
         * @param timeoutMs how long to wait
         * @return whether the future has completed
         * @throws NaparnikAccessException when the wait itself fails
         */
        boolean await(long timeoutMs)
            throws NaparnikAccessException;

        /**
         * Cancels the installation's own token.
         */
        void cancel();

        /**
         * @return the failure the future completed with, or {@code null} when it completed normally
         *         or has not completed
         */
        Throwable failure();

        /**
         * @return the answer text, or {@code null} when there is none
         */
        String text();

        /**
         * @return the conversation id from the result session
         */
        String conversationId();

        /**
         * @return the reply-to id from the result session
         */
        String replyTo();

        /**
         * @return how many assistant messages the result counted
         */
        int assistantMessages();

        /**
         * @return the tool names that started, in order
         */
        List<String> toolsCalled();
    }
}
