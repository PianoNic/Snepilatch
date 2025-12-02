(function() {
  if (window.__spotifyPCMCapture) return;
  window.__spotifyPCMCapture = true;

  console.log('🎯 Spotify PCM Audio Capture Starting...');

  // WebSocket connection - Try all possible IPs
  const WS_URLS = [
    'ws://10.0.2.2:{{PORT}}',     // Android emulator host
    'ws://172.29.112.1:{{PORT}}',  // Your IP 1
    'ws://172.26.240.1:{{PORT}}',  // Your IP 2
    'ws://192.168.204.1:{{PORT}}', // Your IP 3
    'ws://192.168.145.1:{{PORT}}', // Your IP 4
    'ws://10.74.237.1:{{PORT}}',   // Your IP 5
    'ws://127.0.0.1:{{PORT}}',     // Localhost
    'ws://localhost:{{PORT}}',     // Localhost alternative
  ];

  let ws = null;
  let audioContext = null;
  let isCapturing = false;
  let interceptedElements = new WeakSet();
  let activeProcessors = new Set();
  let packetsSent = 0;
  let totalBytes = 0;

  // Connect WebSocket
  async function connectWebSocket() {
    console.log('🔌 Attempting WebSocket connection...');

    for (const url of WS_URLS) {
      try {
        console.log('🔌 Trying:', url);
        const testWs = new WebSocket(url);

        await new Promise((resolve, reject) => {
          const timeout = setTimeout(() => {
            console.log('⏱️ Timeout for:', url);
            testWs.close();
            reject();
          }, 2000);

          testWs.onopen = () => {
            clearTimeout(timeout);
            ws = testWs;
            console.log('✅ WebSocket connected:', url);
            resolve();
          };

          testWs.onerror = (error) => {
            console.log('❌ WebSocket error for', url, ':', error);
            clearTimeout(timeout);
            reject();
          };
        });

        if (ws) {
          console.log('🎉 WebSocket successfully connected to:', url);
          break;
        }
      } catch (e) {
        console.log('❌ Failed:', url, e);
      }
    }

    if (!ws) {
      console.error('❌ Failed to connect to ANY WebSocket URL!');
      console.error('Tried:', WS_URLS);
    }

    return ws;
  }

  // PCM Worklet Processor Code (inline)
  const processorCode = `
    class PCMProcessor extends AudioWorkletProcessor {
      constructor() {
        super();
        this.bufferSize = 4096; // Buffer size for smoother streaming
        this.buffer = [];
      }

      process(inputs, outputs) {
        const input = inputs[0];

        if (input && input[0]) {
          // Convert stereo to mono by averaging channels
          const leftChannel = input[0];
          const rightChannel = input[1] || leftChannel;

          const monoData = new Float32Array(leftChannel.length);
          for (let i = 0; i < leftChannel.length; i++) {
            monoData[i] = (leftChannel[i] + rightChannel[i]) / 2;
          }

          // Add to buffer
          this.buffer.push(...monoData);

          // Send when buffer is full
          if (this.buffer.length >= this.bufferSize) {
            // Convert Float32 [-1, 1] to Int16 PCM [-32768, 32767]
            const int16Data = new Int16Array(this.bufferSize);
            for (let i = 0; i < this.bufferSize; i++) {
              const sample = Math.max(-1, Math.min(1, this.buffer[i]));
              int16Data[i] = sample < 0 ? sample * 32768 : sample * 32767;
            }

            // Send to main thread
            this.port.postMessage({
              type: 'pcm',
              data: int16Data.buffer
            }, [int16Data.buffer]);

            // Clear sent data from buffer
            this.buffer = this.buffer.slice(this.bufferSize);
          }
        }

        // Keep processor alive
        return true;
      }
    }

    registerProcessor('pcm-processor', PCMProcessor);
  `;

  // Initialize audio capture
  async function initializeCapture() {
    console.log('🎧 Initializing PCM audio capture...');

    // Create audio context with optimal settings
    audioContext = new (window.AudioContext || window.webkitAudioContext)({
      latencyHint: 'interactive',
      sampleRate: 44100
    });

    // Create and register the worklet
    const blob = new Blob([processorCode], { type: 'application/javascript' });
    const workletUrl = URL.createObjectURL(blob);

    try {
      await audioContext.audioWorklet.addModule(workletUrl);
      console.log('✅ PCM processor registered');
    } catch (e) {
      console.error('❌ Failed to register PCM processor:', e);
      return;
    }

    URL.revokeObjectURL(workletUrl);
  }

  // Intercept and process audio element
  async function interceptElement(element) {
    if (!element || interceptedElements.has(element)) return;
    if (element.tagName !== 'AUDIO' && element.tagName !== 'VIDEO') return;

    console.log('🎣 Intercepting', element.tagName, 'element for PCM capture');

    try {
      // Ensure audio context is running
      if (audioContext.state === 'suspended') {
        await audioContext.resume();
      }

      // Create media element source
      const source = audioContext.createMediaElementSource(element);

      // Create PCM processor node
      const pcmNode = new AudioWorkletNode(audioContext, 'pcm-processor');

      // Handle PCM data from processor
      pcmNode.port.onmessage = (event) => {
        if (event.data.type === 'pcm') {
          const data = new Uint8Array(event.data.data);

          // Check WebSocket status
          if (!ws) {
            if (packetsSent === 0) {
              console.error('❌ WebSocket not connected! Cannot send PCM data');
            }
            return;
          }

          if (ws.readyState !== WebSocket.OPEN) {
            if (packetsSent === 0) {
              console.error('❌ WebSocket not open! State:', ws.readyState);
            }
            return;
          }

          // Send PCM data through WebSocket
          ws.send(data);

          packetsSent++;
          totalBytes += data.length;

          if (packetsSent === 1) {
            console.log('🎉 FIRST PCM PACKET SENT!');
            console.log('📊 PCM Format: 16-bit, 44.1kHz, Mono');
          }

          if (packetsSent % 50 === 0) {
            console.log('📤 PCM Packet #' + packetsSent + ' (' + (totalBytes/1024).toFixed(1) + 'KB)');
          }
        }
      };

      // Create gain node to mute WebView output
      const muteGain = audioContext.createGain();
      muteGain.gain.value = 0.0; // MUTE - audio will play through Flutter

      // Connect audio graph: source -> processor -> muted output
      source.connect(pcmNode);
      pcmNode.connect(muteGain);
      muteGain.connect(audioContext.destination);

      console.log('🔇 WebView audio MUTED - audio will play through Flutter');

      // Track processor
      activeProcessors.add(pcmNode);
      interceptedElements.add(element);

      console.log('✅ PCM capture connected for', element.tagName);

      // Monitor element state
      element.addEventListener('play', () => {
        console.log('▶️ Playback started - PCM streaming active');
        isCapturing = true;
      });

      element.addEventListener('pause', () => {
        console.log('⏸️ Playback paused');
        isCapturing = false;
      });

    } catch (e) {
      console.error('❌ Failed to setup PCM capture:', e);
    }
  }

  // Override createElement
  const originalCreateElement = document.createElement.bind(document);
  document.createElement = function(tagName) {
    const element = originalCreateElement(tagName);

    if (tagName.toUpperCase() === 'AUDIO' || tagName.toUpperCase() === 'VIDEO') {
      console.log('🏗️ Creating', tagName, 'element');

      // Intercept when added to DOM
      setTimeout(() => {
        if (element.parentNode) {
          interceptElement(element);
        }
      }, 100);
    }

    return element;
  };

  // Override Audio constructor
  const OriginalAudio = window.Audio;
  window.Audio = function(src) {
    console.log('🏗️ Creating Audio via constructor');
    const audio = new OriginalAudio(src);
    setTimeout(() => interceptElement(audio), 100);
    return audio;
  };

  // Scan for existing elements
  function scanForElements() {
    const elements = document.querySelectorAll('audio, video');
    console.log('🔍 Found ' + elements.length + ' audio/video elements in DOM');

    elements.forEach(element => {
      if (!interceptedElements.has(element)) {
        console.log('🎯 Intercepting', element.tagName, 'src:', element.src || 'no src');
        interceptElement(element);
      }
    });

    if (elements.length === 0) {
      console.log('⚠️ No audio/video elements found - waiting for Spotify to create them');
    }
  }

  // Setup mutation observer
  const observer = new MutationObserver(mutations => {
    mutations.forEach(mutation => {
      mutation.addedNodes.forEach(node => {
        if (node.nodeType === 1) {
          if (node.tagName === 'AUDIO' || node.tagName === 'VIDEO') {
            interceptElement(node);
          }

          if (node.querySelectorAll) {
            node.querySelectorAll('audio, video').forEach(interceptElement);
          }
        }
      });
    });
  });

  // Main initialization
  (async function() {
    try {
      console.log('🏁 PCM Audio Capture initializing...');
      console.log('📱 User Agent:', navigator.userAgent);

      // Connect WebSocket
      await connectWebSocket();

      if (!ws) {
        console.error('❌ Failed to connect WebSocket - Audio will play from WebView directly');
        console.error('💡 Try: 1) Check if app is running, 2) Restart app, 3) Check network');
        // Continue anyway - audio will play from WebView
      } else {
        console.log('🎊 WebSocket ready for PCM streaming');
      }

      // Send PCM format info (only if connected)
      if (ws && ws.readyState === WebSocket.OPEN) {
        const formatInfo = {
          type: 'format',
          sampleRate: 44100,
          bitDepth: 16,
          channels: 1, // Mono
          encoding: 'pcm_s16le' // 16-bit signed little-endian
        };
        ws.send(JSON.stringify(formatInfo));
        console.log('📋 Sent PCM format info:', formatInfo);
      }

      // Initialize audio capture
      await initializeCapture();

      // Start observing
      observer.observe(document, {
        childList: true,
        subtree: true
      });

      // Initial scan
      scanForElements();

      // Periodic status
      setInterval(() => {
        const status = {
          context: audioContext ? audioContext.state : 'null',
          processors: activeProcessors.size,
          capturing: isCapturing,
          packets: packetsSent,
          data: (totalBytes/1024).toFixed(1) + 'KB',
          ws: ws ? ws.readyState : 'null'
        };

        console.log('📊 PCM Status: Context=' + status.context +
                    ', Processors=' + status.processors +
                    ', Capturing=' + status.capturing +
                    ', Packets=' + status.packets +
                    ', Data=' + status.data +
                    ', WS=' + status.ws);

        // Try to find audio elements if none captured yet
        if (activeProcessors.size === 0) {
          console.log('🔍 Looking for audio/video elements...');
          scanForElements();
        }
      }, 5000);

      // Resume on user interaction
      ['click', 'touchstart', 'keydown'].forEach(eventType => {
        document.addEventListener(eventType, async () => {
          if (audioContext && audioContext.state === 'suspended') {
            await audioContext.resume();
            console.log('✅ Audio context resumed after', eventType);
          }
        }, { once: true });
      });

      console.log('✅ PCM audio capture ready!');
      console.log('📋 Streaming format: 16-bit PCM, 44.1kHz, Mono');

    } catch (error) {
      console.error('❌ PCM initialization failed:', error);
    }
  })();
})();