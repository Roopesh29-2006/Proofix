package com.proofix.backend.run.api;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RunProperties.class)
public class RunConfig {
}
