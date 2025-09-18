package com.uploadservers3;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class UploadServerS3Application {

    public static void main(String[] args) {
        SpringApplication.run(UploadServerS3Application.class, args);
    }

}
