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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.gravitee.fetcher.api.FetcherException;
import io.gravitee.fetcher.api.ResourceNotFoundException;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitFetcherPathNormalizationTest {

    @TempDir
    static Path repositoryDirectory;

    static String repositoryUri;
    static String branch;

    @BeforeAll
    static void create_local_repository() throws Exception {
        try (Git git = Git.init().setDirectory(repositoryDirectory.toFile()).call()) {
            Files.writeString(repositoryDirectory.resolve("README.md"), "root readme");
            Files.createDirectories(repositoryDirectory.resolve("docs"));
            Files.writeString(repositoryDirectory.resolve("docs").resolve("page.md"), "docs page");

            git.add().addFilepattern(".").call();
            PersonIdent committer = new PersonIdent("Gravitee Test", "test@gravitee.io");
            git.commit().setMessage("init").setAuthor(committer).setCommitter(committer).setSign(false).call();

            branch = git.getRepository().getBranch();
            repositoryUri = repositoryDirectory.toUri().toString();
        }
    }

    @Test
    void should_fetch_file_when_path_has_leading_slash() throws Exception {
        GitFetcher fetcher = new GitFetcher(configuration("/README.md"));

        assertThat(readAll(fetcher.fetch().getContent())).isEqualTo("root readme");
    }

    @Test
    void should_throw_explicit_error_when_file_does_not_exist() {
        GitFetcher fetcher = new GitFetcher(configuration("docs/unknown.md"));

        assertThatThrownBy(fetcher::fetch)
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessage("Unable to find file 'docs/unknown.md' in repository '" + repositoryUri + "' (ref: " + branch + ")");
    }

    @Test
    void should_strip_credentials_embedded_in_the_repository_url() {
        assertThat(GitFetcher.sanitizeRepository("https://user:s3cr3t-token@example.org/org/repo.git")).isEqualTo(
            "https://example.org/org/repo.git"
        );
    }

    @Test
    void should_leave_repository_url_untouched_when_it_carries_no_credentials() {
        assertThat(GitFetcher.sanitizeRepository("https://example.org/org/repo.git")).isEqualTo("https://example.org/org/repo.git");
        assertThat(GitFetcher.sanitizeRepository("git@example.org:org/repo.git")).isEqualTo("git@example.org:org/repo.git");
    }

    @Test
    void should_throw_explicit_error_when_path_is_only_a_slash() {
        GitFetcher fetcher = new GitFetcher(configuration("/"));

        assertThatThrownBy(fetcher::fetch)
            .isInstanceOf(FetcherException.class)
            .hasMessage("Unable to fetch git content: the path to the file to fetch is missing");
    }

    private static GitFetcherConfiguration configuration(String path) {
        GitFetcherConfiguration configuration = new GitFetcherConfiguration();
        configuration.setRepository(repositoryUri);
        configuration.setBranchOrTag(branch);
        configuration.setPath(path);
        return configuration;
    }

    private static String readAll(InputStream inputStream) throws Exception {
        try (InputStream is = inputStream) {
            return new String(is.readAllBytes());
        }
    }
}
