(async function() {
  if (window.__audioStreamInjected) return;
  window.__audioStreamInjected = true;

  console.log('🚀 Audio Capture for EME/MSE Starting...');

  // WebSocket URLs
  const WS_URLS = [
    'ws://10.0.2.2:{{PORT}}',
    'ws://127.0.0.1:{{PORT}}',
    'ws://localhost:{{PORT}}',
  ];

  let ws = null;

  // Connect to WebSocket
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
            reject();
          };
        });

        if (ws) break;
      } catch (e) {
        console.log('❌ Failed:', url);
      }
    }

    if (!ws) throw new Error('No WebSocket');
    return ws;
  }

  try {
    await tryConnect();

    console.log('🎧 Creating audio context...');

    // Create audio context
    const AudioContextClass = window.AudioContext || window.webkitAudioContext;
    const audioContext = new AudioContextClass({
      latencyHint: 'interactive',
      sampleRate: 44100
    });

    // Create destination for capture
    const destination = audioContext.createMediaStreamDestination();

    // Create gain node for speaker output
    const outputGain = audioContext.createGain();
    outputGain.gain.value = 1.0;
    outputGain.connect(audioContext.destination);

    // Track connected elements
    const connectedElements = new WeakMap();
    let elementCount = 0;
    let packetsSent = 0;
    let totalBytes = 0;

    // Connect audio/video element to capture system
    function connectMediaElement(element) {
      // Check if already connected
      if (connectedElements.has(element)) {
        return connectedElements.get(element);
      }

      try {
        console.log('🔊 Connecting media element:', {
          type: element.tagName,
          src: element.src || element.currentSrc,
          readyState: element.readyState,
          networkState: element.networkState,
          crossOrigin: element.crossOrigin,
          mediaKeys: element.mediaKeys ? 'Present (EME)' : 'None'
        });

        // Check if element has EME/DRM
        if (element.mediaKeys) {
          console.log('🔐 Element uses EME/DRM - Spotify encrypted content detected');
        }

        // Create media element source
        const source = audioContext.createMediaElementSource(element);

        // Connect to both destinations
        source.connect(destination);  // For capture
        source.connect(outputGain);   // For speakers

        connectedElements.set(element, source);
        elementCount++;

        console.log('✅ Connected element #' + elementCount);

        // Monitor element state
        element.addEventListener('encrypted', (e) => {
          console.log('🔐 Encrypted event:', e.initDataType);
        });

        element.addEventListener('play', () => {
          console.log('▶️ Playing');
          if (audioContext.state === 'suspended') {
            audioContext.resume();
          }
        });

        element.addEventListener('loadeddata', () => {
          console.log('📊 Data loaded');
        });

        element.addEventListener('canplay', () => {
          console.log('✅ Can play');
        });

        return source;
      } catch (error) {
        console.error('❌ Connect failed:', error);
        return null;
      }
    }

    // Setup MediaRecorder for capture
    console.log('📹 Setting up MediaRecorder...');

    // Check for audio tracks
    const tracks = destination.stream.getAudioTracks();
    console.log('🎤 Stream has', tracks.length, 'audio tracks');

    // Select codec
    let mimeType = 'audio/webm';
    if (MediaRecorder.isTypeSupported('audio/webm;codecs=opus')) {
      mimeType = 'audio/webm;codecs=opus';
    }
    console.log('📼 Using codec:', mimeType);

    const mediaRecorder = new MediaRecorder(destination.stream, {
      mimeType: mimeType,
      audioBitsPerSecond: 128000
    });

    mediaRecorder.ondataavailable = (event) => {
      if (event.data && event.data.size > 0) {
        packetsSent++;
        totalBytes += event.data.size;

        if (packetsSent === 1) {
          console.log('🎉 FIRST AUDIO PACKET!');
        }

        if (packetsSent % 10 === 0) {
          console.log('📤 Packet #' + packetsSent + ' (' + (totalBytes/1024).toFixed(1) + 'KB total)');
        }

        if (ws && ws.readyState === WebSocket.OPEN) {
          event.data.arrayBuffer().then(buffer => {
            ws.send(buffer);
          });
        }
      }
    };

    // Function to scan for media elements
    function scanForMediaElements() {
      // Look for audio and video elements
      const elements = document.querySelectorAll('audio, video');

      elements.forEach((element) => {
        if (!connectedElements.has(element)) {
          connectMediaElement(element);
        }
      });

      // Also check for elements in shadow roots (Spotify might use these)
      const allElements = document.querySelectorAll('*');
      allElements.forEach(el => {
        if (el.shadowRoot) {
          const shadowElements = el.shadowRoot.querySelectorAll('audio, video');
          shadowElements.forEach(element => {
            if (!connectedElements.has(element)) {
              console.log('🌑 Found element in shadow DOM');
              connectMediaElement(element);
            }
          });
        }
      });

      return elements.length;
    }

    // Setup mutation observer to catch dynamically added elements
    const observer = new MutationObserver((mutations) => {
      mutations.forEach(mutation => {
        mutation.addedNodes.forEach(node => {
          if (node.nodeType === 1) {
            // Direct audio/video elements
            if (node.tagName === 'AUDIO' || node.tagName === 'VIDEO') {
              console.log('🆕 New', node.tagName, 'element');
              connectMediaElement(node);
            }

            // Check children
            if (node.querySelectorAll) {
              const children = node.querySelectorAll('audio, video');
              children.forEach(connectMediaElement);
            }

            // Check shadow root
            if (node.shadowRoot) {
              const shadowElements = node.shadowRoot.querySelectorAll('audio, video');
              shadowElements.forEach(element => {
                console.log('🌑 New shadow DOM element');
                connectMediaElement(element);
              });
            }
          }
        });
      });
    });

    // Observe entire document including shadow DOM
    observer.observe(document, {
      childList: true,
      subtree: true
    });

    // Start recording
    mediaRecorder.start(50); // Smaller chunks for lower latency
    console.log('🎬 Recording started!');

    // Initial scan
    scanForMediaElements();

    // Resume audio context on user interaction if needed
    if (audioContext.state === 'suspended') {
      console.log('⚠️ Audio context suspended - waiting for user interaction');

      const resumeContext = () => {
        if (audioContext.state === 'suspended') {
          audioContext.resume().then(() => {
            console.log('✅ Audio context resumed');
          });
        }
      };

      document.addEventListener('click', resumeContext);
      document.addEventListener('touchstart', resumeContext);
      document.addEventListener('keydown', resumeContext);
    }

    // Periodic status check
    setInterval(() => {
      const elements = document.querySelectorAll('audio, video');
      const playing = Array.from(elements).filter(e => !e.paused);

      console.log('📊 Status:', {
        context: audioContext.state,
        elements: elements.length,
        playing: playing.length,
        packets: packetsSent,
        data: (totalBytes/1024).toFixed(1) + 'KB',
        recorder: mediaRecorder.state
      });

      // Re-scan for new elements
      scanForMediaElements();

      // Check for EME/MSE usage
      if (playing.length > 0 && packetsSent === 0) {
        console.warn('⚠️ Audio playing but no capture!');
        playing.forEach(el => {
          console.log('  Element:', {
            mediaKeys: el.mediaKeys ? 'YES' : 'NO',
            src: el.src || el.currentSrc,
            crossOrigin: el.crossOrigin
          });
        });
      }
    }, 3000);

    // Spotify-specific detection
    console.log('🎵 Looking for Spotify player...');

    // Check for Spotify Web Playback SDK
    if (window.Spotify) {
      console.log('✅ Spotify SDK detected');
    }

    // Check for Web Audio API usage (Spotify might use this)
    const originalCreateMediaElementSource = audioContext.createMediaElementSource.bind(audioContext);
    audioContext.createMediaElementSource = function(element) {
      console.log('🎯 createMediaElementSource called by page');
      const source = originalCreateMediaElementSource(element);
      // Also connect our capture
      if (!connectedElements.has(element)) {
        connectMediaElement(element);
      }
      return source;
    };

    console.log('✅ EME/MSE audio capture initialized!');

  } catch (error) {
    console.error('❌ Setup failed:', error);
  }
})();