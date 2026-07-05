# Despawn persistent hostile mobs that are 128+ blocks from all players.
# Runs every 5 minutes. Mimics vanilla despawn distance for named mobs
# that structures/spawners made persistent.

# Clear safety tags from previous sweep
tag @e[tag=adventure_near] remove adventure_near

# At each player, mark persistent hostiles within 128 blocks as safe
execute as @a at @s run tag @e[type=#adventure:despawnable_hostile,nbt={PersistenceRequired:1b},distance=..128] add adventure_near

# Kill persistent hostiles that aren't near any player
kill @e[type=#adventure:despawnable_hostile,nbt={PersistenceRequired:1b},tag=!adventure_near]

# Clean up
tag @e[tag=adventure_near] remove adventure_near

# Reschedule
schedule function adventure:sweep 6000t
