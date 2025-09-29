(function() {
  if (window.__spotifyAudioIntercept) return;
  window.__spotifyAudioIntercept = true;

  console.log('🎯 Spotify Audio Interceptor Starting...');

  // WebSocket connection
  const WS_URLS = [
    'ws://10.0.2.2:{{PORT}}',
    'ws://127.0.0.1:{{PORT}}',
    'ws://localhost:{{PORT}}',
  ];

  let ws = null;
  let audioContext = null;
  let destination = null;
  let outputGain = null;
  let mediaRecorder = null;
  let interceptedElements = new WeakSet();
  let packetsSent = 0;
  let totalBytes = 0;

  // Connect WebSocket
  async function connectWebSocket() {
    for (const url of WS_URLS) {
      try {
        console.log('🔌 Trying:', url);
        const testWs = new WebSocket(url);

        await new Promise((resolve, reject) => {
          const timeout = setTimeout(() => {
            testWs.close();
            reject();
          }, 2000);

          testWs.onopen = () => {
            clearTimeout(timeout);
            ws = testWs;
            console.log('✅ WebSocket connected:', url);
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

    return ws;
  }

  // Initialize audio capture
  async function initializeCapture() {
    console.log('🎧 Initializing audio capture...');

    // Create audio context
    audioContext = new (window.AudioContext || window.webkitAudioContext)({
      latencyHint: 'interactive',
      sampleRate: 44100
    });

    // Create destination for capture
    destination = audioContext.createMediaStreamDestination();

    // Create gain for speakers
    outputGain = audioContext.createGain();
    outputGain.gain.value = 1.0;
    outputGain.connect(audioContext.destination);

    // Setup MediaRecorder
    const tracks = destination.stream.getAudioTracks();
    console.log('🎤 Audio tracks in stream:', tracks.length);

    mediaRecorder = new MediaRecorder(destination.stream, {
      mimeType: MediaRecorder.isTypeSupported('audio/webm;codecs=opus') ?
               'audio/webm;codecs=opus' : 'audio/webm',
      audioBitsPerSecond: 128000
    });

    mediaRecorder.ondataavailable = (event) => {
      if (event.data && event.data.size > 0) {
        packetsSent++;
        totalBytes += event.data.size;

        if (packetsSent === 1) {
          console.log('🎉 FIRST PACKET CAPTURED!');
        }

        if (packetsSent % 10 === 0) {
          console.log('📤 Packet #' + packetsSent + ' (' + (totalBytes/1024).toFixed(1) + 'KB)');
        }

        if (ws && ws.readyState === WebSocket.OPEN) {
          event.data.arrayBuffer().then(buffer => {
            ws.send(buffer);
          });
        }
      }
    };

    mediaRecorder.start(50);
    console.log('🎬 MediaRecorder started');
  }

  // Intercept element creation
  function interceptElement(element) {
    if (!element || interceptedElements.has(element)) return;
    if (element.tagName !== 'AUDIO' && element.tagName !== 'VIDEO') return;

    console.log('🎣 Intercepted', element.tagName, 'element!');

    try {
      // Wait a bit for element to be ready
      setTimeout(() => {
        if (audioContext && audioContext.state === 'suspended') {
          audioContext.resume();
        }

        const source = audioContext.createMediaElementSource(element);
        source.connect(destination);  // For capture
        source.connect(outputGain);   // For speakers

        interceptedElements.add(element);
        console.log('✅ Connected', element.tagName, 'to capture system');

        // Monitor events
        element.addEventListener('play', () => {
          console.log('▶️ Playing audio');
          if (audioContext.state === 'suspended') {
            audioContext.resume();
          }
        });

        element.addEventListener('loadeddata', () => {
          console.log('📊 Audio data loaded');
        });
      }, 100);
    } catch (e) {
      console.error('❌ Failed to connect:', e);
    }
  }

  // Override createElement to intercept audio/video elements
  const originalCreateElement = document.createElement.bind(document);
  document.createElement = function(tagName) {
    const element = originalCreateElement(tagName);

    if (tagName.toUpperCase() === 'AUDIO' || tagName.toUpperCase() === 'VIDEO') {
      console.log('🏗️ Creating', tagName, 'element');

      // Intercept when it's added to DOM
      const originalAppendChild = Node.prototype.appendChild;
      const originalInsertBefore = Node.prototype.insertBefore;
      const originalReplaceChild = Node.prototype.replaceChild;

      // Track when element is added to DOM
      let addedToDOM = false;

      const checkAndIntercept = () => {
        if (!addedToDOM && element.parentNode) {
          addedToDOM = true;
          setTimeout(() => interceptElement(element), 0);
        }
      };

      // Override methods that add to DOM
      ['appendChild', 'insertBefore', 'replaceChild'].forEach(method => {
        const original = element[method];
        if (original) {
          element[method] = function() {
            const result = original.apply(this, arguments);
            checkAndIntercept();
            return result;
          };
        }
      });

      // Also check when src is set
      Object.defineProperty(element, 'src', {
        set: function(value) {
          this.setAttribute('src', value);
          setTimeout(() => interceptElement(element), 100);
        },
        get: function() {
          return this.getAttribute('src');
        }
      });

      // Check periodically
      setTimeout(checkAndIntercept, 500);
      setTimeout(checkAndIntercept, 1000);
      setTimeout(checkAndIntercept, 2000);
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

  // Intercept Web Audio API
  const OriginalAudioContext = window.AudioContext || window.webkitAudioContext;
  const OriginalMediaElementSource = OriginalAudioContext.prototype.createMediaElementSource;

  OriginalAudioContext.prototype.createMediaElementSource = function(element) {
    console.log('🎯 createMediaElementSource called by page!');
    const source = OriginalMediaElementSource.call(this, element);

    // If this isn't our audio context, intercept the element
    if (this !== audioContext) {
      setTimeout(() => interceptElement(element), 0);
    }

    return source;
  };

  // Scan for existing elements
  function scanForElements() {
    const elements = document.querySelectorAll('audio, video');
    console.log('🔍 Found', elements.length, 'media elements');

    elements.forEach(element => {
      if (!interceptedElements.has(element)) {
        interceptElement(element);
      }
    });

    // Check shadow roots
    document.querySelectorAll('*').forEach(el => {
      if (el.shadowRoot) {
        const shadowElements = el.shadowRoot.querySelectorAll('audio, video');
        shadowElements.forEach(element => {
          console.log('🌑 Found in shadow DOM');
          interceptElement(element);
        });
      }
    });
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

          if (node.shadowRoot) {
            node.shadowRoot.querySelectorAll('audio, video').forEach(element => {
              console.log('🌑 New shadow DOM element');
              interceptElement(element);
            });
          }
        }
      });
    });
  });

  // Main initialization
  (async function() {
    try {
      // Connect WebSocket
      await connectWebSocket();

      // Initialize audio capture
      await initializeCapture();

      // Start observing
      observer.observe(document, {
        childList: true,
        subtree: true
      });

      // Initial scan
      scanForElements();

      // Periodic rescans
      setInterval(() => {
        scanForElements();

        const elements = document.querySelectorAll('audio, video');
        const playing = Array.from(elements).filter(e => !e.paused);

        console.log('📊 Status:', {
          context: audioContext ? audioContext.state : 'null',
          elements: elements.length,
          intercepted: interceptedElements.size || 0,
          playing: playing.length,
          packets: packetsSent,
          data: (totalBytes/1024).toFixed(1) + 'KB'
        });

        // Try to resume context if suspended
        if (audioContext && audioContext.state === 'suspended') {
          console.log('⚠️ Context suspended, attempting resume...');
          audioContext.resume();
        }
      }, 3000);

      // Resume on user interaction
      ['click', 'touchstart', 'keydown'].forEach(eventType => {
        document.addEventListener(eventType, () => {
          if (audioContext && audioContext.state === 'suspended') {
            audioContext.resume().then(() => {
              console.log('✅ Context resumed after', eventType);
            });
          }
        }, { once: true });
      });

      console.log('✅ Spotify audio interceptor ready!');

    } catch (error) {
      console.error('❌ Initialization failed:', error);
    }
  })();
})();