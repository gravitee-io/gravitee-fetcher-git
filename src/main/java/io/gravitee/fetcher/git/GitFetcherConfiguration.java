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

import io.gravitee.fetcher.api.FetcherConfiguration;

/**
 * @author Nicolas GERAUD (nicolas <AT> graviteesource.com)
 * @author GraviteeSource Team
 */
public class GitFetcherConfiguration implements FetcherConfiguration {

    private String repository;

    private String branchOrTag;

    private String path;

    private String fetchCron;

    private boolean autoFetch = false;

    @Override
    public String getFetchCron() {
        return fetchCron;
    }

    public void setFetchCron(String fetchCron) {
        this.fetchCron = fetchCron;
    }

    @Override
    public boolean isAutoFetch() {
        return autoFetch;
    }

    public void setAutoFetch(boolean autoFetch) {
        this.autoFetch = autoFetch;
    }

    public String getRepository() {
        return repository;
    }

    public void setRepository(String repository) {
        this.repository = repository;
    }

    public String getBranchOrTag() {
        return branchOrTag;
    }

    public void setBranchOrTag(String branchOrTag) {
        this.branchOrTag = branchOrTag;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }
}
