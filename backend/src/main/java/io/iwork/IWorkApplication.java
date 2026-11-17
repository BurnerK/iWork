package io.iwork;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class IWorkApplication {
    public static void main(String[] args) {
        if (java.util.Arrays.asList(args).contains("--recover-admin")) {
            if (args.length != 1) {
                System.err.println("Recovery accepts no password arguments.");
                System.exit(1);
            }
            System.exit(io.iwork.identity.AdministratorRecovery.run());
            return;
        }
        SpringApplication.run(IWorkApplication.class, args);
    }
}
