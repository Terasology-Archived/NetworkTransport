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

import org.junit.Test;
import org.terasology.math.geom.Vector3f;

import static org.junit.Assert.*;

public class LinearTransportRouteTest {
    @Test
    public void testOnePoint() {
        LinearTransportRoute route = new LinearTransportRoute(new Vector3f(0, 0, 0), 1000);
        assertEquals(0, route.getTransportDuration());
        assertEquals(new Vector3f(0, 0, 0), route.getPosition(0));
        assertEquals(new Vector3f(0, 0, 0), route.getPosition(1000));
    }

    @Test
    public void testWithTwoPoints() {
        LinearTransportRoute route = new LinearTransportRoute(new Vector3f(0, 0, 0), 1000);
        route.addPoint(new Vector3f(3, 0, 0));
        assertEquals(3000, route.getTransportDuration());
        assertEquals(new Vector3f(0, 0, 0), route.getPosition(0));
        assertEquals(new Vector3f(1, 0, 0), route.getPosition(1000));
        assertEquals(new Vector3f(1.5f, 0, 0), route.getPosition(1500));
        assertEquals(new Vector3f(3, 0, 0), route.getPosition(3000));
        assertEquals(new Vector3f(3, 0, 0), route.getPosition(5000));
    }

    @Test
    public void testWithThreePoints() {
        LinearTransportRoute route = new LinearTransportRoute(new Vector3f(0, 0, 0), 1000);
        route.addPoint(new Vector3f(3, 0, 0));
        route.addPoint(new Vector3f(3, 2, 0));
        assertEquals(5000, route.getTransportDuration());
        assertEquals(new Vector3f(0, 0, 0), route.getPosition(0));
        assertEquals(new Vector3f(1, 0, 0), route.getPosition(1000));
        assertEquals(new Vector3f(1.5f, 0, 0), route.getPosition(1500));
        assertEquals(new Vector3f(3, 0, 0), route.getPosition(3000));
        assertEquals(new Vector3f(3, 1.5f, 0), route.getPosition(4500));
        assertEquals(new Vector3f(3, 2, 0), route.getPosition(5000));
        assertEquals(new Vector3f(3, 2, 0), route.getPosition(7000));
    }
}
