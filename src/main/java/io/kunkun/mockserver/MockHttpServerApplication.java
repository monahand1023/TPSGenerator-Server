package io.kunkun.mockserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@EnableConfigurationProperties
@SpringBootApplication
public class MockHttpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(MockHttpServerApplication.class, args);
    }
}
