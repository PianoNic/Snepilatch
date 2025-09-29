// Spotify Action Functions
// These functions control playback and navigation in the Spotify web player

(function() {
    // Inject play function into window
    window.spotifyPlay = function() {
        const playButton = document.querySelector('[data-testid="control-button-playpause"]');
        if (playButton && playButton.getAttribute('aria-label')?.includes('Play')) {
            playButton.click();
            return true;
        }
        return false;
    };

    // Inject pause function into window
    window.spotifyPause = function() {
        const pauseButton = document.querySelector('[data-testid="control-button-playpause"]');
        if (pauseButton && pauseButton.getAttribute('aria-label')?.includes('Pause')) {
            pauseButton.click();
            return true;
        }
        return false;
    };

    // Inject next function into window
    window.spotifyNext = function() {
        const nextButton = document.querySelector('[data-testid="control-button-skip-forward"]');
        if (nextButton) {
            nextButton.click();
            return true;
        }
        return false;
    };

    // Inject previous function into window
    window.spotifyPrevious = function() {
        const prevButton = document.querySelector('[data-testid="control-button-skip-back"]');
        if (prevButton) {
            prevButton.click();
            return true;
        }
        return false;
    };

    // Inject toggle shuffle function into window
    window.spotifyToggleShuffle = function() {
        const buttons = document.querySelectorAll('button[aria-label]');
        const shuffleButton = Array.from(buttons).find(button =>
            button.getAttribute('aria-label')?.toLowerCase().includes('shuffle')
        );
        if (shuffleButton) {
            shuffleButton.click();
            return true;
        }
        return false;
    };

    // Inject toggle repeat function into window
    window.spotifyToggleRepeat = function() {
        const repeatButton = document.querySelector('[data-testid="control-button-repeat"]');
        if (repeatButton) {
            repeatButton.click();
            return true;
        }
        return false;
    };

    // Inject toggle like function into window
    window.spotifyToggleLike = function() {
        const likeButton = document.querySelector('[data-testid="now-playing-widget"] button[aria-label*="Lieblingssongs"]') ||
                          document.querySelector('[data-testid="now-playing-widget"] button[aria-label*="Playlist"]') ||
                          document.querySelector('[data-testid="now-playing-widget"] button[aria-label*="favorite"]') ||
                          document.querySelector('[data-testid="now-playing-widget"] button[aria-label*="like"]') ||
                          document.querySelector('[data-testid="now-playing-widget"] button[aria-checked]');
        if (likeButton) {
            likeButton.click();
            return true;
        }
        return false;
    };

    // Inject seek function into window
    window.spotifySeek = function(percentage) {
        const progressInput = document.querySelector('[data-testid="playback-progressbar"] input[type="range"]');
        if (progressInput) {
            const max = parseInt(progressInput.max) || 0;
            const newValue = Math.floor(max * percentage);

            // Update the value
            progressInput.value = newValue;

            // Trigger input event to update the UI
            progressInput.dispatchEvent(new Event('input', { bubbles: true }));

            // Trigger change event to actually seek
            progressInput.dispatchEvent(new Event('change', { bubbles: true }));

            // Alternative: click on the progress bar itself
            const progressBar = document.querySelector('[data-testid="playback-progressbar"]');
            if (progressBar) {
                const rect = progressBar.getBoundingClientRect();
                const x = rect.left + (rect.width * percentage);
                const y = rect.top + (rect.height / 2);

                const clickEvent = new MouseEvent('click', {
                    view: window,
                    bubbles: true,
                    cancelable: true,
                    clientX: x,
                    clientY: y
                });
                progressBar.dispatchEvent(clickEvent);
            }
            return true;
        }
        return false;
    };

    // Inject navigation to search page
    window.spotifyNavigateToSearch = function() {
        const currentUrl = window.location.href;
        console.log('Current URL: ' + currentUrl);

        // Check if we're already on the search page
        if (currentUrl.includes('/search')) {
            console.log('Already on Search page');
            return true;
        }

        // Method 1: Try to use Spotify's internal navigation via history API
        try {
            // Use Spotify's SPA navigation
            const history = window.history;
            const searchUrl = 'https://open.spotify.com/search';

            // Push state without reload
            history.pushState({}, '', searchUrl);

            // Trigger a popstate event to let Spotify's router handle it
            const popstateEvent = new PopStateEvent('popstate', { state: {} });
            window.dispatchEvent(popstateEvent);

            console.log('Triggered SPA navigation to Search');

            // Give it a moment to see if it works
            setTimeout(() => {
                // Check if we're still not on the page, then try clicking
                if (!window.location.href.includes('/search')) {
                    tryClickSearchNavigation();
                }
            }, 500);

            return 'spa_navigation';
        } catch (err) {
            console.log('SPA navigation failed, trying click method');
        }

        // Fallback - try SPA navigation even if main method failed
        function tryClickSearchNavigation() {
            // Method 1: Direct search link with SPA approach
            const searchLink = document.querySelector('a[href="/search"]');
            if (searchLink) {
                console.log('Found search link, using SPA navigation fallback');
                try {
                    const history = window.history;
                    const searchUrl = 'https://open.spotify.com/search';
                    history.pushState({}, '', searchUrl);
                    const popstateEvent = new PopStateEvent('popstate', { state: {} });
                    window.dispatchEvent(popstateEvent);
                    return true;
                } catch (e) {
                    console.log('SPA fallback failed, clicking as last resort');
                    searchLink.click();
                    return true;
                }
            }

            // Method 2: Try the browse button (when already on search page) - click only
            const browseButton = document.querySelector('button[data-testid="browse-button"]');
            if (browseButton) {
                browseButton.click();
                console.log('Clicked browse button');
                return true;
            }

            // Method 3: Find search in navigation by icon or aria-label - click only
            const navSearchButton = document.querySelector('nav button[aria-label*="Search"], nav button[aria-label*="search"], nav button[aria-label*="Suche"]');
            if (navSearchButton) {
                navSearchButton.click();
                console.log('Clicked nav search button as last resort');
                return true;
            }

            console.log('Could not find search navigation element');
            return false;
        }

        return tryClickSearchNavigation();
    };

    // Inject Now Playing View toggle function into window
    window.spotifyToggleNPV = function(forceOpen) {
        const npvButton = document.querySelector('[data-testid="control-button-npv"]');
        if (npvButton) {
            const isActive = npvButton.getAttribute('data-active') === 'true';

            // If forceOpen is true, only click if not already active
            if (forceOpen && !isActive) {
                npvButton.click();
                console.log('Now Playing View opened');
                return true;
            }
            // If forceOpen is false, only click if active
            else if (forceOpen === false && isActive) {
                npvButton.click();
                console.log('Now Playing View closed');
                return true;
            }
            // If no forceOpen specified, toggle
            else if (forceOpen === undefined) {
                npvButton.click();
                console.log('Now Playing View toggled');
                return true;
            }

            return false;
        }
        return false;
    };

    // Inject function to check NPV state
    window.isNPVOpen = function() {
        const npvButton = document.querySelector('[data-testid="control-button-npv"]');
        if (npvButton) {
            return npvButton.getAttribute('data-active') === 'true';
        }
        return false;
    };

    // Inject search function into window
    window.spotifySearch = function(query) {
        // Wait for search page to load if needed
        const checkAndSearch = () => {
            const searchInput = document.querySelector('input[data-testid="search-input"]');
            if (searchInput) {
                // Clear existing value
                searchInput.value = '';
                searchInput.focus();

                // Type the query
                searchInput.value = query;

                // Trigger input event
                const inputEvent = new Event('input', { bubbles: true });
                searchInput.dispatchEvent(inputEvent);

                // Also trigger change event
                const changeEvent = new Event('change', { bubbles: true });
                searchInput.dispatchEvent(changeEvent);

                // Optionally trigger Enter key
                setTimeout(() => {
                    const enterEvent = new KeyboardEvent('keydown', {
                        key: 'Enter',
                        keyCode: 13,
                        bubbles: true
                    });
                    searchInput.dispatchEvent(enterEvent);
                }, 100);

                console.log('Search query entered: ' + query);
                return true;
            }
            return false;
        };

        // Try immediately
        if (checkAndSearch()) {
            return true;
        }

        // If not found, navigate to search first
        if (window.spotifyNavigateToSearch()) {
            setTimeout(() => {
                checkAndSearch();
            }, 1000);
            return true;
        }

        return false;
    };

    // Inject logout function into window
    window.spotifyLogout = function() {
        const accountButton = document.querySelector('[data-testid="user-widget-link"]');
        if (accountButton) {
            accountButton.click();
            setTimeout(() => {
                const logoutButton = document.querySelector('[data-testid="user-widget-dropdown-logout"]');
                if (logoutButton) logoutButton.click();
            }, 500);
            return true;
        }
        return false;
    };

    // Inject play track at index function into window
    window.spotifyPlayTrackAtIndex = function(trackNumber) {
        // Find track by its actual position number (not array index)
        const trackRows = document.querySelectorAll('[data-testid="tracklist-row"]');
        let targetRow = null;

        for (const row of trackRows) {
            const positionEl = row.querySelector('[aria-colindex="1"] span');
            if (positionEl) {
                const position = parseInt(positionEl.textContent.trim());
                if (position === trackNumber) {
                    targetRow = row;
                    break;
                }
            }
        }

        if (targetRow) {
            // Try to find and click the play button first
            const playButton = targetRow.querySelector('button[aria-label*="abspielen"], button[aria-label*="play"], button[aria-label*="Play"]');
            if (playButton) {
                playButton.click();
                console.log('Clicked play button for track #' + trackNumber);
                return true;
            }

            // Fallback: double-click the row to play
            const doubleClick = new MouseEvent('dblclick', {
                view: window,
                bubbles: true,
                cancelable: true
            });
            targetRow.dispatchEvent(doubleClick);
            console.log('Double-clicked track #' + trackNumber);
            return true;
        }

        console.log('Track #' + trackNumber + ' not found in DOM');
        return false;
    };

    // Inject scroll function into window
    window.spotifyScrollPage = function(offset) {
        const mainView = document.querySelector('#main') ||
                         document.querySelector('.main-view-container__scroll-node') ||
                         document.querySelector('[data-testid="playlist-page"]');
        if (mainView) {
            mainView.scrollTop = offset;
            return true;
        }
        return false;
    };

    // Inject open liked songs function into window
    window.spotifyOpenLikedSongs = function() {
        const currentUrl = window.location.href;
        console.log('Current URL: ' + currentUrl);

        // Check if we're already on the liked songs page
        if (currentUrl.includes('/collection/tracks')) {
            console.log('Already on Liked Songs page');
            return true;
        }

        // Method 1: Try to use Spotify's internal navigation via history API
        try {
            // Use Spotify's SPA navigation
            const history = window.history;
            const likedSongsUrl = 'https://open.spotify.com/collection/tracks';

            // Push state without reload
            history.pushState({}, '', likedSongsUrl);

            // Trigger a popstate event to let Spotify's router handle it
            const popstateEvent = new PopStateEvent('popstate', { state: {} });
            window.dispatchEvent(popstateEvent);

            console.log('Triggered SPA navigation to Liked Songs');

            // Give it a moment to see if it works
            setTimeout(() => {
                // Check if we're still not on the page, then try clicking
                if (!window.location.href.includes('/collection/tracks')) {
                    tryClickNavigation();
                }
            }, 500);

            return 'spa_navigation';
        } catch (err) {
            console.log('SPA navigation failed, trying click method');
        }

        // Fallback - use SPA navigation for library/liked songs
        function tryClickNavigation() {
            // First ensure we're on library page using SPA navigation
            if (!currentUrl.includes('/collection')) {
                const libraryLink = document.querySelector('a[href="/collection/playlists"], a[href="/collection"], nav a[aria-label*="library"], nav a[aria-label*="Library"], nav a[aria-label*="Bibliothek"]');
                if (libraryLink) {
                    console.log('Navigating to library first using SPA...');
                    try {
                        const history = window.history;
                        const libraryUrl = 'https://open.spotify.com/collection/playlists';
                        history.pushState({}, '', libraryUrl);
                        const popstateEvent = new PopStateEvent('popstate', { state: {} });
                        window.dispatchEvent(popstateEvent);

                        // After navigating to library, schedule navigation to liked songs
                        setTimeout(() => {
                            const likedSongsUrl = 'https://open.spotify.com/collection/tracks';
                            history.pushState({}, '', likedSongsUrl);
                            window.dispatchEvent(new PopStateEvent('popstate', { state: {} }));
                        }, 500);

                        return 'navigating_to_library';
                    } catch (e) {
                        console.log('SPA navigation to library failed, using click as last resort');
                        libraryLink.click();
                        return 'navigating_to_library';
                    }
                }
            }

            // If we're already on library page, navigate directly to liked songs
            try {
                console.log('Navigating directly to Liked Songs using SPA...');
                const history = window.history;
                const likedSongsUrl = 'https://open.spotify.com/collection/tracks';
                history.pushState({}, '', likedSongsUrl);
                const popstateEvent = new PopStateEvent('popstate', { state: {} });
                window.dispatchEvent(popstateEvent);
                return true;
            } catch (e) {
                console.log('Direct SPA navigation failed, trying element click as last resort');

                // Last resort: Find and click the Liked Songs element
                let likedSongsElement = null;

                // Method 1: Direct link
                likedSongsElement = document.querySelector('a[href="/collection/tracks"]');

                // Method 2: Find by aria-labelledby
                if (!likedSongsElement) {
                    likedSongsElement = document.querySelector('[aria-labelledby="listrow-title-spotify:collection:tracks"]');
                }

                // Method 3: Find the row with the liked songs image
                if (!likedSongsElement) {
                    const likedSongsRow = document.querySelector('div[role="row"][aria-labelledby*="spotify:collection:tracks"]');
                    if (likedSongsRow) {
                        likedSongsElement = likedSongsRow.querySelector('div[role="button"], a[href="/collection/tracks"]') || likedSongsRow;
                    }
                }

                if (likedSongsElement) {
                    console.log('Found Liked Songs element, clicking as last resort...');
                    likedSongsElement.click();
                    return true;
                }
            }

            return false;
        }

        return tryClickNavigation();
    };

    console.log('Spotify action functions injected into window');
    return true;
})();