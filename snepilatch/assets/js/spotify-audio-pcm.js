// Spotify Audio PCM Capture Script
// Captures raw PCM audio data for better compatibility with Flutter playback

(function() {
    'use strict';

    console.log('🎵 PCM Audio Capture: Initializing...');

    // Skip if already initialized
    if (window.pcmAudioCaptureActive) {
        console.log('⚠️ PCM capture already active');
        return;
    }

    let audioContext = null;
    let sourceNode = null;
    let scriptProcessor = null;
    let outputGain = null;
    let ws = null;
    let isCapturing = false;

    // WebSocket connection
    function connectWebSocket() {
        if (ws && ws.readyState === WebSocket.OPEN) return;

        try {
            ws = new WebSocket('ws://localhost:{{PORT}}/audio');

            ws.onopen = () => {
                console.log('✅ WebSocket connected for PCM audio');
            };

            ws.onerror = (error) => {
                console.error('❌ WebSocket error:', error);
            };

            ws.onclose = () => {
                console.log('🔄 WebSocket closed, will reconnect...');
                setTimeout(connectWebSocket, 2000);
            };
        } catch (e) {
            console.error('❌ Failed to create WebSocket:', e);
            setTimeout(connectWebSocket, 2000);
        }
    }

    // Convert float32 PCM to 16-bit PCM
    function floatTo16BitPCM(float32Array) {
        const buffer = new ArrayBuffer(float32Array.length * 2);
        const view = new DataView(buffer);
        let offset = 0;
        for (let i = 0; i < float32Array.length; i++, offset += 2) {
            const s = Math.max(-1, Math.min(1, float32Array[i]));
            view.setInt16(offset, s < 0 ? s * 0x8000 : s * 0x7FFF, true);
        }
        return buffer;
    }

    // Detect if music is playing
    function isMusicPlaying() {
        const playButton = document.querySelector('[data-testid="control-button-playpause"]');
        if (playButton) {
            const ariaLabel = playButton.getAttribute('aria-label') || '';
            return ariaLabel.toLowerCase().includes('pause');
        }
        return false;
    }

    // Setup audio capture
    function setupCapture() {
        const videos = document.querySelectorAll('video');
        const audios = document.querySelectorAll('audio');
        const mediaElements = [...videos, ...audios];

        if (mediaElements.length === 0) {
            console.log('⏳ No media elements found yet');
            setTimeout(setupCapture, 1000);
            return;
        }

        const mediaElement = mediaElements[0];
        console.log(`📺 Found media element: ${mediaElement.tagName}`);

        try {
            // Create audio context
            audioContext = new (window.AudioContext || window.webkitAudioContext)({
                sampleRate: 44100  // Standard CD quality
            });

            // Create source from media element
            sourceNode = audioContext.createMediaElementSource(mediaElement);

            // Create script processor for capturing raw PCM
            scriptProcessor = audioContext.createScriptProcessor(4096, 2, 2);

            scriptProcessor.onaudioprocess = (event) => {
                if (!isCapturing || !isMusicPlaying()) return;

                // Get PCM data from left and right channels
                const leftChannel = event.inputBuffer.getChannelData(0);
                const rightChannel = event.inputBuffer.getChannelData(1);

                // Interleave channels
                const interleaved = new Float32Array(leftChannel.length * 2);
                for (let i = 0; i < leftChannel.length; i++) {
                    interleaved[i * 2] = leftChannel[i];
                    interleaved[i * 2 + 1] = rightChannel[i];
                }

                // Convert to 16-bit PCM and send
                if (ws && ws.readyState === WebSocket.OPEN) {
                    const pcmData = floatTo16BitPCM(interleaved);
                    ws.send(pcmData);
                }
            };

            // Create gain node for muting speakers
            outputGain = audioContext.createGain();
            outputGain.gain.value = 0.0;  // MUTE - audio plays through Flutter

            // Connect nodes
            sourceNode.connect(scriptProcessor);
            scriptProcessor.connect(outputGain);
            outputGain.connect(audioContext.destination);

            console.log('✅ PCM audio capture setup complete');
            console.log('🔇 WebView audio MUTED - will play through Flutter');

            isCapturing = true;

        } catch (e) {
            console.error('❌ Error setting up PCM capture:', e);
            setTimeout(setupCapture, 2000);
        }
    }

    // Monitor play state changes
    function monitorPlayState() {
        const wasPlaying = isMusicPlaying();

        setInterval(() => {
            const isPlaying = isMusicPlaying();
            if (isPlaying !== wasPlaying) {
                console.log(isPlaying ? '▶️ Music started playing' : '⏸️ Music paused');
            }
        }, 500);
    }

    // Initialize everything
    connectWebSocket();
    setupCapture();
    monitorPlayState();

    window.pcmAudioCaptureActive = true;
    console.log('🎵 PCM Audio Capture: Ready');
})();