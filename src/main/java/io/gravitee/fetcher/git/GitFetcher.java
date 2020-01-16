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
import org.eclipse.jgit.api.Git;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;

/**
 * @author Nicolas GERAUD (nicolas <AT> graviteesource.com)
 * @author GraviteeSource Team
 */
public class GitFetcher implements Fetcher{

    private final Logger LOGGER = LoggerFactory.getLogger(GitFetcher.class);
    private GitFetcherConfiguration gitFetcherConfiguration;

    public GitFetcher(GitFetcherConfiguration gitFetcherConfiguration) {
        this.gitFetcherConfiguration = gitFetcherConfiguration;
    }

    @Override
    public Resource fetch() throws FetcherException {
        File localPath = null;
        try {
            localPath = File.createTempFile("Gravitee-io", "");
            localPath.delete();
        } catch (IOException e) {
            LOGGER.error(e.getMessage(), e);
            throw new FetcherException("Unable to create temporary directory to fetch git repository", e);
        }

        try (Git result = Git.cloneRepository()
                .setURI(this.gitFetcherConfiguration.getRepository())
                .setDirectory(localPath)
                .setBranch(this.gitFetcherConfiguration.getBranchOrTag())
                .call()) {
            LOGGER.debug("Having repository: {}", result.getRepository().getDirectory());
            File fileToFetch = new File(result.getRepository().getWorkTree().getAbsolutePath() + File.separatorChar + gitFetcherConfiguration.getPath());
            result.close();
            final Resource resource = new Resource();
            resource.setContent(new FileInputStream(fileToFetch));
            return resource;
        } catch (Exception e) {
            LOGGER.error(e.getMessage(), e);
            throw new FetcherException("Unable to fetch git content (" + e.getMessage() + ")", e);
        }
    }

    @Override
    public FetcherConfiguration getConfiguration() {
        return gitFetcherConfiguration;
    }
}
