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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CleanupInputStreamTest {

    /**
     * Verifies tmpDirectory is deleted when close() completes normally.
     */
    @Test
    void shouldDeleteTmpDirectoryOnClose(@TempDir File base) throws Exception {
        File managed = new File(base, "managed");
        managed.mkdir();
        File testFile = new File(managed, "file.txt");
        testFile.createNewFile();

        var stream = new GitFetcher.CleanupInputStream(new FileInputStream(testFile), managed);
        assertThat(managed).exists();
        stream.close();
        assertThat(managed).doesNotExist();
    }

    /**
     * Verifies that tmpDirectory is deleted even when super.close() throws.
     * This is the reason the close() body uses try { super.close() } finally { delete }.
     */
    @Test
    void shouldDeleteTmpDirectoryEvenWhenSuperCloseThrows(@TempDir File base) throws Exception {
        File managed = new File(base, "managed");
        managed.mkdir();
        File testFile = new File(managed, "file.txt");
        testFile.createNewFile();

        FileInputStream failingStream = new FileInputStream(testFile) {
            @Override
            public void close() throws IOException {
                throw new IOException("simulated close failure");
            }
        };

        var stream = new GitFetcher.CleanupInputStream(failingStream, managed);
        assertThatThrownBy(stream::close).isInstanceOf(IOException.class).hasMessage("simulated close failure");
        // deletion must have run in the finally block despite the thrown exception
        assertThat(managed).doesNotExist();
    }

    /**
     * Verifies that calling close() a second time does not throw and does not crash.
     * tmpDirectory is nulled after first deletion; deleteQuietly guards on null.
     */
    @Test
    void shouldBeDoubleCloseSafe(@TempDir File base) throws Exception {
        File managed = new File(base, "managed");
        managed.mkdir();
        File testFile = new File(managed, "file.txt");
        testFile.createNewFile();

        var stream = new GitFetcher.CleanupInputStream(new FileInputStream(testFile), managed);
        stream.close();
        assertThat(managed).doesNotExist();
        assertThatCode(stream::close).doesNotThrowAnyException();
    }
}
