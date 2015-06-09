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

import com.google.common.collect.Lists;
import org.terasology.math.geom.Vector3f;

import java.util.Deque;
import java.util.Iterator;
import java.util.Queue;

public class LinearTransportRoute implements TransportRoute {
    private Deque<Vector3f> route = Lists.newLinkedList();
    private float length;
    private long millisPerUnit;

    public LinearTransportRoute(Vector3f startingPosition, long millisPerUnit) {
        this.millisPerUnit = millisPerUnit;
        route.add(startingPosition);
    }

    public void addPoint(Vector3f point) {
        Vector3f last = route.getLast();
        route.add(point);
        length += point.distance(last);
    }

    @Override
    public long getTransportDuration() {
        return (long) (length * millisPerUnit);
    }

    @Override
    public Vector3f getPosition(long progress) {
        if (progress <= 0) {
            return route.getFirst();
        }
        if (progress >= getTransportDuration()) {
            return route.getLast();
        }

        float distanceTravelled = 0;
        Iterator<Vector3f> routeIterator = route.iterator();
        Vector3f lastPosition = routeIterator.next();
        while (routeIterator.hasNext()) {
            Vector3f nextPosition = routeIterator.next();
            float distanceFromLast = lastPosition.distance(nextPosition);

            float arrivalAtNextPosition = (distanceTravelled + distanceFromLast) * millisPerUnit;
            if (arrivalAtNextPosition > progress) {
                // The entity is somewhere between lastPosition and nextPosition
                float arrivalAtLastPosition = distanceTravelled * millisPerUnit;
                float progressBetweenPositions = (progress - arrivalAtLastPosition) / (arrivalAtNextPosition - arrivalAtLastPosition);
                return getPositionBetween(progressBetweenPositions, lastPosition, nextPosition);
            }
            distanceTravelled += distanceFromLast;
            lastPosition = nextPosition;
        }

        return null;
    }

    private Vector3f getPositionBetween(float progressBetweenPositions, Vector3f lastPosition, Vector3f nextPosition) {
        return new Vector3f(
                lastPosition.x + progressBetweenPositions * (nextPosition.x - lastPosition.x),
                lastPosition.y + progressBetweenPositions * (nextPosition.y - lastPosition.y),
                lastPosition.z + progressBetweenPositions * (nextPosition.z - lastPosition.z));
    }
}
