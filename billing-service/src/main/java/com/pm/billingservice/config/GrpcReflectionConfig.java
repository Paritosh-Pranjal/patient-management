package com.pm.billingservice.config;

import io.grpc.BindableService;
import io.grpc.protobuf.services.ProtoReflectionService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GrpcReflectionConfig {

    // Spring Boot only answers the NEW reflection version (v1).
    // IntelliJ asks with the OLD version (v1alpha), so we register that too.
    // Only needed for testing tools like IntelliJ / Postman.
    @Bean
    @SuppressWarnings("deprecation")
    BindableService reflectionV1Alpha() {
        return ProtoReflectionService.newInstance();
    }
}