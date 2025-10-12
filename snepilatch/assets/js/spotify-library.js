// Spotify Library Controller
// Manages the library items list with virtual scrolling support

(function() {
    // Library Controller - manages library items collection with scroll-based loading
    class LibraryController {
        constructor() {
            this.items = new Map(); // Use Map to avoid duplicates by ID
            this.isLoading = false;
            this.lastScrollTime = 0;
            this.stuckCounter = 0;
            this.lastItemCount = 0;
        }

        // Get library items from the current viewport
        getVisibleItems() {
            try {
                const items = [];

                // Find all library item rows - try multiple selectors
                let itemRows = document.querySelectorAll('.YourLibraryX [data-encore-id="listRow"]');

                // Debug: log what we found
                console.log(`[LibraryController] Found ${itemRows.length} items with .YourLibraryX [data-encore-id="listRow"]`);

                // If nothing found, try alternative selector
                if (itemRows.length === 0) {
                    itemRows = document.querySelectorAll('[data-testid="rootlist-item"]');
                    console.log(`[LibraryController] Trying alternative: found ${itemRows.length} items with [data-testid="rootlist-item"]`);
                }

                console.log(`[LibraryController] About to iterate over ${itemRows.length} rows, type: ${typeof itemRows}, has forEach: ${typeof itemRows.forEach}`);

                itemRows.forEach((row, index) => {
                    try {
                        console.log(`[LibraryController] Processing row ${index + 1}/${itemRows.length}`);

                        // Get the ID from aria-labelledby attribute
                        const labelId = row.getAttribute('aria-labelledby');
                        console.log(`[LibraryController] Row ${index + 1} aria-labelledby:`, labelId);
                        if (!labelId) {
                            console.log('[LibraryController] Row has no aria-labelledby');
                            return;
                        }

                        // Extract Spotify URI from the label ID
                        // Format: "listrow-title-spotify:playlist:xxx listrow-subtitle-spotify:playlist:xxx"
                        // We need to extract just the first ID
                        const match = labelId.match(/listrow-title-(spotify:[^\s]+)/);
                        if (!match) {
                            console.log('[LibraryController] No match for labelId:', labelId);
                            return;
                        }

                        const id = match[1];
                        console.log('[LibraryController] Parsed ID:', id);

                        // Skip if we already have this item
                        if (this.items.has(id)) return;

                        // Get title
                        const titleElement = row.querySelector('[data-encore-id="listRowTitle"]');
                        const title = titleElement?.textContent?.trim() || '';
                        console.log('[LibraryController] Title:', title);

                        // Get subtitle (simplified selector without class name)
                        const subtitleElement = row.querySelector('[data-encore-id="listRowSubtitle"]');
                        const subtitle = subtitleElement?.textContent?.trim() || '';
                        console.log('[LibraryController] Subtitle:', subtitle);

                        // Get image URL
                        const img = row.querySelector('img[data-testid="entity-image"]');
                        let imageUrl = img?.src || '';

                        // Upgrade image quality if possible
                        if (imageUrl && imageUrl.includes('i.scdn.co/image/')) {
                            // Upgrade to higher quality
                            imageUrl = imageUrl.replace('/ab67616d000011eb', '/ab67616d00001e02')
                                               .replace('/ab6761610000101f', '/ab67616100001e02');
                        }

                        // Determine type from ID
                        let type = 'unknown';
                        if (id.includes(':playlist:')) type = 'playlist';
                        else if (id.includes(':artist:')) type = 'artist';
                        else if (id.includes(':album:')) type = 'album';
                        else if (id.includes(':collection:')) type = 'collection';

                        // Check if pinned (has pin icon)
                        const isPinned = row.querySelector('svg[data-encore-id="icon"] title')?.textContent?.includes('ngeheftet') ||
                                       row.querySelector('svg[data-encore-id="icon"] title')?.textContent?.includes('Pinned') ||
                                       false;

                        // Extract owner from subtitle if it's a playlist
                        let owner = null;
                        if (type === 'playlist' && subtitle.includes('•')) {
                            const parts = subtitle.split('•');
                            if (parts.length > 1) {
                                owner = parts[1].trim();
                            }
                        }

                        const item = {
                            id: id,
                            title: title,
                            subtitle: subtitle,
                            imageUrl: imageUrl,
                            type: type,
                            isPinned: isPinned,
                            owner: owner
                        };

                        console.log('[LibraryController] Created item:', item);
                        this.items.set(id, item);
                        items.push(item);

                    } catch (e) {
                        console.error('Error parsing library item:', e);
                    }
                });

                console.log('[LibraryController] Returning', items.length, 'new items, total in Map:', this.items.size);
                return items;

            } catch (e) {
                console.error('Error getting visible library items:', e);
                return [];
            }
        }

        // Load more items by scrolling
        async loadMore() {
            if (this.isLoading) {
                return { success: false, message: 'Already loading' };
            }

            try {
                this.isLoading = true;
                const initialCount = this.items.size;

                // Get current visible items first
                this.getVisibleItems();

                // Find the scrollable container for the library
                const scrollContainer = document.querySelector('.YourLibraryX .IfMCntz4HO4NOjoFdO2v [data-overlayscrollbars-viewport]');

                if (!scrollContainer) {
                    this.isLoading = false;
                    return { success: false, message: 'Scroll container not found' };
                }

                // Scroll down to load more
                const scrollAmount = scrollContainer.scrollHeight * 0.5; // Scroll 50% down
                scrollContainer.scrollTop += scrollAmount;

                // Wait for new items to load
                await new Promise(resolve => setTimeout(resolve, 300));

                // Get newly visible items
                const newItems = this.getVisibleItems();
                const newCount = this.items.size;

                // Check if we're stuck (not loading new items)
                if (newCount === this.lastItemCount) {
                    this.stuckCounter++;
                    if (this.stuckCounter >= 5) {
                        // Try scrolling to bottom
                        scrollContainer.scrollTop = scrollContainer.scrollHeight;
                        await new Promise(resolve => setTimeout(resolve, 300));
                        this.getVisibleItems();
                        this.stuckCounter = 0;
                    }
                } else {
                    this.stuckCounter = 0;
                }

                this.lastItemCount = newCount;
                this.lastScrollTime = Date.now();
                this.isLoading = false;

                return {
                    success: true,
                    newItems: newItems.length,
                    totalItems: this.items.size,
                    itemsAdded: newCount - initialCount
                };

            } catch (e) {
                console.error('Error loading more library items:', e);
                this.isLoading = false;
                return { success: false, error: e.message };
            }
        }

        // Get all collected items
        get() {
            return Array.from(this.items.values());
        }

        // Reset the collection
        reset() {
            this.items.clear();
            this.isLoading = false;
            this.stuckCounter = 0;
            this.lastItemCount = 0;
            return true;
        }

        // Get info about the collection
        getInfo() {
            return {
                totalItems: this.items.size,
                isLoading: this.isLoading,
                lastScrollTime: this.lastScrollTime
            };
        }

        // Scroll to a specific index in the library
        scrollToIndex(index) {
            try {
                const scrollContainer = document.querySelector('.YourLibraryX .IfMCntz4HO4NOjoFdO2v [data-overlayscrollbars-viewport]');
                if (!scrollContainer) return false;

                // Approximate item height (56px based on HTML)
                const itemHeight = 56;
                const targetScroll = index * itemHeight;

                scrollContainer.scrollTop = targetScroll;

                // Wait a bit and get visible items
                setTimeout(() => {
                    this.getVisibleItems();
                }, 100);

                return true;
            } catch (e) {
                console.error('Error scrolling to index:', e);
                return false;
            }
        }
    }

    // Helper function to check if sidebar is collapsed
    function isSidebarCollapsed() {
        const sidebar = document.querySelector('.YourLibraryX');
        if (!sidebar) return true;

        // Check if sidebar has minimal width (collapsed state)
        const width = sidebar.offsetWidth;
        console.log('[LibraryDebug] Sidebar width:', width);

        // Spotify's collapsed sidebar is typically 72px wide
        return width < 100;
    }

    // Helper function to expand the sidebar
    function expandSidebar() {
        try {
            // Look for the expand button with "Bibliothek öffnen" or "Open library"
            const expandButton =
                document.querySelector('.YourLibraryX button[aria-label="Bibliothek öffnen"]') ||
                document.querySelector('.YourLibraryX button[aria-label="Open library"]') ||
                document.querySelector('.YourLibraryX button[aria-label*="öffnen"]') ||
                document.querySelector('.YourLibraryX button[aria-label*="Expand"]');

            if (expandButton) {
                console.log('[LibraryDebug] Found expand button, clicking...', expandButton.getAttribute('aria-label'));
                expandButton.click();
                return true;
            }

            console.log('[LibraryDebug] No expand button found');
            return false;
        } catch (e) {
            console.error('[LibraryDebug] Error expanding sidebar:', e);
            return false;
        }
    }

    // Create global instance
    if (!window.LibraryController) {
        window.LibraryController = new LibraryController();
    }

    // Function to ensure sidebar is expanded before scraping
    window.ensureSidebarExpanded = async function() {
        if (isSidebarCollapsed()) {
            console.log('[LibraryDebug] Sidebar is collapsed, expanding...');
            const expanded = expandSidebar();

            if (expanded) {
                // Wait longer for sidebar to expand and content to fully load
                await new Promise(resolve => setTimeout(resolve, 2000));

                // Check if it actually expanded
                const newWidth = document.querySelector('.YourLibraryX')?.offsetWidth || 0;
                console.log('[LibraryDebug] Width after expansion:', newWidth);

                return { expanded: true, wasCollapsed: true, newWidth: newWidth };
            }

            return { expanded: false, wasCollapsed: true, error: 'Could not find expand button' };
        }

        return { expanded: true, wasCollapsed: false };
    };

    // Debug function to check DOM structure
    window.debugLibraryDOM = function() {
        const sidebar = document.querySelector('.YourLibraryX');
        console.log('[LibraryDebug] Sidebar found:', !!sidebar);

        if (sidebar) {
            // Check if sidebar is expanded or collapsed
            const sidebarHTML = sidebar.outerHTML.substring(0, 300);
            console.log('[LibraryDebug] Sidebar HTML snippet:', sidebarHTML);

            // Try different selectors
            const listRows = sidebar.querySelectorAll('[data-encore-id="listRow"]');
            const rootItems = sidebar.querySelectorAll('[data-testid="rootlist-item"]');
            const liElements = sidebar.querySelectorAll('li');
            const links = sidebar.querySelectorAll('a');
            const allDivs = sidebar.querySelectorAll('div[role="button"]');

            console.log('[LibraryDebug] Items in sidebar:');
            console.log('  - [data-encore-id="listRow"]:', listRows.length);
            console.log('  - [data-testid="rootlist-item"]:', rootItems.length);
            console.log('  - li elements:', liElements.length);
            console.log('  - a (links):', links.length);
            console.log('  - div[role="button"]:', allDivs.length);

            // Check for playlist/artist links
            const playlistLinks = sidebar.querySelectorAll('a[href*="/playlist/"]');
            const artistLinks = sidebar.querySelectorAll('a[href*="/artist/"]');
            const albumLinks = sidebar.querySelectorAll('a[href*="/album/"]');

            console.log('[LibraryDebug] Content links:');
            console.log('  - Playlist links:', playlistLinks.length);
            console.log('  - Artist links:', artistLinks.length);
            console.log('  - Album links:', albumLinks.length);

            // Sample first item if it exists
            if (liElements.length > 0) {
                console.log('[LibraryDebug] First li element:', liElements[0].outerHTML.substring(0, 500));
            } else if (links.length > 0) {
                console.log('[LibraryDebug] First link:', links[0].outerHTML.substring(0, 300));
            }

            return {
                sidebarExists: true,
                listRowCount: listRows.length,
                liCount: liElements.length,
                linkCount: links.length,
                playlistCount: playlistLinks.length
            };
        }

        return {
            sidebarExists: false,
            error: 'Sidebar not found'
        };
    };

    // Inject library functions into window
    window.getLibraryItems = function() {
        try {
            const items = window.LibraryController.get();
            console.log('[getLibraryItems] Returning', items.length, 'items');
            console.log('[getLibraryItems] First item:', items[0]);
            return JSON.stringify(items);
        } catch (e) {
            console.error('Error getting library items:', e);
            return JSON.stringify([]);
        }
    };

    window.loadMoreLibraryItems = function() {
        return window.LibraryController.loadMore();
    };

    window.resetLibraryItems = function() {
        return window.LibraryController.reset();
    };

    window.getLibraryInfo = function() {
        return window.LibraryController.getInfo();
    };

    window.scrollLibraryToIndex = function(index) {
        return window.LibraryController.scrollToIndex(index);
    };

    console.log('Spotify library functions injected into window');
    return true;
})();
