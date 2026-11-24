/* Example Dark Reader — demo content script for the .novaext format.
 *
 * Forces a dark background with light text on example.com pages.
 * Runs as the page, like every NOVA content script: it only touches
 * this page's own DOM, makes no network requests, and reads nothing.
 * Install it from extensions/example-dark-reader.novaext to try the
 * zip-install flow end to end.
 */
(function () {
    'use strict';
    if (window.__novaExampleDarkReader) return;
    window.__novaExampleDarkReader = true;

    function applyDark() {
        var body = document.body;
        if (!body) return;
        body.style.backgroundColor = '#121214';
        body.style.color = '#e8e8e8';
        var links = document.querySelectorAll('a');
        for (var i = 0; i < links.length; i++) {
            links[i].style.color = '#e8a33d';
        }
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', applyDark);
    } else {
        applyDark();
    }
})();
