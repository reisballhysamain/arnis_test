package com.arnis.ingame;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.PersistentState;

final class ArnisWorldState extends PersistentState {
    private static final String STATE_ID = "arnis_world";
    private static final String BORDER_LOOP_KEY = "borderLoopEnabled";
    private static final String BORDER_INITIALIZED_KEY = "borderInitialized";

    private boolean borderLoopEnabled;
    private boolean borderInitialized;

    static ArnisWorldState get(ServerWorld world) {
        return world.getPersistentStateManager().getOrCreate(
            ArnisWorldState::readNbt,
            ArnisWorldState::new,
            STATE_ID
        );
    }

    boolean isBorderLoopEnabled() {
        return borderLoopEnabled;
    }

    boolean isBorderInitialized() {
        return borderInitialized;
    }

    void setBorderLoopEnabled(boolean enabled) {
        if (borderLoopEnabled != enabled) {
            borderLoopEnabled = enabled;
            markDirty();
        }
    }

    void setBorderInitialized() {
        if (!borderInitialized) {
            borderInitialized = true;
            markDirty();
        }
    }

    private static ArnisWorldState readNbt(NbtCompound nbt) {
        ArnisWorldState state = new ArnisWorldState();
        state.borderLoopEnabled = nbt.getBoolean(BORDER_LOOP_KEY);
        state.borderInitialized = nbt.getBoolean(BORDER_INITIALIZED_KEY);
        return state;
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        nbt.putBoolean(BORDER_LOOP_KEY, borderLoopEnabled);
        nbt.putBoolean(BORDER_INITIALIZED_KEY, borderInitialized);
        return nbt;
    }
}
