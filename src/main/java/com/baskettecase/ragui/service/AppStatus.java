package com.baskettecase.ragui.service;

import org.springframework.stereotype.Component;

@Component
public class AppStatus {
    private String status = "Idle";
    public synchronized String getStatus() { return status; }
    public synchronized void setStatus(String status) { this.status = status; }
}
