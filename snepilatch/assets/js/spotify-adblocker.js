/**
 * Advanced Spotify Ad Blocker for Snepilatch
 * Based on webpack injection and esperanto ads API manipulation
 * @author ririxi (adapted for Snepilatch)
 */
"use strict"; 

(function() {
    'use strict';

    console.log('🛡️ Snepilatch AdBlocker: Initializing...');

    // =====================================
    // SENTRY ERROR REPORTING BLOCKER
    // =====================================

    /**
     * Block Sentry error reporting to prevent PlayerAPIClientError alerts
     */
    const blockSentry = () => {
        let sentryBlockCount = 0;

        // Block Sentry initialization
        window.Sentry = undefined;
        Object.defineProperty(window, 'Sentry', {
            get: () => {
                sentryBlockCount++;
                console.log(`🛡️ [${sentryBlockCount}] Blocked Sentry initialization attempt`);
                return undefined;
            },
            set: () => {
                sentryBlockCount++;
                console.log(`🛡️ [${sentryBlockCount}] Blocked Sentry.set() attempt`);
            },
            configurable: false
        });

        // Block Raven (old Sentry client)
        window.Raven = undefined;
        Object.defineProperty(window, 'Raven', {
            get: () => {
                sentryBlockCount++;
                console.log(`🛡️ [${sentryBlockCount}] Blocked Raven (old Sentry) initialization attempt`);
                return undefined;
            },
            set: () => {
                sentryBlockCount++;
                console.log(`🛡️ [${sentryBlockCount}] Blocked Raven.set() attempt`);
            },
            configurable: false
        });

        // Intercept and block Sentry network requests
        const originalFetch = window.fetch;
        window.fetch = function(...args) {
            const url = args[0]?.toString() || '';
            if (url.includes('sentry.io') || url.includes('sentry-cdn')) {
                sentryBlockCount++;
                console.log(`🛡️ [${sentryBlockCount}] Blocked Sentry fetch request:`, url);
                return Promise.resolve(new Response('{}', { status: 200 }));
            }
            return originalFetch.apply(this, args);
        };

        // Also block XMLHttpRequest to Sentry
        const originalXHROpen = XMLHttpRequest.prototype.open;
        XMLHttpRequest.prototype.open = function(method, url, ...rest) {
            if (url.toString().includes('sentry.io') || url.toString().includes('sentry-cdn')) {
                sentryBlockCount++;
                console.log(`🛡️ [${sentryBlockCount}] Blocked Sentry XHR ${method} request:`, url);
                // Return a no-op
                return;
            }
            return originalXHROpen.call(this, method, url, ...rest);
        };

        console.log('✅ Sentry error reporting blocker installed');

        // Log summary every 30 seconds if there were blocks
        setInterval(() => {
            if (sentryBlockCount > 0) {
                console.log(`📊 Sentry Blocker Summary: ${sentryBlockCount} total blocks`);
            }
        }, 30000);
    };

    // Block Sentry immediately
    blockSentry();

    // =====================================
    // CONSOLE ERROR SUPPRESSION
    // =====================================

    /**
     * Suppress PlayerAPIClientError messages in console
     */
    const suppressPlayerAPIErrors = () => {
        const originalConsoleError = console.error;
        const originalConsoleWarn = console.warn;

        // Override console.error to suppress PlayerAPIClientError messages
        console.error = function(...args) {
            const message = args.join(' ');
            // Suppress PlayerAPIClientError timeout messages
            if (message.includes('PlayerAPIClientError') ||
                message.includes("didn't receive an acknowledgement")) {
                // Silently ignore these errors
                return;
            }
            originalConsoleError.apply(console, args);
        };

        // Override console.warn to suppress PlayerAPIClientError warnings
        console.warn = function(...args) {
            const message = args.join(' ');
            // Suppress PlayerAPIClientError timeout warnings
            if (message.includes('PlayerAPIClientError') ||
                message.includes("didn't receive an acknowledgement")) {
                // Silently ignore these warnings
                return;
            }
            originalConsoleWarn.apply(console, args);
        };

        // Handle unhandled promise rejections (this is the key!)
        // PlayerAPIClientError appears as "Uncaught (in promise)" which means it's an unhandled rejection
        window.addEventListener('unhandledrejection', function(event) {
            const reason = event.reason?.toString() || '';
            const message = event.reason?.message || '';

            // Check if this is a PlayerAPIClientError
            if (reason.includes('PlayerAPIClientError') ||
                message.includes('PlayerAPIClientError') ||
                message.includes("didn't receive an acknowledgement")) {
                // Prevent the error from being logged to console
                event.preventDefault();
                // Optionally log a suppressed message (comment out if you don't want any logging)
                // console.log('🛡️ Suppressed PlayerAPIClientError:', message);
            }
        });

        // Also handle regular window errors
        const originalOnError = window.onerror;
        window.onerror = function(msg, url, lineNo, columnNo, error) {
            const message = msg?.toString() || '';

            // Suppress PlayerAPIClientError
            if (message.includes('PlayerAPIClientError') ||
                message.includes("didn't receive an acknowledgement")) {
                // Return true to prevent default browser error handling
                return true;
            }

            // Call original handler for other errors
            if (originalOnError) {
                return originalOnError(msg, url, lineNo, columnNo, error);
            }
            return false;
        };

        console.log('✅ PlayerAPIClientError console suppression installed (including Promise rejections)');
    };

    // Suppress console errors immediately
    suppressPlayerAPIErrors();

    // =====================================
    // WEBPACK LOADER & AD CLIENT INJECTION
    // =====================================

    /**
     * Load webpack cache and function modules from Spotify's web player
     */
    const loadWebpack = () => {
        try {
            // Check if webpack is available
            if (!window.webpackChunkclient_web) {
                console.warn('AdBlocker: Webpack not yet available, will retry...');
                return { cache: [], functionModules: [] };
            }

            const require = window.webpackChunkclient_web.push([[Symbol()], {}, (re) => re]);
            const cache = Object.keys(require.m).map(id => require(id));
            const modules = cache
                .filter(module => typeof module === "object")
                .flatMap(module => {
                    try {
                        return Object.values(module);
                    }
                    catch { }
                });
            const functionModules = modules.filter(module => typeof module === "function");

            console.log(`✅ AdBlocker: Loaded webpack - ${cache.length} cache items, ${functionModules.length} function modules`);
            return { cache, functionModules };
        }
        catch (error) {
            console.error("AdBlocker: Failed to load webpack", error);
            return { cache: [], functionModules: [] };
        }
    };

    /**
     * Get the esperanto settings client for ad configuration
     */
    const getSettingsClient = (cache, functionModules = [], transport = {}) => {
        try {
            const settingsClient = cache.find((m) => m?.settingsClient)?.settingsClient;
            if (!settingsClient) {
                const settings = functionModules.find(m =>
                    m?.SERVICE_ID === "spotify.ads.esperanto.settings.proto.Settings" ||
                    m?.SERVICE_ID === "spotify.ads.esperanto.proto.Settings"
                );
                if (!settings) return null;
                return new settings(transport);
            }
            return settingsClient;
        }
        catch (error) {
            console.error("AdBlocker: Failed to get ads settings client", error);
            return null;
        }
    };

    /**
     * Get the slots client for ad slot manipulation
     */
    const getSlotsClient = (functionModules, transport) => {
        try {
            const slots = functionModules.find(m =>
                m.SERVICE_ID === "spotify.ads.esperanto.slots.proto.Slots" ||
                m.SERVICE_ID === "spotify.ads.esperanto.proto.Slots"
            );
            if (!slots) return null;
            return new slots(transport);
        }
        catch (error) {
            console.error("AdBlocker: Failed to get slots client", error);
            return null;
        }
    };

    /**
     * Get the testing client for playtime manipulation
     */
    const getTestingClient = (functionModules, transport) => {
        try {
            const testing = functionModules.find(m =>
                m.SERVICE_ID === "spotify.ads.esperanto.testing.proto.Testing" ||
                m.SERVICE_ID === "spotify.ads.esperanto.proto.Testing"
            );
            if (!testing) return null;
            return new testing(transport);
        }
        catch (error) {
            console.error("AdBlocker: Failed to get testing client", error);
            return null;
        }
    };

    // Retry counter for slot handling with exponential backoff
    const retryMap = new Map();
    const retryCounter = (slotId, action) => {
        if (!retryMap.has(slotId)) retryMap.set(slotId, { count: 0 });
        if (action === "increment") retryMap.get(slotId).count++;
        else if (action === "clear") retryMap.delete(slotId);
        else if (action === "get") return retryMap.get(slotId)?.count;
    };

    /**
     * Calculate exponential backoff delay
     */
    const getBackoffDelay = (retryCount) => {
        // Exponential backoff: 2s, 4s, 8s, 16s
        return Math.min(2000 * Math.pow(2, retryCount), 16000);
    };

    // =====================================
    // CSS-BASED AD HIDING
    // =====================================

    const hideAdLikeElements = () => {
        if (document.getElementById('snepilatch-adblocker-styles')) return;

        const css = document.createElement('style');
        css.id = 'snepilatch-adblocker-styles';

        // Get upgrade text in current locale (fallback to default selectors)
        const upgradeSelectors = [
            'button[aria-label*="Upgrade"]',
            'button[aria-label*="Premium"]',
            'button[title*="Upgrade"]',
            'button[title*="Premium"]',
            '.main-topBar-UpgradeButton'
        ].join(', ');

        css.innerHTML = `
            /* Hide ad slots and containers */
            .sl_aPp6GDg05ItSfmsS7, .nHCJskDZVlmDhNNS9Ixv, .utUDWsORU96S7boXm2Aq,
            .cpBP3znf6dhHLA2dywjy, .G7JYBeU1c2QawLyFs5VK, .vYl1kgf1_R18FCmHgdw2,
            .vZkc6VwrFz0EjVBuHGmx, .iVAZDcTm1XGjxwKlQisz, ._I_1HMbDnNlNAaViEnbp,
            .xXj7eFQ8SoDKYXy6L3E1, .F68SsPm8lZFktQ1lWsQz, .MnW5SczTcbdFHxLZ_Z8j,
            .WiPggcPDzbwGxoxwLWFf, .ReyA3uE3K7oEz7PTTnAn, .x8e0kqJPS0bM4dVK7ESH,
            .gZ2Nla3mdRREDCwybK6X, .SChMe0Tert7lmc5jqH01, .AwF4EfqLOIJ2xO7CjHoX,
            .UlkNeRDFoia4UDWtrOr4, .k_RKSQxa2u5_6KmcOoSw, ._mWmycP_WIvMNQdKoAFb,
            .O3UuqEx6ibrxyOJIdpdg, .akCwgJVf4B4ep6KYwrk5, .bIA4qeTh_LSwQJuVxDzl,
            .ajr9pah2nj_5cXrAofU_, .gvn0k6QI7Yl_A0u46hKn, .obTnuSx7ZKIIY1_fwJhe,
            .IiLMLyxs074DwmEH4x5b, .RJjM91y1EBycwhT_wH59, .mxn5B5ceO2ksvMlI1bYz,
            .l8wtkGVi89_AsA3nXDSR, .Th1XPPdXMnxNCDrYsnwb, .SJMBltbXfqUiByDAkUN_,
            .Nayn_JfAUsSO0EFapLuY, .YqlFpeC9yMVhGmd84Gdo, .HksuyUyj1n3aTnB4nHLd,
            .DT8FJnRKoRVWo77CPQbQ, ._Cq69xKZBtHaaeMZXIdk,
            .main-leaderboardComponent-container, .sponsor-container,
            a.link-subtle.main-navBar-navBarLink.GKnnhbExo0U9l7Jz2rdc,
            ${upgradeSelectors},
            .main-contextMenu-menuItem a[href^="https://www.spotify.com/premium/"],
            div[data-testid*="hpto"],
            [data-testid="ad-slot-container"],
            [class*="ad-slot"], [class*="AdSlot"],
            .ad-container, .AdUnitContainer, .sponsored-content,
            [data-testid="sponsored-slot"],
            [aria-label*="Advertisement"], [aria-label*="Sponsored"] {
                display: none !important;
                visibility: hidden !important;
                opacity: 0 !important;
                height: 0 !important;
                width: 0 !important;
                overflow: hidden !important;
                pointer-events: none !important;
            }
        `;
        document.head.appendChild(css);
        console.log('✅ AdBlocker: Injected CSS to hide ad elements');
    };

    // =====================================
    // CORE AD BLOCKING FUNCTIONALITY
    // =====================================

    let webpackCache = { cache: [], functionModules: [] };
    let Platform = null;
    let AdManagers = null;
    let productState = null;
    let isInitialized = false;

    /**
     * Wait for Spotify platform API to be available
     */
    const waitForPlatform = () => {
        return new Promise((resolve) => {
            const checkPlatform = () => {
                // Try to find Spotify platform in various locations
                if (window.Spotify && window.Spotify.Platform) {
                    Platform = window.Spotify.Platform;
                    AdManagers = Platform.AdManagers;

                    // Get product state
                    const UserAPI = Platform.UserAPI;
                    productState = UserAPI?._product_state ||
                                   UserAPI?._product_state_service ||
                                   Platform?.ProductStateAPI?.productStateApi;

                    if (AdManagers && Object.keys(AdManagers).length > 0 && productState) {
                        console.log('✅ AdBlocker: Spotify Platform API found');
                        resolve(true);
                        return;
                    }
                }

                // Retry after delay
                setTimeout(checkPlatform, 500);
            };
            checkPlatform();
        });
    };

    /**
     * Disable ads in product state
     */
    const disableAds = async () => {
        if (!productState) return;
        try {
            await productState.putOverridesValues({
                pairs: {
                    ads: "0",
                    catalogue: "premium",
                    product: "premium",
                    type: "premium"
                }
            });
            console.log('✅ AdBlocker: Set product state to premium');
        }
        catch (error) {
            console.error("AdBlocker: Failed to disable ads in product state", error);
        }
    };

    // Debouncing for ad manager configuration
    let configureAdManagersTimeout = null;
    let lastConfigureTime = 0;
    const CONFIGURE_COOLDOWN = 10000; // 10 seconds minimum between calls (increased from 5s)

    /**
     * Configure all ad managers to disable ads (with debouncing)
     */
    const configureAdManagers = async () => {
        // Clear any pending configuration
        if (configureAdManagersTimeout) {
            clearTimeout(configureAdManagersTimeout);
        }

        // Check cooldown
        const now = Date.now();
        if (now - lastConfigureTime < CONFIGURE_COOLDOWN) {
            console.log('AdBlocker: Skipping configure (cooldown active)');
            return;
        }

        if (!AdManagers) return;

        try {
            lastConfigureTime = now;
            const { audio, billboard, leaderboard, sponsoredPlaylist, inStreamApi, vto } = AdManagers;

            // Disable audio ads (without API calls when possible)
            if (audio) {
                audio.isNewAdsNpvEnabled = false;
                // Only call disable if not already disabled
                if (typeof audio.disable === 'function' && !audio._disabled) {
                    try {
                        await Promise.race([
                            audio.disable(),
                            new Promise((_, reject) => setTimeout(() => reject(new Error('Timeout')), 3000))
                        ]);
                        audio._disabled = true;
                    } catch (e) {
                        console.warn('AdBlocker: Audio disable timeout/error (non-critical)');
                    }
                }
            }

            // Disable other ad managers with timeout protection
            const disableWithTimeout = async (manager, name) => {
                if (manager && typeof manager.disable === 'function' && !manager._disabled) {
                    try {
                        await Promise.race([
                            manager.disable(),
                            new Promise((_, reject) => setTimeout(() => reject(new Error('Timeout')), 3000))
                        ]);
                        manager._disabled = true;
                    } catch (e) {
                        console.warn(`AdBlocker: ${name} disable timeout/error (non-critical)`);
                    }
                }
            };

            await Promise.all([
                disableWithTimeout(billboard, 'Billboard'),
                disableWithTimeout(inStreamApi, 'InStream'),
                disableWithTimeout(sponsoredPlaylist, 'SponsoredPlaylist'),
            ]);

            // Handle leaderboard separately (different method name)
            if (leaderboard && typeof leaderboard.disableLeaderboard === 'function' && !leaderboard._disabled) {
                try {
                    await Promise.race([
                        leaderboard.disableLeaderboard(),
                        new Promise((_, reject) => setTimeout(() => reject(new Error('Timeout')), 3000))
                    ]);
                    leaderboard._disabled = true;
                } catch (e) {
                    console.warn('AdBlocker: Leaderboard disable timeout/error (non-critical)');
                }
            }

            // Handle VTO
            if (vto) {
                vto.isNewAdsNpvEnabled = false;
                if (vto.manager && typeof vto.manager.disable === 'function' && !vto.manager._disabled) {
                    try {
                        await Promise.race([
                            vto.manager.disable(),
                            new Promise((_, reject) => setTimeout(() => reject(new Error('Timeout')), 3000))
                        ]);
                        vto.manager._disabled = true;
                    } catch (e) {
                        console.warn('AdBlocker: VTO disable timeout/error (non-critical)');
                    }
                }
            }

            console.log('✅ AdBlocker: Configured all ad managers');

            // Update product state with debouncing
            setTimeout(disableAds, 100);
        }
        catch (error) {
            console.error("AdBlocker: Failed to configure ad managers", error);
        }
    };

    /**
     * Get ad slots from Spotify
     */
    const getAdSlots = async () => {
        try {
            const slotsClient = getSlotsClient(webpackCache.functionModules, productState.transport);
            if (slotsClient) {
                const response = await slotsClient.getSlots();
                return response.adSlots || [];
            }
        }
        catch (error) {
            console.error("AdBlocker: Failed to get ad slots", error);
        }
        return [];
    };

    /**
     * Handle individual ad slot (with debouncing and timeout protection)
     */
    const handleAdSlot = async (data) => {
        const slotId = data?.adSlotEvent?.slotId || data?.slotId;
        if (!slotId) return;

        try {
            // Clear slot using ads core connector (with timeout)
            const adsCoreConnector = AdManagers?.audio?.inStreamApi?.adsCoreConnector;
            if (typeof adsCoreConnector?.clearSlot === "function") {
                try {
                    await Promise.race([
                        Promise.resolve(adsCoreConnector.clearSlot(slotId)),
                        new Promise((_, reject) => setTimeout(() => reject(new Error('Timeout')), 2000))
                    ]);
                } catch (e) {
                    console.warn(`AdBlocker: clearSlot timeout for ${slotId} (non-critical)`);
                }
            }

            // Clear using slots client (with timeout)
            const slotsClient = getSlotsClient(webpackCache.functionModules, productState.transport);
            if (slotsClient) {
                try {
                    await Promise.race([
                        slotsClient.clearAllAds({ slotId }),
                        new Promise((_, reject) => setTimeout(() => reject(new Error('Timeout')), 2000))
                    ]);
                } catch (e) {
                    console.warn(`AdBlocker: clearAllAds timeout for ${slotId} (non-critical)`);
                }
            }

            // Update slot settings (with timeout)
            try {
                await Promise.race([
                    updateSlotSettings(slotId),
                    new Promise((_, reject) => setTimeout(() => reject(new Error('Timeout')), 2000))
                ]);
            } catch (e) {
                console.warn(`AdBlocker: updateSlotSettings timeout for ${slotId} (non-critical)`);
            }

            console.log(`✅ AdBlocker: Handled ad slot ${slotId}`);
        }
        catch (error) {
            console.warn(`AdBlocker: Error handling slot ${slotId} (non-critical):`, error.message);

            // Exponential backoff retry logic
            retryCounter(slotId, "increment");
            const retryCount = retryCounter(slotId, "get");

            if (retryCount > 3) {
                console.warn(`AdBlocker: Giving up on slot ${slotId} after 3 retries`);
                retryCounter(slotId, "clear");
                return;
            }

            const backoffDelay = getBackoffDelay(retryCount);
            console.log(`AdBlocker: Retrying slot ${slotId} in ${backoffDelay}ms (attempt ${retryCount + 1})`);
            setTimeout(() => handleAdSlot(data), backoffDelay);
        }

        // Don't reconfigure ad managers here - causes too many API calls
        // configureAdManagers();
    };

    /**
     * Update settings for specific ad slot
     */
    const updateSlotSettings = async (slotId) => {
        try {
            const settingsClient = getSettingsClient(
                webpackCache.cache,
                webpackCache.functionModules,
                productState.transport
            );
            if (!settingsClient) return;

            await settingsClient.updateAdServerEndpoint({
                slotIds: [slotId],
                url: "http://localhost/no/thanks"
            });
            await settingsClient.updateStreamTimeInterval({
                slotId,
                timeInterval: "0"
            });
            await settingsClient.updateSlotEnabled({
                slotId,
                enabled: false
            });
            await settingsClient.updateDisplayTimeInterval({
                slotId,
                timeInterval: "0"
            });
        }
        catch (error) {
            console.error(`AdBlocker: Failed to update slot settings for ${slotId}`, error);
        }
    };

    /**
     * Subscribe to ad slot events
     */
    const subscribeToSlot = (slotId) => {
        try {
            const adsCoreConnector = AdManagers?.audio?.inStreamApi?.adsCoreConnector;
            if (adsCoreConnector && typeof adsCoreConnector.subscribeToSlot === 'function') {
                adsCoreConnector.subscribeToSlot(slotId, handleAdSlot);
                console.log(`✅ AdBlocker: Subscribed to slot ${slotId}`);
            }
        }
        catch (error) {
            console.error(`AdBlocker: Failed to subscribe to slot ${slotId}`, error);
        }
    };

    /**
     * Bind to all ad slots
     */
    const bindToSlots = async () => {
        const slots = await getAdSlots();
        console.log(`✅ AdBlocker: Found ${slots.length} ad slots`);

        for (const slot of slots) {
            const slotId = slot.slotId || slot.slot_id;
            if (slotId) {
                subscribeToSlot(slotId);
                // Immediately handle the slot
                setTimeout(() => handleAdSlot({ adSlotEvent: { slotId } }), 50);
            }
        }
    };

    /**
     * Update all slot settings periodically
     */
    const updateAllSlotSettings = async () => {
        const slots = await getAdSlots();
        for (const slot of slots) {
            const slotId = slot.slotId || slot.slot_id;
            if (slotId) {
                await updateSlotSettings(slotId);
            }
        }
    };

    /**
     * Enable experimental features to hide ads
     */
    const enableExperimentalFeatures = async () => {
        try {
            const overrides = {
                enableEsperantoMigration: true,
                enableInAppMessaging: false,
                hideUpgradeCTA: true,
                enablePremiumUserForMiniPlayer: true,
            };

            // Try Platform API first
            if (Platform?.RemoteConfigDebugAPI) {
                for (const [key, value] of Object.entries(overrides)) {
                    await Platform.RemoteConfigDebugAPI.setOverride(
                        { source: "web", type: "boolean", name: key },
                        value
                    );
                }
                console.log('✅ AdBlocker: Set experimental features via Platform API');
            }
        }
        catch (error) {
            console.error("AdBlocker: Failed to set experimental features", error);
        }
    };

    /**
     * Main initialization function
     */
    const initializeAdBlocker = async () => {
        if (isInitialized) {
            console.log('⚠️ AdBlocker: Already initialized');
            return;
        }

        console.log('🚀 AdBlocker: Starting initialization...');

        // Inject CSS immediately
        hideAdLikeElements();

        // Wait for Spotify Platform to be ready
        await waitForPlatform();

        // Load webpack cache
        webpackCache = loadWebpack();
        if (webpackCache.cache.length === 0) {
            console.warn('⚠️ AdBlocker: Webpack not loaded yet, retrying in 2 seconds...');
            setTimeout(initializeAdBlocker, 2000);
            return;
        }

        // Bind to ad slots
        await bindToSlots();

        // Configure ad managers
        await configureAdManagers();

        // Enable experimental features
        await enableExperimentalFeatures();

        // Subscribe to product state changes
        if (productState && typeof productState.subValues === 'function') {
            productState.subValues(
                { keys: ["ads", "catalogue", "product", "type"] },
                () => configureAdManagers()
            );
        }

        // Periodic updates (less frequent to avoid API spam)
        setTimeout(enableExperimentalFeatures, 5000);
        setTimeout(updateAllSlotSettings, 15000);

        // Periodic slot settings updates every 120 seconds (increased from 60s)
        setInterval(updateAllSlotSettings, 120000);

        // Periodic ad manager reconfiguration every 120 seconds
        setInterval(configureAdManagers, 120000);

        isInitialized = true;
        console.log('✅ AdBlocker: Fully initialized and active!');
    };

    // =====================================
    // FALLBACK DOM-BASED AD BLOCKING
    // =====================================

    /**
     * Fallback DOM observer to remove ad elements
     */
    const initDOMObserver = () => {
        const observer = new MutationObserver(() => {
            // Remove any ad elements that appear
            const adSelectors = [
                '[data-testid="ad-slot-container"]',
                '[class*="ad-slot"]',
                '.ad-container',
                '[aria-label*="Advertisement"]'
            ];

            adSelectors.forEach(selector => {
                document.querySelectorAll(selector).forEach(el => {
                    if (el && el.parentNode) {
                        el.remove();
                    }
                });
            });
        });

        if (document.body) {
            observer.observe(document.body, {
                childList: true,
                subtree: true
            });
        }
    };

    // =====================================
    // STARTUP
    // =====================================

    // Start initialization when page is ready
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', () => {
            initializeAdBlocker();
            initDOMObserver();
        });
    } else {
        initializeAdBlocker();
        initDOMObserver();
    }

    // Public API for Flutter to control the ad blocker
    window.spotifyAdBlocker = {
        enable: async () => {
            console.log('AdBlocker: Enabling...');
            await initializeAdBlocker();
        },
        disable: () => {
            console.log('AdBlocker: Disable not fully implemented (requires full state reset)');
            isInitialized = false;
        },
        status: () => {
            return {
                initialized: isInitialized,
                hasWebpack: webpackCache.cache.length > 0,
                hasPlatform: Platform !== null,
                hasAdManagers: AdManagers !== null
            };
        },
        forceUpdate: async () => {
            await configureAdManagers();
            await updateAllSlotSettings();
        }
    };

    // Global status function
    window.getAdBlockerStatus = function() {
        const status = window.spotifyAdBlocker.status();
        return JSON.stringify({
            enabled: status.initialized,
            webpackLoaded: status.hasWebpack,
            platformReady: status.hasPlatform,
            adManagersFound: status.hasAdManagers,
            timestamp: new Date().toISOString()
        });
    };

    console.log('✅ Snepilatch AdBlocker script loaded');

})();
