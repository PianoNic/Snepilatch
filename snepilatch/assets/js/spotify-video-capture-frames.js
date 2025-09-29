(async function() {
    'use strict';

    console.log('🎵 Spotify NPV Video Frame Extractor initialized');

    // Function to check and open sidenav if needed
    function ensureSidenavOpen() {
        // Check if sidenav is closed (look for collapsed state)
        const sidenavToggle = document.querySelector('[data-testid="LayoutResizer__resize-button"]');

        if (sidenavToggle) {
            // Check if sidenav is collapsed by looking for the collapsed state
            const isCollapsed = sidenavToggle.getAttribute('aria-label')?.includes('Expand') ||
                               document.querySelector('.LayoutResizer--collapsed') !== null;

            if (isCollapsed) {
                console.log('📁 Sidenav is collapsed, opening it...');
                sidenavToggle.click();
                return true;
            }
        }

        return false;
    }

    // Function to capture video frame as base64
    function captureVideoFrame(video) {
        try {
            // Create canvas with video dimensions
            const canvas = document.createElement('canvas');
            canvas.width = video.videoWidth || video.clientWidth;
            canvas.height = video.videoHeight || video.clientHeight;

            const ctx = canvas.getContext('2d');

            // Draw current video frame to canvas
            ctx.drawImage(video, 0, 0, canvas.width, canvas.height);

            // Convert to base64
            const base64 = canvas.toDataURL('image/png');
            return base64;
        } catch (error) {
            console.error('Error capturing video frame:', error);
            return null;
        }
    }

    // Function to capture multiple frames
    async function captureVideoFrames(video, frameCount = 10, delay = 100) {
        const frames = [];

        for (let i = 0; i < frameCount; i++) {
            const frame = captureVideoFrame(video);
            if (frame) {
                frames.push(frame);
                console.log(`📸 Captured frame ${i + 1}/${frameCount}`);
            }
            await new Promise(resolve => setTimeout(resolve, delay));
        }

        return frames;
    }

    // Alternative: Try to intercept video source
    function interceptVideoSource() {
        const originalCreateElement = document.createElement;
        document.createElement = function(tagName) {
            const element = originalCreateElement.call(document, tagName);

            if (tagName.toLowerCase() === 'video') {
                const originalSrc = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src');
                Object.defineProperty(element, 'src', {
                    get: function() {
                        return originalSrc.get.call(this);
                    },
                    set: function(value) {
                        console.log('🎥 Video source intercepted:', value);
                        window.lastVideoSource = value;
                        originalSrc.set.call(this, value);
                    }
                });
            }

            return element;
        };
    }

    // Function to download video using fetch with different approach
    async function tryDownloadVideo(video) {
        try {
            // Get video source
            const videoSrc = video.src || video.currentSrc;
            console.log('🔍 Attempting to download from:', videoSrc);

            // Try creating a new video element with the same source
            const tempVideo = document.createElement('video');
            tempVideo.crossOrigin = 'anonymous';
            tempVideo.src = videoSrc;

            return new Promise((resolve) => {
                tempVideo.addEventListener('loadeddata', () => {
                    const canvas = document.createElement('canvas');
                    canvas.width = tempVideo.videoWidth;
                    canvas.height = tempVideo.videoHeight;
                    const ctx = canvas.getContext('2d');
                    ctx.drawImage(tempVideo, 0, 0);
                    resolve(canvas.toDataURL('image/png'));
                });

                tempVideo.addEventListener('error', () => {
                    console.log('⚠️ Cannot load video in temporary element');
                    resolve(null);
                });

                tempVideo.load();
            });
        } catch (error) {
            console.error('Download attempt failed:', error);
            return null;
        }
    }

    // Main extraction function
    async function extractVideo() {
        // First ensure sidenav is open
        ensureSidenavOpen();

        // Check if NPV is open
        const npvButton = document.querySelector('button[data-testid="control-button-npv"]');
        if (!npvButton) {
            console.log('❌ NPV button not found');
            return;
        }

        const isOpen = npvButton.getAttribute('data-active') === 'true';

        if (!isOpen) {
            console.log('⚠️ NPV is closed. Click the Now Playing View button to open it.');
            return;
        }

        // Find the video element in the NPV
        const video = document.querySelector('.canvasVideoContainerNPV video, video[src^="blob:"]');

        if (!video) {
            console.log('❌ Video element not found in NPV');
            return;
        }

        console.log('🎬 Found video element');
        console.log('📊 Video properties:', {
            src: video.src,
            currentSrc: video.currentSrc,
            width: video.videoWidth,
            height: video.videoHeight,
            duration: video.duration,
            currentTime: video.currentTime,
            paused: video.paused,
            readyState: video.readyState
        });

        // Method 1: Capture current frame
        console.log('📸 Method 1: Capturing current video frame...');
        const currentFrame = captureVideoFrame(video);

        if (currentFrame) {
            console.log('✅ Current frame captured successfully!');
            console.log(currentFrame);
            window.spotifyVideoFrame = currentFrame;
            console.log('💾 Frame saved to window.spotifyVideoFrame');

            // Store for Flutter access
            window.latestVideoData = {
                success: true,
                type: 'frame',
                frame: currentFrame,
                width: video.videoWidth,
                height: video.videoHeight,
                timestamp: Date.now()
            };

            // Also capture multiple frames
            console.log('📸 Capturing multiple frames...');
            const frames = await captureVideoFrames(video, 5, 200);
            if (frames.length > 0) {
                window.spotifyVideoFrames = frames;
                console.log(`✅ Captured ${frames.length} frames`);
                console.log('💾 Frames saved to window.spotifyVideoFrames');

                // Log first frame as sample
                console.log('📋 Sample frame (first):', frames[0].substring(0, 100) + '...');
            }
        } else {
            console.log('❌ Failed to capture video frame - may be CORS protected');

            // Method 2: Try alternative download
            console.log('📸 Method 2: Attempting alternative capture...');
            const altCapture = await tryDownloadVideo(video);
            if (altCapture) {
                console.log('✅ Alternative capture successful!');
                window.spotifyVideoFrame = altCapture;

                // Store for Flutter access
                window.latestVideoData = {
                    success: true,
                    type: 'frame',
                    frame: altCapture,
                    width: video.videoWidth,
                    height: video.videoHeight,
                    timestamp: Date.now()
                };
            } else {
                console.log('❌ Alternative capture also failed');
            }
        }

        // Method 3: Record video stream
        console.log('📸 Method 3: Attempting to record video stream...');
        if (video.captureStream) {
            try {
                const stream = video.captureStream();
                const mediaRecorder = new MediaRecorder(stream);
                const chunks = [];

                mediaRecorder.ondataavailable = (e) => {
                    if (e.data.size > 0) {
                        chunks.push(e.data);
                    }
                };

                mediaRecorder.onstop = () => {
                    const blob = new Blob(chunks, { type: 'video/webm' });
                    const reader = new FileReader();
                    reader.onloadend = () => {
                        console.log('✅ Video recording captured!');
                        window.spotifyVideoRecording = reader.result;
                        console.log('💾 Recording saved to window.spotifyVideoRecording');
                        console.log('📏 Recording size:', reader.result.length, 'characters');
                    };
                    reader.readAsDataURL(blob);
                };

                mediaRecorder.start();
                console.log('🔴 Recording started - will capture 3 seconds...');

                setTimeout(() => {
                    mediaRecorder.stop();
                    console.log('⏹️ Recording stopped');
                }, 3000);

            } catch (error) {
                console.error('❌ Stream capture failed:', error);
            }
        } else {
            console.log('❌ captureStream not supported');
        }
    }

    // Function to monitor NPV state changes
    function monitorNPV() {
        const npvButton = document.querySelector('button[data-testid="control-button-npv"]');

        if (!npvButton) {
            console.log('⏳ Waiting for NPV button to appear...');
            setTimeout(monitorNPV, 1000);
            return;
        }

        // Create observer for NPV button state changes
        const observer = new MutationObserver((mutations) => {
            mutations.forEach(async (mutation) => {
                if (mutation.type === 'attributes' &&
                    (mutation.attributeName === 'data-active' || mutation.attributeName === 'aria-pressed')) {

                    const isNowOpen = npvButton.getAttribute('data-active') === 'true';

                    if (isNowOpen) {
                        console.log('🎵 NPV opened - ensuring sidenav is open and waiting for video to load...');
                        // First ensure sidenav is open
                        ensureSidenavOpen();
                        // Wait longer for video to properly load
                        setTimeout(extractVideo, 1000);
                    } else {
                        console.log('📴 NPV closed');
                    }
                }
            });
        });

        observer.observe(npvButton, {
            attributes: true,
            attributeFilter: ['data-active', 'aria-pressed']
        });

        console.log('👀 Monitoring NPV state changes...');

        // Check initial state
        const isInitiallyOpen = npvButton.getAttribute('data-active') === 'true';
        if (isInitiallyOpen) {
            console.log('🎵 NPV is already open - ensuring sidenav is open and extracting video...');
            ensureSidenavOpen();
            setTimeout(extractVideo, 1000);
        } else {
            console.log('📴 NPV is currently closed - open it to extract video');
        }
    }

    // Setup interception
    interceptVideoSource();

    // Add manual extraction function to window
    window.extractSpotifyVideo = extractVideo;
    console.log('🎮 Manual extraction available: window.extractSpotifyVideo()');

    // Start monitoring
    monitorNPV();

    // Add helper to toggle NPV
    window.toggleNPV = function() {
        const npvButton = document.querySelector('button[data-testid="control-button-npv"]');
        if (npvButton) {
            npvButton.click();
            console.log('🔄 Toggled NPV');
        } else {
            console.log('❌ NPV button not found');
        }
    };
    console.log('🎮 Toggle helper available: window.toggleNPV()');

    // Helper to get all video info
    window.getVideoInfo = function() {
        const video = document.querySelector('.canvasVideoContainerNPV video, video[src^="blob:"]');
        if (video) {
            return {
                src: video.src,
                currentSrc: video.currentSrc,
                width: video.videoWidth,
                height: video.videoHeight,
                duration: video.duration,
                currentTime: video.currentTime,
                paused: video.paused,
                readyState: video.readyState,
                networkState: video.networkState,
                error: video.error,
                crossOrigin: video.crossOrigin,
                lastVideoSource: window.lastVideoSource
            };
        }
        return null;
    };
    console.log('🎮 Video info helper available: window.getVideoInfo()');

})();