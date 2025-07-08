class EmbedStatusDashboard {
    constructor() {
        this.apiEndpoint = '/api/embed-status';
        this.refreshInterval = null;
        this.isLoading = false;
        
        this.initializeEventListeners();
        this.loadData();
        
        // Auto-refresh every 30 seconds
        this.startAutoRefresh();
    }
    
    initializeEventListeners() {
        const refreshBtn = document.getElementById('refreshBtn');
        const clearBtn = document.getElementById('clearBtn');
        
        refreshBtn.addEventListener('click', () => this.loadData());
        clearBtn.addEventListener('click', () => {
            if (confirm('Are you sure you want to clear the cache? This will reset all instance data and may take a moment to refresh.')) {
                this.clearCache();
            }
        });
    }
    
    async loadData() {
        if (this.isLoading) return;
        
        this.isLoading = true;
        this.setLoadingState(true);
        
        try {
            const response = await fetch(this.apiEndpoint);
            
            if (!response.ok) {
                throw new Error(`HTTP error! status: ${response.status}`);
            }
            
            const data = await response.json();
            this.updateDashboard(data);
            
        } catch (error) {
            console.error('Error loading embed status:', error);
            this.showError('Failed to load embed status data. Please try again.');
        } finally {
            this.isLoading = false;
            this.setLoadingState(false);
        }
    }
    
    async clearCache() {
        if (this.isLoading) return;
        
        this.isLoading = true;
        this.setLoadingState(true);
        
        try {
            const response = await fetch('/api/embed-status/debug/clear-cache', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                }
            });
            
            if (!response.ok) {
                throw new Error(`HTTP error! status: ${response.status}`);
            }
            
            const result = await response.json();
            console.log('Cache cleared successfully:', result);
            
            // Show success message
            this.showSuccessMessage('Cache cleared successfully! Refreshing data...');
            
            // Reload data after a short delay
            setTimeout(() => {
                this.loadData();
            }, 1000);
            
        } catch (error) {
            console.error('Error clearing cache:', error);
            this.showErrorMessage('Failed to clear cache. Please try again.');
        } finally {
            this.isLoading = false;
            this.setLoadingState(false);
        }
    }
    
    setLoadingState(loading) {
        const refreshBtn = document.getElementById('refreshBtn');
        const clearBtn = document.getElementById('clearBtn');
        const refreshIcon = refreshBtn.querySelector('.refresh-icon');
        const clearIcon = clearBtn.querySelector('.clear-icon');
        
        if (loading) {
            refreshBtn.classList.add('loading');
            clearBtn.classList.add('loading');
            refreshBtn.disabled = true;
            clearBtn.disabled = true;
        } else {
            refreshBtn.classList.remove('loading');
            clearBtn.classList.remove('loading');
            refreshBtn.disabled = false;
            clearBtn.disabled = false;
        }
    }
    
    updateDashboard(data) {
        this.updateSummaryCards(data.summary);
        this.updateInstancesGrid(data.instances);
        this.updateLastUpdated();
    }
    
    updateSummaryCards(summary) {
        // Update summary cards
        document.getElementById('totalInstances').textContent = summary.totalInstances || 0;
        document.getElementById('activeInstances').textContent = summary.activeInstances || 0;
        document.getElementById('avgProcessingRate').textContent = 
            summary.averageProcessingRate ? `${summary.averageProcessingRate.toFixed(2)}/s` : '0.00/s';
        document.getElementById('totalChunks').textContent = summary.totalProcessedChunks || 0;
        document.getElementById('totalErrors').textContent = summary.totalErrorCount || 0;
    }
    
    updateInstancesGrid(instances) {
        const instancesGrid = document.getElementById('instancesGrid');
        
        if (!instances || instances.length === 0) {
            instancesGrid.innerHTML = '<div class="loading">No instances found</div>';
            return;
        }
        
        instancesGrid.innerHTML = instances.map(instance => this.createInstanceCard(instance)).join('');
    }
    
    createInstanceCard(instance) {
        const progressPercentage = instance.progressPercentage || 0;
        const statusClass = this.getStatusClass(instance.status);
        const lastUpdated = this.formatTimestamp(instance.lastUpdated);
        
        return `
            <div class="instance-card">
                <div class="instance-header">
                    <div class="instance-id">${instance.instanceId}</div>
                    <div class="status-badge ${statusClass}">${instance.status || 'unknown'}</div>
                </div>
                
                <div class="instance-metrics">
                    <div class="metric-item">
                        <div class="metric-label">Processed</div>
                        <div class="metric-value">${instance.processedChunks || 0}</div>
                    </div>
                    <div class="metric-item">
                        <div class="metric-label">Total</div>
                        <div class="metric-value">${instance.totalChunks || 0}</div>
                    </div>
                    <div class="metric-item">
                        <div class="metric-label">Rate</div>
                        <div class="metric-value">${instance.processingRate ? instance.processingRate.toFixed(2) : '0.00'}/s</div>
                    </div>
                    <div class="metric-item">
                        <div class="metric-label">Errors</div>
                        <div class="metric-value">${instance.errorCount || 0}</div>
                    </div>
                </div>
                
                <div class="progress-section">
                    <div class="progress-label">
                        <span>Progress</span>
                        <span>${progressPercentage.toFixed(1)}%</span>
                    </div>
                    <div class="progress-bar">
                        <div class="progress-fill" style="width: ${progressPercentage}%"></div>
                    </div>
                </div>
                
                <div class="instance-footer">
                    <div class="uptime">Uptime: ${instance.uptime || 'N/A'}</div>
                    <div class="last-updated-time">Updated: ${lastUpdated}</div>
                </div>
            </div>
        `;
    }
    
    getStatusClass(status) {
        if (!status) return 'offline';
        
        const statusLower = status.toLowerCase();
        if (statusLower.includes('active') || statusLower.includes('running')) return 'active';
        if (statusLower.includes('idle') || statusLower.includes('waiting')) return 'idle';
        if (statusLower.includes('error') || statusLower.includes('failed')) return 'error';
        return 'offline';
    }
    
    formatTimestamp(timestamp) {
        if (!timestamp) return 'N/A';
        
        try {
            const date = new Date(timestamp);
            const now = new Date();
            const diffMs = now - date;
            const diffSeconds = Math.floor(diffMs / 1000);
            const diffMinutes = Math.floor(diffSeconds / 60);
            const diffHours = Math.floor(diffMinutes / 60);
            
            if (diffSeconds < 60) {
                return `${diffSeconds}s ago`;
            } else if (diffMinutes < 60) {
                return `${diffMinutes}m ago`;
            } else if (diffHours < 24) {
                return `${diffHours}h ago`;
            } else {
                return date.toLocaleDateString() + ' ' + date.toLocaleTimeString();
            }
        } catch (error) {
            return 'N/A';
        }
    }
    
    updateLastUpdated() {
        const now = new Date();
        const timeString = now.toLocaleTimeString();
        document.getElementById('lastUpdated').textContent = timeString;
    }
    
    showError(message) {
        const instancesGrid = document.getElementById('instancesGrid');
        instancesGrid.innerHTML = `<div class="error-message">${message}</div>`;
    }
    
    showSuccessMessage(message) {
        this.showNotification(message, 'success');
    }
    
    showErrorMessage(message) {
        this.showNotification(message, 'error');
    }
    
    showNotification(message, type) {
        // Remove existing notification
        const existingNotification = document.querySelector('.notification');
        if (existingNotification) {
            existingNotification.remove();
        }
        
        // Create notification element
        const notification = document.createElement('div');
        notification.className = `notification ${type}`;
        notification.textContent = message;
        
        // Add to page
        document.body.appendChild(notification);
        
        // Remove after 3 seconds
        setTimeout(() => {
            if (notification.parentNode) {
                notification.remove();
            }
        }, 3000);
    }
    
    startAutoRefresh() {
        this.refreshInterval = setInterval(() => {
            this.loadData();
        }, 30000); // Refresh every 30 seconds
    }
    
    stopAutoRefresh() {
        if (this.refreshInterval) {
            clearInterval(this.refreshInterval);
            this.refreshInterval = null;
        }
    }
}

// Initialize the dashboard when the page loads
document.addEventListener('DOMContentLoaded', () => {
    new EmbedStatusDashboard();
});

// Handle page visibility changes to pause/resume auto-refresh
document.addEventListener('visibilitychange', () => {
    if (document.hidden) {
        // Page is hidden, could stop auto-refresh here if needed
    } else {
        // Page is visible again, could resume auto-refresh here if needed
    }
}); 