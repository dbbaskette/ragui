package com.baskettecase.ragui.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Value("${app.security.default-user.username}")
    private String defaultUsername;

    @Value("${app.security.default-user.password}")
    private String defaultPassword;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/css/**", "/js/**", "/images/**", "/webjars/**").permitAll()
                        .requestMatchers("/*.css", "/*.js", "/*.png", "/*.jpg", "/*.ico").permitAll()
                        .requestMatchers("/api/**").permitAll() // Keep API endpoints accessible
                        .requestMatchers("/actuator/**").permitAll() // Keep actuator accessible
                        .requestMatchers("/embed-status").permitAll() // Allow embed status dashboard
                        .anyRequest().authenticated()) // Require authentication for other pages
                .formLogin(form -> form
                    .loginPage("/login")
                    .defaultSuccessUrl("/", true) // Always redirect to root after login
                    .permitAll()) // Enable form login
                .csrf(csrf -> csrf.disable()) // Keep CSRF disabled for API calls
                .logout(logout -> logout
                    .logoutSuccessUrl("/")
                    .permitAll());
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService() {
        UserDetails user = User.builder()
                .username(defaultUsername)
                .password(passwordEncoder().encode(defaultPassword))
                .roles("USER")
                .build();
        return new InMemoryUserDetailsManager(user);
    }
}
