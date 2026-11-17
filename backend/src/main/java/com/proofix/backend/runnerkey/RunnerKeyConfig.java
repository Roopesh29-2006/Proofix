package com.proofix.backend.runnerkey;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RunnerKeyProperties.class)
public class RunnerKeyConfig {
}
