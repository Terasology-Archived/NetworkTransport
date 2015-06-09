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

import org.terasology.blockNetwork.Network2;
import org.terasology.entitySystem.entity.EntityRef;
import org.terasology.math.geom.Vector3f;

import java.util.Collection;

public interface EntityTransportHandler {
    /**
     * Called when a network was modified (block was added/removed from it) in a way that did not split or merge this
     * network). This could potentially mean that the entity should be rerouted to adapt to a new path.
     *
     * @param network
     * @param routedEntities
     */
    void networkModified(Network2<EntityTransportNetworkNode> network, Collection<RoutedEntity> routedEntities);

    /**
     * Called when an entity was discovered (loaded) into a network. Please note, that this entity will be destroyed
     * after this method returns. If an entity is to be routed through the new network, it should be scheduled to be
     * routed (not rerouted!) again via EntityTransportRegistry call with the new network.
     *
     * @param entity
     * @param network
     * @param progress
     */
    void entityDiscoveredInNetwork(EntityRef entity, Network2<EntityTransportNetworkNode> network, long progress);

    /**
     * Called when an entity belonging to the network is moved between networks (due to network split). Please note,
     * that this entity will be destroyed after this method returns. If an entity is to be routed through the new
     * network, it should be scheduled to be routed (not rerouted!) again via EntityTransportRegistry call with
     * the new network.
     *
     * @param entity
     * @param fromNetwork
     * @param toNetwork
     */
    void entityMovedBetweenNetworks(EntityRef entity, long progress, Network2<EntityTransportNetworkNode> fromNetwork,
                                    Network2<EntityTransportNetworkNode> toNetwork);

    /**
     * Called when an entity belonging to the network is forcefully removed from network (i.e. block that the entity
     * was in was destroyed). Please note, that this entity will be destroyed after this method returns.
     *
     * @param entity
     * @param network
     */
    void entityRemovedFromNetwork(EntityRef entity, long progress, Network2<EntityTransportNetworkNode> network);

    /**
     * Called when an entity belonging to the network has arrived at its destination. Please note, that this entity
     * will be destroyed after this method returns.
     *
     * @param entity
     */
    void entityArrived(EntityRef entity);
}
