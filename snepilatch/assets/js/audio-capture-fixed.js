(async function() {
  if (window.__audioStreamInjected) return;
  window.__audioStreamInjected = true;

  console.log('🚀 Starting enhanced audio capture...');

  // Try multiple WebSocket URLs to find one that works
  const WS_URLS = [
    'ws://10.0.2.2:{{PORT}}',      // Android emulator
    'ws://127.0.0.1:{{PORT}}',     // Localhost
    'ws://localhost:{{PORT}}',     // Alternative localhost
  ];

  let ws = null;

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

    console.log('🎧 Creating audio context...');
    const audioContext = new (window.AudioContext || window.webkitAudioContext)();
    const destination = audioContext.createMediaStreamDestination();
    const connectedElements = new WeakSet();
    let totalConnected = 0;
    let packetsSent = 0;
    let totalBytes = 0;

    // Create gain node to route audio to speakers as well
    const outputGain = audioContext.createGain();
    outputGain.gain.value = 1.0; // Keep original volume
    outputGain.connect(audioContext.destination); // Connect to speakers

    function connectAudioElement(element) {
      if (connectedElements.has(element)) return;

      try {
        console.log('🔊 Found audio element:', element.tagName,
                    'src:', element.src || element.currentSrc || 'no src',
                    'paused:', element.paused);

        // DO NOT MUTE! We want to hear the audio
        // Create source from the audio element
        const source = audioContext.createMediaElementSource(element);

        // Connect to BOTH destinations - WebSocket recording AND speakers
        source.connect(destination);  // For recording/streaming
        source.connect(outputGain);    // For local playback

        connectedElements.add(element);
        totalConnected++;

        console.log('✅ Connected element #' + totalConnected + ' to both outputs');

        // Monitor play/pause events
        element.addEventListener('play', () => {
          console.log('▶️ Audio playing');
        }, { once: true });

        element.addEventListener('pause', () => {
          console.log('⏸️ Audio paused');
        }, { once: true });

      } catch (e) {
        console.warn('⚠️ Could not connect element:', e.message);
      }
    }

    // Setup MediaRecorder
    console.log('📹 Setting up MediaRecorder...');

    const tracks = destination.stream.getAudioTracks();
    console.log('🎤 Audio tracks available:', tracks.length);

    // Check codec support
    let selectedCodec = 'audio/webm;codecs=opus';
    if (!MediaRecorder.isTypeSupported(selectedCodec)) {
      selectedCodec = 'audio/webm';
      console.log('⚠️ Opus not supported, using:', selectedCodec);
    }

    const mediaRecorder = new MediaRecorder(destination.stream, {
      mimeType: selectedCodec,
      audioBitsPerSecond: 128000
    });

    mediaRecorder.ondataavailable = (event) => {
      if (event.data && event.data.size > 0) {
        packetsSent++;
        totalBytes += event.data.size;

        // Log every 10th packet
        if (packetsSent % 10 === 0) {
          console.log('📤 Packet #' + packetsSent +
                      ', Size: ' + event.data.size + 'B' +
                      ', Total: ' + (totalBytes / 1024).toFixed(1) + 'KB');
        }

        if (ws.readyState === WebSocket.OPEN) {
          event.data.arrayBuffer().then(buffer => {
            ws.send(buffer);
          }).catch(err => console.error('Failed to send:', err));
        }
      }
    };

    mediaRecorder.onerror = (event) => {
      console.error('❌ MediaRecorder error:', event.error);
    };

    mediaRecorder.onstart = () => {
      console.log('🟢 Recording started');
    };

    // Find and connect existing audio/video elements
    console.log('🔍 Searching for media elements...');
    const mediaElements = document.querySelectorAll('audio, video');
    console.log('📊 Found ' + mediaElements.length + ' media elements');
    mediaElements.forEach(connectAudioElement);

    // Watch for new elements
    const observer = new MutationObserver((mutations) => {
      mutations.forEach(mutation => {
        mutation.addedNodes.forEach(node => {
          if (node.nodeType === 1) {
            if (node.tagName === 'AUDIO' || node.tagName === 'VIDEO') {
              console.log('🆕 New media element added');
              connectAudioElement(node);
            }
            // Check children too
            if (node.querySelectorAll) {
              const children = node.querySelectorAll('audio, video');
              children.forEach(child => connectAudioElement(child));
            }
          }
        });
      });
    });

    observer.observe(document.body, {
      childList: true,
      subtree: true
    });

    // Start recording
    mediaRecorder.start(100); // Send every 100ms
    console.log('🎬 Audio capture active!');

    // Handle audio context suspension
    if (audioContext.state === 'suspended') {
      console.log('⚠️ Audio context suspended - click to resume');
      document.addEventListener('click', () => {
        audioContext.resume().then(() => {
          console.log('✅ Audio context resumed');
        });
      }, { once: true });
    }

    // Status monitor
    setInterval(() => {
      const elements = document.querySelectorAll('audio, video');
      const playing = Array.from(elements).filter(e => !e.paused).length;

      console.log('📊 Status - Context: ' + audioContext.state +
                  ', Elements: ' + elements.length +
                  ', Playing: ' + playing +
                  ', Sent: ' + packetsSent + ' packets');
    }, 5000);

    ws.onerror = (error) => console.error('❌ WebSocket error:', error);
    ws.onclose = () => {
      console.log('📡 WebSocket closed');
      if (mediaRecorder.state === 'recording') {
        mediaRecorder.stop();
      }
    };

  } catch (error) {
    console.error('❌ Audio capture failed:', error);
  }
})();