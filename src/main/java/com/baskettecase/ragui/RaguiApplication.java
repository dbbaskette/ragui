package com.baskettecase.ragui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.core.env.Environment;

import java.io.PrintStream;
import java.util.Arrays;

@SpringBootApplication
public class RaguiApplication {
    private static final Logger logger = LoggerFactory.getLogger(RaguiApplication.class);
    
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(RaguiApplication.class);
        app.addInitializers(new com.baskettecase.ragui.config.AiServicePropertyInitializer());
        app = new SpringApplicationBuilder(app)
            .banner(new Banner() {
                @Override
                public void printBanner(Environment environment, Class<?> sourceClass, PrintStream out) {
                    // Custom banner with environment info
                    String[] activeProfiles = environment.getActiveProfiles();
                    String profiles = activeProfiles.length == 0 ? "default" : String.join(", ", activeProfiles);
                    
                    out.println("  _____    _    ____      ____ _           _   ");
                    out.println(" |  __ \\  / \\  |  _ \\    / ___| |__   __ _| |_ ");
                    out.println(" | |__) |/ _ \\ | |_) |  | |   | '_ \\ / _` | __|");
                    out.println(" |  _  // ___ \\|  _ <   | |___| | | | (_| | |_ ");
                    out.println(" |_| \\_/_/   \\_\\_| \\_\\   \\____|_| |_|\\__,_|\\__|");
                    out.println("");
                    out.println(":: RAG Chat ::");
                    out.println("Profiles: " + profiles);
                    out.println("");
                    
                    // Log important environment info
                    logEnvironmentInfo(environment);
                }
            })
            .build();
            
        app.run(args);
    }
    
    private static void logEnvironmentInfo(Environment env) {
        String[] activeProfiles = env.getActiveProfiles();
        logger.info("Active profiles: {}", activeProfiles.length == 0 ? "[default]" : Arrays.toString(activeProfiles));
        
        // Log important properties
        logIfPresent(env, "spring.datasource.url", "Database URL");
        logIfPresent(env, "spring.ai.ollama.base-url", "Ollama URL");
        logIfPresent(env, "spring.ai.ollama.chat.model", "Chat Model");
        logIfPresent(env, "spring.ai.ollama.embedding.model", "Embedding Model");
        logIfPresent(env, "vcap.application.name", "CF App Name");
        logIfPresent(env, "vcap.application.space_name", "CF Space");
        
        // Special handling for array properties
        try {
            String appUris = env.getProperty("vcap.application.uris[0]");
            if (appUris != null && !appUris.isEmpty()) {
                logger.info("CF App URL: https://{}", appUris);
            }
        } catch (Exception e) {
            logger.debug("Could not read CF App URL: {}", e.getMessage());
        }
    }
    
    private static void logIfPresent(Environment env, String key, String description) {
        try {
            String value = env.getProperty(key);
            if (value != null && !value.isEmpty()) {
                logger.info("{}: {}", description, maskSensitiveInfo(key, value));
            }
        } catch (Exception e) {
            logger.debug("Could not read property {}: {}", key, e.getMessage());
        }
    }
    
    private static String maskSensitiveInfo(String key, String value) {
        if (key.contains("password") || key.contains("secret") || key.contains("credential")) {
            return "********";
        }
        if (key.contains("url") && value != null) {
            try {
                java.net.URI uri = new java.net.URI(value);
                if (uri.getUserInfo() != null) {
                    return value.replace(uri.getUserInfo() + "@", "*****:*****@");
                }
            } catch (Exception e) {
                // If URI parsing fails, return the original value
            }
        }
        return value;
    }
}
