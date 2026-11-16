package io.iwork.conversation;

/** Local fault fixture; HTTP delivery controls and synthetic providers are excluded from the production jar. */
public class CancellationFixtureApplication {
    public static void main(String[] args) {
        new org.springframework.boot.SpringApplication(io.iwork.IWorkApplication.class,CancellationClosureTest.ProviderConfiguration.class).run(args);
    }
}
