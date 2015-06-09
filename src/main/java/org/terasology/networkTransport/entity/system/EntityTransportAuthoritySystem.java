/*
 * Copyright 2015 MovingBlocks
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.terasology.networkTransport.entity.system;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terasology.blockNetwork.BlockNetworkUtil;
import org.terasology.blockNetwork.EfficientBlockNetwork;
import org.terasology.blockNetwork.EfficientNetworkTopologyListener;
import org.terasology.blockNetwork.Network2;
import org.terasology.blockNetwork.NetworkChangeReason;
import org.terasology.engine.Time;
import org.terasology.entitySystem.entity.EntityManager;
import org.terasology.entitySystem.entity.EntityRef;
import org.terasology.entitySystem.entity.lifecycleEvents.BeforeDeactivateComponent;
import org.terasology.entitySystem.entity.lifecycleEvents.OnActivatedComponent;
import org.terasology.entitySystem.entity.lifecycleEvents.OnChangedComponent;
import org.terasology.entitySystem.event.ReceiveEvent;
import org.terasology.entitySystem.systems.BaseComponentSystem;
import org.terasology.entitySystem.systems.RegisterMode;
import org.terasology.entitySystem.systems.RegisterSystem;
import org.terasology.entitySystem.systems.UpdateSubscriberSystem;
import org.terasology.logic.delay.DelayManager;
import org.terasology.logic.delay.DelayedActionTriggeredEvent;
import org.terasology.logic.location.LocationComponent;
import org.terasology.math.Side;
import org.terasology.math.SideBitFlag;
import org.terasology.math.geom.Vector3f;
import org.terasology.math.geom.Vector3i;
import org.terasology.networkTransport.entity.component.EntityTransporterComponent;
import org.terasology.networkTransport.entity.component.RoutedEntityComponent;
import org.terasology.networkTransport.entity.component.RoutingProgressComponent;
import org.terasology.registry.In;
import org.terasology.registry.Share;
import org.terasology.world.WorldProvider;
import org.terasology.world.block.BeforeDeactivateBlocks;
import org.terasology.world.block.Block;
import org.terasology.world.block.BlockComponent;
import org.terasology.world.block.OnActivatedBlocks;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

@RegisterSystem(RegisterMode.AUTHORITY)
@Share(EntityTransportRegistry.class)
public class EntityTransportAuthoritySystem extends BaseComponentSystem implements EntityTransportRegistry, UpdateSubscriberSystem {
    private static final Logger logger = LoggerFactory.getLogger(EntityTransportAuthoritySystem.class);
    private static final String ARRIVAL_ACTION_ID = "NetworkTransport:Arrival";

    @In
    private WorldProvider worldProvider;
    @In
    private EntityManager entityManager;
    @In
    private Time time;
    @In
    private DelayManager delayManager;

    private Map<String, EntityTransportHandler> entityTransportRegistry = Maps.newHashMap();

    private Map<String, EfficientBlockNetwork<EntityTransportNetworkNode>> entityTransportNetworks = Maps.newHashMap();

    private Map<String, Map<Network2<EntityTransportNetworkNode>, Collection<RoutedEntity>>> entityNetworkRoutedEntities = Maps.newHashMap();

    private Multimap<String, EntityRef> pendingLoadedUnassignedEntities = HashMultimap.create();

    /**
     * Merges all the pending (loaded) entities into their networks.
     *
     * @param delta The time (in seconds) since the last engine update.
     */
    @Override
    public void update(float delta) {
        if (!pendingLoadedUnassignedEntities.isEmpty()) {
            for (Map.Entry<String, EntityRef> transportTypeEntityEntry : pendingLoadedUnassignedEntities.entries()) {
                String transportType = transportTypeEntityEntry.getKey();
                EntityRef entity = transportTypeEntityEntry.getValue();

                mergeRoutedEntityIntoNetwork(transportType, entity);
                entity.destroy();
            }
            pendingLoadedUnassignedEntities.clear();
        }
    }

    /**
     * Unloads all entities from routed networks. This is to have consistent state between games recovered from crash
     * (from auto-save) and games that just unloaded all chunks (game exit).
     */
    @Override
    public void preSave() {
        for (Map<Network2<EntityTransportNetworkNode>, Collection<RoutedEntity>> routedEntitiesInNetworksOfType : entityNetworkRoutedEntities.values()) {
            for (Collection<RoutedEntity> routedEntitiesInNetwork : routedEntitiesInNetworksOfType.values()) {
                for (RoutedEntity routedEntity : routedEntitiesInNetwork) {
                    updateEntityForStoring(routedEntity, routedEntity.entity);
                }
            }
        }
    }

    /**
     * Loads all entities into networks that have been temporarily removed from them, due to saving.
     */
    @Override
    public void postSave() {
        for (Map<Network2<EntityTransportNetworkNode>, Collection<RoutedEntity>> routedEntitiesInNetworksOfType : entityNetworkRoutedEntities.values()) {
            for (Collection<RoutedEntity> routedEntitiesInNetwork : routedEntitiesInNetworksOfType.values()) {
                for (RoutedEntity routedEntity : routedEntitiesInNetwork) {
                    long transportDuration = routedEntity.transportRoute.getTransportDuration();
                    long progress = routedEntity.entity.getComponent(RoutingProgressComponent.class).progress;
                    delayManager.addDelayedAction(routedEntity.entity, ARRIVAL_ACTION_ID, transportDuration - progress);
                }
            }
        }
    }

    private void mergeRoutedEntityIntoNetwork(String transportType, EntityRef entity) {
        RoutingProgressComponent routingProgressComponent = entity.getComponent(RoutingProgressComponent.class);
        Vector3f worldLocation = entity.getComponent(LocationComponent.class).getWorldPosition();

        Vector3i blockLocation = new Vector3i(worldLocation);
        Network2<EntityTransportNetworkNode> network = findNetworkWithBlock(entityTransportNetworks.get(transportType).getNetworks(), blockLocation);
        if (network != null) {
            entityTransportRegistry.get(transportType).entityDiscoveredInNetwork(entity, network, routingProgressComponent.progress);
        } else {
            logger.error("Discovered transported entity without network it belongs to.");
        }
    }

    @Override
    public void registerEntityTransportHandler(String transporterType, EntityTransportHandler entityTransportHandler) {
        entityTransportRegistry.put(transporterType, entityTransportHandler);
        EfficientBlockNetwork<EntityTransportNetworkNode> blockNetwork = new EfficientBlockNetwork<>();
        entityTransportNetworks.put(transporterType, blockNetwork);
        blockNetwork.addTopologyListener(new TransporterTypeNetworkTopologyListener(transporterType));
        entityNetworkRoutedEntities.put(transporterType, Maps.newHashMap());
    }

    @Override
    public Network2<EntityTransportNetworkNode> findNetworkAt(String transporterType, Vector3i location, Side connectionOnSide, boolean input) {
        for (Network2<EntityTransportNetworkNode> network : entityTransportNetworks.get(transporterType).getNetworks()) {
            for (EntityTransportNetworkNode networkNode : network.getNetworkingNodes()) {
                byte nodeSides = input ? networkNode.inputSides : networkNode.outputSides;
                if (networkNode.location.x == location.x
                        && networkNode.location.y == location.y
                        && networkNode.location.z == location.z
                        && SideBitFlag.hasSide(nodeSides, connectionOnSide)) {
                    return network;
                }
            }
        }

        return null;
    }

    @Override
    public EntityRef routeEntityThroughNetwork(String transporterType, Network2<EntityTransportNetworkNode> network, TransportRoute route, long duration) {
        Collection<RoutedEntity> routedEntities = entityNetworkRoutedEntities.get(transporterType).get(network);
        EntityRef entity = entityManager.create();
        RoutedEntityComponent routedEntityComponent = new RoutedEntityComponent();
        routedEntityComponent.transporterType = transporterType;
        entity.addComponent(routedEntityComponent);
        routeEntityInternal(routedEntities, entity, route, duration);
        return entity;
    }

    @Override
    public void rerouteEntityThroughNetwork(String transporterType, Network2<EntityTransportNetworkNode> network, EntityRef entity, TransportRoute route, long duration) {
        Collection<RoutedEntity> routedEntities = entityNetworkRoutedEntities.get(transporterType).get(network);
        delayManager.cancelDelayedAction(entity, ARRIVAL_ACTION_ID);
        removeRoutedEntity(entity, routedEntities);
        routeEntityInternal(routedEntities, entity, route, duration);
    }

    private void routeEntityInternal(Collection<RoutedEntity> routedEntities, EntityRef entity, TransportRoute route, long duration) {
        routedEntities.add(new RoutedEntity(entity, route, time.getGameTimeInMs()));
        delayManager.addDelayedAction(entity, ARRIVAL_ACTION_ID, duration);
    }

    private void removeRoutedEntity(EntityRef entity, Collection<RoutedEntity> routedEntities) {
        Iterator<RoutedEntity> entityIterator = routedEntities.iterator();
        while (entityIterator.hasNext()) {
            RoutedEntity routedEntity = entityIterator.next();
            if (routedEntity.entity == entity) {
                entityIterator.remove();
            }
        }
    }

    @ReceiveEvent
    public void routedEntityLoaded(OnActivatedComponent event, EntityRef entity, RoutedEntityComponent routedEntity) {
        // Entities created do not have the LocationComponent by default, so we are sure this one is actually loaded
        if (entity.hasComponent(LocationComponent.class) && entity.hasComponent(RoutingProgressComponent.class)) {
            pendingLoadedUnassignedEntities.put(routedEntity.transporterType, entity);
        }
    }

    @ReceiveEvent
    public void delayedEventTriggered(DelayedActionTriggeredEvent event, EntityRef entity, RoutedEntityComponent routedEntity) {
        if (event.getActionId().equals(ARRIVAL_ACTION_ID)) {
            entityTransportRegistry.get(routedEntity.transporterType).entityArrived(entity);
        }
    }

    @ReceiveEvent
    public void prefabTransporterLoaded(OnActivatedBlocks event, EntityRef blockTypeEntity, EntityTransporterComponent entityTransporter) {
        String transporterType = entityTransporter.transporterType;
        validateTransporterType(transporterType);
        for (EntityTransporterComponent.RouteGroup routeGroup : entityTransporter.routeGroups) {
            Set<EntityTransportNetworkNode> transportNodes = Sets.newHashSet();
            for (Vector3i location : event.getBlockPositions()) {
                final EntityTransportNetworkNode transportNode = toNode(location, worldProvider.getBlock(location), routeGroup.inputSides, routeGroup.outputSides);
                transportNodes.add(transportNode);
            }
            entityTransportNetworks.get(transporterType).addNetworkingBlocks(transportNodes, NetworkChangeReason.CHUNK_EVENT);
        }
    }

    @ReceiveEvent
    public void prefabTransporterUnloaded(BeforeDeactivateBlocks event, EntityRef blockTypeEntity, EntityTransporterComponent entityTransporter) {
        String transporterType = entityTransporter.transporterType;
        validateTransporterType(transporterType);
        for (EntityTransporterComponent.RouteGroup routeGroup : entityTransporter.routeGroups) {
            Set<EntityTransportNetworkNode> transportNodes = Sets.newHashSet();
            for (Vector3i location : event.getBlockPositions()) {
                final EntityTransportNetworkNode transportNode = toNode(location, worldProvider.getBlock(location), routeGroup.inputSides, routeGroup.outputSides);
                transportNodes.add(transportNode);
            }
            entityTransportNetworks.get(transporterType).removeNetworkingBlocks(transportNodes, NetworkChangeReason.CHUNK_EVENT);
        }
    }

    @ReceiveEvent
    public void transporterAdded(OnActivatedComponent event, EntityRef blockEntity, EntityTransporterComponent entityTransporter, BlockComponent block) {
        String transporterType = entityTransporter.transporterType;
        validateTransporterType(transporterType);
        final Vector3i location = new Vector3i(block.getPosition());
        for (EntityTransporterComponent.RouteGroup routeGroup : entityTransporter.routeGroups) {
            final EntityTransportNetworkNode transportNode = toNode(location, block.getBlock(), routeGroup.inputSides, routeGroup.outputSides);

            entityTransportNetworks.get(transporterType).addNetworkingBlock(transportNode, NetworkChangeReason.WORLD_CHANGE);
        }
    }

    @ReceiveEvent
    public void transporterUpdated(OnChangedComponent event, EntityRef blockEntity, EntityTransporterComponent entityTransporter, BlockComponent block) {
        String transporterType = entityTransporter.transporterType;
        validateTransporterType(transporterType);
        final Vector3i location = new Vector3i(block.getPosition());
        EfficientBlockNetwork<EntityTransportNetworkNode> entityTransportNetwork = entityTransportNetworks.get(transporterType);
        Collection<EntityTransportNetworkNode> oldTransportNodes = entityTransportNetwork.getNetworkingNodesAt(location);
        if (oldTransportNodes.size() > 0) {
            entityTransportNetwork.removeNetworkingBlocks(oldTransportNodes, NetworkChangeReason.WORLD_CHANGE);
        }

        for (EntityTransporterComponent.RouteGroup routeGroup : entityTransporter.routeGroups) {
            final Vector3i location1 = new Vector3i(location);
            final EntityTransportNetworkNode newConductorNode = toNode(location1, block.getBlock(), routeGroup.inputSides, routeGroup.outputSides);
            entityTransportNetwork.addNetworkingBlock(newConductorNode, NetworkChangeReason.WORLD_CHANGE);
        }
    }

    @ReceiveEvent
    public void transporterRemoved(BeforeDeactivateComponent event, EntityRef blockEntity, EntityTransporterComponent entityTransporter, BlockComponent block) {
        String transporterType = entityTransporter.transporterType;
        validateTransporterType(transporterType);
        final Vector3i location = new Vector3i(block.getPosition());
        for (EntityTransporterComponent.RouteGroup routeGroup : entityTransporter.routeGroups) {
            final EntityTransportNetworkNode conductorNode = toNode(location, block.getBlock(), routeGroup.inputSides, routeGroup.outputSides);
            entityTransportNetworks.get(transporterType).removeNetworkingBlock(conductorNode, NetworkChangeReason.WORLD_CHANGE);
        }
    }

    private EntityTransportNetworkNode toNode(Vector3i location, Block block, int inputDefinedSides, int outputDefinedSides) {
        return new EntityTransportNetworkNode(location, getConnections(block, (byte) inputDefinedSides), getConnections(block, (byte) outputDefinedSides));
    }

    private byte getConnections(Block block, byte definedSides) {
        return BlockNetworkUtil.getResultConnections(block, definedSides);
    }

    private void validateTransporterType(String transporterType) {
        if (!entityTransportRegistry.containsKey(transporterType)) {
            throw new IllegalStateException("Unable to locate EntityTransportHandler for transporter type " + transporterType);
        }
    }

    private Network2<EntityTransportNetworkNode> findNetworkWithBlock(Collection<? extends Network2<EntityTransportNetworkNode>> networks, Vector3i blockPosition) {
        for (Network2<EntityTransportNetworkNode> network : networks) {
            for (EntityTransportNetworkNode entityTransportNetworkNode : network.getNetworkingNodes()) {
                if (entityTransportNetworkNode.location.toVector3i().equals(blockPosition)) {
                    return network;
                }
            }
        }
        return null;
    }

    private void updateEntityForStoring(RoutedEntity routedEntity, EntityRef entity) {
        long progress = time.getGameTimeInMs() - routedEntity.routingStart;
        Vector3f entityPosition = routedEntity.transportRoute.getPosition(progress);
        delayManager.cancelDelayedAction(entity, ARRIVAL_ACTION_ID);
        if (entity.hasComponent(LocationComponent.class)) {
            LocationComponent location = entity.getComponent(LocationComponent.class);
            location.setWorldPosition(entityPosition);
            entity.saveComponent(location);
        } else {
            LocationComponent location = new LocationComponent(entityPosition);
            entity.addComponent(location);
        }
        RoutingProgressComponent routingProgressComponent = new RoutingProgressComponent();
        routingProgressComponent.progress = progress;
        entity.addComponent(routingProgressComponent);
    }

    private final class TransporterTypeNetworkTopologyListener implements EfficientNetworkTopologyListener<EntityTransportNetworkNode> {
        private String transporterType;

        private TransporterTypeNetworkTopologyListener(String transporterType) {
            this.transporterType = transporterType;
        }

        @Override
        public void networkAdded(Network2<EntityTransportNetworkNode> network, NetworkChangeReason reason) {
            entityNetworkRoutedEntities.get(transporterType).put(network, Sets.newHashSet());
        }

        @Override
        public void networkRemoved(Network2<EntityTransportNetworkNode> network, NetworkChangeReason reason) {
            EntityTransportHandler entityTransportHandler = entityTransportRegistry.get(transporterType);

            Collection<RoutedEntity> routedEntities = entityNetworkRoutedEntities.get(transporterType).remove(network);
            for (RoutedEntity routedEntity : routedEntities) {
                entityRemovedFromNetwork(network, entityTransportHandler, routedEntity, reason);
            }
        }

        @Override
        public void networkSplit(Network2<EntityTransportNetworkNode> oldNetwork,
                                 Set<? extends Network2<EntityTransportNetworkNode>> resultNetworks, NetworkChangeReason reason) {
            EntityTransportHandler entityTransportHandler = entityTransportRegistry.get(transporterType);

            Collection<RoutedEntity> routedEntities = entityNetworkRoutedEntities.get(transporterType).remove(oldNetwork);
            long gameTime = time.getGameTimeInMs();
            for (RoutedEntity routedEntity : routedEntities) {
                long routingStart = routedEntity.routingStart;
                Vector3f position = routedEntity.transportRoute.getPosition(gameTime - routingStart);
                Vector3i blockPosition = new Vector3i(position);
                Network2<EntityTransportNetworkNode> networkWithNode = findNetworkWithBlock(resultNetworks, blockPosition);
                if (networkWithNode != null) {
                    entityTransportHandler.entityMovedBetweenNetworks(routedEntity.entity, time.getGameTimeInMs() - routedEntity.routingStart, oldNetwork, networkWithNode);
                } else {
                    entityRemovedFromNetwork(oldNetwork, entityTransportHandler, routedEntity, reason);
                }
                routedEntity.entity.destroy();
            }
        }

        private void entityRemovedFromNetwork(Network2<EntityTransportNetworkNode> network, EntityTransportHandler entityTransportHandler,
                                              RoutedEntity routedEntity, NetworkChangeReason reason) {
            EntityRef entity = routedEntity.entity;
            if (reason == NetworkChangeReason.WORLD_CHANGE) {
                entityTransportHandler.entityRemovedFromNetwork(entity, time.getGameTimeInMs() - routedEntity.routingStart, network);
            } else if (reason == NetworkChangeReason.CHUNK_EVENT) {
                updateEntityForStoring(routedEntity, entity);
            }
        }

        @Override
        public void networksMerged(Set<? extends Network2<EntityTransportNetworkNode>> oldNetworks,
                                   Network2<EntityTransportNetworkNode> newNetwork, NetworkChangeReason reason) {
            EntityTransportHandler entityTransportHandler = entityTransportRegistry.get(transporterType);
            for (Network2<EntityTransportNetworkNode> oldNetwork : oldNetworks) {
                Collection<RoutedEntity> routedEntities = entityNetworkRoutedEntities.get(transporterType).get(oldNetwork);
                for (RoutedEntity routedEntity : routedEntities) {
                    entityTransportHandler.entityMovedBetweenNetworks(routedEntity.entity, time.getGameTimeInMs() - routedEntity.routingStart, oldNetwork, newNetwork);
                    routedEntity.entity.destroy();
                }
            }
        }

        @Override
        public void networkingNodesAdded(Network2<EntityTransportNetworkNode> network, Set<EntityTransportNetworkNode> networkingNodes, NetworkChangeReason reason) {
            entityTransportRegistry.get(transporterType).networkModified(network, entityNetworkRoutedEntities.get(transporterType).get(network));
        }

        @Override
        public void networkingNodesRemoved(Network2<EntityTransportNetworkNode> network, Set<EntityTransportNetworkNode> networkingNodes, NetworkChangeReason reason) {
            entityTransportRegistry.get(transporterType).networkModified(network, entityNetworkRoutedEntities.get(transporterType).get(network));
        }
    }
}
