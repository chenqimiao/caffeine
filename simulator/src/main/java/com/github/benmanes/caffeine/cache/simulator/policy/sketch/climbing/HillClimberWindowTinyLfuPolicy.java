/*
 * Copyright 2016 Ben Manes. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing;

import static com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.HillClimber.QueueType.PROBATION;
import static com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.HillClimber.QueueType.PROTECTED;
import static com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.HillClimber.QueueType.WINDOW;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.Sets.toImmutableEnumSet;
import static java.util.Locale.US;
import static java.util.Objects.requireNonNull;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.admission.Admission;
import com.github.benmanes.caffeine.cache.simulator.admission.Admittor;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.KeyOnlyPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.PolicySpec;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.HillClimber.Adaptation;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.HillClimber.QueueType;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.Var;
import com.typesafe.config.Config;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * The Window TinyLfu algorithm where the size of the admission window is adjusted using a hill
 * climbing algorithm.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@PolicySpec(name = "sketch.HillClimberWindowTinyLfu")
public final class HillClimberWindowTinyLfuPolicy implements KeyOnlyPolicy {
  private static final boolean debug = false;
  private static final boolean trace = false;

  private final double initialPercentMain;
  private final Long2ObjectMap<Node> data;
  private final PolicyStats policyStats;
  private final HillClimber climber;
  private final Admittor admittor;
  private final int maximumSize;

  private final Node headWindow;
  private final Node headProbation;
  private final Node headProtected;

  private int maxWindow;
  private int maxProtected;

  private double windowSize;
  private double protectedSize;

  @SuppressWarnings("Varifier")
  public HillClimberWindowTinyLfuPolicy(HillClimberType strategy,
      double percentMain, HillClimberWindowTinyLfuSettings settings) {
    this.maximumSize = Math.toIntExact(settings.maximumSize());
    int maxMain = (int) (maximumSize * percentMain);
    this.maxProtected = (int) (maxMain * settings.percentMainProtected());
    this.maxWindow = maximumSize - maxMain;

    this.data = new Long2ObjectOpenHashMap<>();
    this.headProtected = new Node();
    this.headProbation = new Node();
    this.headWindow = new Node();

    this.initialPercentMain = percentMain;
    this.policyStats = new PolicyStats(name() + " (%s %.0f%%)",
        strategy.name().toLowerCase(US), 100 * (1.0 - initialPercentMain));
    this.admittor = Admission.TINYLFU.from(settings.config(), policyStats);
    this.climber = strategy.create(settings.config());

    printSegmentSizes();
  }

  /** Returns all variations of this policy based on the configuration parameters. */
  public static Set<Policy> policies(Config config) {
    var policies = new HashSet<Policy>();
    var settings = new HillClimberWindowTinyLfuSettings(config);
    for (HillClimberType climber : settings.strategy()) {
      for (double percentMain : settings.percentMain()) {
        policies.add(new HillClimberWindowTinyLfuPolicy(climber, percentMain, settings));
      }
    }
    return policies;
  }

  @Override
  public PolicyStats stats() {
    return policyStats;
  }

  @Override
  public void record(long key) {
    boolean isFull = (data.size() >= maximumSize);
    policyStats.recordOperation();
    Node node = data.get(key);
    admittor.record(key);

    @Var QueueType queue = null;
    if (node == null) {
      onMiss(key);
      policyStats.recordMiss();
    } else {
      onHit(node);
      queue = node.queue;
    }
    climb(key, queue, isFull);
  }

  /** Adds the entry to the admission window, evicting if necessary. */
  private void onMiss(long key) {
    var node = new Node(key, WINDOW);
    node.appendToTail(headWindow);
    data.put(key, node);
    windowSize++;
    evict();
  }

  /** Moves or promotes as if necessary. */
  private void onHit(Node node) {
    requireNonNull(node.queue);
    policyStats.recordHit();
    switch (node.queue) {
      case WINDOW -> onWindowHit(node);
      case PROBATION -> onProbationHit(node);
      case PROTECTED -> onProtectedHit(node);
    }
  }

  /** Moves the entry to the MRU position in the admission window. */
  private void onWindowHit(Node node) {
    node.moveToTail(headWindow);
  }

  /** Promotes the entry to the protected region's MRU position, demoting an entry if necessary. */
  private void onProbationHit(Node node) {
    node.remove();
    node.queue = PROTECTED;
    node.appendToTail(headProtected);

    protectedSize++;
    demoteProtected();
  }

  private void demoteProtected() {
    if (protectedSize > maxProtected) {
      Node demote = requireNonNull(headProtected.next);
      demote.remove();
      demote.queue = PROBATION;
      demote.appendToTail(headProbation);
      protectedSize--;
    }
  }

  /** Moves the entry to the MRU position if it falls outside of the fast-path threshold. */
  private void onProtectedHit(Node node) {
    node.moveToTail(headProtected);
  }

  /**
   * Evicts from the admission window into the probation space. If the size exceeds the maximum,
   * then the admission candidate and probation's victim are evaluated and one is evicted.
   */
  private void evict() {
    if (windowSize <= maxWindow) {
      return;
    }

    Node candidate = requireNonNull(headWindow.next);
    windowSize--;

    candidate.remove();
    candidate.queue = PROBATION;
    candidate.appendToTail(headProbation);

    if (data.size() > maximumSize) {
      Node victim = requireNonNull(headProbation.next);
      Node evict = admittor.admit(candidate.key, victim.key) ? victim : candidate;
      data.remove(evict.key);
      evict.remove();

      policyStats.recordEviction();
    }
  }

  /** Performs the hill climbing process. */
  private void climb(long key, @Nullable QueueType queue, boolean isFull) {
    if (queue == null) {
      climber.onMiss(key, isFull);
    } else {
      climber.onHit(key, queue, isFull);
    }

    double probationSize = maximumSize - windowSize - protectedSize;
    Adaptation adaptation = climber.adapt(windowSize, probationSize, protectedSize, isFull);
    switch (adaptation.type()) {
      case INCREASE_WINDOW -> increaseWindow(adaptation.amount());
      case DECREASE_WINDOW -> decreaseWindow(adaptation.amount());
      case HOLD -> {}
    }
  }

  private void increaseWindow(double amount) {
    checkState(amount >= 0.0);
    if (maxProtected == 0) {
      return;
    }

    double quota = Math.min(amount, maxProtected);
    int steps = (int) (windowSize + quota) - (int) windowSize;
    windowSize += quota;

    for (int i = 0; i < steps; i++) {
      maxWindow++;
      maxProtected--;

      demoteProtected();
      requireNonNull(headProbation.next);

      Node candidate = headProbation.next;
      candidate.remove();
      candidate.queue = WINDOW;
      candidate.appendToTail(headWindow);
    }
    checkState(windowSize >= 0);
    checkState(maxWindow >= 0);
    checkState(maxProtected >= 0);

    if (trace) {
      System.out.printf("+%,d (%,d -> %,d)%n", steps, maxWindow - steps, maxWindow);
    }
  }

  private void decreaseWindow(double amount) {
    checkState(amount >= 0.0);
    if (maxWindow == 0) {
      return;
    }

    double quota = Math.min(amount, maxWindow);
    int steps = (int) windowSize - (int) (windowSize - quota);
    windowSize -= quota;

    for (int i = 0; i < steps; i++) {
      maxWindow--;
      maxProtected++;
      requireNonNull(headWindow.next);

      Node candidate = headWindow.next;
      candidate.remove();
      candidate.queue = PROBATION;
      candidate.appendToHead(headProbation);
    }
    checkState(windowSize >= 0);
    checkState(maxWindow >= 0);
    checkState(maxProtected >= 0);

    if (trace) {
      System.out.printf("-%,d (%,d -> %,d)%n", steps, maxWindow + steps, maxWindow);
    }
  }

  private void printSegmentSizes() {
    if (debug) {
      System.out.printf("maxWindow=%d, maxProtected=%d, percentWindow=%.1f",
          maxWindow, maxProtected, (100.0 * maxWindow) / maximumSize);
    }
  }

  @Override
  public void finished() {
    policyStats.setPercentAdaption(
        (maxWindow / (double) maximumSize) - (1.0 - initialPercentMain));
    printSegmentSizes();

    long actualWindowSize = data.values().stream().filter(n -> n.queue == WINDOW).count();
    long actualProbationSize = data.values().stream().filter(n -> n.queue == PROBATION).count();
    long actualProtectedSize = data.values().stream().filter(n -> n.queue == PROTECTED).count();
    long calculatedProbationSize = data.size() - actualWindowSize - actualProtectedSize;

    checkState((long) windowSize == actualWindowSize,
        "Window: %s != %s", (long) windowSize, actualWindowSize);
    checkState((long) protectedSize == actualProtectedSize,
        "Protected: %s != %s", (long) protectedSize, actualProtectedSize);
    checkState(actualProbationSize == calculatedProbationSize,
        "Probation: %s != %s", actualProbationSize, calculatedProbationSize);
    checkState(data.size() <= maximumSize, "Maximum: %s > %s", data.size(), maximumSize);
  }

  /** A node on the double-linked list. */
  static final class Node {
    final long key;

    @Nullable Node prev;
    @Nullable Node next;
    @Nullable QueueType queue;

    /** Creates a new sentinel node. */
    public Node() {
      this.key = Integer.MIN_VALUE;
      this.prev = this;
      this.next = this;
    }

    /** Creates a new, unlinked node. */
    public Node(long key, QueueType queue) {
      this.queue = queue;
      this.key = key;
    }

    public void moveToTail(Node head) {
      remove();
      appendToTail(head);
    }

    /** Appends the node to the tail of the list. */
    public void appendToHead(Node head) {
      Node first = requireNonNull(head.next);
      head.next = this;
      first.prev = this;
      prev = head;
      next = first;
    }

    /** Appends the node to the tail of the list. */
    public void appendToTail(Node head) {
      Node tail = requireNonNull(head.prev);
      head.prev = this;
      tail.next = this;
      next = head;
      prev = tail;
    }

    /** Removes the node from the list. */
    public void remove() {
      requireNonNull(prev);
      requireNonNull(next);

      prev.next = next;
      next.prev = prev;
      next = prev = null;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("key", key)
          .add("queue", queue)
          .toString();
    }
  }

  public static final class HillClimberWindowTinyLfuSettings extends BasicSettings {
    public HillClimberWindowTinyLfuSettings(Config config) {
      super(config);
    }
    public List<Double> percentMain() {
      return config().getDoubleList("hill-climber-window-tiny-lfu.percent-main");
    }
    public double percentMainProtected() {
      return config().getDouble("hill-climber-window-tiny-lfu.percent-main-protected");
    }
    public ImmutableSet<HillClimberType> strategy() {
      return config().getStringList("hill-climber-window-tiny-lfu.strategy").stream()
          .map(strategy -> strategy.replace('-', '_').toUpperCase(US))
          .map(HillClimberType::valueOf)
          .collect(toImmutableEnumSet());
    }
  }
}
