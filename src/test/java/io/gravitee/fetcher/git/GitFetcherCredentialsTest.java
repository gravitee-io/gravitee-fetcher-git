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

import static org.assertj.core.api.Assertions.assertThat;

import io.gravitee.fetcher.api.Sensitive;
import org.eclipse.jgit.transport.CredentialItem;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.URIish;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * How the configured credentials are turned into JGit credentials, including the token-only form accepted by most
 * hosting providers (PORTAL-197).
 *
 * @author GraviteeSource Team
 */
class GitFetcherCredentialsTest {

    private static final URIish REPOSITORY = uri("https://example.org/org/documentation.git");

    @Test
    void should_not_build_any_credentials_provider_when_nothing_is_configured() {
        assertThat(GitFetcher.credentialsProvider(configuration(null, null))).isNull();
    }

    @Test
    void should_not_build_any_credentials_provider_when_the_credentials_are_blank() {
        assertThat(GitFetcher.credentialsProvider(configuration("  ", "  "))).isNull();
    }

    @Test
    void should_build_a_credentials_provider_from_the_username_and_the_password() {
        CredentialsProvider provider = GitFetcher.credentialsProvider(configuration("publisher", "s3cr3t-token"));

        assertThat(provider).isNotNull();
        assertThat(usernameOf(provider)).isEqualTo("publisher");
        assertThat(passwordOf(provider)).isEqualTo("s3cr3t-token");
    }

    @Test
    void should_build_a_credentials_provider_with_an_empty_username_when_only_a_token_is_configured() {
        CredentialsProvider provider = GitFetcher.credentialsProvider(configuration(null, "s3cr3t-token"));

        assertThat(provider).isNotNull();
        assertThat(usernameOf(provider)).isEmpty();
        assertThat(passwordOf(provider)).isEqualTo("s3cr3t-token");
    }

    @Test
    void should_build_a_credentials_provider_with_an_empty_password_when_only_a_username_is_configured() {
        CredentialsProvider provider = GitFetcher.credentialsProvider(configuration("publisher", null));

        assertThat(provider).isNotNull();
        assertThat(usernameOf(provider)).isEqualTo("publisher");
        assertThat(passwordOf(provider)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = { "http://example.org/org/documentation.git", " HTTP://example.org/org/documentation.git" })
    void should_flag_configured_credentials_sent_over_a_plain_http_repository(String repository) {
        GitFetcherConfiguration configuration = configuration("publisher", "s3cr3t-token");
        configuration.setRepository(repository);

        assertThat(GitFetcher.sendsCredentialsInClearText(configuration)).isTrue();
    }

    @Test
    void should_flag_credentials_embedded_in_a_plain_http_repository_url() {
        GitFetcherConfiguration configuration = configuration(null, null);
        configuration.setRepository("http://someone:s3cr3t-token@example.org/org/documentation.git");

        assertThat(GitFetcher.sendsCredentialsInClearText(configuration)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "https://example.org/org/documentation.git",
            "https://someone:s3cr3t-token@example.org/org/documentation.git",
            "ssh://git@example.org/org/documentation.git",
            "git+ssh://git@example.org/org/documentation.git",
            "git@example.org:org/documentation.git",
            "deploy@example.org:org/documentation.git",
            "example.org:org/documentation.git",
            "git://example.org/org/documentation.git",
            "file:///srv/git/documentation.git",
            "/srv/git/documentation.git",
        }
    )
    void should_not_flag_a_repository_that_does_not_send_credentials_in_clear_text(String repository) {
        GitFetcherConfiguration configuration = configuration("publisher", "s3cr3t-token");
        configuration.setRepository(repository);

        assertThat(GitFetcher.sendsCredentialsInClearText(configuration)).isFalse();
    }

    @Test
    void should_not_flag_a_plain_http_repository_fetched_without_credentials() {
        GitFetcherConfiguration configuration = configuration(null, null);
        configuration.setRepository("http://example.org/org/documentation.git");

        assertThat(GitFetcher.sendsCredentialsInClearText(configuration)).isFalse();
    }

    @Test
    void should_send_the_password_as_typed_without_trimming_it() {
        CredentialsProvider provider = GitFetcher.credentialsProvider(configuration(" publisher ", " pass phrase "));

        assertThat(usernameOf(provider)).isEqualTo("publisher");
        assertThat(passwordOf(provider)).isEqualTo(" pass phrase ");
    }

    @Test
    void should_expose_the_password_as_sensitive_so_that_apim_masks_it() throws Exception {
        assertThat(GitFetcherConfiguration.class.getDeclaredField("password").isAnnotationPresent(Sensitive.class)).isTrue();
        assertThat(GitFetcherConfiguration.class.getDeclaredField("username").isAnnotationPresent(Sensitive.class)).isFalse();
    }

    private static String usernameOf(CredentialsProvider provider) {
        CredentialItem.Username username = new CredentialItem.Username();
        assertThat(provider.get(REPOSITORY, username)).isTrue();
        return username.getValue();
    }

    private static String passwordOf(CredentialsProvider provider) {
        CredentialItem.Password password = new CredentialItem.Password();
        assertThat(provider.get(REPOSITORY, password)).isTrue();
        return new String(password.getValue());
    }

    private static GitFetcherConfiguration configuration(String username, String password) {
        GitFetcherConfiguration configuration = new GitFetcherConfiguration();
        configuration.setRepository(REPOSITORY.toString());
        configuration.setBranchOrTag("master");
        configuration.setPath("docs/page.md");
        configuration.setUsername(username);
        configuration.setPassword(password);
        return configuration;
    }

    private static URIish uri(String value) {
        try {
            return new URIish(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
