package com.mobstands.stand;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.common.util.INBTSerializable;

import java.util.UUID;

/**
 * Per-player Stand state. Stored as a NeoForge data attachment (persists across death/relog)
 * and mirrored to the client through {@code SyncStandPayload}.
 */
public class StandData implements INBTSerializable<CompoundTag> {
    public static final int MAX_LEVEL = 10;
    /** The Spin technique levels up to 10 (10 = master). */
    public static final int SPIN_MAX_LEVEL = 10;
    public static final int SLOTS = 6; // 0 = basic, 1 = Z, 2 = X, 3 = C, 4 = V, 5 = B (only some Stands use V and B)

    private StandType type;
    private int level = 1;
    private int xp;
    private boolean summoned;
    private final long[] cooldownEnd = new long[SLOTS];

    // Runtime-only (not saved)
    public UUID activeStand;
    public long climbUntil;
    public long lastToggle;
    /** Skeleton "Double Shot" timing window (game ticks, inclusive). Runtime only. */
    public long doubleShotOpen;
    public long doubleShotClose;
    /** Set when the player clicked too early this shot; spamming never earns the bonus arrow. */
    public boolean doubleShotFumbled;
    /** Punching before this game tick (right after summoning) plays the summon-punch sound. Runtime only. */
    public long summonPunchUntil;
    /** Allay Stand: how many Allays you have right now (your Allay + its duplicates, up to 4). */
    public int allayCount = 1;
    /** When each duplicate Allay drifts away (game time; they last 3 minutes). */
    public final java.util.List<Long> allayDupes = new java.util.ArrayList<>();
    /** Your Allay roams freely (and can be "killed" to duplicate) instead of standing by your side. */
    public boolean allayRoam;
    /** Ticks you've been standing in water (after a while, your duplicates' timers fill back up). */
    public int allayWaterTicks;
    /** Hits landed on each duplicate (index -> {hits, last hit time}). */
    public final java.util.Map<Integer, long[]> dupeHits = new java.util.HashMap<>();
    /** Enemies your Allays are holding up in the air. */
    public final java.util.List<AllayHold> allayHolds = new java.util.ArrayList<>();
    /** Allay: the held enemy you're dragging with your crosshair (-1 = none), and how far out it floats. */
    public int allayDragId = -1;
    /** Pig line: the highest Act unlocked (0 = not on the Pig line), and each Act's own level and XP (index = Act). */
    public int pigActMax;
    public final int[] actLevel = new int[5], actXp = new int[5];
    /** Act 2 -> 3 ritual, step 1 done: a Totem of Undying caught the lightning and restored the body. */
    public boolean bodyRestored;
    public double allayDragDist;
    /** Your view's pitch last tick while dragging (a hard swing down = Slam). */
    public float allayLastPitch;

    public static final class AllayHold {
        public int entityId;
        public int allays;
        public long end;
        public net.minecraft.world.phys.Vec3 anchor;
        /** Where the enemy was standing when grabbed (the lift height is measured from here). */
        public double baseY;
        public boolean usesMain;
    }

    /** Allays currently busy holding enemies. */
    public int heldAllays() {
        int n = 0;
        for (AllayHold h : allayHolds) n += h.allays;
        return n;
    }

    /** Allays free to fight (always at least your own). */
    public int freeAllays() {
        return Math.max(1, allayCount - heldAllays());
    }
    /** Where you stood when you struck your pose; walking away from it drops the pose. */
    public net.minecraft.world.phys.Vec3 poseAnchor;
    /**
     * Pending punch (regular or summon-rush): tick of the click and ticks until it lands. A second click before it
     * lands turns it into a right-left double. Runtime only.
     */
    public long punchStart = -1L;
    public int punchHitAt;
    public boolean punchDouble;
    /** After a single punch lands, a second click before this tick throws the left follow-up with no cooldown. */
    public long followUpUntil;
    /** Damage settings of the last punch, reused by the follow-up. */
    public double punchDmg, punchKnock, punchUp;
    /** Panic-summon tracking: start of the current burst of hits, hits in it, and until when the Stand fights along. */
    public long attackBurstStart = -1000L;
    public int attackBurstHits;
    public long panicUntil;
    /** Iron Golem rage: start of the current run of hits you've taken, how many, and when it can roar again. */
    public long hurtBurstStart = -1000L;
    public int hurtBurstHits;
    public long golemRageReady;
    /** Squid Stand rain: until when it rains / pours / crushes, and how long each enemy has stood in it (ticks). */
    public long rainUntil, surgeUntil, delugeUntil;
    /** Squid Stand: November Rain switched on (it costs hunger while it falls). */
    public boolean rainOn;
    /** Dolphin: Rain Walk is on (you stand on the raindrops). */
    public boolean rainWalk;
    /** Allay Stand: carrying you through the air until this game time. */
    public long liftStart, liftUntil;
    /** Horse Stand Rush: charging until this time (0 = not charging); running while rushing. */
    public long rushChargeUntil;
    /** Spin: the held item is spinning (synced to the owner's client). */
    public boolean spinning;
    /** Game time the spin started (it winds down and stops after StandManager.SPIN_TICKS). */
    public long spinStart;
    /** Arrows shot from a spinning bow/crossbow this spin (3 and the spin is used up). */
    public int spinArrows;
    /** Game time of the last spinning-sword ground slash (synced: the sword rolls forward in your hand). */
    public long spinSlashAt = -1000L;
    /** The Spin technique (learned from a Steel Ball): separate from the Stand, with its own level. */
    public boolean spinLearned;
    /** Weaver of Webs: the Möbius Strip has awakened at least once (see Mobius). */
    public boolean mobiusLearned;
    /** Requiem Trials: steps done (bit flags) and a counter (see RequiemTrials). Whether you've reached the End. */
    public int trialFlags, trialCount;
    public boolean reachedEnd;
    /** Has been to the Nether (Nether Stands can be rolled). */
    public boolean reachedNether;
    /** Sniffer: Sniff Out rolls passed on in a row (3 = the next one is a search). */
    public int sniffMisses;

    /** The Spin only works for people without a Stand. */
    public boolean canSpin() {
        return spinLearned && type == null;
    }
    /** Sniffer: structures you've been led to and reached ("dimension|x|z"); it won't lead you back to them. */
    public final java.util.List<String> sniffVisited = new java.util.ArrayList<>();
    /** Pigstep: stolen sounds (entity type ids, or "thunder") and which one is ready. */
    public final java.util.List<String> stolenSounds = new java.util.ArrayList<>();
    public int soundSel;
    /** Minecart Stand: riding it right now. */
    public boolean cartRiding;
    /** Requiem aura on/off (/stand aura). */
    public boolean auraOff;
    /** Wonder of Deep: summoning it doesn't darken the world around you. */
    public boolean wodDarkOff;
    public Vec3Holder cartLast = new Vec3Holder();
    /** Mobs the cart just ran over -> game time it may hit them again. */
    public final java.util.Map<java.util.UUID, Long> cartHits = new java.util.HashMap<>();

    /** Last tick's position while riding (to work out the cart's real speed on the server). */
    public static final class Vec3Holder {
        public double x, y, z;
        public boolean set;
    }
    public int spinLevel = 1, spinXp;
    /** The Ender World: game time time was stopped, and when it starts again (synced: the client's screen effect). */
    public long timeStopStart = -1000L, timeStopEnd = -1000L;
    /** The Ender World's Time Skip: when it last happened (synced: a quick inverted-colour flash). */
    public long timeSkipAt = -1000L;
    /** Stop Heart: when your heart was stopped, and when it starts again (game time). */
    public long heartStopAt = -1000L, heartStopEnd = -1000L;
    /** Axolotl's Neoteny (Mandom): when the last rewind happened (synced: a quick drained-colour flash). */
    public long mandomAt = -1000L;
    /** Spin rocket (two spins thrown at your feet): when it launched you; no fall damage until you land. */
    public long spinRocketAt = -1000L;
    /** The off hand can hold a spinning item too (swap it over with F, then spin another in your main hand). */
    public boolean offSpinning;
    public long offSpinStart;
    public net.minecraft.world.item.Item offSpinItem;
    public net.minecraft.world.item.Item spinItem;
    public int spinSlot = -1;
    public long spinLastPress = -100L;
    public int spinStreak;
    public boolean rushing;
    public int rushTicks;
    public double rushLastX, rushLastZ;
    public final java.util.Map<java.util.UUID, Integer> rainExposure = new java.util.HashMap<>();
    public int comboHit2 = -1;

    public StandType getType() { return type; }
    public void setType(StandType type) {
        this.type = type;
        if (type != null && type.pigAct() > pigActMax) pigActMax = type.pigAct(); // a new Act unlocked
    }
    public int getLevel() { return level; }
    public void setLevel(int level) { this.level = Math.max(1, Math.min(MAX_LEVEL, level)); }
    public int getXp() { return xp; }
    public void setXp(int xp) { this.xp = Math.max(0, xp); }
    public boolean isSummoned() { return summoned; }
    public void setSummoned(boolean summoned) { this.summoned = summoned; }

    public long cooldownEnd(int slot) { return cooldownEnd[slot]; }
    public void setCooldownEnd(int slot, long gameTime) { cooldownEnd[slot] = gameTime; }
    public int cooldownRemaining(int slot, long gameTime) {
        return (int) Math.max(0L, cooldownEnd[slot] - gameTime);
    }
    public void clearCooldowns() { java.util.Arrays.fill(cooldownEnd, 0L); }

    public void resetProgress() {
        trialFlags = 0;
        trialCount = 0;
        stolenSounds.clear();
        soundSel = 0;
        level = 1;
        xp = 0;
        clearCooldowns();
    }

    public static int xpForNext(int level) {
        return 40 + 30 * (level - 1);
    }

    public int xpForNext() {
        return xpForNext(level);
    }

    /** Adds XP and returns how many levels were gained. */
    public int addXp(int amount) {
        if (type == null || level >= MAX_LEVEL || amount <= 0) return 0;
        xp += amount;
        int gained = 0;
        while (level < MAX_LEVEL && xp >= xpForNext(level)) {
            xp -= xpForNext(level);
            level++;
            gained++;
        }
        if (level >= MAX_LEVEL) xp = 0;
        return gained;
    }

    /** Adds Spin practice XP and returns how many Spin levels were gained. */
    public int addSpinXp(int amount) {
        if (!spinLearned || spinLevel >= SPIN_MAX_LEVEL || amount <= 0) return 0;
        spinXp += amount;
        int gained = 0;
        while (spinLevel < SPIN_MAX_LEVEL && spinXp >= xpForNext(spinLevel)) {
            spinXp -= xpForNext(spinLevel);
            spinLevel++;
            gained++;
        }
        if (spinLevel >= SPIN_MAX_LEVEL) spinXp = 0;
        return gained;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Type", type == null ? "" : type.id());
        tag.putInt("Level", level);
        tag.putInt("Xp", xp);
        tag.putBoolean("Summoned", summoned);
        tag.putLongArray("Cooldowns", cooldownEnd);
        tag.putBoolean("Spin", spinning);
        long[] dupes = new long[allayDupes.size()];
        for (int i = 0; i < dupes.length; i++) dupes[i] = allayDupes.get(i);
        tag.putLongArray("Dupes", dupes);
        tag.putLong("SpinStart", spinStart);
        tag.putBoolean("OffSpin", offSpinning);
        tag.putLong("SpinSlash", spinSlashAt);
        tag.putLong("TsStart", timeStopStart);
        tag.putBoolean("SpinLearned", spinLearned);
        tag.putBoolean("MobiusLearned", mobiusLearned);
        tag.putBoolean("AuraOff", auraOff);
        tag.putBoolean("WodDarkOff", wodDarkOff);
        tag.putInt("SpinLevel", spinLevel);
        tag.putInt("SpinXp", spinXp);
        tag.putLong("TsEnd", timeStopEnd);
        tag.putLong("TsSkip", timeSkipAt);
        tag.putLong("HeartAt", heartStopAt);
        tag.putLong("HeartEnd", heartStopEnd);
        tag.putLong("Mandom", mandomAt);
        tag.putLong("OffSpinStart", offSpinStart);
        tag.putInt("TrialFlags", trialFlags);
        tag.putInt("TrialCount", trialCount);
        tag.putBoolean("ReachedEnd", reachedEnd);
        tag.putBoolean("ReachedNether", reachedNether);
        tag.putInt("SniffMisses", sniffMisses);
        net.minecraft.nbt.ListTag sv = new net.minecraft.nbt.ListTag();
        for (String v : sniffVisited) sv.add(net.minecraft.nbt.StringTag.valueOf(v));
        tag.put("SniffVisited", sv);
        tag.putString("Sounds", String.join(",", stolenSounds));
        tag.putInt("SoundSel", soundSel);
        tag.putBoolean("RainWalk", rainWalk);
        tag.putInt("PigActMax", pigActMax);
        tag.putIntArray("ActLevels", actLevel);
        tag.putIntArray("ActXp", actXp);
        tag.putBoolean("BodyRestored", bodyRestored);
        return tag;
    }

    public void load(CompoundTag tag) {
        type = StandType.byId(tag.getString("Type"));
        spinLearned = tag.getBoolean("SpinLearned");
        mobiusLearned = tag.getBoolean("MobiusLearned");
        auraOff = tag.getBoolean("AuraOff");
        wodDarkOff = tag.getBoolean("WodDarkOff");
        spinLevel = Math.max(1, Math.min(SPIN_MAX_LEVEL, tag.getInt("SpinLevel")));
        spinXp = Math.max(0, tag.getInt("SpinXp"));
        if ("spin".equals(tag.getString("Type"))) {
            // Old worlds: the Spin used to be a "Stand". It becomes the technique, keeping its level.
            spinLearned = true;
            spinLevel = Math.max(1, Math.min(SPIN_MAX_LEVEL, tag.getInt("Level")));
            spinXp = Math.max(0, tag.getInt("Xp"));
            tag.putString("Type", "");
            tag.putInt("Level", 1);
            tag.putInt("Xp", 0);
            tag.putBoolean("Summoned", false);
        }
        level = Math.max(1, Math.min(MAX_LEVEL, tag.getInt("Level")));
        xp = Math.max(0, tag.getInt("Xp"));
        summoned = tag.getBoolean("Summoned");
        long[] cds = tag.getLongArray("Cooldowns");
        for (int i = 0; i < SLOTS; i++) cooldownEnd[i] = i < cds.length ? cds[i] : 0L;
        if (tag.getInt("Level") == 0) level = 1;
        spinning = tag.getBoolean("Spin");
        spinStart = tag.getLong("SpinStart");
        offSpinning = tag.getBoolean("OffSpin");
        spinSlashAt = tag.contains("SpinSlash") ? tag.getLong("SpinSlash") : -1000L;
        timeStopStart = tag.contains("TsStart") ? tag.getLong("TsStart") : -1000L;
        timeStopEnd = tag.contains("TsEnd") ? tag.getLong("TsEnd") : -1000L;
        timeSkipAt = tag.contains("TsSkip") ? tag.getLong("TsSkip") : -1000L;
        heartStopAt = tag.contains("HeartAt") ? tag.getLong("HeartAt") : -1000L;
        heartStopEnd = tag.contains("HeartEnd") ? tag.getLong("HeartEnd") : -1000L;
        mandomAt = tag.contains("Mandom") ? tag.getLong("Mandom") : -1000L;
        offSpinStart = tag.getLong("OffSpinStart");
        trialFlags = tag.getInt("TrialFlags");
        trialCount = tag.getInt("TrialCount");
        reachedEnd = tag.getBoolean("ReachedEnd");
        reachedNether = tag.getBoolean("ReachedNether");
        rainWalk = tag.getBoolean("RainWalk");
        pigActMax = tag.getInt("PigActMax");
        int[] al = tag.getIntArray("ActLevels"), ax = tag.getIntArray("ActXp");
        for (int i = 0; i < 5; i++) {
            actLevel[i] = i < al.length ? al[i] : 0;
            actXp[i] = i < ax.length ? ax[i] : 0;
        }
        bodyRestored = tag.getBoolean("BodyRestored");
        // Worlds from before Acts: whatever Pig-line Stand you have counts as unlocked.
        if (type != null && type.pigAct() > pigActMax) pigActMax = type.pigAct();
        sniffMisses = tag.getInt("SniffMisses");
        sniffVisited.clear();
        net.minecraft.nbt.ListTag sv = tag.getList("SniffVisited", net.minecraft.nbt.Tag.TAG_STRING);
        for (int i = 0; i < sv.size(); i++) sniffVisited.add(sv.getString(i));
        stolenSounds.clear();
        for (String sId : tag.getString("Sounds").split(",")) if (!sId.isBlank()) stolenSounds.add(sId);
        soundSel = tag.getInt("SoundSel");
        allayDupes.clear();
        for (long t : tag.getLongArray("Dupes")) allayDupes.add(t); // when each duplicate drifts away (game time)
        allayCount = 1 + allayDupes.size();
    }

    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        return save();
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag nbt) {
        load(nbt);
    }
}
