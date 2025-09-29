const String audioCapturScript = '''
(async function() {
  if (window.__audioStreamInjected) return;
  window.__audioStreamInjected = true;

  // Try multiple WebSocket URLs to find one that works
  const WS_URLS = [
    'ws://10.0.2.2:{{PORT}}',      // Android emulator
    'ws://127.0.0.1:{{PORT}}',     // Localhost
    'ws://localhost:{{PORT}}',     // Alternative localhost
  ];

  let ws = null;
  let wsUrl = '';

  // Try each URL until one connects
  async function tryConnect() {
    for (const url of WS_URLS) {
      try {
        console.log('🔌 Trying WebSocket connection to:', url);
        const testWs = new WebSocket(url);

        // Wait for connection with timeout
        await new Promise((resolve, reject) => {
          const timeout = setTimeout(() => {
            testWs.close();
            reject(new Error('Connection timeout'));
          }, 2000);

          testWs.onopen = () => {
            clearTimeout(timeout);
            ws = testWs;
            wsUrl = url;
            console.log('✅ WebSocket connected to:', url);
            resolve();
          };

          testWs.onerror = () => {
            clearTimeout(timeout);
            reject(new Error('Connection failed'));
          };
        });

        if (ws) break;
      } catch (e) {
        console.log('❌ Failed to connect to:', url);
      }
    }

    if (!ws) {
      throw new Error('Could not connect to any WebSocket URL');
    }

    return ws;
  }

  try {
    const ws = await tryConnect();
    const audioContext = new AudioContext();
    const destination = audioContext.createMediaStreamDestination();
    const connectedElements = new WeakSet();

    // Create gain node to route audio to speakers
    const outputGain = audioContext.createGain();
    outputGain.gain.value = 1.0; // Keep original volume
    outputGain.connect(audioContext.destination); // Connect to speakers

    function connectAudioElement(element) {
      if (connectedElements.has(element)) return;
      try {
        const source = audioContext.createMediaElementSource(element);
        // Connect to BOTH destinations
        source.connect(destination);  // For WebSocket recording
        source.connect(outputGain);    // For speakers playback
        connectedElements.add(element);
        console.log('🎵 Connected:', element.tagName, 'to both outputs');
      } catch (e) {
        console.warn('Could not connect element:', e.message);
      }
    }

    ws.onopen = () => {
      console.log('🎵 Connected to Flutter audio service');

      document.querySelectorAll('audio, video').forEach(connectAudioElement);

      new MutationObserver((mutations) => {
        mutations.forEach(mutation => {
          mutation.addedNodes.forEach(node => {
            if (node.tagName === 'AUDIO' || node.tagName === 'VIDEO') {
              connectAudioElement(node);
            }
          });
        });
      }).observe(document.body, { childList: true, subtree: true });

      let packetsSent = 0;
      let totalBytes = 0;

      const mediaRecorder = new MediaRecorder(destination.stream, {
        mimeType: 'audio/webm;codecs=opus',
        audioBitsPerSecond: 128000
      });

      mediaRecorder.ondataavailable = (event) => {
        if (event.data && event.data.size > 0) {
          packetsSent++;
          totalBytes += event.data.size;

          // Log every 10th packet
          if (packetsSent % 10 === 0) {
            console.log('📤 Packet #' + packetsSent + ', Size: ' + event.data.size + 'B, Total: ' + (totalBytes/1024).toFixed(1) + 'KB');
          }

          if (ws.readyState === WebSocket.OPEN) {
            event.data.arrayBuffer().then(buffer => ws.send(buffer));
          }
        }
      };

      mediaRecorder.start(100); // Send every 100ms
      console.log('🎵 Audio streaming started - NOT MUTED!');
    };

    ws.onerror = (error) => console.error('❌ WebSocket error:', error);
    ws.onclose = () => console.log('📡 Disconnected from Flutter');

  } catch (error) {
    console.error('❌ Audio capture error:', error);
  }
})();
''';