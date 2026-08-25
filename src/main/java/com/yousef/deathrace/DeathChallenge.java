package com.yousef.deathrace;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Mob;

import java.util.List;
import java.util.function.Predicate;

public record DeathChallenge(String id, Component title, Component hint, Predicate<DamageSource> matcher) {
    private static boolean is(DamageSource source, ResourceKey<DamageType> type) {
        return source.is(type);
    }

    public static final List<DeathChallenge> ALL = List.of(
            challenge("fire", "Burn to Death", "Die from fire or while burning",
                    source -> is(source, DamageTypes.IN_FIRE) || is(source, DamageTypes.ON_FIRE)),
            challenge("lava", "Melt in Lava", "Die while swimming in lava",
                    source -> is(source, DamageTypes.LAVA)),
            challenge("fall", "Hit the Ground Too Hard", "Die from fall damage",
                    source -> source.is(DamageTypeTags.IS_FALL)),
            challenge("drown", "Drown", "Run out of air underwater",
                    source -> is(source, DamageTypes.DROWN)),
            challenge("explosion", "Explode", "Die to any explosion",
                    source -> source.is(DamageTypeTags.IS_EXPLOSION)),
            challenge("lightning", "Struck by Lightning", "Let lightning finish you",
                    source -> is(source, DamageTypes.LIGHTNING_BOLT)),
            challenge("freeze", "Freeze to Death", "Die from powdered-snow freezing",
                    source -> is(source, DamageTypes.FREEZE)),
            challenge("cactus", "Hug a Cactus", "Die from cactus damage",
                    source -> is(source, DamageTypes.CACTUS)),
            challenge("berry", "Sweet Berry Bush", "Die to a sweet berry bush",
                    source -> is(source, DamageTypes.SWEET_BERRY_BUSH)),
            challenge("suffocate", "Suffocate in a Wall", "Die while trapped inside a block",
                    source -> is(source, DamageTypes.IN_WALL)),
            challenge("starve", "Starve", "Let hunger finish you",
                    source -> is(source, DamageTypes.STARVE)),
            challenge("wither", "Wither Away", "Die from the Wither effect",
                    source -> is(source, DamageTypes.WITHER)),
            challenge("magic", "Killed by Magic", "Die from magical damage",
                    source -> is(source, DamageTypes.MAGIC) || is(source, DamageTypes.INDIRECT_MAGIC)),
            challenge("anvil", "Crushed by an Anvil", "Let a falling anvil finish you",
                    source -> is(source, DamageTypes.FALLING_ANVIL)),
            challenge("stalagmite", "Impaled on a Stalagmite", "Fall onto pointed dripstone",
                    source -> is(source, DamageTypes.STALAGMITE)),
            challenge("stalactite", "Crushed by a Stalactite", "Let pointed dripstone fall on you",
                    source -> is(source, DamageTypes.FALLING_STALACTITE)),
            challenge("arrow", "Shot by an Arrow", "Die to an arrow",
                    source -> is(source, DamageTypes.ARROW)),
            challenge("trident", "Skewered by a Trident", "Die to a thrown trident",
                    source -> is(source, DamageTypes.TRIDENT)),
            challenge("firework", "Firework Accident", "Die to a firework rocket",
                    source -> is(source, DamageTypes.FIREWORKS)),
            challenge("mob", "Slain by a Mob", "Let any hostile or neutral mob finish you",
                    source -> source.getEntity() instanceof Mob),
            challenge("void", "Fall Out of the World", "Die in the void",
                    source -> is(source, DamageTypes.OUT_OF_WORLD)),
            challenge("border", "World Border", "Die outside the world border",
                    source -> is(source, DamageTypes.OUTSIDE_BORDER)),
            challenge("fly_wall", "Kinetic Energy", "Crash into a wall with an elytra",
                    source -> is(source, DamageTypes.FLY_INTO_WALL)),
            challenge("cramming", "Entity Cramming", "Die from too many entities in one space",
                    source -> is(source, DamageTypes.CRAMMING))
    );

    private static DeathChallenge challenge(String id, String title, String hint, Predicate<DamageSource> matcher) {
        return new DeathChallenge(id, Component.literal(title), Component.literal(hint), matcher);
    }
}
