// Spotify Homepage Functions
// These functions scrape and interact with the Spotify homepage

(function() {
    // Inject getHomepageSections into window
    window.getHomepageSections = function() {
        try {
            const sections = [];
            const seenTitles = new Set(); // Track section titles to avoid duplicates

            // Find all homepage sections with aria-labels
            // We only need sections, not other elements with these aria-labels
            const sectionElements = document.querySelectorAll('section[aria-label]');

            sectionElements.forEach(section => {
                const sectionTitle = section.getAttribute('aria-label') || '';

                // Skip if we've already processed this section title
                if (seenTitles.has(sectionTitle)) {
                    return;
                }

                const items = [];

                // Find all cards within the section
                const cards = section.querySelectorAll('[data-encore-id="card"], .RKstfK7T5nPbsDOYT6sT, [role="listitem"]');

                cards.forEach(card => {
                    // Get the title
                    const titleEl = card.querySelector('[data-encore-id="cardTitle"], .CardTitle__CardText-sc-1h38un4-1, p[title]');
                    const title = titleEl?.textContent?.trim() || titleEl?.getAttribute('title') || '';

                    // Get subtitle (artist, playlist creator, etc.)
                    const subtitleEl = card.querySelector('[data-encore-id="cardSubtitle"], .CardDetails__CardDetailText-sc-1gdonml-1');
                    const subtitle = subtitleEl?.textContent?.trim() || null;

                    // Get image URL
                    const imgEl = card.querySelector('img[data-testid="card-image"], img[data-testid="shortcut-image"]');
                    let imageUrl = imgEl?.src || '';

                    // Upgrade image quality if needed
                    if (imageUrl && imageUrl.includes('ab67616d00004851')) {
                        imageUrl = imageUrl.replace('ab67616d00004851', 'ab67616d00001e02');
                    }

                    // Get href for navigation
                    const linkEl = card.querySelector('a[href]');
                    const href = linkEl?.getAttribute('href') || '';

                    // Determine type from href
                    let type = 'unknown';
                    if (href.includes('/playlist/')) type = 'playlist';
                    else if (href.includes('/album/')) type = 'album';
                    else if (href.includes('/artist/')) type = 'artist';
                    else if (href.includes('/collection')) type = 'collection';
                    else if (href.includes('/user/')) type = 'collection';

                    // Extract ID from href
                    let id = '';
                    if (type === 'playlist' || type === 'album' || type === 'artist') {
                        const parts = href.split('/');
                        id = parts[parts.length - 1] || '';
                    } else {
                        id = href;
                    }

                    // Check if currently playing (for pause button)
                    const playButton = card.querySelector('[data-testid="play-button"]');
                    const isPlaying = playButton?.getAttribute('aria-label')?.includes('pausieren') ||
                                     playButton?.getAttribute('aria-label')?.includes('pause') ||
                                     false;

                    if (title && imageUrl) {
                        items.push({
                            id: id,
                            title: title,
                            subtitle: subtitle,
                            imageUrl: imageUrl,
                            href: href,
                            type: type,
                            isPlaying: isPlaying
                        });
                    }
                });

                if (items.length > 0) {
                    seenTitles.add(sectionTitle); // Mark this title as seen
                    sections.push({
                        title: sectionTitle,
                        sectionId: section.id || null,
                        items: items
                    });
                }
            });

            return JSON.stringify(sections);
        } catch (e) {
            console.error('Error getting homepage sections:', e);
            return JSON.stringify([]);
        }
    };

    // Function to play/pause a homepage item
    window.playHomepageItem = function(itemId, itemType) {
        try {
            console.log('Attempting to play item:', itemId, 'type:', itemType);

            // Method 1: Find by href in link elements
            const allLinks = document.querySelectorAll('a[href*="' + itemId + '"]');
            console.log('Found', allLinks.length, 'links with itemId');

            for (const link of allLinks) {
                // Look for play button in the same card/container
                const card = link.closest('[data-encore-id="card"], [role="listitem"], .RKstfK7T5nPbsDOYT6sT, article');
                if (card) {
                    const playBtn = card.querySelector('[data-testid="play-button"], button[aria-label*="Play"], button[aria-label*="play"], button[aria-label*="Wiedergabe"], button[aria-label*="wiedergabe"]');
                    if (playBtn) {
                        console.log('Found play button in card, clicking...');
                        playBtn.click();
                        return true;
                    }
                }
            }

            // Method 2: Find by looking at all cards
            const cards = document.querySelectorAll('[data-encore-id="card"], [role="listitem"], .RKstfK7T5nPbsDOYT6sT, article');
            console.log('Checking', cards.length, 'cards');

            for (const card of cards) {
                const linkEl = card.querySelector('a[href]');
                const href = linkEl?.getAttribute('href') || '';

                if (href.includes(itemId) || (itemType === 'collection' && href.includes('/collection'))) {
                    // Try multiple play button selectors
                    const playBtn = card.querySelector('[data-testid="play-button"], button[aria-label*="Play"], button[aria-label*="play"], button[aria-label*="Wiedergabe"], button[aria-label*="wiedergabe"]');
                    if (playBtn) {
                        console.log('Found play button via card search, clicking...');
                        playBtn.click();
                        return true;
                    }

                    // Try hovering first to reveal play button
                    const event = new MouseEvent('mouseenter', { bubbles: true });
                    card.dispatchEvent(event);

                    setTimeout(() => {
                        const playBtnAfterHover = card.querySelector('[data-testid="play-button"], button[aria-label*="Play"], button[aria-label*="play"], button[aria-label*="Wiedergabe"], button[aria-label*="wiedergabe"]');
                        if (playBtnAfterHover) {
                            console.log('Found play button after hover, clicking...');
                            playBtnAfterHover.click();
                        }
                    }, 100);

                    return true; // Return true even if we need to wait for hover
                }
            }

            // Method 3: If it's a collection (Liked Songs), try the specific selector
            if (itemType === 'collection' || itemId.includes('/collection')) {
                const likedSongsCard = document.querySelector('a[href="/collection/tracks"]')?.closest('[data-encore-id="card"], article, [role="listitem"]');
                if (likedSongsCard) {
                    const playBtn = likedSongsCard.querySelector('button');
                    if (playBtn) {
                        console.log('Found collection play button, clicking...');
                        playBtn.click();
                        return true;
                    }
                }
            }

            console.log('Could not find play button for item:', itemId);
            return false;
        } catch (e) {
            console.error('Error playing homepage item:', e);
            return false;
        }
    };

    // Function to navigate to a homepage item using SPA navigation
    window.navigateToHomepageItem = function(href) {
        try {
            const currentUrl = window.location.href;
            const targetUrl = 'https://open.spotify.com' + href;

            // Check if we're already on this page
            if (currentUrl === targetUrl) {
                console.log('Already on target page');
                return true;
            }

            // Use Spotify's SPA navigation (same pattern as spotify-actions.js)
            const history = window.history;

            // Push state without reload
            history.pushState({}, '', targetUrl);

            // Trigger a popstate event to let Spotify's router handle it
            const popstateEvent = new PopStateEvent('popstate', { state: {} });
            window.dispatchEvent(popstateEvent);

            console.log('Triggered SPA navigation to: ' + targetUrl);
            return true;
        } catch (err) {
            console.error('SPA navigation failed:', err);

            // Fallback: try clicking the link
            const linkEl = document.querySelector(`a[href="${href}"]`);
            if (linkEl) {
                linkEl.click();
                return true;
            }

            return false;
        }
    };

    console.log('Spotify homepage functions injected into window');
    return true;
})();