package me.eliassanchezfernandez.puntodeventa.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        // Esto le dice a Spring: "Cuando alguien pida un PasswordEncoder, 
        // dale esta implementación de BCrypt".
        return new BCryptPasswordEncoder();
    }
}