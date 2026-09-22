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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The form displayed by the Console is generated from this schema, so the credential fields have to be declared there
 * and the secret has to be flagged as a password (PORTAL-197).
 *
 * @author GraviteeSource Team
 */
class GitFetcherSchemaTest {

    private static JsonNode schema;

    @BeforeAll
    static void read_schema() throws Exception {
        try (InputStream content = GitFetcherSchemaTest.class.getResourceAsStream("/schemas/schema-form.json")) {
            schema = new ObjectMapper().readTree(content);
        }
    }

    @Test
    void should_declare_the_credential_fields() {
        assertThat(property("username").get("type").asText()).isEqualTo("string");
        assertThat(property("password").get("type").asText()).isEqualTo("string");
    }

    @Test
    void should_declare_the_password_as_a_password_field_in_both_form_engines() {
        // The Next Gen forms read "format", the Console's legacy forms read "x-schema-form"
        assertThat(property("password").get("format").asText()).isEqualTo("password");
        assertThat(property("password").get("x-schema-form").get("type").asText()).isEqualTo("password");
        assertThat(property("password").get("writeOnly").asBoolean()).isTrue();
    }

    @Test
    void should_keep_the_credentials_optional_so_that_public_repositories_still_work() {
        assertThat(schema.get("required")).map(JsonNode::asText).doesNotContain("username", "password");
    }

    private static JsonNode property(String name) {
        return schema.get("properties").get(name);
    }
}
