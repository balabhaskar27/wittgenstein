package net.consensys.wittgenstein.protocols.ethpow;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import net.consensys.wittgenstein.core.BlockChainNetwork;
import net.consensys.wittgenstein.core.NodeBuilder;
import net.consensys.wittgenstein.core.RegistryNetworkLatencies;
import net.consensys.wittgenstein.core.RegistryNodeBuilders;

/**
 * To call this agent from python: 1) Install the right tools:
 *
 * <pre>{@code
 * sudo pip3 install Cython
 * sudo pip3 install pyjnius
 * }</pre>
 *
 * <p>2) Build the package
 *
 * <pre>{@code
 * gradle clean shadowJar
 * }</pre>
 *
 * <p>3) You can now use this code from python, for example with:
 *
 * <pre>{@code
 * import jnius_config
 * jnius_config.set_classpath('.', './build/libs/wittgenstein-all.jar')
 * from jnius import autoclass
 * p = autoclass('net.consensys.wittgenstein.protocols.ethpow.ETHMinerAgent').create(0.25)
 * p.init()
 * p.goNextStep()
 * p.network().printNetworkLatency()
 * p.getByzNode().head.height
 * }</pre>
 */
public class ETHMinerAgent extends ETHMiner {

  /**
   * We allow the agent to decide if it publishes the block. So our head may differ from the other
   * miners' head. We keep track of the two information.
   */
  private ETHPoW.POWBlock privateMinerBlock;

  ETHPoW.POWBlock otherMinersHead = genesis;

  /** A boolean we use to decide if we want the agent to take a decision. */
  boolean decisionNeeded = false;

  public ETHMinerAgent(
      BlockChainNetwork<ETHPoW.POWBlock, ETHMiner> network,
      NodeBuilder nb,
      int hashPowerGHs,
      ETHPoW.POWBlock genesis) {
    super(network, nb, hashPowerGHs, genesis);
  }

  @Override
  protected boolean sendMinedBlock(ETHPoW.POWBlock mined) {
    return false;
  }

  public void sendMinedBlocks(int howMany) {
    if (!decisionNeeded) {
      throw new IllegalStateException("no action needed");
    }

    while (howMany-- > 0 && !minedToSend.isEmpty()) {
      actionSendOldestBlockMined();
    }
    if (howMany == 0 && this.inMining != null && privateMinerBlock != null) {
      startNewMining(privateMinerBlock);
    }
    if (minedToSend.isEmpty()) {
      privateMinerBlock = null;
    }
  }

  public boolean goNextStep() {
    decisionNeeded = false;
    while (!decisionNeeded) {
      network.runMs(1);
    }
    return true;
  }

  public int getSecretBlockSize() {
    return minedToSend.size();
  }

  public int getAdvance() {
    int priv = privateMinerBlock == null ? 0 : privateMinerBlock.height;
    int diff = priv - otherMinersHead.height;
    return Math.max(diff, 0);
  }

  public double getReward() {
    return head.allRewards().getOrDefault(this, 0.0);
  }

  public double getReward(int lastBlocksCount) {
    return head.allRewards(head.height - lastBlocksCount).getOrDefault(this, 0.0);
  }

  public double getRewardRatio() {
    HashMap<ETHMiner, Double> ar = head.allRewards();
    double all = ar.values().stream().mapToDouble(Double::doubleValue).sum();
    double me = ar.getOrDefault(this, 0.0);
    return me > 0 ? me / all : 0;
  }

  public boolean iAmAhead() {
    return head.producer == this;
  }

  public int countMyBlocks() {
    int count = 0;
    ETHPoW.POWBlock cur = head;
    while (cur != null) {
      if (cur.producer == this) {
        count++;
      }
      cur = cur.parent;
    }
    return count;
  }

  public static class ETHPowWithAgent extends ETHPoW {

    public ETHPowWithAgent(ETHPoWParameters params) {
      super(params);
    }

    public long getTimeInSeconds() {
      return this.network().time / 1000;
    }

    public ETHMinerAgent getByzNode() {
      return (ETHMinerAgent) network.allNodes.get(1);
    }
  }

  /**
   * In this agent we don't try to mine on old blocks nor we ask the agent to take the decision: we
   * always mine on the head (but it can be our private head).
   */
  @Override
  protected void onNewHead(ETHPoW.POWBlock oldHead, ETHPoW.POWBlock newHead) {
    startNewMining(newHead);
  }

  @Override
  protected void onReceivedBlock(ETHPoW.POWBlock rcv) {
    otherMinersHead = best(otherMinersHead, rcv);
    if (head == rcv) {
      decisionNeeded = true;
    }
    if (best(head, rcv) == head) {
      decisionNeeded = true;
    }
    if (!minedToSend.isEmpty()) {
      ETHPoW.POWBlock youngest =
          Collections.max(minedToSend, Comparator.comparingInt(o -> o.proposalTime));
      if (youngest.height - otherMinersHead.height < 0) {
        sendAllMined();
        startNewMining(otherMinersHead);
      }
    }
  }

  @Override
  protected void onMinedBlock(ETHPoW.POWBlock mined) {
    decisionNeeded = true;
    if (privateMinerBlock != null && mined.height <= privateMinerBlock.height) {
      throw new IllegalStateException(
          "privateMinerBlock=" + privateMinerBlock + ", mined=" + mined);
    }
    privateMinerBlock = mined;

    // startNewMining(privateMinerBlock);
  }

  public void actionSendOldestBlockMined() {
    ETHPoW.POWBlock oldest =
        Collections.min(minedToSend, Comparator.comparingInt(o -> o.proposalTime));
    if (oldest.height > otherMinersHead.height) {
      otherMinersHead = oldest;
    }
    sendBlock(oldest);
  }

  public static ETHPowWithAgent create(double byzHashPowerShare) {
    return create(byzHashPowerShare, System.nanoTime());
  }

  public static ETHPowWithAgent create(double byzHashPowerShare, long rdSeed) {
    final String bdlName = RegistryNodeBuilders.name(RegistryNodeBuilders.Location.CITIES, true, 0);
    final String nlName = RegistryNetworkLatencies.name(RegistryNetworkLatencies.Type.FIXED, 1000);

    ETHPoW.ETHPoWParameters params =
        new ETHPoW.ETHPoWParameters(
            bdlName, nlName, 10, ETHMinerAgent.class.getName(), byzHashPowerShare);

    ETHPowWithAgent res = new ETHPowWithAgent(params);
    res.network.rd.setSeed(rdSeed);
    return res;
  }

  public static void main(String... args) {
    final int runs = 4;
    final int hours = 6;
    final String bdlName = RegistryNodeBuilders.name(RegistryNodeBuilders.Location.RANDOM, true, 0);
    final String nlName = RegistryNetworkLatencies.name(RegistryNetworkLatencies.Type.FIXED, 1000);

    final double[] pows = new double[] {0.10, 0.40, 0.60};

    ETHMiner.tryMiner(bdlName, nlName, ETHSelfishMiner.class, pows, hours, runs);
    ETHMiner.tryMiner(bdlName, nlName, ETHMiner.class, pows, hours, runs);
  }
}
