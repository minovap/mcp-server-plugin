// WebSocketManager class to handle multiple WebSocket connections
class WebSocketManager {
    constructor() {
        this.sockets = {};
        this.basePorts = [63342, 63343, 63344, 63345, 63346];
        this.activeConnections = 0;
        this.maxConnections = this.basePorts.length;
        this.baseReconnectDelay = 1000;
        this.portCheckInterval = 10000;
        this.running = false;
        this.timers = {};
        this.origTitle = document.title;
        this.indicator = null;

        // Create the visual indicator
        this.createVisualIndicator();

        // Auto-allow permissions timer
        this.timers['autoAllow'] = setInterval(() => {
            const btn = [...document.querySelectorAll('button')].find(btn => btn.textContent.trim() === "Allow for This Chat");
            if (btn) btn.click();
        }, 250);

        // Update window title and visual indicator to show connection count
        this.timers['updateStatus'] = setInterval(() => {
            this.updateTitle();
            this.updateVisualIndicator();
        }, 1000);
    }

    // Create a visual indicator for WebSocket connections
    createVisualIndicator() {
        // Remove any existing indicator
        const existingIndicator = document.getElementById('ws-visual-indicator');
        if (existingIndicator) {
            existingIndicator.remove();
        }

        // Create new indicator element
        this.indicator = document.createElement('div');
        this.indicator.id = 'ws-visual-indicator';
        this.indicator.style.cssText = `
            position: absolute;
            top: 18px;
            left: 100px;
            background-color: #cc0000;
            color: white;
            font-size: 12px;
            font-weight: bold;
            padding: 2px 8px;
            border-radius: 10px;
            z-index: 9999;
            box-shadow: 0 2px 4px rgba(0,0,0,0.3);
            opacity: 0.9;
            user-select: none;
            cursor: pointer;
            transition: background-color 0.3s ease;
        `;
        this.indicator.textContent = '0';
        this.indicator.title = 'WebSocket Connections';

        // Add to document body
        document.body.appendChild(this.indicator);

        // Add click handler to toggle a more detailed popup
        this.indicator.addEventListener('click', () => {
            this.showConnectionDetails();
        });

        // Update status immediately
        this.updateVisualIndicator();
    }

    // Update the visual indicator
    updateVisualIndicator() {
        if (!this.indicator || !this.running) return;

        // Update color based on connection status
        if (this.activeConnections > 0) {
            this.indicator.style.backgroundColor = '#00cc00'; // Green for active connections
        } else {
            this.indicator.style.backgroundColor = '#cc0000'; // Red for no connections
        }

        // Update text
        this.indicator.textContent = this.activeConnections.toString();

        // Update tooltip with IDE information
        let tooltip = 'WebSocket Connections';

        // Add connected IDE info to tooltip
        const connectedPorts = Object.values(this.sockets)
            .filter(info => info.connected && info.ideName)
            .map(info => `${info.port}: ✅ ${info.ideName}`)
            .join(', ');

        if (connectedPorts) {
            tooltip += ` - ${connectedPorts}`;
        }

        this.indicator.title = tooltip;
    }

    // Show connection details popup
    showConnectionDetails() {
        let popup = document.getElementById('ws-details-popup');
        if (popup) {
            popup.remove();
            return;
        }

        popup = document.createElement('div');
        popup.id = 'ws-details-popup';
        popup.style.cssText = `
            position: absolute;
            top: 30px;
            left: 100px;
            background-color: white;
            color: black;
            font-size: 12px;
            padding: 8px;
            border-radius: 4px;
            z-index: 9998;
            box-shadow: 0 2px 8px rgba(0,0,0,0.3);
            min-width: 250px;
        `;

        // Build connection details content
        const content = document.createElement('div');
        content.innerHTML = `
            <div style="font-weight: bold; margin-bottom: 5px;">WebSocket Connections</div>
            <div>Active: ${this.activeConnections}/${this.maxConnections}</div>
            <div style="margin-top: 5px;">Ports:</div>
            <ul style="margin-top: 2px; padding-left: 20px;">
                ${Object.entries(this.sockets).map(([key, info]) => `
                    <li>
                        <div>
                            <strong>Port ${info.port}:</strong> ${info.connected ? `✅ ${info.ideName || ''}` : '❌'}
                        </div>
                    </li>
                `).join('')}
            </ul>
            <div style="margin-top: 10px; font-size: 11px; color: #666;">
                <button id="ws-reconnect-all" style="font-size: 11px; padding: 2px 5px; margin-right: 5px;">Reconnect All</button>
                <button id="ws-refresh-info" style="font-size: 11px; padding: 2px 5px;">Refresh Info</button>
            </div>
        `;

        popup.appendChild(content);
        document.body.appendChild(popup);

        // Set up reconnect button
        const reconnectBtn = document.getElementById('ws-reconnect-all');
        if (reconnectBtn) {
            reconnectBtn.addEventListener('click', () => {
                this.reconnectAll();
                setTimeout(() => {
                    this.showConnectionDetails(); // Refresh the popup
                }, 500);
            });
        }

        // Set up refresh button
        const refreshBtn = document.getElementById('ws-refresh-info');
        if (refreshBtn) {
            refreshBtn.addEventListener('click', () => {
                this.refreshServerInfo();
                setTimeout(() => {
                    this.showConnectionDetails(); // Refresh the popup
                }, 500);
            });
        }

        // Close popup when clicking outside
        setTimeout(() => {
            const clickHandler = (e) => {
                if (!popup.contains(e.target) && e.target !== this.indicator) {
                    popup.remove();
                    document.removeEventListener('click', clickHandler);
                }
            };
            document.addEventListener('click', clickHandler);
        }, 10);
    }

    // Refresh server info for all connections
    refreshServerInfo() {
        for (const [key, wsInfo] of Object.entries(this.sockets)) {
            // For connected sockets, send a request for IDE info
            if (wsInfo.connected && wsInfo.socket && wsInfo.socket.readyState === WebSocket.OPEN) {
                try {
                    wsInfo.socket.send(JSON.stringify({type: 'get-ide-info'}));
                } catch (e) {
                    // Silently handle errors
                }
            }
        }

        // Update the visual indicator
        this.updateVisualIndicator();
    }

    // Update the popup if it's currently open
    updateOpenPopup() {
        const popup = document.getElementById('ws-details-popup');
        if (popup) {
            // Re-generate the popup content with updated information
            const content = document.createElement('div');
            content.innerHTML = `
                <div style="font-weight: bold; margin-bottom: 5px;">WebSocket Connections</div>
                <div>Active: ${this.activeConnections}/${this.maxConnections}</div>
                <div style="margin-top: 5px;">Ports:</div>
                <ul style="margin-top: 2px; padding-left: 20px;">
                    ${Object.entries(this.sockets).map(([key, info]) => `
                        <li>
                            <div>
                                <strong>Port ${info.port}:</strong> ${info.connected ? `✅ ${info.ideName || ''}` : '❌'}
                            </div>
                        </li>
                    `).join('')}
                </ul>
                <div style="margin-top: 10px; font-size: 11px; color: #666;">
                    <button id="ws-reconnect-all" style="font-size: 11px; padding: 2px 5px; margin-right: 5px;">Reconnect All</button>
                    <button id="ws-refresh-info" style="font-size: 11px; padding: 2px 5px;">Refresh Info</button>
                </div>
            `;

            // Clear and replace the popup content
            popup.innerHTML = '';
            popup.appendChild(content);

            // Re-attach event listeners to buttons
            const reconnectBtn = document.getElementById('ws-reconnect-all');
            if (reconnectBtn) {
                reconnectBtn.addEventListener('click', () => {
                    this.reconnectAll();
                    setTimeout(() => {
                        this.updateOpenPopup(); // Refresh the popup
                    }, 500);
                });
            }

            const refreshBtn = document.getElementById('ws-refresh-info');
            if (refreshBtn) {
                refreshBtn.addEventListener('click', () => {
                    this.refreshServerInfo();
                    setTimeout(() => {
                        this.updateOpenPopup(); // Refresh the popup
                    }, 500);
                });
            }
        }
    }

    // Reconnect all WebSockets
    reconnectAll() {
        // Close all WebSocket connections
        for (const [key, wsInfo] of Object.entries(this.sockets)) {
            if (wsInfo.socket) {
                try {
                    wsInfo.socket.close(1000);
                } catch (e) {
                    // Silently handle close errors
                }
                wsInfo.socket = null;
                wsInfo.connected = false;
            }

            // Reset reconnect attempt
            wsInfo.reconnectAttempt = 0;

            // Trigger reconnection
            this.checkPortAvailability(wsInfo.port, key);
        }

        // Reset active connections counter
        this.activeConnections = 0;
        this.updateVisualIndicator();
        this.updateTitle();
    }

    // Update the title to show connection count
    updateTitle() {
        if (!this.running) {
            this.setTitle(this.origTitle);
            return;
        }

        if (this.activeConnections > 0) {
            this.setTitle(`(${this.activeConnections}) ${this.origTitle}`);
        } else {
            this.setTitle(this.origTitle);
        }
    }

    // Helper method to set title in various environments
    setTitle(title) {
        // Try document.title (standard browser approach)
        document.title = title;

        // Try Electron-specific approaches
        try {
            // Method 1: Directly access electron if available
            if (typeof require === 'function') {
                try {
                    const electron = require('electron');
                    const win = electron.remote?.getCurrentWindow();
                    if (win) {
                        win.setTitle(title);
                        return;
                    }
                } catch (e) {
                    // Couldn't load electron, continue to other methods
                }
            }

            // Method 2: Try to access through window properties Electron might set
            if (window.electronAPI?.setTitle) {
                window.electronAPI.setTitle(title);
                return;
            }

            // Method 3: Try using window.electron that some Electron apps expose
            if (window.electron?.setTitle) {
                window.electron.setTitle(title);
                return;
            }

            // Method 4: Try ipcRenderer if available
            if (window.ipcRenderer) {
                window.ipcRenderer.send('set-title', title);
                return;
            }
        } catch (e) {
            // If all electron-specific methods fail, we've already set document.title above
        }
    }

    // Initialize WebSocket connections
    initializeConnections() {
        if (this.running) {
            return;
        }

        this.running = true;

        // Safely store the original title
        if (!this.origTitle) {
            this.origTitle = document.title || 'Claude';

            // Try to get title from electron if available
            try {
                if (typeof require === 'function') {
                    const electron = require('electron');
                    const win = electron.remote?.getCurrentWindow();
                    if (win) {
                        this.origTitle = win.getTitle() || this.origTitle;
                    }
                }
            } catch (e) {
                // Ignore any errors when trying to access electron APIs
            }
        }

        // Start by checking port availability for all ports
        this.basePorts.forEach(port => {
            this.startPortMonitoring(port);
        });

        // Update the title
        this.updateTitle();
    }

    // Stop the WebSocket manager
    stop() {
        // Clear all timers
        Object.values(this.timers).forEach(timer => {
            if (timer) clearTimeout(timer);
            if (timer) clearInterval(timer);
        });
        this.timers = {};

        // Close all WebSocket connections
        for (const [key, wsInfo] of Object.entries(this.sockets)) {
            if (wsInfo.socket) {
                try {
                    wsInfo.socket.close(1000, 'WebSocket manager stopped');
                } catch (e) {
                    // Silently handle close errors
                }
                wsInfo.socket = null;
            }
        }

        // Reset state
        this.sockets = {};
        this.activeConnections = 0;
        this.running = false;

        // Reset the document title
        this.setTitle(this.origTitle);

        // Remove the visual indicator
        if (this.indicator) {
            this.indicator.remove();
            this.indicator = null;
        }

        // Remove the details popup if it exists
        const popup = document.getElementById('ws-details-popup');
        if (popup) {
            popup.remove();
        }

        // Remove from window object
        if (window.wsManager === this) {
            delete window.wsManager;
        }

        return true;
    }

    // Start monitoring a port for availability
    startPortMonitoring(port) {
        const wsKey = `ws-${port}`;

        // Initialize socket info but don't connect yet
        this.sockets[wsKey] = {
            port: port,
            reconnectAttempt: 0,
            connected: false,
            lastError: null,
            socket: null,
            lastCheck: Date.now(),
            isAvailable: false,
            ideName: null,  // Store IDE name or null
            ideDetails: null  // Store detailed IDE information
        };

        // Check port availability immediately
        this.checkPortAvailability(port, wsKey);
    }

    // Check if port is available and connect if it is
    checkPortAvailability(port, wsKey) {
        // Skip if manager is stopped
        if (!this.running) return;

        this.sockets[wsKey].lastCheck = Date.now();

        // Assume port is available (we'll connect directly)
        const isAvailable = this.sockets[wsKey].isAvailable || false;

        // Always update socket availability status
        this.sockets[wsKey].isAvailable = isAvailable;

        // Try connecting regardless of availability check
        if (!this.sockets[wsKey].socket || !this.sockets[wsKey].connected) {
            this.connectToPort(port, wsKey);
        }
    }

    // Connect to a specific port and set up event listeners
    connectToPort(port, wsKey) {
        // Skip if manager is stopped
        if (!this.running) return;

        try {
            const socket = new WebSocket(`ws://localhost:${port}/api/mcpws`);

            this.sockets[wsKey].socket = socket;
            this.sockets[wsKey].connectTime = Date.now();

            // Connection opened event
            socket.addEventListener('open', (event) => {
                // Skip if manager was stopped
                if (!this.running) {
                    try { socket.close(); } catch(e) {}
                    return;
                }

                this.sockets[wsKey].connected = true;
                this.sockets[wsKey].isAvailable = true;
                this.sockets[wsKey].reconnectAttempt = 0;
                this.sockets[wsKey].lastError = null;
                this.activeConnections++;

                // Update UI
                this.updateTitle();
                this.updateVisualIndicator();

                // Update popup if it's open
                this.updateOpenPopup();
            });

            // Connection closed event
            socket.addEventListener('close', (event) => {
                // Skip if manager was stopped
                if (!this.running) return;

                const wasConnected = this.sockets[wsKey].connected;
                this.sockets[wsKey].connected = false;
                this.sockets[wsKey].socket = null;

                if (wasConnected) {
                    this.activeConnections = Math.max(0, this.activeConnections - 1);
                    this.updateTitle();
                    this.updateVisualIndicator();

                    // Update popup if it's open
                    this.updateOpenPopup();
                }

                // Restart the port availability checking
                if (this.running) {
                    this.timers[`reconnect-${wsKey}`] = setTimeout(() => {
                        this.checkPortAvailability(port, wsKey);
                    }, this.portCheckInterval);
                }
            });

            // Connection error event
            socket.addEventListener('error', (error) => {
                // Skip if manager was stopped
                if (!this.running) return;

                // Record the error
                this.sockets[wsKey].lastError = error;
                this.sockets[wsKey].isAvailable = false;
                // No need to attempt reconnect here as the close event will fire
            });

            // Listen for messages
            socket.addEventListener('message', (event) => {
                // Skip if manager was stopped
                if (!this.running) return;

                try {
                    const data = JSON.parse(event.data);

                    // Extract IDE info from any message containing it
                    if (data.ideInfo) {
                        if (data.ideInfo.productName) {
                            this.sockets[wsKey].ideName = data.ideInfo.productName;
                            this.sockets[wsKey].ideDetails = data.ideInfo;
                            this.updateVisualIndicator();

                            // Update popup if it's open
                            this.updateOpenPopup();
                        }
                    }

                    this.handleMessage(data, port);
                } catch (e) {
                    // Silently handle parsing errors
                }
            });
        } catch (error) {
            // Skip if manager was stopped
            if (!this.running) return;

            // Record connection errors
            this.sockets[wsKey].socket = null;
            this.sockets[wsKey].connected = false;
            this.sockets[wsKey].lastError = error;

            // Restart port checking after a delay
            if (this.running) {
                this.timers[`error-${wsKey}`] = setTimeout(() => {
                    this.checkPortAvailability(port, wsKey);
                }, this.portCheckInterval);
            }
        }
    }

    // Handle different message types
    handleMessage(data, port) {
        // Skip if manager was stopped
        if (!this.running) return;

        // Handle different message types based on type field
        if (data.type === 'new-chat') {
            // Find and click the new chat button
            const newChatButton = document.querySelector('a[href="/new"].flex');
            if (newChatButton) {
                newChatButton.click();

                // Set up the chat content
                if (this.running) {
                    this.timers['new-chat-content'] = setTimeout(() => {
                        if (!this.running) return; // Skip if stopped

                        const editor = document.querySelector('.ProseMirror');
                        if (editor) {
                            editor.innerHTML = data.content;

                            // Send the message
                            if (this.running) {
                                this.timers['new-chat-send'] = setTimeout(() => {
                                    if (!this.running) return; // Skip if stopped

                                    const sendButton = document.querySelector('button[aria-label="Send Message"]');
                                    if (sendButton) {
                                        sendButton.click();
                                    }
                                }, 1000);
                            }
                        }
                    }, 1000);
                }
            }
        }
    }

    // Send a message to all connected WebSockets
    sendToAll(message) {
        // Skip if manager was stopped
        if (!this.running) {
            return false;
        }

        let sentCount = 0;
        for (const [key, wsInfo] of Object.entries(this.sockets)) {
            if (wsInfo.connected && wsInfo.socket && wsInfo.socket.readyState === WebSocket.OPEN) {
                wsInfo.socket.send(JSON.stringify(message));
                sentCount++;
            }
        }

        return sentCount > 0;
    }
}

// Stop any existing WebSocket manager first
if (window.wsManager) {
    window.wsManager.stop();
}

// Create and initialize the WebSocket manager
const wsManager = new WebSocketManager();
wsManager.initializeConnections();

// Expose the websocket manager globally
window.wsManager = wsManager;