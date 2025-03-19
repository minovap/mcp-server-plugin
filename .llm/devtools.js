// Allow all
setInterval(() => {
    const btn = [...document.querySelectorAll('button')].find(btn => btn.textContent.trim() === "Allow for This Chat");
    if (btn) btn.click();
}, 250);

// Create a WebSocket connection
const socket = new WebSocket('ws://localhost:63342/api/mcpws');

// Connection opened event
socket.addEventListener('open', function (event) {
  console.log('Connected to the WebSocket server');
});

// Connection opened event
socket.addEventListener('close', function (event) {
  console.log('Closed');
});

// Connection opened event
socket.addEventListener('error', function (error) {
  console.log('WS Error', error);
});

// Listen for messages
socket.addEventListener('message', function (event) {
  try {
    const data = JSON.parse(event.data);

    // Format different message types
    if (data.type === 'new-chat') {
      console.log(`Starting new chat: ${data.content}`, 'color: blue; font-weight: bold');
  	  document.querySelector('a[href="/new"]').click();
        setTimeout(() => {
            document.querySelector('.ProseMirror').textContent = data.content;

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
