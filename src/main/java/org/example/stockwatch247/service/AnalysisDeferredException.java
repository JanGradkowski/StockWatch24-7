package org.example.stockwatch247.service;

/** Control flow: incomplete work remains eligible and never consumes calculation retries. */
final class AnalysisDeferredException extends RuntimeException {
    AnalysisDeferredException(String reason) { super(reason, null, false, false); }
}
