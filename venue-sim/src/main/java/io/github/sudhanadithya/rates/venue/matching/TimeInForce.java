package io.github.sudhanadithya.rates.venue.matching;

/** How long an order may rest after it has matched what it can. */
public enum TimeInForce {

    /** Any unfilled remainder rests on the book. */
    DAY,

    /** Immediate or cancel: any unfilled remainder is discarded and never shows on the feed. */
    IOC
}
