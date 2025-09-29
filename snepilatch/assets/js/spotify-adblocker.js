// Spotify Ad Blocker Functions
// Comprehensive ad blocking for audio, visual ads, and premium prompts

(function() {
    'use strict';

    // Configuration
    const config = {
        debug: false,
        muteAds: true,
        skipAds: true,
        hideVisualAds: true,
        checkInterval: 250
    };

    // Logging utility
    const log = (message, ...args) => {
        if (config.debug) {
            console.log('[Spotify AdBlocker]', message, ...args);
        }
    };

    // Query for element with retry
    const queryAsync = (query, interval = config.checkInterval) => {
        return new Promise((resolve) => {
            let attempts = 0;
            const maxAttempts = 40; // 10 seconds max wait
            const checkElement = () => {
                attempts++;
                const element = document.querySelector(query);
                if (element) {
                    resolve(element);
                } else if (attempts < maxAttempts) {
                    setTimeout(checkElement, interval);
                } else {
                    resolve(null); // Resolve with null after timeout
                }
            };
            checkElement();
        });
    };

    // State tracking
    let audioElement = null;
    let lastAdState = false;
    let playInterval = null;

    // Check if an ad is currently playing
    const isAdPlaying = () => {
        // Check for ad indicators in multiple places
        const adIndicators = [
            // Direct ad indicators
            document.querySelector('[data-testid="context-item-info-ad-subtitle"]'),
            document.querySelector('.now-playing a[href*="/ad/"]'),
            document.querySelector('[aria-label*="Advertisement"]'),
            document.querySelector('.now-playing-bar .advertisement'),
            // Check for ad in now playing section
            document.querySelector('.now-playing > a'),
            // Check if current track info contains ad markers
            document.querySelector('[data-testid="now-playing-widget"] a[href*="/ad/"]')
        ];

        const hasAdIndicator = adIndicators.some(indicator => indicator !== null);

        // Also check audio element source
        if (!hasAdIndicator && audioElement && audioElement.src) {
            const isAdUrl = audioElement.src.includes('/ad/') ||
                            audioElement.src.includes('spotify.com/ad');
            if (isAdUrl) {
                log('Ad detected via audio URL');
                return true;
            }
        }

        if (hasAdIndicator && !lastAdState) {
            log('Ad started playing');
            lastAdState = true;
        } else if (!hasAdIndicator && lastAdState) {
            log('Ad finished playing');
            lastAdState = false;
        }

        return hasAdIndicator;
    };

    // Remove ad elements from DOM
    const removeAdElements = () => {
        const adSelectors = [
            // Premium upgrade prompts
            '[aria-label="Upgrade to Premium"]',
            '[data-testid="upgrade-button"]',
            '.upgrade-button',
            'a[href*="/premium"]:not([data-testid="logo"])',
            'button:has-text("Upgrade")',
            '.ButtonInner-sc-14ud5tc-0.fcsOIN',

            // Ad containers
            '[data-testid="ad-slot-container"]',
            '[class*="ad-slot"]',
            '[class*="AdSlot"]',
            '.ad-container',
            '.AdUnitContainer',
            '.sponsored-content',
            '[data-testid="sponsored-slot"]',

            // Banner ads
            '.desktoproutes-homepage-takeover-ad-hptoComponent',
            '.InlineAds',
            '.WiPggcPDzbwGxoxwLWFf',
            '[class*="AdBanner"]',

            // Video ads
            'video[class*="ad"]',
            '[data-testid="video-ad"]',
            '.video-ads-container',

            // Sponsored content
            '[aria-label*="Sponsored"]',
            'div[aria-label*="Advertisement"]',
            '[data-testid="context-item-info-ad-subtitle"]',
            '.sponsored-track'
        ];

        let removedCount = 0;
        adSelectors.forEach(selector => {
            try {
                document.querySelectorAll(selector).forEach(element => {
                    if (element && element.parentNode) {
                        element.style.display = 'none';
                        element.remove();
                        removedCount++;
                    }
                });
            } catch (e) {
                // Ignore selector errors
            }
        });

        if (removedCount > 0) {
            log(`Removed ${removedCount} ad elements`);
        }
    };

    // Handle audio ads by muting or skipping
    const handleAudioAds = async () => {
        try {
            // Try to find audio element
            if (!audioElement) {
                audioElement = document.querySelector('audio');
            }
            if (!audioElement) return;

            // Check if ad is playing
            if (isAdPlaying()) {
                if (config.muteAds) {
                    audioElement.volume = 0;
                    audioElement.muted = true;
                    log('Muted ad audio');
                }

                if (config.skipAds) {
                    // Method 1: Try to skip forward
                    const skipButton = document.querySelector('[data-testid="control-button-skip-forward"]');
                    if (skipButton && !skipButton.disabled) {
                        skipButton.click();
                        log('Clicked skip button');
                    }

                    // Method 2: Fast forward through ad
                    if (audioElement.duration && isFinite(audioElement.duration)) {
                        audioElement.currentTime = audioElement.duration - 0.1;
                        log('Fast forwarded through ad');
                    }

                    // Method 3: Clear audio source
                    if (audioElement.src && audioElement.src.includes('/ad/')) {
                        audioElement.src = '';
                        audioElement.pause();
                        log('Cleared ad audio source');

                        // Try to resume playback
                        setTimeout(() => {
                            const playButton = document.querySelector('[data-testid="control-button-playpause"]');
                            if (playButton && playButton.getAttribute('aria-label')?.includes('Play')) {
                                playButton.click();
                            }
                        }, 500);
                    }
                }
            } else {
                // Restore audio if not an ad
                if (audioElement.muted && config.muteAds) {
                    audioElement.volume = 1;
                    audioElement.muted = false;
                }
            }
        } catch (error) {
            log('Error handling audio ads:', error);
        }
    };

    // Intercept audio element creation
    const interceptAudioCreation = () => {
        const originalCreateElement = document.createElement.bind(document);

        document.createElement = function(tagName) {
            const element = originalCreateElement(tagName);

            if (tagName.toLowerCase() === 'audio') {
                audioElement = element;
                log('Audio element captured');

                // Override play method
                const originalPlay = element.play.bind(element);
                element.play = function() {
                    if (isAdPlaying()) {
                        log('Blocked ad audio playback');

                        // Still try to play but muted
                        element.volume = 0;
                        element.muted = true;

                        // Try to skip
                        setTimeout(() => {
                            const skipButton = document.querySelector('[data-testid="control-button-skip-forward"]');
                            if (skipButton) skipButton.click();
                        }, 100);

                        return originalPlay().catch(() => {});
                    }
                    return originalPlay();
                };
            }

            return element;
        };
    };

    // Monitor now playing bar for ads
    const monitorNowPlayingBar = async () => {
        const nowPlayingBar = await queryAsync('[data-testid="now-playing-bar"], .now-playing-bar');
        const playButton = await queryAsync('[data-testid="control-button-playpause"], button[title="Play"], button[title="Pause"]');

        if (!nowPlayingBar) {
            log('Now playing bar not found, retrying in 5 seconds');
            setTimeout(monitorNowPlayingBar, 5000);
            return;
        }

        let skipAttempts = 0;
        const maxSkipAttempts = 3;

        // Monitor for changes
        const observer = new MutationObserver(() => {
            const link = document.querySelector('.now-playing > a');

            if (link || isAdPlaying()) {
                log('Ad detected in now playing bar');

                // Clear any existing play interval
                if (playInterval) {
                    clearInterval(playInterval);
                    playInterval = null;
                }

                // Multiple skip strategies
                skipAttempts = 0;
                const trySkip = () => {
                    skipAttempts++;

                    // Try skip button
                    const skipButton = document.querySelector('[data-testid="control-button-skip-forward"]');
                    if (skipButton && !skipButton.disabled) {
                        skipButton.click();
                        log('Attempted skip via skip button');
                    }

                    // Clear audio if possible
                    if (audioElement) {
                        audioElement.src = '';
                        audioElement.pause();
                    }

                    // Try to play/pause to skip
                    if (playButton) {
                        playButton.click();
                        setTimeout(() => {
                            if (document.querySelector('.now-playing > a') && skipAttempts < maxSkipAttempts) {
                                trySkip();
                            }
                        }, 500);
                    }
                };

                trySkip();

                // Set up interval to keep trying
                if (!playInterval) {
                    playInterval = setInterval(() => {
                        if (!document.querySelector('.now-playing > a') && !isAdPlaying()) {
                            clearInterval(playInterval);
                            playInterval = null;
                            log('Ad cleared, stopping skip attempts');
                        } else if (playButton) {
                            playButton.click();
                        }
                    }, 500);
                }
            } else if (playInterval) {
                clearInterval(playInterval);
                playInterval = null;
            }
        });

        observer.observe(nowPlayingBar, {
            childList: true,
            subtree: true,
            attributes: true,
            characterData: true
        });

        log('Now playing bar observer attached');
    };

    // Add CSS to hide visual ads
    const injectAdBlockerStyles = () => {
        if (document.getElementById('spotify-adblocker-styles')) return;

        const style = document.createElement('style');
        style.id = 'spotify-adblocker-styles';
        style.textContent = `
            /* Hide upgrade buttons and premium prompts */
            [aria-label="Upgrade to Premium"],
            [data-testid="upgrade-button"],
            .upgrade-button,
            a[href*="/premium"]:not([data-testid="logo"]),
            .ButtonInner-sc-14ud5tc-0.fcsOIN,

            /* Hide ad containers */
            [data-testid="ad-slot-container"],
            [class*="ad-slot"],
            [class*="AdSlot"],
            .ad-container,
            .AdUnitContainer,
            .sponsored-content,
            [data-testid="sponsored-slot"],

            /* Hide banners */
            .desktoproutes-homepage-takeover-ad-hptoComponent,
            .InlineAds,
            .WiPggcPDzbwGxoxwLWFf,
            [class*="AdBanner"],

            /* Hide popups and overlays */
            body > div:not(#main):not([class*="spotify"]):not([class*="Root"]),

            /* Hide video ads */
            video[class*="ad"],
            [data-testid="video-ad"],
            .video-ads-container,

            /* Hide sponsored content */
            [aria-label*="Sponsored"],
            div[aria-label*="Advertisement"],
            [data-testid="context-item-info-ad-subtitle"] {
                display: none !important;
                visibility: hidden !important;
                opacity: 0 !important;
                pointer-events: none !important;
                height: 0 !important;
                width: 0 !important;
                overflow: hidden !important;
            }

            /* Ensure main content is visible */
            #main,
            .Root__main-view,
            [data-testid="main-view"] {
                display: block !important;
                visibility: visible !important;
            }
        `;
        document.head.appendChild(style);
        log('Injected ad blocker styles');
    };

    // Main initialization
    const initAdBlocker = async () => {
        log('Initializing Spotify AdBlocker');

        // Inject styles immediately
        injectAdBlockerStyles();

        // Set up audio interception
        interceptAudioCreation();

        // Start monitoring
        monitorNowPlayingBar();

        // Set up periodic checks
        setInterval(() => {
            if (config.hideVisualAds) {
                removeAdElements();
            }
            if (config.skipAds || config.muteAds) {
                handleAudioAds();
            }
        }, config.checkInterval);

        // Set up mutation observer for dynamic content
        const observer = new MutationObserver(() => {
            if (config.hideVisualAds) {
                removeAdElements();
            }
            if (config.skipAds || config.muteAds) {
                handleAudioAds();
            }
        });

        // Wait for body to be available
        const body = await queryAsync('body');
        if (body) {
            observer.observe(body, {
                childList: true,
                subtree: true,
                attributes: false,
                attributeOldValue: false
            });
        }

        log('AdBlocker initialized successfully');
    };

    // Start initialization
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initAdBlocker);
    } else {
        initAdBlocker();
    }

    // Public API for external control
    window.spotifyAdBlocker = {
        enable: () => {
            config.skipAds = true;
            config.muteAds = true;
            config.hideVisualAds = true;
            log('AdBlocker enabled');
        },
        disable: () => {
            config.skipAds = false;
            config.muteAds = false;
            config.hideVisualAds = false;
            log('AdBlocker disabled');
        },
        setDebug: (enabled) => {
            config.debug = enabled;
            log(`Debug mode ${enabled ? 'enabled' : 'disabled'}`);
        },
        isAdPlaying: isAdPlaying,
        forceRemoveAds: removeAdElements,
        getConfig: () => config
    };

    // Status function
    window.getAdBlockerStatus = function() {
        return JSON.stringify({
            enabled: config.skipAds || config.muteAds || config.hideVisualAds,
            adDetected: isAdPlaying(),
            config: {
                skipAds: config.skipAds,
                muteAds: config.muteAds,
                hideVisualAds: config.hideVisualAds,
                debug: config.debug
            },
            timestamp: new Date().toISOString()
        });
    };

    console.log('Spotify AdBlocker loaded and ready');
})();