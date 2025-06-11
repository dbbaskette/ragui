package com.baskettecase.ragui.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class VersionController {
    @GetMapping("/version")
    public Map<String, String> getVersion() {
        Map<String, String> version = new HashMap<>();
        try {
            // Try to read version from pom.properties (packaged in JAR by Maven)
            String v = null;
            try {
                java.util.Properties props = new java.util.Properties();
                java.io.InputStream in = getClass().getClassLoader().getResourceAsStream("META-INF/maven/com.baskettecase/ragui/pom.properties");
                if (in != null) {
                    props.load(in);
                    v = props.getProperty("version");
                }
            } catch (Exception e) { /* ignore */ }
            if (v == null) {
                // Fallback: try to read from pom.xml (dev mode)
                java.nio.file.Path pom = java.nio.file.Paths.get("pom.xml");
                if (java.nio.file.Files.exists(pom)) {
                    String xml = java.nio.file.Files.readString(pom);
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("<version>([^<]+)</version>").matcher(xml);
                    if (m.find()) v = m.group(1);
                }
            }
            version.put("version", v != null ? v : "unknown");
        } catch (Exception e) {
            version.put("version", "unknown");
        }
        return version;
    }
}
