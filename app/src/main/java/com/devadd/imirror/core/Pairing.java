package com.devadd.imirror.core;

import java.math.BigInteger;
import java.security.SecureRandom;

/**
 * The 6-digit pairing code. Single use, expires, and three wrong tries lock it out: the code is
 * replaced and new attempts wait for a cooldown. Attempts are counted for the code, not per
 * connection, so opening many connections gains an attacker nothing.
 */
public final class Pairing {
    public interface Listener { void onPin(String pin); }

    public static final class Attempt {
        public final byte[] salt;
        public final Srp.Server srp;
        final long generation;
        Attempt(byte[] salt, Srp.Server srp, long generation) { this.salt = salt; this.srp = srp; this.generation = generation; }
    }

    static final int MAX_TRIES = 3;
    private static final long LIFETIME_MS = 10 * 60_000L, COOLDOWN_MS = 30_000L;
    private final SecureRandom rng = new SecureRandom();
    private final Listener listener;
    private final java.util.function.LongSupplier clock;
    private String pin;
    private byte[] salt;
    private BigInteger verifier;
    private long expiresAt, cooldownUntil, generation;
    private int failures;

    public Pairing(Listener listener) { this(listener, System::currentTimeMillis); }

    Pairing(Listener listener, java.util.function.LongSupplier clock) {
        this.listener = listener;
        this.clock = clock;
        renew();
    }

    private void renew() {
        pin = String.format("%06d", rng.nextInt(1_000_000));
        salt = new byte[16];
        rng.nextBytes(salt);
        verifier = Srp.verifier(salt, pin);
        expiresAt = clock.getAsLong() + LIFETIME_MS;
        failures = 0;
        generation++;
        if (listener != null) listener.onPin(pin);
    }

    /** The code to show on the phone. */
    public synchronized String pin() {
        if (clock.getAsLong() > expiresAt && cooldownUntil <= clock.getAsLong()) renew();
        return pin;
    }

    public synchronized long cooldownSeconds() {
        long left = cooldownUntil - clock.getAsLong();
        return left <= 0 ? 0 : (left + 999) / 1000;
    }

    /** Starts an attempt, or null during the cooldown. */
    public synchronized Attempt begin() {
        pin();
        if (cooldownSeconds() > 0) return null;
        return new Attempt(salt, new Srp.Server(verifier), generation);
    }

    /** Records a wrong code. Returns tries left, or 0 if the code is now locked and replaced. */
    public synchronized int fail(Attempt a) {
        if (a.generation != generation) return 0;
        failures++;
        if (failures >= MAX_TRIES) {
            renew();
            cooldownUntil = clock.getAsLong() + COOLDOWN_MS;
            return 0;
        }
        return MAX_TRIES - failures;
    }

    /** Accepts the attempt only if the code was not replaced meanwhile, and burns the code. */
    public synchronized boolean succeed(Attempt a) {
        if (a.generation != generation) return false;
        renew();
        return true;
    }
}
