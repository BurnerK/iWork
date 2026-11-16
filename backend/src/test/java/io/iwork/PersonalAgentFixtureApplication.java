package io.iwork;

/** Deterministic local acceptance only; excluded from the production artifact. */
public class PersonalAgentFixtureApplication {
    public static void main(String[] args) {
        new org.springframework.boot.SpringApplication(IWorkApplication.class,PersonalAgentTest.ProviderConfiguration.class).run(args);
    }
}
