package com.customdimensions.core.portal;

import com.customdimensions.core.config.PortalDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * One side of a link's aura: the far side's nature as plain block and feature
 * ids, fixed at link time, and the lifetime budget spent so far. Lists are
 * never null.
 */
public final class AuraState {
    public List<String> terrain = new ArrayList<>();
    public List<String> flora = new ArrayList<>();
    /** Feature ids for the logs found at link time; passes plant only the configured {@code aura.trees}. */
    public List<String> trees = new ArrayList<>();
    public List<String> fluids = new ArrayList<>();
    public int spent;

    public AuraState() {
    }

    public AuraState(List<String> terrain, List<String> flora, List<String> trees, List<String> fluids, int spent) {
        this.terrain = copy(terrain);
        this.flora = copy(flora);
        this.trees = copy(trees);
        this.fluids = copy(fluids);
        this.spent = Math.max(0, spent);
    }

    /** The palettes an aura block names; a list it leaves out is empty. */
    public static AuraState explicit(PortalDefinition.AuraSettings settings) {
        return new AuraState(settings.palette, settings.flora, settings.trees, settings.fluids, 0);
    }

    private static List<String> copy(List<String> list) {
        return list == null ? new ArrayList<>() : new ArrayList<>(list);
    }
}
