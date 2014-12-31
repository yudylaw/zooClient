/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.yudylaw.demo.nio.server.quorum;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * This class manages the quorum protocol. There are three states this server
 * can be in:
 * <ol>
 * <li>Leader election - each server will elect a leader (proposing itself as a
 * leader initially).</li>
 * <li>Follower - the server will synchronize with the leader and replicate any
 * transactions.</li>
 * <li>Leader - the server will process requests and forward them to followers.
 * A majority of followers must log the request before it can be accepted.
 * </ol>
 *
 * This class will setup a datagram socket that will always respond with its
 * view of the current leader. The response will take the form of:
 *
 * <pre>
 * int xid;
 *
 * long myid;
 *
 * long leader_id;
 *
 * long leader_zxid;
 * </pre>
 *
 * The request for the current leader will consist solely of an xid: int xid;
 */
public class QuorumPeer extends Thread {
    private static final Logger LOG = LoggerFactory.getLogger(QuorumPeer.class);

    QuorumCnxManager qcm;

    /* ZKDatabase is a top level member of quorumpeer 
     * which will be used in all the zookeeperservers
     * instantiated later. Also, it is created once on 
     * bootup and only thrown away in case of a truncate
     * message from the leader
     */
//    private ZKDatabase zkDb;

    public static class QuorumServer {
        public QuorumServer(long id, InetSocketAddress addr,
                InetSocketAddress electionAddr) {
            this.id = id;
            this.addr = addr;
            this.electionAddr = electionAddr;
        }

        public QuorumServer(long id, InetSocketAddress addr) {
            this.id = id;
            this.addr = addr;
            this.electionAddr = null;
        }
        
        public QuorumServer(long id, InetSocketAddress addr,
                    InetSocketAddress electionAddr, LearnerType type) {
            this.id = id;
            this.addr = addr;
            this.electionAddr = electionAddr;
            this.type = type;
        }
        
        public InetSocketAddress addr;

        public InetSocketAddress electionAddr;
        
        public long id;
        
        public LearnerType type = LearnerType.PARTICIPANT;
    }

    public enum ServerState {
        LOOKING, FOLLOWING, LEADING, OBSERVING;
    }
    
    /*
     * A peer can either be participating, which implies that it is willing to
     * both vote in instances of consensus and to elect or become a Leader, or
     * it may be observing in which case it isn't.
     * 
     * We need this distinction to decide which ServerState to move to when 
     * conditions change (e.g. which state to become after LOOKING). 
     */
    public enum LearnerType {
        PARTICIPANT, OBSERVER;
    }
    
    /*
     * To enable observers to have no identifier, we need a generic identifier
     * at least for QuorumCnxManager. We use the following constant to as the
     * value of such a generic identifier. 
     */
    
    static final long OBSERVER_ID = Long.MAX_VALUE;

    /*
     * Record leader election time
     */
    public long start_fle, end_fle;
    
    /*
     * Default value of peer is participant
     */
    private LearnerType learnerType = LearnerType.PARTICIPANT;
    
    public LearnerType getLearnerType() {
        return learnerType;
    }
    
    /**
     * Sets the LearnerType both in the QuorumPeer and in the peerMap
     */
    public void setLearnerType(LearnerType p) {
        learnerType = p;
        if (quorumPeers.containsKey(this.myid)) {
            this.quorumPeers.get(myid).type = p;
        } else {
            LOG.error("Setting LearnerType to " + p + " but " + myid 
                    + " not in QuorumPeers. ");
        }
        
    }
    /**
     * The servers that make up the cluster
     */
    protected Map<Long, QuorumServer> quorumPeers;
    public int getQuorumSize(){
        return getVotingView().size();
    }
    
    private QuorumConfig config;
    
    /**
     * My id
     */
    private long myid;


    /**
     * get the id of this quorum peer.
     */
    public long getId() {
        return myid;
    }

    /**
     * This is who I think the leader currently is.
     */
    volatile private Vote currentVote;
    
    /**
     * ... and its counterpart for backward compatibility
     */
    volatile private Vote bcVote;
        
    public synchronized Vote getCurrentVote(){
        return currentVote;
    }
       
    public synchronized void setCurrentVote(Vote v){
        currentVote = v;
    }
    
    synchronized Vote getBCVote() {
        if (bcVote == null) {
            return currentVote;
        } else {
            return bcVote;
        }
    }

    synchronized void setBCVote(Vote v) {
        bcVote = v;
    }
    
    volatile boolean running = true;

    /**
     * The number of milliseconds of each tick
     */
    protected int tickTime;

    /**
     * Minimum number of milliseconds to allow for session timeout.
     * A value of -1 indicates unset, use default.
     */
    protected int minSessionTimeout = -1;

    /**
     * Maximum number of milliseconds to allow for session timeout.
     * A value of -1 indicates unset, use default.
     */
    protected int maxSessionTimeout = -1;

    /**
     * The number of ticks that the initial synchronization phase can take
     */
    protected int initLimit;

    /**
     * The number of ticks that can pass between sending a request and getting
     * an acknowledgment
     */
    protected int syncLimit;
    
    /**
     * Enables/Disables sync request processor. This option is enabled
     * by default and is to be used with observers.
     */
    protected boolean syncEnabled = true;

    /**
     * The current tick
     */
    protected volatile int tick;

    /**
     * Whether or not to listen on all IPs for the two quorum ports
     * (broadcast and fast leader election).
     */
    protected boolean quorumListenOnAllIPs = false;

    /**
     * @deprecated As of release 3.4.0, this class has been deprecated, since
     * it is used with one of the udp-based versions of leader election, which
     * we are also deprecating. 
     * 
     * This class simply responds to requests for the current leader of this
     * node.
     * <p>
     * The request contains just an xid generated by the requestor.
     * <p>
     * The response has the xid, the id of this server, the id of the leader,
     * and the zxid of the leader.
     *
     *
     */
    @Deprecated

    private ServerState state = ServerState.LOOKING;

    public synchronized void setPeerState(ServerState newState){
        state=newState;
    }

    public synchronized ServerState getPeerState(){
        return state;
    }

    private InetSocketAddress myQuorumAddr;

    public InetSocketAddress getQuorumAddress(){
        return myQuorumAddr;
    }

    Election electionAlg;
    
    public QuorumPeer(QuorumConfig config) throws IOException {
        super("QuorumPeer");
        this.config = config;
        this.quorumPeers = QuorumConfig.quorumPeers;
        this.myid = config.myid;
        this.tickTime = config.tickTime;
        this.initLimit = config.initLimit;
        this.syncLimit = config.syncLimit;        
        this.quorumListenOnAllIPs = config.quorumListenOnAllIPs;
        this.acceptedEpoch = config.localEpoch;
        this.currentEpoch = config.localEpoch;
    }
    
    @Override
    public synchronized void start() {
        loadDataBase();
        startLeaderElection();
        super.start();
    }

    private void loadDataBase() {
        LOG.debug("mock loadDataBase");
    }
    
    synchronized public void startLeaderElection() {
        try {
            currentVote = new Vote(myid, getLastLoggedZxid(), getCurrentEpoch());
        } catch(IOException e) {
            RuntimeException re = new RuntimeException(e.getMessage());
            re.setStackTrace(e.getStackTrace());
            throw re;
        }
        for (QuorumServer p : getView().values()) {
            if (p.id == myid) {
                myQuorumAddr = p.addr;
                break;
            }
        }
        if (myQuorumAddr == null) {
            throw new RuntimeException("My id " + myid + " not in the peer list");
        }
        this.electionAlg = createElectionAlgorithm();
    }
    
    /**
     * Count the number of nodes in the map that could be followers.
     * @param peers
     * @return The number of followers in the map
     */
    protected static int countParticipants(Map<Long,QuorumServer> peers) {
      int count = 0;
      for (QuorumServer q : peers.values()) {
          if (q.type == LearnerType.PARTICIPANT) {
              count++;
          }
      }
      return count;
    }
    
    /**
     * returns the highest zxid that this host has seen
     * 
     * @return the highest zxid for this host
     */
    public long getLastLoggedZxid() {
        //TODO mock
        return config.localZxid;
    }

    protected Election createElectionAlgorithm(){
        Election le=null;
        qcm = new QuorumCnxManager(this);
        QuorumCnxManager.Listener listener = qcm.listener;
        if(listener != null){
            listener.start();
            le = new FastLeaderElection(this, qcm);
        } else {
            LOG.error("Null listener when initializing cnx manager");
        }
        return le;
    }

    @Override
    public void run() {
        setName("QuorumPeer" + "[myid=" + getId() + "]");

        LOG.debug("Starting quorum peer");
        
        try {
            /*
             * Main loop
             */
            while (running) {
                switch (getPeerState()) {
                    case LOOKING:
                        LOG.info( myid + " LOOKING");
                        try {
                            setBCVote(null);
                            setCurrentVote(electionAlg.lookForLeader());
                            LOG.debug(myid + " lookForLeader finished, state is: " + getPeerState());
                        } catch (Exception e) {
                            LOG.warn("Unexpected exception", e);
                            setPeerState(ServerState.LOOKING);
                        }
                        break;
                    case OBSERVING:
                        LOG.info( myid + " OBSERVING");
                        setPeerState(ServerState.OBSERVING);
                        try {
                            Thread.sleep(Long.MAX_VALUE);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                        //TODO 连接Leader
                        break;
                    case FOLLOWING:
                        LOG.info( myid + " FOLLOWING");
                        try {
                            Thread.sleep(Long.MAX_VALUE);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                        //TODO 连接Leader
                        break;
                    case LEADING:
                        LOG.info( myid + " LEADING");
                        setPeerState(ServerState.LEADING);
                        try {
                            Thread.sleep(Long.MAX_VALUE);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                        break;
                }
            }
        } finally {
            LOG.warn("QuorumPeer main thread exited");
        }
    }

    public void shutdown() {
        running = false;
        if(getElectionAlg() != null){
            this.interrupt();
            getElectionAlg().shutdown();
        }
    }

    /**
     * A 'view' is a node's current opinion of the membership of the entire
     * ensemble.    
     */
    public Map<Long,QuorumPeer.QuorumServer> getView() {
        return Collections.unmodifiableMap(this.quorumPeers);
    }
    
    /**
     * Observers are not contained in this view, only nodes with 
     * PeerType=PARTICIPANT.     
     */
    public Map<Long,QuorumPeer.QuorumServer> getVotingView() {
        Map<Long,QuorumPeer.QuorumServer> ret = 
            new HashMap<Long, QuorumPeer.QuorumServer>();
        Map<Long,QuorumPeer.QuorumServer> view = getView();
        for (QuorumServer server : view.values()) {            
            if (server.type == LearnerType.PARTICIPANT) {
                ret.put(server.id, server);
            }
        }        
        return ret;
    }
    
    /**
     * Returns only observers, no followers.
     */
    public Map<Long,QuorumPeer.QuorumServer> getObservingView() {
        Map<Long,QuorumPeer.QuorumServer> ret = 
            new HashMap<Long, QuorumPeer.QuorumServer>();
        Map<Long,QuorumPeer.QuorumServer> view = getView();
        for (QuorumServer server : view.values()) {            
            if (server.type == LearnerType.OBSERVER) {
                ret.put(server.id, server);
            }
        }        
        return ret;
    }
    
    /**
     * Check if a node is in the current view. With static membership, the
     * result of this check will never change; only when dynamic membership
     * is introduced will this be more useful.
     */
    public boolean viewContains(Long sid) {
        return this.quorumPeers.containsKey(sid);
    }

    /**
     * get the id of this quorum peer.
     */
    public long getMyid() {
        return myid;
    }

    /**
     * set the id of this quorum peer.
     */
    public void setMyid(long myid) {
        this.myid = myid;
    }

    /**
     * Get the number of milliseconds of each tick
     */
    public int getTickTime() {
        return tickTime;
    }

    /**
     * Set the number of milliseconds of each tick
     */
    public void setTickTime(int tickTime) {
        LOG.info("tickTime set to " + tickTime);
        this.tickTime = tickTime;
    }
    
    /** minimum session timeout in milliseconds */
    public int getMinSessionTimeout() {
        return minSessionTimeout == -1 ? tickTime * 2 : minSessionTimeout;
    }

    /** minimum session timeout in milliseconds */
    public void setMinSessionTimeout(int min) {
        LOG.info("minSessionTimeout set to " + min);
        this.minSessionTimeout = min;
    }

    /** maximum session timeout in milliseconds */
    public int getMaxSessionTimeout() {
        return maxSessionTimeout == -1 ? tickTime * 20 : maxSessionTimeout;
    }

    /** minimum session timeout in milliseconds */
    public void setMaxSessionTimeout(int max) {
        LOG.info("maxSessionTimeout set to " + max);
        this.maxSessionTimeout = max;
    }

    /**
     * Get the number of ticks that the initial synchronization phase can take
     */
    public int getInitLimit() {
        return initLimit;
    }

    /**
     * Set the number of ticks that the initial synchronization phase can take
     */
    public void setInitLimit(int initLimit) {
        LOG.info("initLimit set to " + initLimit);
        this.initLimit = initLimit;
    }

    /**
     * Get the current tick
     */
    public int getTick() {
        return tick;
    }
    
    /**
     * Get an instance of LeaderElection
     */
        
    public Election getElectionAlg(){
        return electionAlg;
    }
        
    /**
     * Get the synclimit
     */
    public int getSyncLimit() {
        return syncLimit;
    }

    /**
     * Set the synclimit
     */
    public void setSyncLimit(int syncLimit) {
        this.syncLimit = syncLimit;
    }
    
    
    /**
     * The syncEnabled can also be set via a system property.
     */
    public static final String SYNC_ENABLED = "zookeeper.observer.syncEnabled";
    
    /**
     * Return syncEnabled.
     * 
     * @return
     */
    public boolean getSyncEnabled() {
        if (System.getProperty(SYNC_ENABLED) != null) {
            LOG.info(SYNC_ENABLED + "=" + Boolean.getBoolean(SYNC_ENABLED));   
            return Boolean.getBoolean(SYNC_ENABLED);
        } else {        
            return syncEnabled;
        }
    }
    
    /**
     * Set syncEnabled.
     * 
     * @param syncEnabled
     */
    public void setSyncEnabled(boolean syncEnabled) {
        this.syncEnabled = syncEnabled;
    }

    public boolean getQuorumListenOnAllIPs() {
        return quorumListenOnAllIPs;
    }

    public void setQuorumListenOnAllIPs(boolean quorumListenOnAllIPs) {
        this.quorumListenOnAllIPs = quorumListenOnAllIPs;
    }

    public void setQuorumPeers(Map<Long,QuorumServer> quorumPeers) {
        this.quorumPeers = quorumPeers;
    }

    public void setClientPortAddress(InetSocketAddress addr) {
    }

    public void setRunning(boolean running) {
        this.running = running;
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * get reference to QuorumCnxManager
     */
    public QuorumCnxManager getQuorumCnxManager() {
        return qcm;
    }
    
    private long acceptedEpoch = -1;
    private long currentEpoch = -1;

    public static final String CURRENT_EPOCH_FILENAME = "currentEpoch";

    public static final String ACCEPTED_EPOCH_FILENAME = "acceptedEpoch";

    public static final String UPDATING_EPOCH_FILENAME = "updatingEpoch";

    public long getCurrentEpoch() throws IOException {
//        if (currentEpoch == -1) {
//            currentEpoch = readLongFromFile(CURRENT_EPOCH_FILENAME);
//        }
        return currentEpoch;
    }
    
    public long getAcceptedEpoch() throws IOException {
//        if (acceptedEpoch == -1) {
//            acceptedEpoch = readLongFromFile(ACCEPTED_EPOCH_FILENAME);
//        }
        return acceptedEpoch;
    }

    /**
     * Updates leader election info to avoid inconsistencies when
     * a new server tries to join the ensemble.
     * See ZOOKEEPER-1732 for more info.
     */
    protected void updateElectionVote(long newEpoch) {
        Vote currentVote = getCurrentVote();
        setBCVote(currentVote);
        if (currentVote != null) {
            setCurrentVote(new Vote(currentVote.getId(),
                currentVote.getZxid(),
                currentVote.getElectionEpoch(),
                newEpoch,
                currentVote.getState()));
        }
    }

}
