package com.phone;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@MapperScan({"com.phone.module.mapper", "com.phone.system.mapper"})
@SpringBootApplication
public class PhoneApplication {
    public static void main(String[] args) {
        SpringApplication.run(PhoneApplication.class, args);
    }
}
