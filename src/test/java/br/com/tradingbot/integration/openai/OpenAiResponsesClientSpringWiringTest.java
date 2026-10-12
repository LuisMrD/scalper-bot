package br.com.tradingbot.integration.openai;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/** Regression: Spring must autowire the production constructor, not seek a no-arg constructor. */
class OpenAiResponsesClientSpringWiringTest {

    @Test
    void springInstantiatesClientUsingJsonMapperDependency() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(JsonMapper.class, JsonMapper::new);
            context.registerBean(OpenAiResponsesClient.class);
            context.refresh();
            assertNotNull(context.getBean(OpenAiResponsesClient.class));
        }
    }
}
