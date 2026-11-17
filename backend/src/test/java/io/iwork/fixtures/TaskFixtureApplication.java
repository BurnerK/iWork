package io.iwork.fixtures;

import io.iwork.IWorkApplication;
import io.iwork.conversation.TaskRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Browser/restart fixture, never included in the production jar. */
public class TaskFixtureApplication {
    public static void main(String[] args) {
        new SpringApplication(IWorkApplication.class, Execution.class).run(args);
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Execution {
        @Bean
        @org.springframework.context.annotation.Primary
        TaskRunner runner() {
            return input -> {
                Thread.sleep(input.message().equals("hold") ? 30000 : 2000);
                if (input.message().equals("fail"))
                    throw new IllegalStateException("Fixture failure");
                return "测试替身完成：" + input.message();
            };
        }
    }
}
