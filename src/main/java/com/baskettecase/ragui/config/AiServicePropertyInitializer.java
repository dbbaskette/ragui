package com.baskettecase.ragui.config;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;

public class AiServicePropertyInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        ConfigurableEnvironment env = applicationContext.getEnvironment();
        // Use the CloudFoundryAiConfig beans to extract properties
        // But at this point, beans are not available, so we must parse VCAP_SERVICES directly
        String vcapServices = System.getenv("VCAP_SERVICES");
        Map<String, Object> map = CloudFoundryAiConfig.extractAiServicePropertiesFromVcap(vcapServices);
        env.getPropertySources().addFirst(new MapPropertySource("aiServiceProperties", map));
    }
}
