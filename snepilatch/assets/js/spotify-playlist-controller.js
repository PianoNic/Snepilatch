// Spotify Playlist Controller
// This class handles infinite scrolling and track collection for playlists

(function() {
    class SpotifyPlaylistController {
        constructor() {
            this.tracks = new Map();
            this.isLoading = false;
            this.lastScrollPosition = 0;
            console.log('SpotifyPlaylistController initialized');

            // Do initial scan
            this._scanCurrentTracks();
        }

        // Scan and add tracks currently in DOM
        _scanCurrentTracks() {
            const trackRows = document.querySelectorAll('[data-testid="tracklist-row"]');
            let newCount = 0;

            trackRows.forEach((row) => {
                try {
                    // Get track position
                    const positionEl = row.querySelector('[aria-colindex="1"] span');
                    const position = positionEl ? parseInt(positionEl.textContent.trim()) : null;

                    // Get track link for unique ID
                    const trackLink = row.querySelector('a[data-testid="internal-track-link"]');
                    const trackUrl = trackLink ? trackLink.href : null;
                    const title = trackLink ? trackLink.textContent.trim() : null;

                    if (!trackUrl || !title) return;

                    // Skip if we already have this track
                    if (this.tracks.has(trackUrl)) return;

                    // Get cover
                    const coverImg = row.querySelector('img[src*="i.scdn.co/image"]');
                    const coverUrl = coverImg ? coverImg.src : null;

                    // Get artists
                    const artistLinks = row.querySelectorAll('a[href*="/artist/"]');
                    const artists = Array.from(artistLinks).map(link => link.textContent.trim());

                    // Get album
                    const albumLink = row.querySelector('a[href*="/album/"]');
                    const album = albumLink ? albumLink.textContent.trim() : null;

                    // Get duration
                    const durationEl = row.querySelector('[data-testid="track-duration"]');
                    const duration = durationEl ? durationEl.textContent.trim() : null;

                    // Store track with position as key for ordering
                    this.tracks.set(trackUrl, {
                        position: position || this.tracks.size + 1,
                        title: title,
                        artists: artists,
                        album: album || 'Unknown Album',
                        duration: duration,
                        coverUrl: coverUrl,
                        trackUrl: trackUrl
                    });

                    newCount++;
                } catch (err) {
                    console.error('Error parsing track:', err);
                }
            });

            return newCount;
        }

        // Load more tracks by scrolling down
        async loadMore() {
            if (this.isLoading) {
                console.log('Already loading...');
                return { success: false, message: 'Already loading' };
            }

            this.isLoading = true;
            const initialCount = this.tracks.size;

            try {
                // Find the last track row
                const trackRows = document.querySelectorAll('[data-testid="tracklist-row"]');
                if (trackRows.length === 0) {
                    return { success: false, message: 'No tracks found in DOM' };
                }

                const lastTrack = trackRows[trackRows.length - 1];

                // Scroll to last track
                lastTrack.scrollIntoView({ behavior: 'smooth', block: 'end' });

                // Wait for new content
                await this._waitForNewContent();

                // Scan for new tracks
                const newTracks = this._scanCurrentTracks();

                const result = {
                    success: true,
                    previousCount: initialCount,
                    currentCount: this.tracks.size,
                    newTracks: this.tracks.size - initialCount,
                    totalLoaded: this.tracks.size
                };

                console.log('Loaded ' + result.newTracks + ' new tracks. Total: ' + result.totalLoaded);
                return result;

            } finally {
                this.isLoading = false;
            }
        }

        // Wait for new content to load
        _waitForNewContent(timeout = 2000) {
            return new Promise((resolve) => {
                const startTime = Date.now();
                const checkInterval = 100;

                const check = () => {
                    const currentRows = document.querySelectorAll('[data-testid="tracklist-row"]').length;

                    if (Date.now() - startTime > timeout) {
                        resolve();
                        return;
                    }

                    // Check again
                    setTimeout(check, checkInterval);
                };

                // Start checking after initial delay for scroll animation
                setTimeout(check, 500);
            });
        }

        // Get all tracks collected so far
        get() {
            const trackArray = Array.from(this.tracks.values())
                .sort((a, b) => (a.position || 999) - (b.position || 999))
                .map((track, index) => ({
                    index: index + 1,
                    position: track.position,
                    title: track.title,
                    artists: track.artists,
                    album: track.album,
                    duration: track.duration,
                    coverUrl: track.coverUrl
                }));

            return {
                tracks: trackArray,
                count: trackArray.length
            };
        }

        // Clear all collected data and rescan
        reset() {
            this.tracks.clear();
            this._scanCurrentTracks();
            const count = this.tracks.size;
            console.log('Reset complete. Found ' + count + ' tracks in current view.');
            return { count };
        }

        // Get info about current state
        getInfo() {
            const visibleTracks = document.querySelectorAll('[data-testid="tracklist-row"]').length;
            return {
                totalCollected: this.tracks.size,
                visibleInDOM: visibleTracks,
                isLoading: this.isLoading,
                firstTrack: this.get().tracks[0],
                lastTrack: this.get().tracks[this.tracks.size - 1]
            };
        }
    }

    // Inject PlaylistController into window
    if (!window.PlaylistController) {
        window.PlaylistController = new SpotifyPlaylistController();
        window.pc = window.PlaylistController; // Short alias
    } else {
        window.PlaylistController.reset();
    }

    // Helper functions for PlaylistController
    window.loadMoreTracks = async function() {
        if (!window.PlaylistController) {
            console.error('PlaylistController not initialized');
            return JSON.stringify({ success: false, message: 'Controller not initialized' });
        }

        // Force reset if stuck for too long
        if (window.PlaylistController.isLoading) {
            if (!window.PlaylistController._loadingStartTime) {
                window.PlaylistController._loadingStartTime = Date.now();
            } else if (Date.now() - window.PlaylistController._loadingStartTime > 5000) {
                console.log('Force resetting stuck loading state after 5 seconds');
                window.PlaylistController.isLoading = false;
                window.PlaylistController._loadingStartTime = null;
            }
        }

        const result = await window.PlaylistController.loadMore();
        if (result.success) {
            window.PlaylistController._loadingStartTime = null;
        }
        return JSON.stringify(result);
    };

    window.getLoadedTracks = function() {
        if (!window.PlaylistController) {
            return JSON.stringify({ tracks: [], count: 0 });
        }

        return JSON.stringify(window.PlaylistController.get());
    };

    window.debugScrollInfo = function() {
        const tracks = document.querySelectorAll('[data-testid="tracklist-row"]');
        const mainContainer = document.querySelector('#main');
        const info = {
            tracksInDOM: tracks.length,
            mainContainerExists: !!mainContainer,
            mainScrollTop: mainContainer ? mainContainer.scrollTop : 0,
            mainScrollHeight: mainContainer ? mainContainer.scrollHeight : 0,
            controllerLoading: window.PlaylistController ? window.PlaylistController.isLoading : 'no controller',
            tracksLoaded: window.PlaylistController ? window.PlaylistController.tracks.size : 0
        };
        console.log('Debug info:', JSON.stringify(info));
        return JSON.stringify(info);
    };

    window.resetLoadingState = function() {
        if (window.PlaylistController) {
            window.PlaylistController.isLoading = false;
            window.PlaylistController._lastLoadingTime = null;
            console.log('Reset loading state');
            return true;
        }
        return false;
    };

    console.log('PlaylistController injected into window');
    return true;
})();