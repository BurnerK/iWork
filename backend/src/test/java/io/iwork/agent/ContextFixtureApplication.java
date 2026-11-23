package io.iwork.agent;

import io.iwork.IWorkApplication;

/** Actual Harness and persistent business services; only the provider transport is synthetic. */
public class ContextFixtureApplication {
    public static void main(String[] args) {
        new org.springframework.boot.SpringApplication(
                        IWorkApplication.class, ConversationContextTest.ProviderConfiguration.class)
                .run(args);
    }
}
