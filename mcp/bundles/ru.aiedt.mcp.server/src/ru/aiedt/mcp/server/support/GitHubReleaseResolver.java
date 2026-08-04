/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Resolves the download URL of an asset attached to the <em>latest</em> release of a
 * GitHub repository, via the public GitHub REST API
 * ({@code GET /repos/{owner}/{repo}/releases/latest}). Used by the convenience flows
 * ({@code install_extension} with a {@code github:owner/repo} source, and
 * {@code yaxunit_tests installYaxunit=true}) so a caller does not need to know the
 * exact release tag or asset URL - only the repo and, when a release carries several
 * assets, a name prefix that picks the right one.
 * <p>
 * The API is documented and public (clean-room: no proprietary source read). Anonymous
 * calls are rate-limited (~60/hour per IP); a {@code User-Agent} header is mandatory and
 * is sent. The response is parsed as JSON (Gson, already bundled) and the {@code assets}
 * array walked - a regex would break on the nested {@code uploader} object each asset
 * carries between {@code name} and {@code browser_download_url}.
 */
public final class GitHubReleaseResolver
{
    /** GitHub-repo source prefixes accepted by {@link #parseRepoSource}. */
    public static final String PREFIX_GITHUB = "github:"; //$NON-NLS-1$
    public static final String PREFIX_GH = "gh:"; //$NON-NLS-1$

    /** Asset fragment separator: {@code github:owner/repo#prefix}. */
    public static final String ASSET_SEP = "#"; //$NON-NLS-1$

    private GitHubReleaseResolver()
    {
        // utility class
    }

    /** A resolved asset: its file name and its direct download URL. */
    public static final class Asset
    {
        public final String name;
        public final String url;
        public Asset(String name, String url)
        {
            this.name = name;
            this.url = url;
        }
    }

    /**
     * Parses a {@code github:owner/repo} / {@code gh:owner/repo} source, optionally with a
     * {@code #assetNamePrefix} fragment. Returns {@code null} when {@code source} is not a
     * GitHub-repo reference (the caller then treats it as a path/URL).
     *
     * @param source the raw source string
     * @return {@code [owner/repo, assetNamePrefix-or-null]}, or {@code null} when not GitHub
     */
    public static String[] parseRepoSource(String source)
    {
        if (source == null)
        {
            return null;
        }
        String trimmed = source.trim();
        String repo = null;
        if (trimmed.toLowerCase().startsWith(PREFIX_GITHUB))
        {
            repo = trimmed.substring(PREFIX_GITHUB.length());
        }
        else if (trimmed.toLowerCase().startsWith(PREFIX_GH))
        {
            repo = trimmed.substring(PREFIX_GH.length());
        }
        if (repo == null || repo.isEmpty())
        {
            return null;
        }
        String prefix = null;
        int hash = repo.indexOf(ASSET_SEP);
        if (hash >= 0)
        {
            prefix = repo.substring(hash + 1).trim();
            repo = repo.substring(0, hash).trim();
        }
        if (repo.isEmpty())
        {
            return null;
        }
        return new String[]{repo, prefix == null || prefix.isEmpty() ? null : prefix};
    }

    /**
     * Resolves the latest release's matching asset URL.
     *
     * @param repo the {@code owner/repo} identifier
     * @param namePrefix when non-null, pick the first asset whose name starts with this
     *     prefix (case-insensitive) and ends with {@code .cfe}; when null, the first
     *     {@code .cfe} asset
     * @return the matching {@link Asset}, or {@code null} when no asset matches
     * @throws Exception on network/HTTP failure or a non-2xx response
     */
    public static Asset resolveLatestCfe(String repo, String namePrefix) throws Exception
    {
        for (Asset a : listAssets(repo))
        {
            String n = a.name.toLowerCase();
            if (!n.endsWith(".cfe")) //$NON-NLS-1$
            {
                continue;
            }
            if (namePrefix == null || namePrefix.isEmpty() || n.startsWith(namePrefix.toLowerCase()))
            {
                return a;
            }
        }
        return null;
    }

    /**
     * Lists the assets of the latest release. Returns the asset name + download URL for each.
     *
     * @param repo the {@code owner/repo} identifier
     * @return the asset list (empty when the release has no assets)
     * @throws Exception on network/HTTP failure or a non-2xx response
     */
    public static List<Asset> listAssets(String repo) throws Exception
    {
        JsonObject release = parseLatestRelease(repo);
        List<Asset> assets = new ArrayList<>();
        if (release == null)
        {
            return assets;
        }
        JsonElement assetsEl = release.get("assets"); //$NON-NLS-1$
        if (assetsEl == null || !assetsEl.isJsonArray())
        {
            return assets;
        }
        for (JsonElement assetEl : assetsEl.getAsJsonArray())
        {
            if (!assetEl.isJsonObject())
            {
                continue;
            }
            JsonObject asset = assetEl.getAsJsonObject();
            JsonElement nameEl = asset.get("name"); //$NON-NLS-1$
            JsonElement urlEl = asset.get("browser_download_url"); //$NON-NLS-1$
            if (nameEl == null || urlEl == null || nameEl.isJsonNull() || urlEl.isJsonNull())
            {
                continue;
            }
            assets.add(new Asset(nameEl.getAsString(), urlEl.getAsString()));
        }
        return assets;
    }

    /** @return the latest release tag (e.g. {@code 25.12}), or {@code null} when absent. */
    public static String latestTag(String repo) throws Exception
    {
        JsonObject release = parseLatestRelease(repo);
        if (release == null)
        {
            return null;
        }
        JsonElement tag = release.get("tag_name"); //$NON-NLS-1$
        return (tag == null || tag.isJsonNull()) ? null : tag.getAsString();
    }

    private static JsonObject parseLatestRelease(String repo) throws Exception
    {
        String body = fetchLatestRelease(repo);
        JsonElement parsed = JsonParser.parseString(body);
        return (parsed != null && parsed.isJsonObject()) ? parsed.getAsJsonObject() : null;
    }

    private static String fetchLatestRelease(String repo) throws Exception
    {
        if (repo == null || repo.trim().isEmpty())
        {
            throw new IllegalArgumentException("repo is required (owner/repo)"); //$NON-NLS-1$
        }
        String url = "https://api.github.com/repos/" + repo.trim() + "/releases/latest"; //$NON-NLS-1$ //$NON-NLS-2$
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/vnd.github+json") //$NON-NLS-1$ //$NON-NLS-2$
            .header("User-Agent", "ai-edt-mcp") //$NON-NLS-1$ //$NON-NLS-2$
            .GET()
            .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        int code = response.statusCode();
        if (code < 200 || code >= 300)
        {
            throw new IllegalStateException("GitHub API returned HTTP " + code + " for " + repo //$NON-NLS-1$ //$NON-NLS-2$
                + " (anonymous calls are rate-limited; check the repo path and network)."); //$NON-NLS-1$
        }
        return response.body();
    }
}
