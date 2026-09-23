/*
 * Copyright © 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.fetcher.git;

import io.gravitee.fetcher.api.Fetcher;
import io.gravitee.fetcher.api.FetcherConfiguration;
import io.gravitee.fetcher.api.FetcherException;
import io.gravitee.fetcher.api.Resource;
import io.gravitee.fetcher.api.ResourceNotFoundException;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.support.CronExpression;

/**
 * @author Nicolas GERAUD (nicolas <AT> graviteesource.com)
 * @author GraviteeSource Team
 */
public class GitFetcher implements Fetcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(GitFetcher.class);
    private static final Pattern URL_CREDENTIALS = Pattern.compile("^([a-zA-Z][a-zA-Z0-9+.\\-]*://)[^/@]*@");
    private static final List<String> SAFE_TRANSPORT_PREFIXES = List.of("https://", "ssh://", "git@", "file://");
    private static final List<String> AUTHENTICATION_FAILURE_HINTS = List.of(
        "not authorized",
        "authentication is required",
        "authentication failed",
        "401 unauthorized"
    );

    private final GitFetcherConfiguration gitFetcherConfiguration;

    public GitFetcher(GitFetcherConfiguration gitFetcherConfiguration) {
        this.gitFetcherConfiguration = gitFetcherConfiguration;
    }

    @Override
    public Resource fetch() throws FetcherException {
        if (
            gitFetcherConfiguration.isAutoFetch() &&
            (gitFetcherConfiguration.getFetchCron() == null || gitFetcherConfiguration.getFetchCron().isEmpty())
        ) {
            throw new FetcherException("Some required configuration attributes are missing.", null);
        }

        if (gitFetcherConfiguration.isAutoFetch() && gitFetcherConfiguration.getFetchCron() != null) {
            try {
                CronExpression.parse(gitFetcherConfiguration.getFetchCron());
            } catch (IllegalArgumentException e) {
                throw new FetcherException("Cron expression is invalid", e);
            }
        }

        final String normalizedPath = normalizePath(gitFetcherConfiguration.getPath());
        if (normalizedPath.isEmpty()) {
            throw new FetcherException("Unable to fetch git content: the path to the file to fetch is missing", null);
        }

        File tmpDirectory;
        try {
            tmpDirectory = File.createTempFile("Gravitee-io", "");
            tmpDirectory.delete();
        } catch (IOException e) {
            throw new FetcherException("Unable to create temporary directory to fetch git repository", e);
        }

        if (sendsCredentialsInClearText(gitFetcherConfiguration)) {
            LOGGER.warn(
                "Credentials configured for repository '{}' will be sent unencrypted: use an https:// URL to protect them",
                sanitizeRepository(gitFetcherConfiguration.getRepository())
            );
        }

        // Track whether we successfully returned a stream so the finally block can clean up on error paths.
        boolean streamReturned = false;
        try {
            final Resource resource = new Resource();
            Path repositoryPath;
            try (
                Git result = Git.cloneRepository()
                    .setURI(this.gitFetcherConfiguration.getRepository())
                    .setDirectory(tmpDirectory)
                    .setBranch(this.gitFetcherConfiguration.getBranchOrTag())
                    .setCredentialsProvider(credentialsProvider(this.gitFetcherConfiguration))
                    .setDepth(1)
                    .call()
            ) {
                LOGGER.debug("Having repository: {}", result.getRepository().getDirectory());
                repositoryPath = result.getRepository().getWorkTree().toPath();
            } catch (Exception e) {
                throw toFetcherException(e);
            }

            try (Stream<Path> stream = Files.walk(repositoryPath)) {
                File fileToFetch = stream
                    .filter(path -> path.endsWith(normalizedPath))
                    .findAny()
                    .map(Path::toFile)
                    .orElseThrow(() -> new ResourceNotFoundException(buildNotFoundMessage(), null));

                if (Files.isSymbolicLink(fileToFetch.toPath())) {
                    checkSymbolicLinkTargetIsInsideDirectory(fileToFetch, tmpDirectory);
                }

                resource.setContent(new CleanupInputStream(new FileInputStream(fileToFetch), tmpDirectory));
                streamReturned = true;
            } catch (IOException e) {
                throw new FetcherException("Unable to walk through the repository files", e);
            }

            return resource;
        } finally {
            if (!streamReturned) {
                deleteQuietly(tmpDirectory);
            }
        }
    }

    /**
     * Builds the JGit credentials from the configured username and password, or {@code null} when the repository is
     * public and no credentials are configured.
     *
     * <p>An access token is sent as the password, and providers usually ignore the username that goes with it. Half a
     * configuration is still sent rather than dropped, so that a server which accepts it works and the others answer
     * with an authentication failure the publisher can act on.
     */
    static CredentialsProvider credentialsProvider(GitFetcherConfiguration configuration) {
        String username = trimToNull(configuration.getUsername());
        String password = trimToNull(configuration.getPassword());
        if (username == null && password == null) {
            return null;
        }
        return new UsernamePasswordCredentialsProvider(username == null ? "" : username, password == null ? "" : password);
    }

    /**
     * Whether the configured credentials would leave the gateway unencrypted. HTTPS and SSH protect them; a plain
     * {@code http://} remote does not, and a local {@code file://} one sends nothing over the wire.
     */
    static boolean sendsCredentialsInClearText(GitFetcherConfiguration configuration) {
        if (credentialsProvider(configuration) == null || configuration.getRepository() == null) {
            return false;
        }
        String repository = configuration.getRepository().trim().toLowerCase(Locale.ROOT);
        return SAFE_TRANSPORT_PREFIXES.stream().noneMatch(repository::startsWith);
    }

    private FetcherException toFetcherException(Exception e) {
        if (!isAuthenticationFailure(e)) {
            return new FetcherException("Unable to fetch git content (" + e.getMessage() + ")", e);
        }

        String repository = sanitizeRepository(gitFetcherConfiguration.getRepository());
        if (hasCredentials()) {
            return new FetcherException(
                "Unable to fetch git content: authentication failed for repository '" +
                    repository +
                    "', check the configured username and password or token",
                e
            );
        }
        return new FetcherException(
            "Unable to fetch git content: repository '" + repository + "' requires authentication but no credentials are configured",
            e
        );
    }

    /** Credentials carried by the repository URL (https://user:token@host/...) are credentials too. */
    private boolean hasCredentials() {
        String repository = gitFetcherConfiguration.getRepository();
        return credentialsProvider(gitFetcherConfiguration) != null || (repository != null && URL_CREDENTIALS.matcher(repository).find());
    }

    /**
     * JGit reports a rejected or a missing authentication as a transport failure whose wording depends on the
     * transport, so the whole cause chain is scanned for the phrases it uses.
     */
    private static boolean isAuthenticationFailure(Throwable throwable) {
        for (Throwable cause = throwable; cause != null && cause != cause.getCause(); cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message == null) {
                continue;
            }
            String lowerCaseMessage = message.toLowerCase(Locale.ROOT);
            if (AUTHENTICATION_FAILURE_HINTS.stream().anyMatch(lowerCaseMessage::contains)) {
                return true;
            }
        }
        return false;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Accepts both path forms (with or without leading slash); never persisted back to the configuration. */
    private static String normalizePath(String path) {
        return path == null ? "" : path.trim().replaceAll("^/+", "");
    }

    private String buildNotFoundMessage() {
        String ref = gitFetcherConfiguration.getBranchOrTag() == null || gitFetcherConfiguration.getBranchOrTag().isEmpty()
            ? "default branch"
            : gitFetcherConfiguration.getBranchOrTag();
        return (
            "Unable to find file '" +
            gitFetcherConfiguration.getPath() +
            "' in repository '" +
            sanitizeRepository(gitFetcherConfiguration.getRepository()) +
            "' (ref: " +
            ref +
            ")"
        );
    }

    /**
     * Credentials can also be embedded in the repository URL (https://user:token@host/...), as this was the only way to
     * reach a private repository before the plugin had credential fields. Strip the userinfo part before the URL
     * reaches an error message.
     */
    static String sanitizeRepository(String repository) {
        return repository == null ? "" : URL_CREDENTIALS.matcher(repository).replaceFirst("$1");
    }

    static final class CleanupInputStream extends FilterInputStream {

        private File tmpDirectory;

        CleanupInputStream(FileInputStream in, File tmpDirectory) {
            super(in);
            this.tmpDirectory = tmpDirectory;
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                deleteQuietly(tmpDirectory);
                tmpDirectory = null; // double-close guard
            }
        }
    }

    private static void deleteQuietly(File dir) {
        if (dir == null || !dir.exists()) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir.toPath())) {
            walk
                .sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException e) {
                        LOGGER.warn("Failed to delete tmp file {}", p, e);
                    }
                });
        } catch (IOException | UncheckedIOException e) {
            LOGGER.warn("Failed to walk tmp directory for cleanup: {}", dir, e);
        }
    }

    private static void checkSymbolicLinkTargetIsInsideDirectory(File symlink, File directory) throws FetcherException {
        Path symlinkPath;
        try {
            symlinkPath = Files.readSymbolicLink(symlink.toPath()).toAbsolutePath();
        } catch (IOException e) {
            throw new FetcherException("A error occurred while trying to read symbolic link target", e);
        }

        if (!symlinkPath.startsWith(directory.toPath().toAbsolutePath())) {
            throw new FetcherException("Accessing a file outside the Git repository using symbolic links is not allowed", null);
        }
    }

    @Override
    public FetcherConfiguration getConfiguration() {
        return gitFetcherConfiguration;
    }
}
