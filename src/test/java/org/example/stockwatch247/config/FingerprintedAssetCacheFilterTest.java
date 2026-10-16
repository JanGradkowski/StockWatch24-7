package org.example.stockwatch247.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FingerprintedAssetCacheFilterTest {
    private static final String HASH = "0123456789abcdef0123456789abcdef";

    @Test
    void contentVersionedAssetsIncludingDottedVendorPathsAreLongLived() {
        assertThat(FingerprintedAssetCacheFilter.fingerprinted("/css/style-" + HASH + ".css")).isTrue();
        assertThat(FingerprintedAssetCacheFilter.fingerprinted(
                "/js/vendor/lightweight-charts/5.2.1/lightweight-charts.standalone.production-" + HASH + ".js")).isTrue();
    }

    @Test
    void unversionedOrSuspiciousPathsKeepTheirPolicy() {
        assertThat(FingerprintedAssetCacheFilter.fingerprinted("/js/stock-workspace.js")).isFalse();
        assertThat(FingerprintedAssetCacheFilter.fingerprinted("/images/about/analysis-hero.webp")).isFalse();
        assertThat(FingerprintedAssetCacheFilter.fingerprinted("/js/../css/style-" + HASH + ".css")).isFalse();
    }
}
