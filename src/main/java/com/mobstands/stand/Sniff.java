package com.mobstands.stand;

import com.mobstands.network.SniffRollPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.*;

/**
 * Sniffer Stand: Sniff Out. A gamble - three slots roll, each landing on a random structure from this dimension.
 * Pick one and your Sniffer finds the nearest. Nothing you want? The next roll is in 10 minutes - and after three
 * rolls you passed on, its nose is trained: the next sniff lets you search for exactly what you want.
 */
public final class Sniff {
    private Sniff() {}

    public static final int REROLL_TICKS = 12000; // 10 minutes
    public static final int MISSES_FOR_SEARCH = 3;
    public static final int SLOT = 1;
    /** How long the scent trail toward a found structure lasts. */
    public static final int TRAIL_TICKS = 600;

    private record Offer(List<String> options, boolean search) {}
    private static final Map<UUID, Offer> OFFERS = new HashMap<>();
    private record Trail(ServerLevel level, BlockPos target, long until, boolean flash) {}
    /** Structures it led you to that you haven't reached yet (reach one and it counts as visited). */
    private static final Map<UUID, List<String>> PENDING = new HashMap<>();
    /** The End: the scent only shows for this long. Miss it and that's on you. */
    public static final int END_FLASH_TICKS = 20;
    private static final Map<UUID, Trail> TRAILS = new HashMap<>();

    /** Every structure that can generate in this dimension. */
    public static List<String> pool(ServerLevel sl) {
        Set<Holder<Biome>> biomes = sl.getChunkSource().getGenerator().getBiomeSource().possibleBiomes();
        List<String> out = new ArrayList<>();
        var reg = sl.registryAccess().registryOrThrow(Registries.STRUCTURE);
        for (var e : reg.entrySet()) {
            Structure s = e.getValue();
            boolean here = false;
            for (Holder<Biome> b : s.biomes()) {
                if (biomes.contains(b)) {
                    here = true;
                    break;
                }
            }
            if (here) out.add(e.getKey().location().toString());
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }

    /** The ability (Z): roll three slots - or, with a trained nose, open the search. */
    public static boolean use(ServerPlayer p) {
        ServerLevel sl = p.serverLevel();
        StandData d = StandManager.data(p);
        List<String> pool = pool(sl);
        if (pool.isEmpty()) {
            p.displayClientMessage(Component.literal("There's nothing here to sniff out.").withStyle(s -> s.withColor(0xAAAAAA)), true);
            return false;
        }
        // The further from the Overworld, the weaker its nose.
        if (sl.dimension() == net.minecraft.world.level.Level.END) return endSniff(p);
        boolean nether = sl.dimension() == net.minecraft.world.level.Level.NETHER;
        boolean search = d.sniffMisses >= MISSES_FOR_SEARCH;
        List<String> options = new ArrayList<>();
        if (!search) {
            List<String> shuffled = new ArrayList<>(pool);
            Collections.shuffle(shuffled, new Random(p.getRandom().nextLong()));
            options.addAll(shuffled.subList(0, Math.min(nether ? 1 : 3, shuffled.size())));
            if (nether) p.displayClientMessage(Component.literal("Far from the Overworld, your Sniffer's nose dulls... only one scent comes through.")
                    .withStyle(s -> s.withColor(0xFF7A3A).withItalic(true)), true);
        }
        OFFERS.put(p.getUUID(), new Offer(options, search));
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.SNIFFER_SNIFFING, SoundSource.PLAYERS, 1.2F, 1.0F);
        PacketDistributor.sendToPlayer(p, new SniffRollPayload(options, pool, search, d.sniffMisses));
        return true;
    }

    /** The player picked (empty = none of these / closed it). */
    public static void pick(ServerPlayer p, String id) {
        Offer offer = OFFERS.remove(p.getUUID());
        if (offer == null) return;
        StandData d = StandManager.data(p);
        if (d.getType() != StandType.SNIFFER) return;
        ServerLevel sl = p.serverLevel();
        long now = sl.getGameTime();
        if (id.isEmpty()) {
            if (offer.search()) {
                // Backed out of the search: keep it for next time, and no wait.
                d.setCooldownEnd(SLOT, now);
                StandManager.sync(p);
                return;
            }
            d.sniffMisses++;
            d.setCooldownEnd(SLOT, now + REROLL_TICKS);
            StandManager.sync(p);
            if (d.sniffMisses >= MISSES_FOR_SEARCH) {
                p.displayClientMessage(Component.literal("Your Sniffer's nose sharpens... next time, you can sniff out anything.")
                        .withStyle(s -> s.withColor(0x5FD18A).withItalic(true)), false);
            } else {
                p.displayClientMessage(Component.literal("Nothing you wanted. Next sniff in 10 minutes (" + d.sniffMisses + "/" + MISSES_FOR_SEARCH + ")")
                        .withStyle(s -> s.withColor(0xC9A27A)), true);
            }
            return;
        }
        if (!offer.search() && !offer.options().contains(id)) return;
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return;
        var reg = sl.registryAccess().registryOrThrow(Registries.STRUCTURE);
        Optional<Holder.Reference<Structure>> holder = reg.getHolder(net.minecraft.resources.ResourceKey.create(Registries.STRUCTURE, rl));
        if (holder.isEmpty()) return;
        d.sniffMisses = 0;
        d.setCooldownEnd(SLOT, now + REROLL_TICKS);
        StandManager.sync(p);
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.SNIFFER_SCENTING, SoundSource.PLAYERS, 1.2F, 1.0F);
        String name = pretty(id);
        BlockPos at = locateUnvisited(sl, holder.get(), p.blockPosition(), d);
        if (at == null) {
            p.displayClientMessage(Component.literal("Your Sniffer can't pick up any " + name + " you haven't already been to.").withStyle(s -> s.withColor(0xC9A27A)), false);
            return;
        }
        PENDING.computeIfAbsent(p.getUUID(), k -> new ArrayList<>()).add(key(sl, at));
        int dist = (int) Math.sqrt(p.blockPosition().distSqr(new BlockPos(at.getX(), p.getBlockY(), at.getZ())));
        p.displayClientMessage(Component.literal("Your Sniffer caught the scent of a " + name + ": " + dist + " blocks " + direction(p, at)
                + " (" + at.getX() + ", " + at.getZ() + "). Follow the trail!").withStyle(s -> s.withColor(0x5FD18A)), false);
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.SNIFFER_HAPPY, SoundSource.PLAYERS, 1.0F, 1.0F);
        TRAILS.put(p.getUUID(), new Trail(sl, at, now + TRAIL_TICKS, false));
    }

    /**
     * The End: the edge of the world, as far from home as a Sniffer can get. It only ever smells the End City - and
     * only for a moment: the trail shows for one second. No coordinates, no second look.
     */
    private static boolean endSniff(ServerPlayer p) {
        ServerLevel sl = p.serverLevel();
        StandData d = StandManager.data(p);
        var reg = sl.registryAccess().registryOrThrow(Registries.STRUCTURE);
        Optional<Holder.Reference<Structure>> city = reg.getHolder(net.minecraft.world.level.levelgen.structure.BuiltinStructures.END_CITY);
        if (city.isEmpty()) return false;
        BlockPos at = locateUnvisited(sl, city.get(), p.blockPosition(), d);
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.SNIFFER_SNIFFING, SoundSource.PLAYERS, 1.2F, 0.7F);
        if (at == null) {
            p.displayClientMessage(Component.literal("Nothing out here but void...").withStyle(s -> s.withColor(0xD9A8FF).withItalic(true)), true);
            return true;
        }
        PENDING.computeIfAbsent(p.getUUID(), k -> new ArrayList<>()).add(key(sl, at));
        p.displayClientMessage(Component.literal("At the edge of the world the scent is faint... LOOK!").withStyle(s -> s.withColor(0xD9A8FF).withBold(true)), true);
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.SNIFFER_SCENTING, SoundSource.PLAYERS, 1.2F, 0.8F);
        TRAILS.put(p.getUUID(), new Trail(sl, at, sl.getGameTime() + END_FLASH_TICKS, true));
        return true;
    }

    // ---- not leading you back to places you've already been ----

    private static String key(ServerLevel sl, BlockPos at) {
        return sl.dimension().location() + "|" + at.getX() + "|" + at.getZ();
    }

    private static boolean visited(ServerLevel sl, BlockPos at, StandData d) {
        String dim = sl.dimension().location() + "|";
        for (String v : d.sniffVisited) {
            if (!v.startsWith(dim)) continue;
            String[] parts = v.split("\\|");
            try {
                int x = Integer.parseInt(parts[1]), z = Integer.parseInt(parts[2]);
                if (Math.abs(x - at.getX()) < 64 && Math.abs(z - at.getZ()) < 64) return true;
            } catch (RuntimeException ignored) {
            }
        }
        return false;
    }

    /** The nearest one of these you haven't visited yet (searching outward the way /locate does). */
    private static BlockPos locateUnvisited(ServerLevel sl, Holder<Structure> h, BlockPos origin, StandData d) {
        var state = sl.getChunkSource().getGeneratorState();
        long seed = state.getLevelSeed();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (var pl : state.getPlacementsForStructure(h)) {
            if (pl instanceof net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement cr) {
                for (net.minecraft.world.level.ChunkPos cp : state.getRingPositionsFor(cr)) {
                    BlockPos at = pl.getLocatePos(cp);
                    double dd = at.distSqr(new BlockPos(origin.getX(), at.getY(), origin.getZ()));
                    if (dd < bestD && !visited(sl, at, d)) {
                        bestD = dd;
                        best = at;
                    }
                }
            } else if (pl instanceof net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement rs) {
                int spacing = rs.spacing();
                int cx = origin.getX() >> 4, cz = origin.getZ() >> 4;
                for (int r = 0; r <= 100; r++) {
                    boolean foundRing = false;
                    for (int dx = -r; dx <= r; dx++) {
                        for (int dz = -r; dz <= r; dz++) {
                            if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                            net.minecraft.world.level.ChunkPos cp = rs.getPotentialStructureChunk(seed, cx + spacing * dx, cz + spacing * dz);
                            var res = sl.structureManager().checkStructurePresence(cp, h.value(), pl, false);
                            if (res == net.minecraft.world.level.levelgen.structure.StructureCheckResult.START_NOT_PRESENT) continue;
                            BlockPos at;
                            if (res == net.minecraft.world.level.levelgen.structure.StructureCheckResult.START_PRESENT) {
                                at = pl.getLocatePos(cp);
                            } else {
                                var ca = sl.getChunk(cp.x, cp.z, net.minecraft.world.level.chunk.status.ChunkStatus.STRUCTURE_STARTS);
                                var start = sl.structureManager().getStartForStructure(net.minecraft.core.SectionPos.bottomOf(ca), h.value(), ca);
                                if (start == null || !start.isValid()) continue;
                                at = pl.getLocatePos(start.getChunkPos());
                            }
                            if (visited(sl, at, d)) continue;
                            double dd = at.distSqr(new BlockPos(origin.getX(), at.getY(), origin.getZ()));
                            if (dd < bestD) {
                                bestD = dd;
                                best = at;
                            }
                            foundRing = true;
                        }
                    }
                    if (foundRing) break;
                }
            }
        }
        return best;
    }

    /** Server tick: the scent trail - green wisps leading off from you toward what it found. */
    public static void tick(ServerPlayer p) {
        // Reached a place it led you to: it's visited, and it won't lead you there again.
        List<String> pend = PENDING.get(p.getUUID());
        if (pend != null && p.tickCount % 20 == 0) {
            String dim = p.serverLevel().dimension().location() + "|";
            Iterator<String> it = pend.iterator();
            while (it.hasNext()) {
                String k = it.next();
                if (!k.startsWith(dim)) continue;
                String[] parts = k.split("\\|");
                int x = Integer.parseInt(parts[1]), z = Integer.parseInt(parts[2]);
                if (Math.abs(p.getX() - x) < 48.0D && Math.abs(p.getZ() - z) < 48.0D) {
                    it.remove();
                    StandData d = StandManager.data(p);
                    if (!d.sniffVisited.contains(k)) d.sniffVisited.add(k);
                }
            }
        }
        Trail t = TRAILS.get(p.getUUID());
        if (t == null) return;
        long now = p.serverLevel().getGameTime();
        if (p.serverLevel() != t.level() || now > t.until()) {
            TRAILS.remove(p.getUUID());
            return;
        }
        if (now % (t.flash() ? 2L : 5L) != 0L) return;
        Vec3 to = new Vec3(t.target().getX() + 0.5D - p.getX(), 0.0D, t.target().getZ() + 0.5D - p.getZ());
        double len = to.length();
        if (len < 8.0D && !t.flash()) {
            p.displayClientMessage(Component.literal("You're here.").withStyle(s -> s.withColor(0x5FD18A)), true);
            TRAILS.remove(p.getUUID());
            return;
        }
        Vec3 dir = to.scale(1.0D / len);
        int reach = t.flash() ? 30 : 14; // the End's flash is long and bright - but only for a second
        for (int i = 2; i <= reach; i++) {
            Vec3 at = p.position().add(dir.scale(i)).add(0.0D, 0.4D + 0.1D * Math.sin((now + i * 3) * 0.3D), 0.0D);
            p.serverLevel().sendParticles(p, t.flash() ? ParticleTypes.END_ROD : ParticleTypes.COMPOSTER, true, at.x, at.y, at.z, 1, 0.08D, 0.05D, 0.08D, 0.0D);
        }
    }

    public static String pretty(String id) {
        String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        StringBuilder b = new StringBuilder();
        for (String w : path.replace('/', '_').split("_")) {
            if (w.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return b.toString();
    }

    private static String direction(ServerPlayer p, BlockPos at) {
        double dx = at.getX() - p.getX(), dz = at.getZ() - p.getZ();
        String[] dirs = {"south", "south-west", "west", "north-west", "north", "north-east", "east", "south-east"};
        double ang = Math.toDegrees(Math.atan2(-dx, dz));
        int i = (int) Math.floorMod(Math.round(ang / 45.0D), 8);
        return dirs[i];
    }
}
