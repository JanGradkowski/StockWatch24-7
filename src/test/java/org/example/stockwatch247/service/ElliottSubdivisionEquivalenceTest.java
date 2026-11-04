package org.example.stockwatch247.service;

import org.example.stockwatch247.model.EnrichedCandle;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sub-wave search on 10,000 seeded random legs, in every search mode, against fingerprints of what the
 * implementation found before it was made faster (step 7 of docs/five-second-checks-plan.md). A difference names
 * the block of 100 legs it is in. The regular run checks the first 1,000 legs; {@code -DsubdivisionEquivalence=all}
 * checks all 10,000, and {@code -DsubdivisionEquivalence=record} rewrites the fingerprints from the current code.
 */
class ElliottSubdivisionEquivalenceTest {
    private static final int LEGS = 10_000;
    private static final int BLOCK = 100;
    private static final Path FINGERPRINTS = Path.of("src", "test", "resources", "elliott-wave", "subdivision-fingerprints.txt");
    private static final String[] LABELS = {"I", "II", "III", "IV", "V", "A", "B", "C"};

    private record Mode(boolean bestPerLabel, String bestKind) { }

    private static final List<Mode> MODES = List.of(new Mode(false, null), new Mode(true, null), new Mode(true, "ANY"),
            new Mode(true, "MOTIVE"), new Mode(true, "CORRECTIVE"), new Mode(true, "CATEGORIES"));

    private record Leg(List<EnrichedCandle> candles, String label, double start, double end) { }

    @Test
    void theSubWaveSearchFindsExactlyWhatItFoundBefore() throws Exception {
        String setting = System.getProperty("subdivisionEquivalence", "");
        int legs = setting.equals("all") || setting.equals("record") ? LEGS : 1_000;
        var detector = new ElliottWaveDetectionService();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        List<String> blocks = new ArrayList<>();
        int found = 0;
        for (int index = 0; index < legs; index++) {
            Leg leg = leg(index);
            for (Mode mode : MODES) {
                var subdivisions = detector.findStrictSubdivisions(leg.candles(), leg.label(), leg.start(), leg.end(),
                        mode.bestPerLabel(), mode.bestKind());
                found += subdivisions.size();
                digest.update((index + " " + mode + " " + subdivisions + "\n").getBytes(StandardCharsets.UTF_8));
            }
            if ((index + 1) % BLOCK == 0) blocks.add(HexFormat.of().formatHex(digest.digest()));
        }
        // Most random legs have no valid count; enough of them must, or the comparison would prove little.
        assertThat(found).as("sub-waves found").isGreaterThan(legs / 4);
        if (setting.equals("record")) {
            Files.createDirectories(FINGERPRINTS.getParent());
            Files.write(FINGERPRINTS, blocks);
            return;
        }
        List<String> expected = Files.readAllLines(FINGERPRINTS);
        for (int block = 0; block < blocks.size(); block++) {
            assertThat(blocks.get(block)).as("legs %d to %d", block * BLOCK, (block + 1) * BLOCK - 1).isEqualTo(expected.get(block));
        }
    }

    /** A random walk of 12 to 250 candles, drifting the leg's way, with its label and boundary prices. */
    private static Leg leg(int index) {
        Random random = new Random(index * 7_919L + 17);
        int size = 12 + random.nextInt(239);
        boolean rising = random.nextBoolean();
        double volatility = 0.004 + random.nextDouble() * 0.03;
        double drift = (rising ? 1 : -1) * volatility * (0.05 + random.nextDouble() * 0.4);
        List<EnrichedCandle> candles = new ArrayList<>(size);
        double close = 100;
        double atr = Double.NaN;
        for (int day = 0; day < size; day++) {
            double open = close;
            close = Math.max(1, open * (1 + drift + volatility * random.nextGaussian()));
            double high = Math.max(open, close) * (1 + volatility * random.nextDouble() * 0.6);
            double low = Math.min(open, close) * (1 - volatility * random.nextDouble() * 0.6);
            double range = high - low;
            atr = Double.isNaN(atr) ? range : (atr * 13 + range) / 14;
            candles.add(new EnrichedCandle(1_700_000_000L + day * 86_400L, open, high, low, close, 1_000_000, 1_000_000,
                    50, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN, Double.NaN, atr, Double.NaN, Double.NaN, Double.NaN, Double.NaN));
        }
        double start = rising ? candles.getFirst().low() : candles.getFirst().high();
        double end = rising ? candles.stream().mapToDouble(EnrichedCandle::high).max().orElseThrow()
                : candles.stream().mapToDouble(EnrichedCandle::low).min().orElseThrow();
        return new Leg(List.copyOf(candles), LABELS[random.nextInt(LABELS.length)], start, end);
    }
}
