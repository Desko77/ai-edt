/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.wire.jsonrpc;

import ru.aiedt.mcp.server.support.TaskDirectory;
import ru.aiedt.mcp.server.wire.McpServerMeta;

/**
 * A task on the wire, in the two shapes the tasks extension defines for it.
 * <p>
 * The handed-back handle and the answer to a poll are the same object with a different
 * {@code resultType}: {@code task} says "this is not your answer, here is where it will be";
 * {@code complete} says "this is a finished answer, and what it describes is a task". Keeping them
 * one class keeps the field names from drifting apart between the two places a client reads them.
 * </p>
 * <p>
 * {@code result} is the original request's own result shape - for a tool call, what
 * {@code tools/call} would have returned had it not taken so long. It is held as an already-built
 * object rather than text so the serializer writes it as a member and not as a quoted string.
 * </p>
 */
public class TaskResult
{
    private final String resultType;

    private final String taskId;

    private final String status;

    private final String statusMessage;

    private final String createdAt;

    private final String lastUpdatedAt;

    private final Long ttlMs;

    private final Long pollIntervalMs;

    private final Object result;

    private final Object error;

    private TaskResult(String resultType, String taskId, String status, String statusMessage,
        String createdAt, String lastUpdatedAt, Long ttlMs, Long pollIntervalMs, Object result,
        Object error)
    {
        this.resultType = resultType;
        this.taskId = taskId;
        this.status = status;
        this.statusMessage = statusMessage;
        this.createdAt = createdAt;
        this.lastUpdatedAt = lastUpdatedAt;
        this.ttlMs = ttlMs;
        this.pollIntervalMs = pollIntervalMs;
        this.result = result;
        this.error = error;
    }

    /**
     * The handle returned in place of an answer, when the work will take a while.
     *
     * @param task the run being named.
     * @return the handle
     */
    public static TaskResult handle(TaskDirectory.Task task)
    {
        return new TaskResult(McpServerMeta.RESULT_TASK, task.taskId, task.status,
            task.statusMessage, iso(task.createdAt), iso(task.lastUpdatedAt), TaskDirectory.TTL_MS,
            TaskDirectory.POLL_INTERVAL_MS, null, null);
    }

    /**
     * The answer to a poll: the current state, and for a finished task what it produced.
     *
     * @param task the run being reported.
     * @param finishedResult the original request's result, or {@code null} unless completed.
     * @param failure the JSON-RPC error, or {@code null} unless failed.
     * @return the poll answer
     */
    public static TaskResult state(TaskDirectory.Task task, Object finishedResult, Object failure)
    {
        return new TaskResult(McpServerMeta.RESULT_COMPLETE, task.taskId, task.status,
            task.statusMessage, iso(task.createdAt), iso(task.lastUpdatedAt), TaskDirectory.TTL_MS,
            TaskDirectory.POLL_INTERVAL_MS, finishedResult, failure);
    }

    /**
     * Returns the kind of this result.
     *
     * @return {@code task} for a handle, {@code complete} for a poll answer
     */
    public String getResultType()
    {
        return resultType;
    }

    /**
     * Returns the task identifier.
     *
     * @return the id
     */
    public String getTaskId()
    {
        return taskId;
    }

    /**
     * Returns where the task has got to.
     *
     * @return one of {@code working}, {@code completed}, {@code failed}, {@code cancelled}
     */
    public String getStatus()
    {
        return status;
    }

    /**
     * Returns a sentence about the current state.
     *
     * @return the message, or {@code null}
     */
    public String getStatusMessage()
    {
        return statusMessage;
    }

    /**
     * Returns when the task was opened.
     *
     * @return an ISO 8601 instant
     */
    public String getCreatedAt()
    {
        return createdAt;
    }

    /**
     * Returns when the task last moved.
     *
     * @return an ISO 8601 instant
     */
    public String getLastUpdatedAt()
    {
        return lastUpdatedAt;
    }

    /**
     * Returns how long the task remains answerable.
     *
     * @return the lifetime in milliseconds
     */
    public Long getTtlMs()
    {
        return ttlMs;
    }

    /**
     * Returns how often the client is asked to poll.
     *
     * @return the interval in milliseconds
     */
    public Long getPollIntervalMs()
    {
        return pollIntervalMs;
    }

    /**
     * Returns what the original request produced.
     *
     * @return the result, or {@code null} while the task is unfinished
     */
    public Object getResult()
    {
        return result;
    }

    /**
     * Returns what went wrong.
     *
     * @return the error, or {@code null} unless the task failed
     */
    public Object getError()
    {
        return error;
    }

    private static String iso(long epochMillis)
    {
        return java.time.Instant.ofEpochMilli(epochMillis).toString();
    }
}
