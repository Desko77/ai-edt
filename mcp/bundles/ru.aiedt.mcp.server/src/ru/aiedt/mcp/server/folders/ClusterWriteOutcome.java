/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.folders;

import ru.aiedt.mcp.server.folders.model.Cluster;
import ru.aiedt.mcp.server.folders.repository.ClusterSaveOutcome;

/**
 * What one edit of a project's clusters did.
 * <p>
 * The edit either reached the file, in which case {@link #getSaveOutcome()} is that save, or it
 * was refused before a write. A refusal before a write carries {@link #CLUSTER_NOT_FOUND},
 * {@link #CLUSTER_EXISTS} or {@link #NAME_TAKEN}. There is no remembered "last refusal": the
 * reason is this value and nothing else. {@link ClusterSaveOutcome#NO_CHANGE} is not a refusal.
 * </p>
 */
public final class ClusterWriteOutcome
{
    /** The named cluster is not in the project. */
    public static final String CLUSTER_NOT_FOUND = "clusterNotFound"; //$NON-NLS-1$

    /** A cluster already occupies the path a create asked for. */
    public static final String CLUSTER_EXISTS = "clusterExists"; //$NON-NLS-1$

    /** The new name is already used by another cluster. */
    public static final String NAME_TAKEN = "nameTaken"; //$NON-NLS-1$

    private final ClusterSaveOutcome saveOutcome;

    private final String domainCode;

    private final Cluster cluster;

    /**
     * An edit that reached the file.
     *
     * @param saveOutcome what the save did; must not be {@code null}
     * @return the outcome
     */
    public static ClusterWriteOutcome of(ClusterSaveOutcome saveOutcome)
    {
        return of(saveOutcome, null);
    }

    /**
     * An edit that reached the file and, when it was a create that succeeded, the new cluster.
     *
     * @param saveOutcome what the save did; must not be {@code null}
     * @param cluster the created cluster; {@code null} except on a successful create
     * @return the outcome
     */
    public static ClusterWriteOutcome of(ClusterSaveOutcome saveOutcome, Cluster cluster)
    {
        if (saveOutcome == null)
        {
            throw new IllegalArgumentException("save outcome must not be null"); //$NON-NLS-1$
        }
        return new ClusterWriteOutcome(saveOutcome, null, cluster);
    }

    /**
     * A refusal decided before a write.
     *
     * @param code {@link #CLUSTER_NOT_FOUND}, {@link #CLUSTER_EXISTS} or {@link #NAME_TAKEN}
     * @return the outcome
     */
    public static ClusterWriteOutcome domain(String code)
    {
        if (!CLUSTER_NOT_FOUND.equals(code) && !CLUSTER_EXISTS.equals(code) && !NAME_TAKEN.equals(code))
        {
            throw new IllegalArgumentException("not a cluster edit refusal: " + code); //$NON-NLS-1$
        }
        return new ClusterWriteOutcome(null, code, null);
    }

    /**
     * @param saveOutcome the save, or {@code null} when the edit was refused before a write
     * @param domainCode the pre-write refusal, or {@code null} when the edit reached the file
     * @param cluster the created cluster, or {@code null}
     */
    private ClusterWriteOutcome(ClusterSaveOutcome saveOutcome, String domainCode, Cluster cluster)
    {
        this.saveOutcome = saveOutcome;
        this.domainCode = domainCode;
        this.cluster = cluster;
    }

    /**
     * What the save did, or {@code null} when the edit was refused before a write.
     *
     * @return the save outcome
     */
    public ClusterSaveOutcome getSaveOutcome()
    {
        return saveOutcome;
    }

    /**
     * The cluster a successful create added, or {@code null}.
     *
     * @return the created cluster
     */
    public Cluster getCluster()
    {
        return cluster;
    }

    /**
     * The reason code: a domain code, or the save's code when the edit reached the file.
     *
     * @return the code
     */
    public String getCode()
    {
        return domainCode != null ? domainCode : saveOutcome.getCode();
    }

    /**
     * Extra text from the save, or {@code null} when the edit did not reach the file.
     *
     * @return the save detail
     */
    public String getDetail()
    {
        return saveOutcome == null ? null : saveOutcome.getDetail();
    }

    /**
     * Whether the edit changed nothing and is not a refusal.
     *
     * @return {@code true} when the save outcome is {@link ClusterSaveOutcome#NO_CHANGE}
     */
    public boolean isNoChange()
    {
        return saveOutcome != null && saveOutcome.isNoChange();
    }

    /**
     * Whether the edit was refused, either before a write or by the save.
     *
     * @return {@code true} when the caller should report a failure
     */
    public boolean isRefused()
    {
        return domainCode != null || (saveOutcome != null && saveOutcome.isRefused());
    }

    /**
     * Whether the caller can treat the edit as done.
     *
     * @return {@code true} when the edit was not refused
     */
    public boolean succeeded()
    {
        return !isRefused();
    }

    /**
     * A sentence naming this outcome, for a person reading a dialog.
     *
     * @return the sentence
     */
    public String explanation()
    {
        if (CLUSTER_NOT_FOUND.equals(domainCode))
        {
            return "The cluster was not found."; //$NON-NLS-1$
        }
        if (CLUSTER_EXISTS.equals(domainCode))
        {
            return "A cluster already exists at this path."; //$NON-NLS-1$
        }
        if (NAME_TAKEN.equals(domainCode))
        {
            return "That name is already used by another cluster."; //$NON-NLS-1$
        }
        return saveOutcome.explanation();
    }

    /**
     * @return the code, and the detail when there is one
     */
    @Override
    public String toString()
    {
        return saveOutcome == null ? domainCode : saveOutcome.toString();
    }
}
