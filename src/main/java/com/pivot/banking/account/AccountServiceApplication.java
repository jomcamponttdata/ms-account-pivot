package com.pivot.banking.account;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Entry point for the account-service Spring Boot application. */
@SpringBootApplication
public class AccountServiceApplication {

    /** Starts the account-service application. */
    public static void main(String[] args) {
        SpringApplication.run(AccountServiceApplication.class, args);
    }
}
