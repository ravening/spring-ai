package com.example.deeprelay;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.ai.openai.api-key=test-key",
        "spring.datasource.url=jdbc:h2:mem:deeprelay-test;DB_CLOSE_DELAY=-1",
        "rag.vector-store-file=target/test-vector-store.json",
        "rag.ingest-classpath-docs=false"
})
class DeepRelayApplicationTests {

    @Test
    void contextLoads() {
    }
}
