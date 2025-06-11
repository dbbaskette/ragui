package com.baskettecase.ragui.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class CloudConfig {

    private static final Logger logger = LoggerFactory.getLogger(CloudConfig.class);
    private final ConfigurableEnvironment environment;
    private final Map<String, Object> importantProperties = new HashMap<>();

    public CloudConfig(ConfigurableEnvironment environment) {
        this.environment = environment;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logVcapProperties() {
        logger.info("===== VCAP Properties =====");
        
        // Get all property sources
        for (PropertySource<?> propertySource : environment.getPropertySources()) {
            if (propertySource.getName().contains("vcap")) {
                logger.info("Property Source: {}", propertySource.getName());
                if (propertySource instanceof MapPropertySource) {
                    MapPropertySource mapPropertySource = (MapPropertySource) propertySource;
                    for (String propertyName : mapPropertySource.getPropertyNames()) {
                        Object propertyValue = mapPropertySource.getProperty(propertyName);
                        logger.info("{} = {}", propertyName, propertyValue);
                        
                        // Capture the DB plan and any model names from VCAP services.
                        if (propertyName.equals("vcap.services.embed-db.plan") ||
                                (propertyName.startsWith("vcap.services.") && propertyName.endsWith(".credentials.model_name"))) {
                            String displayName = propertyName
                                    .replace("vcap.services.", "")
                                    .replace(".credentials", "");
                            importantProperties.put(displayName, propertyValue);
                        }
                    }
                }
                logger.info("\n");
            }
        }
        
        logger.info("===== Important Configuration =====");
        importantProperties.forEach((key, value) -> 
            logger.info("{} = {}", key, value));
    }
    
    public Map<String, Object> getImportantProperties() {
        return new HashMap<>(importantProperties);
    }
}
