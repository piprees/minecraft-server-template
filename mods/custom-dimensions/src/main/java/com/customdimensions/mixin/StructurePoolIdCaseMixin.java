package com.customdimensions.mixin;

import com.customdimensions.MultiverseServer;
import net.minecraft.registry.RegistryKey;
import net.minecraft.structure.pool.StructurePool;
import net.minecraft.structure.pool.StructurePools;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A jigsaw pool id with a capital letter in it takes the server down. This
 * lowercases it instead.
 *
 * <p>An {@code Identifier} path may only contain {@code [a-z0-9/._-]}, and
 * {@code Identifier.of} throws on anything else. A jigsaw block carries its
 * target pool as a plain string in the structure's NBT, and the jigsaw
 * generator hands that string straight to {@link StructurePools#of} — so one
 * mis-typed character in one structure file, authored by anyone, becomes an
 * exception thrown from chunk generation.
 *
 * <p><b>What that costs is out of all proportion to the typo.</b> Measured
 * twice on this pack (2026-08-14, {@code the_abyssal_shrine},
 * {@code minecraft:emptY}): the chunk never finishes, c2me's chunk system
 * thrashes on it — {@code Can't keep up! Running 3158ms or 63 ticks behind} —
 * and RCON stops answering within minutes while the container still reports
 * healthy. Anyone who reaches that area in ordinary play wedges the server.
 * It is [K1] in TROUBLESHOOTING.md, and no caller-side care avoids it: the
 * second occurrence was against a caller that never waits on a chunk future.
 *
 * <p><b>Why lowercasing is safe rather than a guess.</b> Uppercase is not
 * merely unusual in an identifier, it is forbidden — so no VALID id can be
 * changed by this, only an id that was going to throw. And the repair lands
 * on the right thing: {@code emptY} becomes {@code empty}, and
 * {@code minecraft:empty} is vanilla's own empty pool, the terminator every
 * jigsaw structure uses. The correction recovers what the author meant rather
 * than substituting something plausible.
 *
 * <p><b>Only case is repaired.</b> A space, a second colon, any other illegal
 * character still throws, loudly, because those are not a shift key held a
 * moment too long — they are data this server has no business guessing at.
 *
 * <p>Hooked on VANILLA's {@code StructurePools.of}, not on the mod whose
 * stack trace exposed it. Lithostitched read the data correctly; vanilla's
 * own jigsaw generator would have thrown on the same string. One hook covers
 * every caller, every structure mod, and any future typo.
 */
@Mixin(StructurePools.class)
public class StructurePoolIdCaseMixin {

    /** Each distinct offender is reported once — chunk generation is a hot path. */
    private static final Set<String> customdimensions$reported = ConcurrentHashMap.newKeySet();

    @Inject(method = "of(Ljava/lang/String;)Lnet/minecraft/registry/RegistryKey;",
            at = @At("HEAD"), cancellable = true)
    private static void customdimensions$repairCase(
            String id, CallbackInfoReturnable<RegistryKey<StructurePool>> cir) {
        String lower = id.toLowerCase(Locale.ROOT);
        if (lower.equals(id)) {
            return;
        }
        if (customdimensions$reported.add(id)) {
            MultiverseServer.LOGGER.warn(
                    "Structure pool id '{}' has a capital letter, which an Identifier path "
                    + "may not carry — reading it as '{}'. A jigsaw block in some structure's "
                    + "NBT has this typo; left alone it throws from chunk generation and "
                    + "wedges the server (TROUBLESHOOTING.md#k1).", id, lower);
        }
        // Through the real method, so the key is built exactly the way vanilla
        // builds it rather than a second copy of that construction. The
        // recursion stops at one level: the lowercased id passes the guard.
        cir.setReturnValue(StructurePools.of(lower));
    }
}
