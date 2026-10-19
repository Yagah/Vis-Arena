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
