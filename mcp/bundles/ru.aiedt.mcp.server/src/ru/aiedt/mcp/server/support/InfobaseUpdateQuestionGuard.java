/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseSynchonizationQuestionHandler;

/**
 * Stops an infobase update that would delete data, and keeps the question it was stopped by.
 * <p>
 * The platform restructures an infobase silently unless the base's own
 * "prompt confirmation on restructure" preference is on, and the question it asks when the
 * preference IS on arrives in one of two shapes: a text question handed to the
 * {@link IInfobaseSynchonizationQuestionHandler} service, or a modal dialog the environment opens
 * on its own shell. This guard arms both listeners for the duration of one
 * {@code update_database} run: whichever route delivers the question, its raw text and answer
 * options are parked under the infobase's identity, the table names are parsed out of the text,
 * and the answer the platform receives is the refusal - unless the caller accepted the loss up
 * front, in which case it is the acceptance.
 * </p>
 * <p>
 * Answering is decided by position, not by wording: the platform marks one answer
 * {@code isDefaultAnswer()}, and that is the one a refusal leaves the update stopped by, while the
 * single non-default answer is the acceptance. Where that count does not hold - two defaults, or
 * several non-defaults - the guard returns nothing and the environment's own handler gets the
 * question, which is a dialog a person or {@code answer_dialog} can still answer; a guess here
 * cannot be taken back.
 * </p>
 * <p>
 * The dialog route is watched, never answered: reading what a modal says costs nothing, while
 * pressing its buttons is the reserve route this build does not include. The parked record says
 * which route the question took, so a stand measurement can tell the two apart.
 * </p>
 */
public final class InfobaseUpdateQuestionGuard implements IInfobaseSynchonizationQuestionHandler
{
    /** The route a question arrived by: the synchronization-question service. */
    public static final String ROUTE_SERVICE = "service"; //$NON-NLS-1$

    /** The route a question arrived by: a modal dialog on the environment's own shell. */
    public static final String ROUTE_DIALOG = "dialog"; //$NON-NLS-1$

    /**
     * How many parked questions the registry keeps. One per infobase identity is the working set
     * of a caller deciding whether to resend an update with the loss accepted; eight infobases in
     * one window is more than that, and the oldest record is the one whose update is longest over.
     */
    private static final int PARKED_MAX = 8;

    /** How long a parked question stays readable. An hour-old refusal answers nothing. */
    private static final long PARKED_TTL_MS = 30 * 60_000L;

    /** How often the watcher asks the workbench what it is showing. */
    private static final long DIALOG_POLL_MS = 750;

    /**
     * A metadata address in a question's text: at least two segments of identifiers joined by
     * dots, every segment starting with a letter. Cyrillic identifiers need the word class
     * declared Unicode, and the letter-first rule keeps version numbers ("8.3.27") and file names
     * out. The platform's message wording is localized and is deliberately not read at all - the
     * structure of the names is the only thing relied on.
     */
    private static final Pattern DOTTED_ADDRESS =
        Pattern.compile("\\p{L}\\w*(?:\\.\\p{L}\\w*)+", Pattern.UNICODE_CHARACTER_CLASS); //$NON-NLS-1$

    /** The single guard the update path arms; tests build their own instead. */
    private static final InfobaseUpdateQuestionGuard INSTANCE = new InfobaseUpdateQuestionGuard(
        System::currentTimeMillis);

    private final LongSupplier clock;

    /** Runs currently armed, most recent last; the question of a run lands under its identity. */
    private final List<ActiveRun> active = new CopyOnWriteArrayList<>();

    /**
     * Parked questions by infobase identity, the latest per base, bounded and expiring.
     * Insertion-ordered: records parked within one millisecond still retire in the order they
     * arrived, and every access already sits under the registry's own lock.
     */
    private final Map<String, ParkedQuestion> parkedRegistry = new LinkedHashMap<>();

    /**
     * The guard the update path uses.
     *
     * @return the shared guard
     */
    public static InfobaseUpdateQuestionGuard get()
    {
        return INSTANCE;
    }

    /**
     * A guard with its own clock, for tests that age a parked record.
     *
     * @param clock the source of "now" in milliseconds
     */
    public InfobaseUpdateQuestionGuard(LongSupplier clock)
    {
        this.clock = clock;
    }

    /** A question the guard parked, exactly as it arrived. */
    public static final class ParkedQuestion
    {
        /** Which route the question arrived by: {@link #ROUTE_SERVICE} or {@link #ROUTE_DIALOG}. */
        public final String route;

        /** The raw question text, in the platform's own wording. */
        public final String question;

        /** The answer options as they were offered, in order. */
        public final List<String> answers;

        /** The metadata addresses parsed out of the question text, in order of appearance. */
        public final List<String> dataLossTables;

        /** The identity of the infobase the stopped update targeted, or {@code null}. */
        public final String infobaseIdentity;

        /** Whether the guard answered with the acceptance. */
        public final boolean accepted;

        /** The answer the guard gave, or {@code null} when it left the question to the environment. */
        public final String answerGiven;

        /** When the question arrived, in milliseconds from the guard's clock. */
        public final long parkedAtMs;

        ParkedQuestion(String route, String question, List<String> answers, List<String> dataLossTables,
            String infobaseIdentity, boolean accepted, String answerGiven, long parkedAtMs)
        {
            this.route = route;
            this.question = question;
            this.answers = List.copyOf(answers);
            this.dataLossTables = List.copyOf(dataLossTables);
            this.infobaseIdentity = infobaseIdentity;
            this.accepted = accepted;
            this.answerGiven = answerGiven;
            this.parkedAtMs = parkedAtMs;
        }

        /**
         * The record as the tool answers carry it.
         *
         * @return the fields of this record
         */
        public Map<String, Object> toJson()
        {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("route", route); //$NON-NLS-1$
            json.put("question", question); //$NON-NLS-1$
            json.put("answers", answers); //$NON-NLS-1$
            json.put("dataLossTables", dataLossTables); //$NON-NLS-1$
            json.put("accepted", Boolean.valueOf(accepted)); //$NON-NLS-1$
            json.put("answerGiven", answerGiven); //$NON-NLS-1$
            json.put("parkedAtMs", Long.valueOf(parkedAtMs)); //$NON-NLS-1$
            return json;
        }
    }

    /** One armed run: what to do with a question and where to park it. */
    private static final class ActiveRun
    {
        final String infobaseIdentity;

        final boolean acceptDataLoss;

        final Run handle;

        final AtomicReference<ParkedQuestion> parked = new AtomicReference<>();

        /** The service registration to take down when the run ends, or {@code null}. */
        AutoCloseable serviceRegistration;

        /** Whether the service route was armed; a registration that failed is a fact the answer names. */
        boolean serviceRegistered;

        volatile boolean ended;

        Thread watcher;

        ActiveRun(InfobaseUpdateQuestionGuard guard, String infobaseIdentity, boolean acceptDataLoss)
        {
            this.infobaseIdentity = infobaseIdentity;
            this.acceptDataLoss = acceptDataLoss;
            this.handle = new Run(guard);
            this.handle.owner = this;
        }
    }

    /** The handle a run is ended by, and the run's own facts an answer reports. */
    public static final class Run
    {
        private final InfobaseUpdateQuestionGuard guard;

        private ActiveRun owner;

        Run(InfobaseUpdateQuestionGuard guard)
        {
            this.guard = guard;
        }

        /**
         * Ends the run: the watcher stops, the service registration comes down, and the run no
         * longer receives questions. A question it already parked stays readable both here and in
         * the registry.
         */
        public void end()
        {
            guard.endRun(owner);
        }

        /**
         * Whether the service route was armed for this run. A registration that could not be made
         * leaves the dialog route as the only listener, and an answer that said nothing about it
         * would report a guarded update where the service half was not even listening.
         *
         * @return whether the question service is registered for this run
         */
        public boolean serviceArmed()
        {
            return owner != null && owner.serviceRegistered;
        }

        /**
         * The question this run parked, if one arrived.
         *
         * @return the parked question, or empty
         */
        public Optional<ParkedQuestion> parked()
        {
            return owner == null ? Optional.empty() : Optional.ofNullable(owner.parked.get());
        }
    }

    /**
     * Arms the guard for one update: registers the question service and starts watching the
     * workbench for the dialog, both through the environment's own wiring.
     *
     * @param infobaseIdentity the identity of the infobase being updated, or {@code null}
     * @param acceptDataLoss whether the caller accepted the loss up front
     * @param registrar registers the service for the run, answering what takes it down
     * @return the handle that ends the run
     */
    public Run beginRun(String infobaseIdentity, boolean acceptDataLoss, ServiceRegistrar registrar)
    {
        return beginRun(infobaseIdentity, acceptDataLoss, registrar, ModalDialogWatch::modalDialogsOf,
            DIALOG_POLL_MS);
    }

    /**
     * Arms the guard with every seam supplied, which is how the tests drive routes that need a
     * live environment.
     *
     * @param infobaseIdentity the identity of the infobase being updated, or {@code null}
     * @param acceptDataLoss whether the caller accepted the loss up front
     * @param registrar registers the service for the run; {@code null} leaves the service route off
     * @param dialogs where the watcher reads the workbench's modal dialogs from
     * @param pollMs how often the watcher reads it
     * @return the handle that ends the run
     */
    public Run beginRun(String infobaseIdentity, boolean acceptDataLoss, ServiceRegistrar registrar,
        DialogReading dialogs, long pollMs)
    {
        ActiveRun run = new ActiveRun(this, infobaseIdentity, acceptDataLoss);
        if (registrar != null)
        {
            try
            {
                run.serviceRegistration = registrar.register(this);
                run.serviceRegistered = run.serviceRegistration != null;
            }
            catch (RuntimeException | LinkageError couldNotRegister)
            {
                run.serviceRegistered = false;
            }
        }
        active.add(run);
        run.watcher = watchDialogs(run, dialogs, pollMs);
        run.watcher.start();
        return run.handle;
    }

    /**
     * Takes a run down. Idempotent, and safe from any thread: the watcher may be parked in a
     * read that outlives this call, and it re-checks {@code ended} before acting on what it saw.
     *
     * @param run the run to end, or {@code null}
     */
    void endRun(ActiveRun run)
    {
        if (run == null || run.ended)
        {
            return;
        }
        run.ended = true;
        active.remove(run);
        if (run.serviceRegistration != null)
        {
            try
            {
                run.serviceRegistration.close();
            }
            catch (Exception alreadyGone)
            {
                // The framework tears registrations down with the bundle; taking one down twice
                // is not a state worth reporting.
            }
            run.serviceRegistration = null;
        }
        Thread watcher = run.watcher;
        if (watcher != null)
        {
            watcher.interrupt();
        }
    }

    /**
     * The question service route. Parks the question under the most recent armed run and answers
     * it; with nothing armed the question is left to the environment's own handler, which is what
     * keeps an interactive update interactive.
     *
     * @param context the question the platform asked
     * @return the answer to give, or empty to let the environment's own handler have it
     */
    @Override
    public Optional<InfobaseSynchonizationQuestionAnswer> handleQuestion(
        IInfobaseSynchonizationQuestionContext context)
    {
        ActiveRun run = latestArmedRun();
        if (run == null || context == null)
        {
            return Optional.empty();
        }
        List<InfobaseSynchonizationQuestionAnswer> offered = context.getAnswers();
        List<String> labels = new ArrayList<>();
        if (offered != null)
        {
            for (InfobaseSynchonizationQuestionAnswer answer : offered)
            {
                labels.add(answer == null || answer.getLabel() == null ? "" : answer.getLabel()); //$NON-NLS-1$
            }
        }
        String message = context.getMessage() == null ? "" : context.getMessage(); //$NON-NLS-1$
        InfobaseSynchonizationQuestionAnswer answer =
            run.acceptDataLoss ? acceptanceOf(offered) : refusalOf(offered);
        boolean accepted = run.acceptDataLoss && answer != null;
        park(run, ROUTE_SERVICE, message, labels, accepted, answer == null ? null
            : answer.getLabel());
        return Optional.ofNullable(answer);
    }

    /**
     * The latest run still armed. The question context names no infobase, so a question during
     * overlapping runs belongs to the run that armed last - which is the update in flight.
     *
     * @return the run, or {@code null} when nothing is armed
     */
    private ActiveRun latestArmedRun()
    {
        for (int index = active.size() - 1; index >= 0; index--)
        {
            ActiveRun run = active.get(index);
            if (!run.ended)
            {
                return run;
            }
        }
        return null;
    }

    /**
     * The refusal among the offered answers: the one the platform marked default. Ambiguity - no
     * default, or two - answers nothing, because a wrong press here accepts a loss.
     *
     * @param offered the answers the platform offered
     * @return the default answer, or {@code null} when there is not exactly one
     */
    private static InfobaseSynchonizationQuestionAnswer refusalOf(List<InfobaseSynchonizationQuestionAnswer> offered)
    {
        if (offered == null)
        {
            return null;
        }
        InfobaseSynchonizationQuestionAnswer found = null;
        for (InfobaseSynchonizationQuestionAnswer answer : offered)
        {
            if (answer != null && answer.isDefaultAnswer())
            {
                if (found != null)
                {
                    return null;
                }
                found = answer;
            }
        }
        return found;
    }

    /**
     * The acceptance among the offered answers: the single one the platform did not mark default.
     * Two non-defaults answer nothing - which one accepts the loss is not this guard's to guess.
     *
     * @param offered the answers the platform offered
     * @return the non-default answer, or {@code null} when there is not exactly one
     */
    private static InfobaseSynchonizationQuestionAnswer acceptanceOf(
        List<InfobaseSynchonizationQuestionAnswer> offered)
    {
        if (offered == null)
        {
            return null;
        }
        InfobaseSynchonizationQuestionAnswer found = null;
        for (InfobaseSynchonizationQuestionAnswer answer : offered)
        {
            if (answer != null && !answer.isDefaultAnswer())
            {
                if (found != null)
                {
                    return null;
                }
                found = answer;
            }
        }
        return found;
    }

    /**
     * Watches the workbench for the dialog route: a modal that is up while the run is armed is
     * parked, read and not answered. The first question a run parks is the one it keeps - a
     * dialog that follows the first is a consequence of the first, not new information.
     *
     * @param run the armed run
     * @param dialogs where the modal dialogs are read from
     * @param pollMs how often to read
     * @return the watcher, not yet started
     */
    private Thread watchDialogs(ActiveRun run, DialogReading dialogs, long pollMs)
    {
        Thread watcher = new Thread(() -> {
            while (!run.ended)
            {
                try
                {
                    Thread.sleep(pollMs);
                }
                catch (InterruptedException stopped)
                {
                    return;
                }
                if (run.ended || run.parked.get() != null)
                {
                    continue;
                }
                List<Map<String, Object>> showing;
                try
                {
                    showing = dialogs.modalDialogs();
                }
                catch (RuntimeException unreadable)
                {
                    continue;
                }
                if (showing == null || showing.isEmpty())
                {
                    continue;
                }
                Map<String, Object> dialog = showing.get(0);
                String title = String.valueOf(dialog.get("title")); //$NON-NLS-1$
                Object message = dialog.get("message"); //$NON-NLS-1$
                String text = message == null ? title : title + ": " + message; //$NON-NLS-1$
                List<String> buttons = dialog.get("buttons") instanceof List<?> list //$NON-NLS-1$
                    ? list.stream().map(String::valueOf).toList() : List.of();
                park(run, ROUTE_DIALOG, text, buttons, false, null);
            }
        }, "aiedt-data-loss-watch"); //$NON-NLS-1$
        watcher.setDaemon(true);
        return watcher;
    }

    /**
     * Parks a question for a run and, once the registry has a record for the run's identity, in
     * the registry. The first question a run parks is the one it keeps.
     *
     * @param run the armed run the question belongs to
     * @param route the route it arrived by
     * @param question the raw question text
     * @param answers the answer options as offered
     * @param accepted whether the answer given was the acceptance
     * @param answerLabel the label of the answer given, or {@code null} when it left the question
     *            to the environment
     */
    private void park(ActiveRun run, String route, String question, List<String> answers,
        boolean accepted, String answerLabel)
    {
        ParkedQuestion record = new ParkedQuestion(route, question, answers,
            parseDataLossTables(question), run.infobaseIdentity, accepted, answerLabel,
            clock.getAsLong());
        run.parked.compareAndSet(null, record);
        remember(run.infobaseIdentity, run.parked.get());
    }

    /**
     * Keeps the latest parked question of an infobase, bounded by count and read back expiring by
     * age.
     *
     * @param infobaseIdentity the infobase the question stopped, or {@code null} for a run that
     *            named no identity
     * @param record the record to keep
     */
    private void remember(String infobaseIdentity, ParkedQuestion record)
    {
        if (infobaseIdentity == null || record == null)
        {
            return;
        }
        synchronized (parkedRegistry)
        {
            pruneExpiredLocked();
            while (parkedRegistry.size() >= PARKED_MAX)
            {
                parkedRegistry.remove(parkedRegistry.keySet().iterator().next());
            }
            parkedRegistry.put(infobaseIdentity, record);
        }
    }

    /** Drops records whose window has passed. Called under the registry's own lock. */
    private void pruneExpiredLocked()
    {
        long now = clock.getAsLong();
        parkedRegistry.values().removeIf(record -> now - record.parkedAtMs > PARKED_TTL_MS);
    }

    /**
     * The question an update of this infobase was stopped by, if one is still inside its window.
     *
     * @param infobaseIdentity the identity of the infobase, or {@code null}
     * @return the parked question, or empty
     */
    public Optional<ParkedQuestion> parkedOf(String infobaseIdentity)
    {
        if (infobaseIdentity == null)
        {
            return Optional.empty();
        }
        synchronized (parkedRegistry)
        {
            pruneExpiredLocked();
            ParkedQuestion record = parkedRegistry.get(infobaseIdentity);
            return Optional.ofNullable(record);
        }
    }

    /**
     * Reads the metadata addresses out of a question's text. A metadata address is a chain of at
     * least two dotted identifier segments, each starting with a letter - {@code
     * Catalog.X.Attribute.Y} in either alphabet - and nothing else is relied on: the platform's
     * wording is localized, so no word of it is read.
     *
     * @param text the question text, or {@code null}
     * @return the addresses in order of appearance, without duplicates
     */
    public static List<String> parseDataLossTables(String text)
    {
        if (text == null || text.isEmpty())
        {
            return List.of();
        }
        Matcher addresses = DOTTED_ADDRESS.matcher(text);
        LinkedHashSet<String> found = new LinkedHashSet<>();
        while (addresses.find())
        {
            found.add(addresses.group());
        }
        return List.copyOf(found);
    }

    /** Registers the question service for a run and answers what takes the registration down. */
    public interface ServiceRegistrar
    {
        /**
         * Registers the handler.
         *
         * @param handler the guard's service
         * @return what unregisters it, or {@code null} when the route could not be armed
         */
        AutoCloseable register(IInfobaseSynchonizationQuestionHandler handler);
    }

    /** Where the watcher reads the workbench's modal dialogs from. */
    public interface DialogReading
    {
        /**
         * The modal dialogs showing right now, each with its title, message and buttons.
         *
         * @return the readings, empty when none
         */
        List<Map<String, Object>> modalDialogs();
    }
}
