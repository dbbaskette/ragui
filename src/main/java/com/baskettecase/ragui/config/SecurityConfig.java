package com.baskettecase.ragui.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/css/**", "/js/**", "/images/**", "/webjars/**").permitAll()
                .requestMatchers("/api/**").permitAll()  // Allow all API endpoints
                .requestMatchers("/", "/index.html").permitAll()  // Allow main page
                .anyRequest().authenticated()
            )
            .formLogin(form -> form.permitAll())
            .csrf(csrf -> csrf.disable())  // Disable CSRF for API calls
            .logout(logout -> logout.permitAll());
        return http.build();
    }

    @Bean
    public UserDetailsService userDetailsService() {
        UserDetails user = User.withDefaultPasswordEncoder()
            .username("tanzu")
            .password("t@nzu123")
            .roles("USER")
            .build();
        return new InMemoryUserDetailsManager(user);
    }
}
