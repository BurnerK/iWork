package io.iwork.sandbox;

import io.iwork.IWorkApplication;
import org.springframework.boot.SpringApplication;

/** Real Harness and manager; only the external model transport is synthetic. Not packaged. */
public class ParsingFixtureApplication {
    public static void main(String[] args) {
        new SpringApplication(
                        IWorkApplication.class, MaterialParsingTest.ProviderConfiguration.class)
                .run(args);
    }
}
