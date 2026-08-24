package com.docvalidate.service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class DocValidateApplication {

    public static void main(String[] args) {
        SpringApplication.run(DocValidateApplication.class, args);
    }
}
