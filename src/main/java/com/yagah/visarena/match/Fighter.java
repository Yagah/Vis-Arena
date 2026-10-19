package com.yagah.visarena.match;

import java.util.Optional;
import java.util.UUID;

import com.yagah.visarena.map.TeamColor;

/** One player in a match, and their score. Kept after they leave, so the final scores still list them. */
public final class Fighter {
    private final UUID id;
    private final String name;
    private int kills;
    private int deaths;
    /** The match tick their spawn protection ends. */
    private int protectedUntil;
    private boolean present = true;
    private Optional<TeamColor> team = Optional.empty();
    /** In round modes: rounds won, and whether they're out (downed) until the next round. */
    private int roundsWon;
    private boolean out;

    Fighter(UUID id, String name) {
        this.id = id;
        this.name = name;
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public int kills() {
        return kills;
    }

    public int deaths() {
        return deaths;
    }

    /** Their side in a team mode; empty in free-for-all modes. */
    public Optional<TeamColor> team() {
        return team;
    }

    void setTeam(TeamColor team) {
        this.team = Optional.of(team);
    }

    /** False once they've left the server (or been removed from the match). */
    public boolean present() {
        return present;
    }

    void addKill() {
        kills++;
    }

    void addDeath() {
        deaths++;
    }

    public int roundsWon() {
        return roundsWon;
    }

    void addRound() {
        roundsWon++;
    }

    /** True while downed in a round mode, watching the rest of the round. */
    public boolean out() {
        return out;
    }

    void setOut(boolean out) {
        this.out = out;
    }

    void setProtectedUntil(int tick) {
        protectedUntil = tick;
    }

    boolean protectedAt(int tick) {
        return tick < protectedUntil;
    }

    void leave() {
        present = false;
    }
}
