/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
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
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.eclipse.jgit.api.Git;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.support.CronExpression;

/**
 * @author Nicolas GERAUD (nicolas <AT> graviteesource.com)
 * @author GraviteeSource Team
 */
public class GitFetcher implements Fetcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(GitFetcher.class);
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

        File tmpDirectory;
        try {
            tmpDirectory = File.createTempFile("Gravitee-io", "");
            tmpDirectory.delete();
        } catch (IOException e) {
            throw new FetcherException("Unable to create temporary directory to fetch git repository", e);
        }

        final Resource resource = new Resource();
        Path repositoryPath;
        try (
            Git result = Git
                .cloneRepository()
                .setURI(this.gitFetcherConfiguration.getRepository())
                .setDirectory(tmpDirectory)
                .setBranch(this.gitFetcherConfiguration.getBranchOrTag())
                .setDepth(1)
                .call()
        ) {
            LOGGER.debug("Having repository: {}", result.getRepository().getDirectory());
            repositoryPath = result.getRepository().getWorkTree().toPath();
        } catch (Exception e) {
            throw new FetcherException("Unable to fetch git content (" + e.getMessage() + ")", e);
        }

        try (Stream<Path> stream = Files.walk(repositoryPath)) {
            File fileToFetch = stream
                .filter(path -> path.endsWith(gitFetcherConfiguration.getPath()))
                .findAny()
                .map(Path::toFile)
                .orElseThrow(() -> new FetcherException("Unable to find file to fetch", null));

            if (Files.isSymbolicLink(fileToFetch.toPath())) {
                checkSymbolicLinkTargetIsInsideDirectory(fileToFetch, tmpDirectory);
            }

            resource.setContent(new FileInputStream(fileToFetch));
        } catch (IOException e) {
            throw new FetcherException("Unable to walk through the repository files", e);
        }

        return resource;
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
