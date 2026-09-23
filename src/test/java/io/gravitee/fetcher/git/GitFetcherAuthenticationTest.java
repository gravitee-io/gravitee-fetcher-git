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

import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.gravitee.fetcher.api.FetcherException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.servlet.http.HttpServletRequest;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.http.server.GitServlet;
import org.eclipse.jgit.junit.http.AppServer;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.resolver.RepositoryResolver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * Acceptance tests for PORTAL-197: the fetcher must authenticate against a private repository.
 *
 * <p>The repository is served over HTTP by JGit's own test server, once anonymously and once behind Basic
 * authentication, so that both the authenticated and the unauthenticated paths are exercised for real.
 *
 * @author GraviteeSource Team
 */
class GitFetcherAuthenticationTest {

    private static final String BRANCH = "master";
    private static final String PAGE_PATH = "docs/page.md";
    private static final String PAGE_CONTENT = "private documentation page";

    private static final String AUTHENTICATION_REQUIRED_MESSAGE =
        "Unable to fetch git content: repository '%s' requires authentication but no credentials are configured";
    private static final String AUTHENTICATION_FAILED_MESSAGE =
        "Unable to fetch git content: authentication failed for repository '%s', check the configured username and password or token";

    @TempDir
    static Path repositoryDirectory;

    private static Git git;
    private static Repository repository;
    private static AppServer server;
    private static String publicRepositoryUri;
    private static String privateRepositoryUri;

    @BeforeAll
    static void serve_local_repository() throws Exception {
        git = Git.init().setDirectory(repositoryDirectory.toFile()).setInitialBranch(BRANCH).call();
        Files.createDirectories(repositoryDirectory.resolve("docs"));
        Files.writeString(repositoryDirectory.resolve(PAGE_PATH), PAGE_CONTENT);
        git.add().addFilepattern(".").call();
        PersonIdent committer = new PersonIdent("Gravitee Test", "test@gravitee.io");
        git.commit().setMessage("init").setAuthor(committer).setCommitter(committer).setSign(false).call();
        repository = git.getRepository();

        server = new AppServer(0);
        gitContext("/public");
        ServletContextHandler privateContext = gitContext("/private");
        server.authBasic(privateContext);
        server.setUp();

        publicRepositoryUri = server.getURI() + "/public/documentation.git";
        privateRepositoryUri = server.getURI() + "/private/documentation.git";
    }

    @AfterAll
    static void stop_serving_local_repository() throws Exception {
        if (server != null) {
            server.tearDown();
        }
        if (git != null) {
            git.close();
        }
    }

    @Test
    void should_fetch_a_public_repository_when_no_credentials_are_configured() throws Exception {
        GitFetcher fetcher = new GitFetcher(configuration(publicRepositoryUri, null, null));

        assertThat(readAll(fetcher.fetch().getContent())).isEqualTo(PAGE_CONTENT);
    }

    @Test
    void should_fetch_a_private_repository_with_valid_credentials() throws Exception {
        GitFetcher fetcher = new GitFetcher(configuration(privateRepositoryUri, AppServer.username, AppServer.password));

        assertThat(readAll(fetcher.fetch().getContent())).isEqualTo(PAGE_CONTENT);
    }

    @Test
    void should_fail_with_an_explicit_error_when_the_credentials_are_invalid() {
        GitFetcher fetcher = new GitFetcher(configuration(privateRepositoryUri, AppServer.username, "wrong-token"));

        assertThatThrownBy(fetcher::fetch)
            .isInstanceOf(FetcherException.class)
            .hasMessage(AUTHENTICATION_FAILED_MESSAGE.formatted(privateRepositoryUri))
            .hasCauseInstanceOf(org.eclipse.jgit.api.errors.TransportException.class);
    }

    @Test
    void should_fail_with_an_explicit_error_when_credentials_are_required_but_not_configured() {
        GitFetcher fetcher = new GitFetcher(configuration(privateRepositoryUri, null, null));

        assertThatThrownBy(fetcher::fetch)
            .isInstanceOf(FetcherException.class)
            .hasMessage(AUTHENTICATION_REQUIRED_MESSAGE.formatted(privateRepositoryUri))
            .hasCauseInstanceOf(org.eclipse.jgit.api.errors.TransportException.class);
    }

    @Test
    void should_keep_the_generic_error_when_the_failure_is_not_an_authentication_one() {
        GitFetcherConfiguration configuration = configuration(publicRepositoryUri, null, null);
        configuration.setBranchOrTag("unknown-branch");
        GitFetcher fetcher = new GitFetcher(configuration);

        // A missing branch must not be reported as an authentication problem
        assertThatThrownBy(fetcher::fetch)
            .isInstanceOf(FetcherException.class)
            .hasMessageStartingWith("Unable to fetch git content (")
            .hasMessageNotContaining("authentication");
    }

    @Test
    void should_not_leak_the_configured_password_in_the_error_message() {
        GitFetcher fetcher = new GitFetcher(configuration(privateRepositoryUri, AppServer.username, "s3cr3t-token"));

        assertThatThrownBy(fetcher::fetch).isInstanceOf(FetcherException.class).hasMessageNotContaining("s3cr3t-token");
    }

    @Test
    void should_not_leak_the_credentials_embedded_in_the_repository_url() {
        String repositoryWithCredentials = privateRepositoryUri.replace("http://", "http://someone:s3cr3t-token@");
        GitFetcher fetcher = new GitFetcher(configuration(repositoryWithCredentials, null, null));

        // Credentials carried by the URL are credentials too: the failure is a rejection, not a missing configuration
        assertThatThrownBy(fetcher::fetch)
            .isInstanceOf(FetcherException.class)
            .hasMessage(AUTHENTICATION_FAILED_MESSAGE.formatted(privateRepositoryUri))
            .hasMessageNotContaining("s3cr3t-token");
    }

    @Test
    void should_keep_fetching_with_the_stored_credentials_when_auto_fetch_is_enabled() throws Exception {
        GitFetcherConfiguration configuration = configuration(privateRepositoryUri, AppServer.username, AppServer.password);
        configuration.setAutoFetch(true);
        configuration.setFetchCron("0 0 4 * * *");
        GitFetcher fetcher = new GitFetcher(configuration);

        assertThat(readAll(fetcher.fetch().getContent())).isEqualTo(PAGE_CONTENT);
        assertThat(readAll(fetcher.fetch().getContent())).isEqualTo(PAGE_CONTENT);
    }

    @Test
    void should_warn_without_leaking_them_when_configured_credentials_are_sent_over_plain_http() throws Throwable {
        GitFetcher fetcher = new GitFetcher(configuration(publicRepositoryUri, "someone", "s3cr3t-token"));

        List<ILoggingEvent> warnings = warningsLoggedDuring(() -> readAll(fetcher.fetch().getContent()));

        assertThat(warnings)
            .singleElement()
            .extracting(ILoggingEvent::getFormattedMessage, as(STRING))
            .contains("'" + publicRepositoryUri + "'", "will be sent unencrypted")
            .doesNotContain("s3cr3t-token");
    }

    @Test
    void should_warn_without_leaking_them_when_credentials_embedded_in_the_url_are_sent_over_plain_http() throws Throwable {
        String repositoryWithCredentials = publicRepositoryUri.replace("http://", "http://someone:s3cr3t-token@");
        GitFetcher fetcher = new GitFetcher(configuration(repositoryWithCredentials, null, null));

        List<ILoggingEvent> warnings = warningsLoggedDuring(() -> readAll(fetcher.fetch().getContent()));

        assertThat(warnings)
            .singleElement()
            .extracting(ILoggingEvent::getFormattedMessage, as(STRING))
            .contains("'" + publicRepositoryUri + "'", "will be sent unencrypted")
            .doesNotContain("s3cr3t-token");
    }

    @Test
    void should_not_warn_when_no_credentials_are_sent_over_plain_http() throws Throwable {
        GitFetcher fetcher = new GitFetcher(configuration(publicRepositoryUri, null, null));

        assertThat(warningsLoggedDuring(() -> readAll(fetcher.fetch().getContent()))).isEmpty();
    }

    private static ServletContextHandler gitContext(String path) {
        GitServlet gitServlet = new GitServlet();
        RepositoryResolver<HttpServletRequest> resolver = (request, name) -> {
            // The servlet closes the repository after each request, so hand it a new hold every time
            repository.incrementOpen();
            return repository;
        };
        gitServlet.setRepositoryResolver(resolver);

        ServletContextHandler context = server.addContext(path);
        context.addServlet(new ServletHolder(gitServlet), "/*");
        return context;
    }

    private static GitFetcherConfiguration configuration(String repository, String username, String password) {
        GitFetcherConfiguration configuration = new GitFetcherConfiguration();
        configuration.setRepository(repository);
        configuration.setBranchOrTag(BRANCH);
        configuration.setPath(PAGE_PATH);
        configuration.setUsername(username);
        configuration.setPassword(password);
        return configuration;
    }

    private static List<ILoggingEvent> warningsLoggedDuring(ThrowingCallable action) throws Throwable {
        Logger logger = (Logger) LoggerFactory.getLogger(GitFetcher.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.call();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list
            .stream()
            .filter(event -> event.getLevel() == Level.WARN)
            .toList();
    }

    private static String readAll(InputStream inputStream) throws Exception {
        try (inputStream) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
