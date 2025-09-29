// Spotify Audio Capture Script - Capture without muting
// This version captures audio while still playing it through speakers

(function() {
  'use strict';

  console.log('🎵 Audio Capture (No Mute) Starting...');

  let audioContext = null;
  let destination = null;
  let mediaRecorder = null;
  let ws = null;
  let packetsSent = 0;
  let totalBytes = 0;
  const interceptedElements = new Set();

  // Skip if already initialized
  if (window.spotifyAudioCaptureActive) {
    console.log('⚠️ Audio capture already active');
    return;
  }

  // Connect to WebSocket
  function connectWebSocket() {
    if (ws && ws.readyState === WebSocket.OPEN) return;

    try {
      ws = new WebSocket('ws://localhost:{{PORT}}/audio');

      ws.onopen = () => {
        console.log('✅ WebSocket connected for audio streaming');
      };

      ws.onerror = (error) => {
        console.error('❌ WebSocket error:', error);
      };

      ws.onclose = () => {
        console.log('🔄 WebSocket closed, reconnecting...');
        setTimeout(connectWebSocket, 2000);
      };
    } catch (e) {
      console.error('❌ Failed to create WebSocket:', e);
      setTimeout(connectWebSocket, 2000);
    }
  }

  // Initialize audio capture
  function initAudioCapture() {
    audioContext = new (window.AudioContext || window.webkitAudioContext)();
    console.log('🔊 Audio context created, state:', audioContext.state);

    // Create destination for capture
    destination = audioContext.createMediaStreamDestination();

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

    // Start recording
    mediaRecorder.start(100); // Capture every 100ms
    console.log('🟢 Recording started - audio playing normally');
  }

  // Intercept audio/video elements
  function interceptElement(element) {
    if (!element || interceptedElements.has(element)) return;
    if (element.tagName !== 'AUDIO' && element.tagName !== 'VIDEO') return;

    console.log('🎣 Intercepting', element.tagName, 'element');

    try {
      setTimeout(() => {
        if (audioContext && audioContext.state === 'suspended') {
          audioContext.resume();
        }

        const source = audioContext.createMediaElementSource(element);

        // Connect to destination for capture
        source.connect(destination);

        // ALSO connect to speakers so audio plays normally
        source.connect(audioContext.destination);

        interceptedElements.add(element);
        console.log('✅ Connected', element.tagName, '- capturing and playing');
      }, 100);
    } catch (e) {
      console.error('❌ Failed to connect:', e);
    }
  }

  // Override createElement
  const originalCreateElement = document.createElement.bind(document);
  document.createElement = function(tagName) {
    const element = originalCreateElement(tagName);

    if (tagName.toUpperCase() === 'AUDIO' || tagName.toUpperCase() === 'VIDEO') {
      console.log('🏗️ Creating', tagName, 'element');

      // Intercept when src is set
      Object.defineProperty(element, 'src', {
        set: function(value) {
          this.setAttribute('src', value);
          setTimeout(() => interceptElement(element), 100);
        },
        get: function() {
          return this.getAttribute('src');
        }
      });

      // Also check after delays
      setTimeout(() => interceptElement(element), 500);
      setTimeout(() => interceptElement(element), 1000);
    }

    return element;
  };

  // Check for existing elements
  function checkExistingElements() {
    const videos = document.querySelectorAll('video');
    const audios = document.querySelectorAll('audio');
    const allElements = [...videos, ...audios];

    console.log('🔍 Found', allElements.length, 'media elements');

    allElements.forEach(element => {
      interceptElement(element);
    });

    // Keep checking periodically
    setTimeout(checkExistingElements, 3000);
  }

  // Initialize everything
  connectWebSocket();
  initAudioCapture();
  checkExistingElements();

  window.spotifyAudioCaptureActive = true;
  console.log('✅ Audio capture initialized - playing normally through speakers');
})();