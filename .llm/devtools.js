// Allow all
setInterval(() => {
    const btn = [...document.querySelectorAll('button')].find(btn => btn.textContent.trim() === "Allow for This Chat");
    if (btn) btn.click();
}, 250);

// WebSocket connection manager
let socket;
let reconnectAttempt = 0;
const maxReconnectAttempts = 10;
const baseReconnectDelay = 1000; // Start with 1 second delay

function connectWebSocket() {
    // Create a WebSocket connection
    socket = new WebSocket('ws://localhost:63342/api/mcpws');

    // Connection opened event
    socket.addEventListener('open', function (event) {
        console.log('Connected to the WebSocket server');
        reconnectAttempt = 0; // Reset reconnect counter on successful connection
    });

    // Connection closed event
    socket.addEventListener('close', function (event) {
        console.log('Connection closed');
        attemptReconnect();
    });

    // Connection error event
    socket.addEventListener('error', function (error) {
        console.log('WS Error', error);
        // No need to attempt reconnect here as the close event will fire
    });

    // Listen for messages
    socket.addEventListener('message', function (event) {
        try {
            const data = JSON.parse(event.data);

            // Format different message types
            if (data.type === 'new-chat') {
                console.log(`Starting new chat: ${data.content}`, 'color: blue; font-weight: bold');
                document.querySelector('a[href="/new"].flex').click();
                setTimeout(() => {
                    document.querySelector('.ProseMirror').innerHTML = data.content;

                    setTimeout(() => {
                        document.querySelector('button[aria-label="Send Message"]').click();
                    }, 1000);
                }, 1000);
            } else {
                console.log('Message received:', data);
            }
        } catch (e) {
            console.log('Text message received:', event.data);
        }
    });
}

function attemptReconnect() {
    if (reconnectAttempt >= maxReconnectAttempts) {
        console.log('Maximum reconnection attempts reached');
        return;
    }

    // Calculate delay with exponential backoff (1s, 2s, 4s, 8s, etc.)
    const delay = baseReconnectDelay * Math.pow(2, reconnectAttempt);

    console.log(`Attempting to reconnect in ${delay}ms (attempt ${reconnectAttempt + 1}/${maxReconnectAttempts})`);

    setTimeout(() => {
        reconnectAttempt++;
        connectWebSocket();
    }, delay);
}

// Initial connection
connectWebSocket();