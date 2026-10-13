(() => {
    'use strict';

    // 18×18 line icons for the drawing toolbar. Each icon is a list of SVG path strings drawn with
    // the current text colour; built with createElementNS so no markup is parsed from strings.
    const ICONS = {
        cursor: ['M4 2l10 7-4.4 1.1 2.6 5-2 1-2.6-5L4 14z'],
        eraser: ['M3 12l6-6 6 6-3 3H6z', 'M7 15h9'],
        trendLine: ['M3 15L15 3', 'M3 15m-1.5 0a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0 -3 0', 'M15 3m-1.5 0a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0 -3 0'],
        ray: ['M3 15L17 1', 'M3 15m-1.5 0a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0 -3 0'],
        infoLine: ['M3 15L11 7', 'M10 2h7v5h-7z'],
        extendedLine: ['M1 17L17 1', 'M6 12m-1.5 0a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0 -3 0', 'M12 6m-1.5 0a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0 -3 0'],
        trendAngle: ['M3 15L15 4', 'M3 15h13', 'M10 15a7 7 0 0 0-2-5'],
        horizontalLine: ['M1 9h16', 'M9 9m-1.5 0a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0 -3 0'],
        horizontalRay: ['M4 9h13', 'M4 9m-1.5 0a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0 -3 0'],
        verticalLine: ['M9 1v16', 'M9 9m-1.5 0a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0 -3 0'],
        crossLine: ['M1 9h16', 'M9 1v16'],
        arrowLine: ['M3 15L14 4', 'M8 4h6v6'],
        parallelChannel: ['M2 12L12 2', 'M6 16L16 6', 'M4 14L14 4'],
        regressionTrend: ['M2 8L16 2', 'M2 11L16 5', 'M2 14L16 8'],
        flatTopBottom: ['M2 14L16 8', 'M2 4h14'],
        disjointChannel: ['M2 12L9 5', 'M9 16L16 9'],
        pitchfork: ['M2 9h5', 'M7 9l9-5', 'M7 9h9', 'M7 9l9 5', 'M7 4v10'],
        schiffPitchfork: ['M2 12l5-3', 'M7 9l9-5', 'M7 9h9', 'M7 9l9 5', 'M7 4v10'],
        modifiedSchiffPitchfork: ['M2 14l5-5', 'M7 9l9-5', 'M7 9h9', 'M7 9l9 5', 'M7 4v10'],
        insidePitchfork: ['M4 9h3', 'M7 9l9-4', 'M7 9h9', 'M7 9l9 4', 'M7 5v8'],
        pitchfan: ['M2 15L16 3', 'M2 15L16 7', 'M2 15L16 11', 'M2 15L16 15', 'M16 3v12'],
        fibRetracement: ['M2 3h14', 'M2 7h14', 'M2 11h14', 'M2 15h14', 'M4 15L14 3'],
        fibExtension: ['M2 15L6 5L9 11', 'M10 3h6', 'M10 7h6', 'M10 11h6', 'M10 15h6'],
        fibChannel: ['M2 10L10 2', 'M4 14L14 4', 'M7 17L17 7'],
        fibTimeZone: ['M3 2v14', 'M5 2v14', 'M8 2v14', 'M13 2v14'],
        trendBasedFibTime: ['M2 14L5 6L8 12', 'M10 2v14', 'M13 2v14', 'M16 2v14'],
        fibSpeedResistanceFan: ['M2 16L16 2', 'M2 16L16 7', 'M2 16L16 12', 'M2 16L7 2', 'M2 16L12 2'],
        fibSpeedResistanceArcs: ['M3 15a12 12 0 0 1 12-12', 'M7 15a8 8 0 0 1 8-8', 'M11 15a4 4 0 0 1 4-4'],
        fibCircles: ['M9 9m-2 0a2 2 0 1 0 4 0a2 2 0 1 0 -4 0', 'M9 9m-4.5 0a4.5 4.5 0 1 0 9 0a4.5 4.5 0 1 0 -9 0',
            'M9 9m-7.5 0a7.5 7.5 0 1 0 15 0a7.5 7.5 0 1 0 -15 0'],
        fibSpiral: ['M9 9a1.5 1.5 0 0 1 1.5 1.5a3 3 0 0 1-3 3a5 5 0 0 1-5-5a7.5 7.5 0 0 1 7.5-7.5'],
        fibWedge: ['M2 15L16 4', 'M2 15L16 14', 'M10 9a8 8 0 0 1 0 5', 'M14 7a12 12 0 0 1 0 7'],
        gannBox: ['M2 2h14v14H2z', 'M2 9h14', 'M9 2v14', 'M2 2l14 14'],
        gannSquareFixed: ['M2 2h14v14H2z', 'M2 16L16 2', 'M2 16L16 9', 'M2 16L9 2'],
        gannSquare: ['M2 2h14v14H2z', 'M2 16L16 2', 'M2 6h14', 'M2 11h14', 'M6 2v14', 'M11 2v14'],
        gannFan: ['M2 16L16 2', 'M2 16L16 9', 'M2 16L9 2', 'M2 16L16 13', 'M2 16L13 2'],
        xabcd: ['M1 14L5 4L9 11L13 6L17 15', 'M1 14L9 11', 'M9 11L17 15'],
        cypher: ['M1 13L5 5L8 10L13 2L17 13'],
        abcd: ['M2 14L6 4L11 10L16 2'],
        threeDrives: ['M1 15L4 9L6 11L10 5L12 8L16 2'],
        headAndShoulders: ['M1 14L4 8L6 12L9 3L12 12L14 8L17 14', 'M1 12h16'],
        trianglePattern: ['M2 3L16 9', 'M2 15L16 9', 'M2 3L6 13L10 6L13 11'],
        elliottImpulse: ['M1 15L4 9L6 12L11 3L13 8L17 2'],
        elliottCorrection: ['M2 3L7 13L11 7L16 15'],
        elliottTriangle: ['M1 3L5 14L8 5L11 12L13 7L15 10'],
        elliottDoubleCombo: ['M2 3L6 11L10 7L16 15'],
        elliottTripleCombo: ['M1 3L4 10L7 6L10 13L13 9L17 16'],
        cyclicLines: ['M3 2v14', 'M8 2v14', 'M13 2v14'],
        timeCycles: ['M1 14a4 4 0 0 1 8 0', 'M9 14a4 4 0 0 1 8 0'],
        sineLine: ['M1 9c2-7 4-7 6 0s4 7 6 0 3-5 4-4'],
        longPosition: ['M2 3h14v6H2z', 'M2 9h14v6H2z', 'M5 6l2-2 2 2'],
        shortPosition: ['M2 3h14v6H2z', 'M2 9h14v6H2z', 'M5 12l2 2 2-2'],
        priceRange: ['M9 3v12', 'M6 6l3-3 3 3', 'M6 12l3 3 3-3', 'M3 2h12', 'M3 16h12'],
        dateRange: ['M3 9h12', 'M6 6l-3 3 3 3', 'M12 6l3 3-3 3', 'M2 3v12', 'M16 3v12'],
        datePriceRange: ['M2 2h14v14H2z', 'M9 4v10', 'M4 9h10'],
        forecast: ['M2 13h5', 'M7 13L15 4', 'M11 4h4v4'],
        barsPattern: ['M3 4v9', 'M2 6h2', 'M3 11h2', 'M8 2v9', 'M7 4h2', 'M8 9h2', 'M13 6v10', 'M12 8h2', 'M13 14h2'],
        ghostFeed: ['M3 5v8', 'M2 7h2v4H2z', 'M8 3v8', 'M7 5h2v4H7z', 'M13 7v8', 'M12 9h2v4h-2z'],
        projection: ['M2 14L8 6L10 10L16 2'],
        anchoredVwap: ['M3 2v14', 'M3 12c3-2 5 0 7-3s4-3 6-4'],
        fixedRangeVolumeProfile: ['M2 2v14', 'M16 2v14', 'M2 5h8', 'M2 8h11', 'M2 11h6', 'M2 14h3'],
        anchoredVolumeProfile: ['M2 2v14', 'M2 5h6', 'M2 8h10', 'M2 11h7', 'M2 14h4'],
        rectangle: ['M2 4h14v10H2z'],
        rotatedRectangle: ['M5 2l11 5-4 9L1 11z'],
        circle: ['M9 9m-7 0a7 7 0 1 0 14 0a7 7 0 1 0 -14 0'],
        ellipse: ['M9 9m-8 0a8 5 0 1 0 16 0a8 5 0 1 0 -16 0'],
        triangle: ['M9 2L16 15H2z'],
        arc: ['M2 14a7 7 0 0 1 14 0'],
        curve: ['M2 15c2-12 12-12 14 0'],
        doubleCurve: ['M1 9c3-8 5-8 8 0s5 8 8 0'],
        path: ['M2 15L6 5L11 11L16 3', 'M13 3h3v3'],
        polyline: ['M2 15L5 4L12 6L16 14z'],
        brush: ['M2 15c3-1 3-5 6-6s5 1 8-6'],
        highlighter: ['M3 14L12 5l3 3-9 9H3z', 'M10 7l3 3'],
        arrowMarker: ['M2 9h9V5l6 4-6 4V9'],
        arrowMarkUp: ['M9 2l6 7h-4v7H7V9H3z'],
        arrowMarkDown: ['M9 16l6-7h-4V2H7v7H3z'],
        text: ['M3 4V2h12v2', 'M9 2v14', 'M6 16h6'],
        anchoredText: ['M3 4V2h10v2', 'M8 2v12', 'M5 14h6', 'M15 12v5', 'M13 14h4'],
        note: ['M3 2h12v10l-4 4H3z', 'M11 16v-4h4'],
        anchoredNote: ['M3 2h10v9l-3 3H3z', 'M10 14v-3h3', 'M15 12v5', 'M13 14h4'],
        callout: ['M2 2h14v9H8l-4 4v-4H2z'],
        comment: ['M3 3h12a1 1 0 0 1 1 1v7a1 1 0 0 1-1 1H9l-4 4v-4H3a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z'],
        priceLabel: ['M2 6h9l5 3-5 3H2z'],
        priceNote: ['M2 15L8 9', 'M8 4h8v6H8z'],
        signpost: ['M9 16V6', 'M4 2h10v4H4z'],
        flagMark: ['M4 16V2', 'M4 3h10l-3 3 3 3H4'],
        table: ['M2 3h14v12H2z', 'M2 7h14', 'M2 11h14', 'M7 3v12', 'M12 3v12'],
        emoji: ['M9 1a8 8 0 1 0 0 16a8 8 0 1 0 0-16z', 'M6.5 7v.5', 'M11.5 7v.5', 'M5.5 11a4 4 0 0 0 7 0'],
        measure: ['M2 12L12 2l4 4L6 16z', 'M5 9l2 2', 'M8 6l2 2', 'M11 3l1 1'],
        zoom: ['M7.5 2a5.5 5.5 0 1 0 0 11a5.5 5.5 0 1 0 0-11z', 'M11.5 11.5l5 5', 'M5 7.5h5', 'M7.5 5v5'],
        magnet: ['M4 2v7a5 5 0 0 0 10 0V2', 'M4 5h3', 'M11 5h3'],
        stayInMode: ['M3 15l2-6 8-8 4 4-8 8z', 'M12 2l4 4', 'M2 17h6'],
        lock: ['M5 8V5a4 4 0 0 1 8 0v3', 'M3 8h12v8H3z'],
        unlock: ['M5 8V5a4 4 0 0 1 7.6-1.6', 'M3 8h12v8H3z'],
        eye: ['M1 9s3-6 8-6 8 6 8 6-3 6-8 6-8-6-8-6z', 'M9 7a2 2 0 1 0 0 4a2 2 0 1 0 0-4z'],
        eyeOff: ['M1 9s3-6 8-6 8 6 8 6-3 6-8 6-8-6-8-6z', 'M2 2l14 14'],
        trash: ['M3 5h12', 'M7 5V3h4v2', 'M5 5l1 11h6l1-11'],
        tree: ['M2 3h4v4H2z', 'M2 11h4v4H2z', 'M8 5h8', 'M8 13h8', 'M4 7v4'],
        undo: ['M6 3L2 7l4 4', 'M2 7h9a5 5 0 0 1 0 10H8'],
        redo: ['M12 3l4 4-4 4', 'M16 7H7a5 5 0 0 0 0 10h3'],
        star: ['M9 2l2.1 4.6 5 .5-3.8 3.4 1.1 5-4.4-2.6-4.4 2.6 1.1-5L1.9 7.1l5-.5z'],
        gear: ['M9 6a3 3 0 1 0 0 6a3 3 0 1 0 0-6z', 'M9 1v2', 'M9 15v2', 'M1 9h2', 'M15 9h2', 'M3.3 3.3l1.4 1.4', 'M13.3 13.3l1.4 1.4',
            'M3.3 14.7l1.4-1.4', 'M13.3 4.7l1.4-1.4'],
        clone: ['M6 6h10v10H6z', 'M2 12V2h10'],
        more: ['M4 9h.01', 'M9 9h.01', 'M14 9h.01'],
        chevron: ['M6 4l5 5-5 5'],
        close: ['M4 4l10 10', 'M14 4L4 14'],
        alert: ['M9 2a5 5 0 0 1 5 5v4l2 3H2l2-3V7a5 5 0 0 1 5-5z', 'M7 16h4'],
        demoTrade: ['M2 13l4-4 3 3 7-7', 'M11 5h5v5'],
        camera: ['M2 6h3l1.5-2h5L13 6h3v9H2z', 'M9 8a2.5 2.5 0 1 0 0 5a2.5 2.5 0 1 0 0-5z'],
        fullscreen: ['M2 6V2h4', 'M12 2h4v4', 'M16 12v4h-4', 'M6 16H2v-4'],
        dataWindow: ['M3 3h12v12H3z', 'M3 7h12', 'M7 7v8'],
        favorites: ['M9 2l2.1 4.6 5 .5-3.8 3.4 1.1 5-4.4-2.6-4.4 2.6 1.1-5L1.9 7.1l5-.5z', 'M2 17h14']
    };

    const FILLED = new Set(['cursor', 'arrowMarker', 'arrowMarkUp', 'arrowMarkDown', 'flagMark']);
    const SVG = 'http://www.w3.org/2000/svg';

    function icon(name, size = 18) {
        const svg = document.createElementNS(SVG, 'svg');
        svg.setAttribute('viewBox', '0 0 18 18');
        svg.setAttribute('width', String(size));
        svg.setAttribute('height', String(size));
        svg.setAttribute('aria-hidden', 'true');
        svg.setAttribute('focusable', 'false');
        svg.classList.add('draw-icon');
        (ICONS[name] || ICONS.trendLine).forEach(data => {
            const path = document.createElementNS(SVG, 'path');
            path.setAttribute('d', data);
            path.setAttribute('fill', FILLED.has(name) && data === (ICONS[name] || [])[0] ? 'currentColor' : 'none');
            path.setAttribute('stroke', 'currentColor');
            path.setAttribute('stroke-width', name === 'more' ? '2.4' : '1.4');
            path.setAttribute('stroke-linecap', 'round');
            path.setAttribute('stroke-linejoin', 'round');
            svg.append(path);
        });
        return svg;
    }

    window.StockWatchDrawingIcons = Object.freeze({icon, has: name => Object.prototype.hasOwnProperty.call(ICONS, name)});
})();
