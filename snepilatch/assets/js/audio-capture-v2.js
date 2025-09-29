(async function() {
  if (window.__audioStreamInjected) return;
  window.__audioStreamInjected = true;

  console.log('🚀 Audio Capture V2 Starting...');

  // WebSocket URLs to try
  const WS_URLS = [
    'ws://10.0.2.2:{{PORT}}',
    'ws://127.0.0.1:{{PORT}}',
    'ws://localhost:{{PORT}}',
  ];

  let ws = null;

  // Try connecting to WebSocket
  async function tryConnect() {
    for (const url of WS_URLS) {
      try {
        console.log('🔌 Trying:', url);
        const testWs = new WebSocket(url);

        await new Promise((resolve, reject) => {
          const timeout = setTimeout(() => {
            testWs.close();
            reject(new Error('Timeout'));
          }, 2000);

          testWs.onopen = () => {
            clearTimeout(timeout);
            ws = testWs;
            console.log('✅ Connected:', url);
            resolve();
          };

          testWs.onerror = () => {
            clearTimeout(timeout);
            reject(new Error('Failed'));
          };
        });

        if (ws) break;
      } catch (e) {
        console.log('❌ Failed:', url);
      }
    }

    if (!ws) throw new Error('No WebSocket connection');
    return ws;
  }

  try {
    // Connect WebSocket first
    await tryConnect();

    console.log('🎧 Creating audio context...');
    const audioContext = new (window.AudioContext || window.webkitAudioContext)();

    // Resume context if suspended
    if (audioContext.state === 'suspended') {
      console.log('⚠️ Audio context suspended, attempting resume...');
      audioContext.resume();

      // Also try on user interaction
      document.addEventListener('click', () => {
        if (audioContext.state === 'suspended') {
          audioContext.resume().then(() => {
            console.log('✅ Audio context resumed after click');
          });
        }
      }, { once: true });
    }

    const destination = audioContext.createMediaStreamDestination();
    const connectedElements = new Set(); // Use Set instead of WeakSet for debugging
    let totalConnected = 0;
    let packetsSent = 0;
    let totalBytes = 0;

    // Create gain node for speaker output
    const outputGain = audioContext.createGain();
    outputGain.gain.value = 1.0;
    outputGain.connect(audioContext.destination);

    function connectAudioElement(element) {
      // Use element src as unique identifier
      const id = element.src || element.currentSrc || 'element_' + Math.random();

      if (connectedElements.has(id)) {
        return;
      }

      try {
        console.log('🔊 Connecting audio element:', {
          tag: element.tagName,
          src: element.src || element.currentSrc || 'no src',
          paused: element.paused,
          muted: element.muted,
          volume: element.volume,
          duration: element.duration
        });

        const source = audioContext.createMediaElementSource(element);

        // Connect to both outputs
        source.connect(destination); // For recording
        source.connect(outputGain);  // For speakers

        connectedElements.add(id);
        totalConnected++;

        console.log('✅ Connected element #' + totalConnected);

        // Monitor element events
        element.addEventListener('play', () => {
          console.log('▶️ Element playing');
        });

        element.addEventListener('loadstart', () => {
          console.log('📥 Element loading');
        });

      } catch (e) {
        console.warn('⚠️ Could not connect:', e.message);
      }
    }

    // Setup MediaRecorder
    console.log('📹 Setting up MediaRecorder...');

    // Check audio tracks
    const tracks = destination.stream.getAudioTracks();
    console.log('🎤 Audio tracks:', tracks.length);

    if (tracks.length === 0) {
      console.error('❌ No audio tracks in stream!');
    }

    const mediaRecorder = new MediaRecorder(destination.stream, {
      mimeType: MediaRecorder.isTypeSupported('audio/webm;codecs=opus') ?
               'audio/webm;codecs=opus' : 'audio/webm',
      audioBitsPerSecond: 128000
    });

    mediaRecorder.ondataavailable = (event) => {
      if (event.data && event.data.size > 0) {
        packetsSent++;
        totalBytes += event.data.size;

        if (packetsSent === 1) {
          console.log('🎉 FIRST PACKET RECEIVED!');
        }

        if (packetsSent % 10 === 0) {
          console.log('📤 Packet #' + packetsSent + ', Size: ' + event.data.size +
                      'B, Total: ' + (totalBytes/1024).toFixed(1) + 'KB');
        }

        if (ws && ws.readyState === WebSocket.OPEN) {
          event.data.arrayBuffer().then(buffer => {
            ws.send(buffer);
          }).catch(err => console.error('Send error:', err));
        }
      }
    };

    mediaRecorder.onerror = (event) => {
      console.error('❌ MediaRecorder error:', event);
    };

    // Function to scan for media elements
    function scanForElements() {
      const elements = document.querySelectorAll('audio, video');
      console.log('🔍 Scanning... found ' + elements.length + ' media elements');

      elements.forEach((element, index) => {
        console.log('  Element ' + index + ':', {
          tag: element.tagName,
          src: element.src || element.currentSrc || 'no src',
          hasSource: element.querySelector('source') !== null
        });
        connectAudioElement(element);
      });

      // Also look for iframes that might contain media
      const iframes = document.querySelectorAll('iframe');
      console.log('🖼️ Found ' + iframes.length + ' iframes');

      return elements.length;
    }

    // Setup mutation observer
    const observer = new MutationObserver((mutations) => {
      mutations.forEach(mutation => {
        mutation.addedNodes.forEach(node => {
          if (node.nodeType === 1) {
            if (node.tagName === 'AUDIO' || node.tagName === 'VIDEO') {
              console.log('🆕 New ' + node.tagName + ' detected');
              connectAudioElement(node);
            }

            if (node.querySelectorAll) {
              const children = node.querySelectorAll('audio, video');
              children.forEach(child => {
                console.log('🆕 New child ' + child.tagName);
                connectAudioElement(child);
              });
            }
          }
        });
      });
    });

    observer.observe(document, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: ['src']
    });

    // Start recording
    mediaRecorder.start(100);
    console.log('🎬 Recording started!');

    // Initial scan
    scanForElements();

    // Periodic rescan and status
    setInterval(() => {
      const count = scanForElements();
      console.log('📊 Status Report:');
      console.log('  Audio Context:', audioContext.state);
      console.log('  Elements Connected:', connectedElements.size);
      console.log('  Total Found:', count);
      console.log('  Packets Sent:', packetsSent);
      console.log('  Data Sent:', (totalBytes/1024).toFixed(1) + 'KB');
      console.log('  WebSocket:', ws ? ws.readyState : 'null');
      console.log('  Recorder State:', mediaRecorder.state);

      // Check if any elements are playing
      const allElements = document.querySelectorAll('audio, video');
      const playing = Array.from(allElements).filter(e => !e.paused);
      console.log('  Playing:', playing.length);

      if (playing.length > 0 && packetsSent === 0) {
        console.warn('⚠️ Audio is playing but no packets sent!');
        console.log('  Checking audio tracks...');
        const currentTracks = destination.stream.getAudioTracks();
        console.log('  Stream has', currentTracks.length, 'audio tracks');
        currentTracks.forEach((track, i) => {
          console.log('    Track', i, '- enabled:', track.enabled, 'muted:', track.muted);
        });
      }
    }, 5000);

    // WebSocket handlers
    ws.onclose = () => {
      console.log('📡 WebSocket closed');
      if (mediaRecorder.state === 'recording') {
        mediaRecorder.stop();
      }
    };

    ws.onerror = (error) => {
      console.error('❌ WebSocket error:', error);
    };

    console.log('✅ Audio capture fully initialized!');

  } catch (error) {
    console.error('❌ Setup failed:', error);
  }
})();