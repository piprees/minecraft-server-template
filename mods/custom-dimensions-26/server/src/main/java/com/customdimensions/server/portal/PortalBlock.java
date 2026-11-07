package com.customdimensions.server.portal;

import eu.pb4.polymer.core.api.block.PolymerBlock;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The one portal block ({@code customdimensions:portal}). Upright on the X or
 * Z axis a vanilla client sees a Nether portal on that axis; flat (Y) it sees
 * an End portal. The vanilla {@link Portal} interface does the teleport;
 * {@link PortalTravel} decides where to.
 * <p>
 * It never pops itself on a neighbour update: a change beside it schedules a
 * check of its zone's frame, and the zone breaks as one ({@link Portals#validateAt}).
 */
public final class PortalBlock extends Block implements PolymerBlock, Portal {
    public static final EnumProperty<Direction.Axis> AXIS = BlockStateProperties.AXIS;
    private static final VoxelShape X_SHAPE = Block.box(0, 0, 6, 16, 16, 10);
    private static final VoxelShape Z_SHAPE = Block.box(6, 0, 0, 10, 16, 16);
    private static final VoxelShape Y_SHAPE = Block.box(0, 6, 0, 16, 12, 16);

    public PortalBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(AXIS, Direction.Axis.X));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(AXIS);
    }

    @Override
    public BlockState getPolymerBlockState(BlockState state, PacketContext context) {
        return switch (state.getValue(AXIS)) {
            case X -> Blocks.NETHER_PORTAL.defaultBlockState().setValue(NetherPortalBlock.AXIS, Direction.Axis.X);
            case Z -> Blocks.NETHER_PORTAL.defaultBlockState().setValue(NetherPortalBlock.AXIS, Direction.Axis.Z);
            case Y -> Blocks.END_PORTAL.defaultBlockState();
        };
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(AXIS)) {
            case X -> X_SHAPE;
            case Z -> Z_SHAPE;
            case Y -> Y_SHAPE;
        };
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.empty();
    }

    @Override
    protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity,
                                InsideBlockEffectApplier effects, boolean isPrecise) {
        if (entity.canUsePortal(false)) {
            entity.setAsInsidePortal(this, pos);
        }
    }

    @Override
    public int getPortalTransitionTime(ServerLevel level, Entity entity) {
        return 0;
    }

    @Override
    public TeleportTransition getPortalDestination(ServerLevel level, Entity entity, BlockPos pos) {
        return PortalTravel.destination(level, entity, pos);
    }

    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos,
                                     Direction direction, BlockPos neighbourPos, BlockState neighbourState,
                                     RandomSource random) {
        if (inPlane(state.getValue(AXIS), direction)) {
            ticks.scheduleTick(pos, this, 1);
        }
        return state;
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        Portals.validateAt(level, pos);
    }

    static boolean inPlane(Direction.Axis axis, Direction direction) {
        return direction.getAxis() != switch (axis) {
            case X -> Direction.Axis.Z;
            case Z -> Direction.Axis.X;
            case Y -> Direction.Axis.Y;
        };
    }
}
