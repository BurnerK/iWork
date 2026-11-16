package io.iwork;

/** Local acceptance fixture; production artifacts never include this transport. */
public class RevisionFixtureApplication {
    public static void main(String[] args) {
        new org.springframework.boot.SpringApplication(IWorkApplication.class,DocumentRevisionTest.ProviderConfiguration.class).run(args);
    }
}
