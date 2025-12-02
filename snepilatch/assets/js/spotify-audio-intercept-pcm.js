(function() {
  if (window.__pcmInterceptor) return;
  window.__pcmInterceptor = true;

  console.log('🚀 PCM Audio Interceptor Starting EARLY...');

  let ws = null;
  let audioContext = null;
  let interceptedElements = new WeakSet();
  let pcmProcessors = new Map(); // Map element to processor
  let packetsSent = 0;
  let totalBytes = 0;

  // Connect WebSocket with retry logic
  let wsConnectionAttempts = 0;
  let wsConnected = false;

  async function connectWebSocket() {
    if (wsConnected) return;

    wsConnectionAttempts++;
    console.log('🔌 WebSocket connection attempt #' + wsConnectionAttempts);

    // Try different WebSocket URLs based on the page protocol
    const isSecure = window.location.protocol === 'https:';
    const WS_URLS = isSecure ? [
      // For HTTPS pages, browsers may block ws:// connections
      // We'll try anyway but also add a fallback mechanism
      'ws://127.0.0.1:{{PORT}}',
      'ws://localhost:{{PORT}}',
      'ws://10.0.2.2:{{PORT}}',
    ] : [
      'ws://127.0.0.1:{{PORT}}',
      'ws://localhost:{{PORT}}',
      'ws://10.0.2.2:{{PORT}}',
    ];

    for (const url of WS_URLS) {
      if (wsConnected) break;

      try {
        console.log('🔍 Trying WebSocket:', url);

        // Create WebSocket with timeout
        const testWs = new WebSocket(url);
        let connected = false;

        // Set up promise to wait for connection
        await new Promise((resolve, reject) => {
          const timeout = setTimeout(() => {
            if (!connected) {
              console.log('⏱️ Timeout for:', url);
              testWs.close();
              reject('timeout');
            }
          }, 3000);

          testWs.onopen = () => {
            connected = true;
            clearTimeout(timeout);
            ws = testWs;
            wsConnected = true;
            console.log('✅ PCM WebSocket CONNECTED:', url);

            // Send format info
            ws.send(JSON.stringify({
              type: 'format',
              sampleRate: 44100,
              bitDepth: 16,
              channels: 1,
              encoding: 'pcm_s16le'
            }));

            resolve();
          };

          testWs.onerror = (e) => {
            clearTimeout(timeout);
            console.log('❌ Connection error for:', url);
            reject(e);
          };

          testWs.onclose = () => {
            if (wsConnected) {
              console.log('⚠️ WebSocket disconnected, will retry...');
              wsConnected = false;
              ws = null;
              // Try to reconnect
              setTimeout(() => connectWebSocket(), 2000);
            }
          };
        });

      } catch (e) {
        console.log('⚠️ Failed to connect to:', url);
      }
    }

    if (!wsConnected) {
      console.log('❌ Failed to connect to any WebSocket URL');

      // Retry after a delay
      if (wsConnectionAttempts < 10) {
        console.log('🔄 Will retry in 3 seconds...');
        setTimeout(() => connectWebSocket(), 3000);
      } else {
        console.error('❌ Giving up after 10 attempts. Audio will play from WebView.');
      }
    }
  }

  // Initialize audio context
  function initAudioContext() {
    audioContext = new (window.AudioContext || window.webkitAudioContext)({
      latencyHint: 'interactive',
      sampleRate: 44100
    });
    console.log('🎵 Audio context created');
  }

  // PCM Processor Worklet
  const processorCode = `
    class PCMInterceptor extends AudioWorkletProcessor {
      constructor() {
        super();
        this.buffer = [];
      }

      process(inputs, outputs) {
        const input = inputs[0];

        if (input && input[0]) {
          // Mix to mono
          const left = input[0];
          const right = input[1] || left;
          const mono = new Float32Array(left.length);

          for (let i = 0; i < left.length; i++) {
            mono[i] = (left[i] + right[i]) / 2;
          }

          this.buffer.push(...mono);

          // Send when we have enough samples
          if (this.buffer.length >= 4096) {
            // Convert to 16-bit PCM
            const pcm16 = new Int16Array(4096);
            for (let i = 0; i < 4096; i++) {
              const sample = Math.max(-1, Math.min(1, this.buffer[i]));
              pcm16[i] = sample < 0 ? sample * 32768 : sample * 32767;
            }

            this.port.postMessage({
              type: 'pcm',
              data: pcm16.buffer
            }, [pcm16.buffer]);

            this.buffer = this.buffer.slice(4096);
          }
        }

        // Pass audio through
        for (let channel = 0; channel < outputs[0].length; channel++) {
          outputs[0][channel].set(inputs[0][channel] || new Float32Array(128));
        }

        return true;
      }
    }
    registerProcessor('pcm-interceptor', PCMInterceptor);
  `;

  // Register worklet processor
  async function registerProcessor() {
    if (!audioContext) initAudioContext();

    const blob = new Blob([processorCode], { type: 'application/javascript' });
    const url = URL.createObjectURL(blob);

    try {
      await audioContext.audioWorklet.addModule(url);
      console.log('✅ PCM processor registered');
    } catch (e) {
      console.error('❌ Failed to register processor:', e);
    }

    URL.revokeObjectURL(url);
  }

  // Intercept audio element
  async function interceptAudioElement(element) {
    if (interceptedElements.has(element)) return;

    console.log('🎯 INTERCEPTING', element.tagName, 'element NOW!');

    try {
      // Ensure audio context is ready
      if (!audioContext) {
        initAudioContext();
        await registerProcessor();
      }

      if (audioContext.state === 'suspended') {
        await audioContext.resume();
      }

      // Create nodes
      const source = audioContext.createMediaElementSource(element);
      const pcmNode = new AudioWorkletNode(audioContext, 'pcm-interceptor');
      const gainNode = audioContext.createGain();

      // MUTE the WebView output
      gainNode.gain.value = 0.0;

      // Handle PCM data
      pcmNode.port.onmessage = (event) => {
        if (event.data.type === 'pcm') {
          const pcmData = new Uint8Array(event.data.data);

          // Check if WebSocket is connected
          if (ws && ws.readyState === WebSocket.OPEN) {
            ws.send(pcmData);

            packetsSent++;
            totalBytes += pcmData.byteLength;

            if (packetsSent === 1) {
              console.log('🎉 FIRST PCM PACKET INTERCEPTED AND SENT!');
            }

            if (packetsSent % 50 === 0) {
              console.log('📤 PCM Packets:', packetsSent, 'Total:', (totalBytes/1024).toFixed(1) + 'KB');
            }
          } else if (!wsConnected) {
            // Try to reconnect if not connected
            if (packetsSent === 0) {
              console.log('⚠️ Audio data ready but WebSocket not connected, attempting connection...');
              connectWebSocket();
            }
          }
        }
      };

      // Connect: source -> pcm -> gain(muted) -> speakers
      source.connect(pcmNode);
      pcmNode.connect(gainNode);
      gainNode.connect(audioContext.destination);

      interceptedElements.add(element);
      pcmProcessors.set(element, pcmNode);

      console.log('✅ Audio element INTERCEPTED and MUTED');
      console.log('🔇 Audio will play through Flutter PCM player');

    } catch (e) {
      console.error('❌ Failed to intercept:', e);
    }
  }

  // AGGRESSIVE INTERCEPTION - Override EVERYTHING early
  console.log('🔨 Overriding Audio constructor and createElement...');

  // Store originals
  const OriginalAudio = window.Audio;
  const originalCreateElement = document.createElement;
  const originalAppendChild = Element.prototype.appendChild;
  const originalInsertBefore = Element.prototype.insertBefore;

  // Override Audio constructor
  window.Audio = function(...args) {
    console.log('🏗️ Audio constructor called!');
    const audio = new OriginalAudio(...args);

    // Intercept immediately
    setTimeout(() => interceptAudioElement(audio), 0);

    return audio;
  };

  // Override createElement
  document.createElement = function(tagName) {
    const element = originalCreateElement.call(document, tagName);

    if (tagName.toLowerCase() === 'audio' || tagName.toLowerCase() === 'video') {
      console.log('🏗️ Creating', tagName, 'element');

      // Intercept when added to DOM
      const originalPlay = element.play;
      element.play = function() {
        console.log('▶️ Play() called on', tagName);
        interceptAudioElement(element);
        return originalPlay.call(element);
      };
    }

    return element;
  };

  // Override appendChild to catch elements being added
  Element.prototype.appendChild = function(child) {
    if (child && (child.tagName === 'AUDIO' || child.tagName === 'VIDEO')) {
      console.log('📎 Audio/Video being added to DOM via appendChild');
      setTimeout(() => interceptAudioElement(child), 0);
    }
    return originalAppendChild.call(this, child);
  };

  // Override insertBefore
  Element.prototype.insertBefore = function(newNode, referenceNode) {
    if (newNode && (newNode.tagName === 'AUDIO' || newNode.tagName === 'VIDEO')) {
      console.log('📎 Audio/Video being added to DOM via insertBefore');
      setTimeout(() => interceptAudioElement(newNode), 0);
    }
    return originalInsertBefore.call(this, newNode, referenceNode);
  };

  // Also scan for existing elements
  function scanExisting() {
    const elements = document.querySelectorAll('audio, video');
    console.log('🔍 Scanning... found', elements.length, 'media elements');

    elements.forEach(el => {
      if (!interceptedElements.has(el)) {
        interceptAudioElement(el);
      }
    });
  }

  // Initialize everything
  (async function() {
    console.log('🚀 Initializing PCM interceptor...');
    console.log('📍 Page URL:', window.location.href);
    console.log('🔒 Protocol:', window.location.protocol);

    // Initialize audio context FIRST
    initAudioContext();

    // Register processor immediately
    await registerProcessor();

    // Connect WebSocket (non-blocking)
    connectWebSocket();

    // Scan for existing elements immediately
    scanExisting();

    // Monitor for new elements aggressively
    const observer = new MutationObserver((mutations) => {
      for (const mutation of mutations) {
        if (mutation.addedNodes.length > 0) {
          scanExisting();
        }
      }
    });

    observer.observe(document, {
      childList: true,
      subtree: true,
      attributes: false
    });

    // Resume audio context on user interaction
    ['click', 'touchstart', 'keydown', 'play'].forEach(eventType => {
      document.addEventListener(eventType, async () => {
        if (audioContext && audioContext.state === 'suspended') {
          await audioContext.resume();
          console.log('✅ Audio context resumed on', eventType);
        }
        // Also check for elements when user interacts
        scanExisting();
      }, { capture: true });
    });

    console.log('✅ PCM Interceptor READY - Actively scanning for audio elements...');

    // More frequent scanning in the first 30 seconds
    let scanCount = 0;
    const frequentScanner = setInterval(() => {
      scanExisting();
      scanCount++;

      if (scanCount > 30) {
        clearInterval(frequentScanner);
        console.log('📊 Switching to normal scan frequency');
      }
    }, 1000);

    // Status updates
    setInterval(() => {
      const status = 'Elements: ' + interceptedElements.size +
                     ', Packets: ' + packetsSent +
                     ', Data: ' + (totalBytes/1024).toFixed(1) + 'KB' +
                     ', Context: ' + (audioContext?.state || 'null') +
                     ', WS: ' + (wsConnected ? 'connected' : 'disconnected');
      console.log('📊 Status:', status);

      // Try to reconnect if disconnected
      if (!wsConnected && wsConnectionAttempts < 10) {
        console.log('🔄 Attempting to reconnect WebSocket...');
        connectWebSocket();
      }
    }, 5000);
  })();

})();