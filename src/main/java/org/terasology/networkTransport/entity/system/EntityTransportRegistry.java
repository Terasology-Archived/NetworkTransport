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
import org.terasology.math.Side;
import org.terasology.math.geom.Vector3i;

public interface EntityTransportRegistry {
    void registerEntityTransportHandler(String transporterType, EntityTransportHandler entityTransportHandler);
    Network2<EntityTransportNetworkNode> findNetworkAt(String transporterType, Vector3i location, Side connectionOnSide, boolean input);
    EntityRef routeEntityThroughNetwork(String transporterType, Network2<EntityTransportNetworkNode> network,
                                   TransportRoute route, long duration);
    void rerouteEntityThroughNetwork(String transporterType, Network2<EntityTransportNetworkNode> network,
                                     EntityRef entity, TransportRoute route, long duration);
}
