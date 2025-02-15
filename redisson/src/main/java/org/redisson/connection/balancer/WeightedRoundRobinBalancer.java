/**
 * Copyright (c) 2013-2022 Nikita Koksharov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.redisson.connection.balancer;

import org.redisson.misc.WrappedLock;
import org.redisson.connection.ClientConnectionsEntry;
import org.redisson.misc.RedisURI;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Weighted Round Robin balancer.
 *
 * @author Nikita Koksharov
 *
 */
public class WeightedRoundRobinBalancer implements LoadBalancer {

    static class WeightEntry {

        final int weight;
        int weightCounter;

        WeightEntry(int weight) {
            this.weight = weight;
            this.weightCounter = weight;
        }

        public boolean isWeightCounterZero() {
            return weightCounter == 0;
        }

        public void decWeightCounter() {
            weightCounter--;
        }

        public void resetWeightCounter() {
            weightCounter = weight;
        }

    }

    private final AtomicInteger index = new AtomicInteger(-1);

    private final Map<RedisURI, WeightEntry> weights = new ConcurrentHashMap<>();

    private final int defaultWeight;

    private final WrappedLock lock = new WrappedLock();

    /**
     * Creates weighted round robin balancer.
     *
     * @param weights - weight mapped by slave node addr in <code>redis://host:port</code> format
     * @param defaultWeight - default weight value assigns to slaves not defined in weights map
     */
    public WeightedRoundRobinBalancer(Map<String, Integer> weights, int defaultWeight) {
        for (Entry<String, Integer> entry : weights.entrySet()) {
            RedisURI uri = new RedisURI(entry.getKey());
            if (entry.getValue() <= 0) {
                throw new IllegalArgumentException("Weight can't be less than or equal zero");
            }
            this.weights.put(uri, new WeightEntry(entry.getValue()));
        }
        if (defaultWeight <= 0) {
            throw new IllegalArgumentException("Weight can't be less than or equal zero");
        }

        this.defaultWeight = defaultWeight;
    }

    @Override
    public ClientConnectionsEntry getEntry(List<ClientConnectionsEntry> clients) {
        List<ClientConnectionsEntry> usedClients = findClients(clients, weights);
        for (ClientConnectionsEntry e : clients) {
            if (usedClients.contains(e)) {
                continue;
            }
            weights.put(e.getClient().getConfig().getAddress(), new WeightEntry(defaultWeight));
        }

        return lock.execute(() -> {
            Map<RedisURI, WeightEntry> weightsCopy = new HashMap<>(weights);
            weightsCopy.values().removeIf(WeightEntry::isWeightCounterZero);

            if (weightsCopy.isEmpty()) {
                for (WeightEntry entry : weights.values()) {
                    entry.resetWeightCounter();
                }

                weightsCopy = weights;
            }

            List<ClientConnectionsEntry> clientsCopy = findClients(clients, weightsCopy);

            // If there are no connections available to servers that have a weight counter
            // remaining, then reset the weight counters and find a connection again. In the worst
            // case, there should always be a connection to the master.
            if (clientsCopy.isEmpty()) {
                for (WeightEntry entry : weights.values()) {
                    entry.resetWeightCounter();
                }

                weightsCopy = weights;
                clientsCopy = findClients(clients, weightsCopy);
            }

            int ind = Math.abs(index.incrementAndGet() % clientsCopy.size());
            ClientConnectionsEntry entry = clientsCopy.get(ind);
            for (Entry<RedisURI, WeightEntry> weightEntry : weightsCopy.entrySet()) {
                if (weightEntry.getKey().equals(entry.getClient().getAddr())) {
                    weightEntry.getValue().decWeightCounter();
                    break;
                }
            }
            return entry;
        });
    }

    private List<ClientConnectionsEntry> findClients(List<ClientConnectionsEntry> clients,
                                                        Map<RedisURI, WeightEntry> weightsCopy) {
        return clients.stream()
                        .filter(e -> {
                            for (RedisURI redisURI : weightsCopy.keySet()) {
                                if (redisURI.equals(e.getClient().getAddr())) {
                                    return true;
                                }
                            }
                            return false;
                        })
                        .collect(Collectors.toList());
    }

}
