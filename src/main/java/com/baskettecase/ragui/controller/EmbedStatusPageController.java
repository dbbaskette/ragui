package com.baskettecase.ragui.controller;

import com.baskettecase.ragui.dto.EmbedStatusResponse;
import com.baskettecase.ragui.service.EmbedProcMonitoringService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Controller to serve the embed status dashboard page.
 * This handles the web UI route for the embed status dashboard.
 */
@Controller
public class EmbedStatusPageController {
    
    private static final Logger logger = LoggerFactory.getLogger(EmbedStatusPageController.class);
    
    @Autowired
    private EmbedProcMonitoringService monitoringService;
    
    /**
     * Serves the embed status dashboard page with initial data.
     * @param model the model to add attributes to
     * @return the embed-status view
     */
    @GetMapping("/embed-status")
    public String embedStatusPage(Model model) {
        logger.debug("GET /embed-status - Serving embed status dashboard page");
        
        try {
            // Get initial data to populate the page
            EmbedStatusResponse status = monitoringService.getCurrentStatus();
            model.addAttribute("initialData", status);
            model.addAttribute("hasData", true);
            
        } catch (Exception e) {
            logger.error("Error loading initial embed status data", e);
            model.addAttribute("hasData", false);
            model.addAttribute("error", e.getMessage());
        }
        
        return "embed-status";
    }
} 