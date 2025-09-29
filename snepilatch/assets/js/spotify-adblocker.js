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

    // ===============================
    // SENTRY BLOCKER SECTION
    // ===============================

    console.log('🛡️ Initializing Complete Sentry Blocker...');

    // Block all Sentry-related global objects
    const sentryObjects = [
        'Sentry', '__SENTRY__', '__sentry__', '__sentry_browser_bundle__',
        'Raven', '__raven__', 'SentryReplay', '__sentryReplaySession__',
        '__SENTRY_RELEASE__', '__SENTRY_DSN__', '__sentryEvents__'
    ];

    sentryObjects.forEach(obj => {
        // Block existing objects
        if (window[obj]) {
            delete window[obj];
            console.log(`🚫 Deleted existing ${obj}`);
        }

        // Prevent future assignments
        Object.defineProperty(window, obj, {
            get: () => {
                console.log(`🚫 Blocked access to ${obj}`);
                return undefined;
            },
            set: (value) => {
                console.log(`🚫 Blocked assignment to ${obj}`);
                return false;
            },
            configurable: false,
            enumerable: false
        });
    });

    // Block Sentry methods if they try to attach to other objects
    const blockSentryMethods = (obj) => {
        if (!obj) return;

        const sentryMethods = [
            'init', 'captureException', 'captureMessage', 'captureEvent',
            'configureScope', 'withScope', 'setUser', 'setTag', 'setContext',
            'addBreadcrumb', 'setExtra', 'setLevel', 'setTransaction',
            'startTransaction', 'getCurrentHub', 'getHubFromCarrier'
        ];

        sentryMethods.forEach(method => {
            if (obj[method]) {
                obj[method] = () => {
                    console.log(`🚫 Blocked Sentry.${method}()`);
                    return undefined;
                };
            }
        });
    };

    // Override Error constructor to prevent Sentry from hooking into it
    const OriginalError = window.Error;
    window.Error = function(...args) {
        const error = new OriginalError(...args);
        // Remove any Sentry properties that might be added
        Object.keys(error).forEach(key => {
            if (key.toLowerCase().includes('sentry')) {
                delete error[key];
            }
        });
        return error;
    };
    window.Error.prototype = OriginalError.prototype;

    // Block all Sentry network requests
    const sentryPatterns = [
        'sentry.io',
        'ingest.sentry',
        'sentry-cdn.com',
        'ravenjs.com',
        '/api/[0-9]+/envelope/',
        '/api/[0-9]+/store/',
        'sentry_key=',
        'sentry_version=',
        'sentry-trace',
        'sentry_client='
    ];

    const isSentryRequest = (url) => {
        const urlStr = (url || '').toString().toLowerCase();
        return sentryPatterns.some(pattern => {
            if (pattern.includes('[0-9]+')) {
                const regex = new RegExp(pattern.replace(/\[0-9\]\+/g, '\\d+'));
                return regex.test(urlStr);
            }
            return urlStr.includes(pattern);
        });
    };

    // Block fetch
    const originalFetch = window.fetch;
    window.fetch = function(url, ...args) {
        if (isSentryRequest(url)) {
            console.log('🚫 Blocked Sentry fetch:', url.toString().substring(0, 80));
            return Promise.reject(new Error('Sentry blocked'));
        }
        return originalFetch.call(this, url, ...args);
    };

    // Block XHR
    const XHROpen = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function(method, url, ...rest) {
        this._sentryBlocked = isSentryRequest(url);
        if (this._sentryBlocked) {
            console.log('🚫 Blocked Sentry XHR:', url.toString().substring(0, 80));
        }
        return XHROpen.call(this, method, url, ...rest);
    };

    const XHRSend = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.send = function(data) {
        if (this._sentryBlocked) return;

        // Also check if the data being sent contains Sentry info
        try {
            const dataStr = (data || '').toString();
            if (dataStr.includes('sentry_client') || dataStr.includes('exception')) {
                console.log('🚫 Blocked Sentry data in XHR payload');
                return;
            }
        } catch (e) {}

        return XHRSend.apply(this, arguments);
    };

    // Block SendBeacon
    if (navigator.sendBeacon) {
        const originalBeacon = navigator.sendBeacon;
        navigator.sendBeacon = function(url) {
            if (isSentryRequest(url)) {
                console.log('🚫 Blocked Sentry beacon:', url.toString().substring(0, 80));
                return false;
            }
            return originalBeacon.apply(this, arguments);
        };
    }

    // Block WebSocket
    const OriginalWebSocket = window.WebSocket;
    window.WebSocket = new Proxy(OriginalWebSocket, {
        construct(target, args) {
            const url = args[0];
            if (isSentryRequest(url)) {
                console.log('🚫 Blocked Sentry WebSocket:', url);
                throw new Error('Sentry WebSocket blocked');
            }
            return new target(...args);
        }
    });

    // Block script tags
    const observer = new MutationObserver((mutations) => {
        mutations.forEach((mutation) => {
            mutation.addedNodes.forEach((node) => {
                if (node.tagName === 'SCRIPT') {
                    // Check src
                    if (node.src && isSentryRequest(node.src)) {
                        node.remove();
                        console.log('🚫 Removed Sentry script tag');
                    }
                    // Check inline script content
                    if (node.textContent &&
                        (node.textContent.includes('Sentry') ||
                         node.textContent.includes('raven') ||
                         node.textContent.includes('sentry'))) {
                        node.textContent = '// Sentry blocked';
                        console.log('🚫 Neutralized inline Sentry script');
                    }
                }
            });
        });
    });

    observer.observe(document.documentElement, {
        childList: true,
        subtree: true
    });

    // Override console methods to strip Sentry
    ['log', 'error', 'warn', 'info', 'debug'].forEach(method => {
        const original = console[method];
        console[method] = function(...args) {
            // Filter out Sentry-related logs
            const argsStr = args.join(' ').toLowerCase();
            if (argsStr.includes('sentry') || argsStr.includes('raven')) {
                return;
            }
            return original.apply(console, args);
        };
    });

    // Block localStorage/sessionStorage Sentry data
    const blockSentryStorage = () => {
        const storages = [localStorage, sessionStorage];
        storages.forEach(storage => {
            try {
                Object.keys(storage).forEach(key => {
                    if (key.toLowerCase().includes('sentry') ||
                        key.toLowerCase().includes('raven')) {
                        storage.removeItem(key);
                        console.log(`🚫 Removed Sentry data from storage: ${key}`);
                    }
                });
            } catch (e) {}
        });
    };

    // Clean storage immediately and periodically
    blockSentryStorage();
    setInterval(blockSentryStorage, 5000);

    // Override addEventListener to prevent Sentry error handlers
    const originalAddEventListener = window.addEventListener;
    window.addEventListener = function(type, listener, ...args) {
        if (type === 'error' || type === 'unhandledrejection') {
            const listenerStr = listener.toString();
            if (listenerStr.includes('Sentry') || listenerStr.includes('raven')) {
                console.log(`🚫 Blocked Sentry ${type} event listener`);
                return;
            }
        }
        return originalAddEventListener.call(this, type, listener, ...args);
    };

    // Block performance observer (Sentry uses this for monitoring)
    if (window.PerformanceObserver) {
        const OriginalPerfObserver = window.PerformanceObserver;
        window.PerformanceObserver = new Proxy(OriginalPerfObserver, {
            construct(target, args) {
                const callback = args[0];
                if (callback && callback.toString().includes('sentry')) {
                    console.log('🚫 Blocked Sentry PerformanceObserver');
                    return {
                        observe: () => {},
                        disconnect: () => {},
                        takeRecords: () => []
                    };
                }
                return new target(...args);
            }
        });
    }

    console.log('✅ Complete Sentry Blocker Active');
    console.log('🚫 Blocking: All Sentry initialization, objects, network requests, and monitoring');

    // Final status message
    console.log('Spotify AdBlocker & Sentry Blocker loaded and ready');
})();