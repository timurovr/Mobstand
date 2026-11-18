package com.mobstands.stand;

@FunctionalInterface
public interface AbilityAction {
    /** Runs on the server. Return false to abort without consuming the cooldown (e.g. no target). */
    boolean run(AbilityContext ctx);
}
