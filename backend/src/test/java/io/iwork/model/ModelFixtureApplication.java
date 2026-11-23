package io.iwork.model;

import io.iwork.IWorkApplication;
import org.springframework.boot.SpringApplication;

/** Actual Harness with a synthetic provider transport; excluded from the production jar. */
public class ModelFixtureApplication {
    public static void main(String[] args) {
        new SpringApplication(
                        IWorkApplication.class, ModelExecutionTest.ProviderConfiguration.class)
                .run(args);
    }
}
