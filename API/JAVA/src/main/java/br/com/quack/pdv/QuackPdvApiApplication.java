package br.com.quack.pdv;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication(exclude = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class QuackPdvApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(QuackPdvApiApplication.class, args);
    }
}
