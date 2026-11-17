package com.proofix.backend.run.runner;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RunnerApiProperties.class)
public class RunnerApiConfig {
}
