/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;

/**
 * Waits for the HTTP endpoint a launched client is supposed to open, on behalf of the launch
 * tools.
 * <p>
 * A client that opens an external processor or report is ready when that processor is listening,
 * not when the process exists. The readiness rule is a GET: any final answer with a status below
 * 500 counts, a connection refused or a timeout does not; a redirect is followed, not more than
 * five times, and the answer after the last hop is the one that counts. One read gets at most 5
 * seconds and never more than the budget left, so a server that accepts TCP and says nothing
 * cannot hold the whole wait.
 * </p>
 */
public final class EndpointWaiter
{
    /** The longest one request may take, independent of the budget left. */
    public static final int MAX_SINGLE_REQUEST_MS = 5000;

    /** The longest a redirect chain may be. */
    public static final int MAX_REDIRECTS = 5;

    /** How often the endpoint is asked. */
    public static final int POLL_STEP_MS = 500;

    private EndpointWaiter()
    {
        // utility
    }

    /** What one wait found. */
    public static final class Outcome
    {
        /** The endpoint answered, below 500. */
        public boolean ready;
        /** The status the final answer carried, or 0 when no answer came. */
        public int httpStatus;
        /** How long the wait took, in milliseconds. */
        public long waitedMs;
        /** What went wrong last, when nothing answered - connection refused or a timeout. */
        public String lastProblem;
        /** The URL that was asked, after any redirects. */
        public String asked;
    }

    /**
     * Waits until the endpoint answers or the budget is spent.
     *
     * @param url the endpoint to poll.
     * @param budgetMs how long to wait in total.
     * @return what was seen, never <code>null</code>
     */
    public static Outcome waitFor(String url, long budgetMs)
    {
        Outcome outcome = new Outcome();
        outcome.asked = url;
        long deadline = System.currentTimeMillis() + budgetMs;
        String current = url;
        while (System.currentTimeMillis() < deadline)
        {
            long left = deadline - System.currentTimeMillis();
            int requestMs = (int)Math.min(MAX_SINGLE_REQUEST_MS, Math.max(250, left));
            int[] status = {0};
            String[] redirectTo = {null};
            String problem = askOnce(current, requestMs, status, redirectTo);
            outcome.lastProblem = problem;
            if (problem == null)
            {
                if (redirectTo[0] != null)
                {
                    current = resolve(current, redirectTo[0]);
                    int hops = countRedirects(url, current);
                    if (hops > MAX_REDIRECTS)
                    {
                        outcome.lastProblem = "more than " + MAX_REDIRECTS + " redirects"; //$NON-NLS-1$
                        break;
                    }
                    continue;
                }
                if (status[0] < 500)
                {
                    outcome.ready = true;
                    outcome.httpStatus = status[0];
                    outcome.asked = current;
                    outcome.waitedMs = budgetMs - (deadline - System.currentTimeMillis());
                    return outcome;
                }
                outcome.lastProblem = "HTTP " + status[0]; //$NON-NLS-1$
            }
            sleepQuietly(Math.min(POLL_STEP_MS, Math.max(50, left)));
        }
        outcome.waitedMs = budgetMs;
        return outcome;
    }

    /**
     * One GET, no redirects honoured by the connection itself.
     *
     * @param url the address.
     * @param timeoutMs the request's own timeout.
     * @param status out: the answer's status, when one came.
     * @param redirectTo out: the Location header of a 3xx answer.
     * @return what went wrong, or <code>null</code> when an answer came
     */
    private static String askOnce(String url, int timeoutMs, int[] status, String[] redirectTo)
    {
        HttpURLConnection connection = null;
        try
        {
            connection = (HttpURLConnection)new URL(url).openConnection();
            connection.setRequestMethod("GET"); //$NON-NLS-1$
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
            connection.setInstanceFollowRedirects(false);
            connection.connect();
            int code = connection.getResponseCode();
            status[0] = code;
            if (code >= 300 && code < 400)
            {
                redirectTo[0] = connection.getHeaderField("Location"); //$NON-NLS-1$
            }
            return null;
        }
        catch (IOException e)
        {
            return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        }
        finally
        {
            if (connection != null)
            {
                connection.disconnect();
            }
        }
    }

    private static String resolve(String base, String location)
    {
        try
        {
            return new URL(new URL(base), location).toString();
        }
        catch (Exception e)
        {
            return location;
        }
    }

    private static int countRedirects(String from, String to)
    {
        try
        {
            URI f = URI.create(from);
            URI t = URI.create(to);
            return f.equals(t) ? 0 : 1;
        }
        catch (Exception e)
        {
            return 1;
        }
    }

    private static void sleepQuietly(long ms)
    {
        try
        {
            Thread.sleep(ms);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }
}
