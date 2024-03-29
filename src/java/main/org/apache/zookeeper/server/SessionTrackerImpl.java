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

package org.apache.zookeeper.server;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.SessionExpiredException;

/**
 * This is a full featured SessionTracker. It tracks session in grouped by tick
 * interval. It always rounds up the tick interval to provide a sort of grace
 * period. Sessions are thus expired in batches made up of sessions that expire
 * in a given interval.
 */
public class SessionTrackerImpl extends Thread implements SessionTracker {
    private static final Logger LOG = LoggerFactory.getLogger(SessionTrackerImpl.class);

    /**
     * sessionId 和session实例的映射
     */
    HashMap<Long, SessionImpl> sessionsById = new HashMap<Long, SessionImpl>();

    /**
     * 过期时刻 和 处理过后相同过期时刻的session集合 map
     */
    HashMap<Long, SessionSet> sessionSets = new HashMap<Long, SessionSet>();

    /**
     * sessionId 对应 过期时间的map
     */
    ConcurrentHashMap<Long, Integer> sessionsWithTimeout;
    long nextSessionId = 0;
    long nextExpirationTime;

    int expirationInterval;

    public static class SessionImpl implements Session {
        SessionImpl(long sessionId, int timeout, long expireTime) {
            this.sessionId = sessionId;
            this.timeout = timeout;
            this.tickTime = expireTime;
            isClosing = false;
        }

        final long sessionId;
        final int timeout;
        long tickTime;
        boolean isClosing;

        Object owner;

        public long getSessionId() { return sessionId; }
        public int getTimeout() { return timeout; }
        public boolean isClosing() { return isClosing; }
    }

    /**
     * 初始化sessionId 后续创建session的时候 只需要 nextSessionId++ 即可
     * 比如当前时间戳为1584800165867
     * 转为二进制为
     *  0000 0000 0000 0000 0000 0001 0111 0000 1111 1101 0111 0011 0110 1111 1110 1011
     * 左移24位 后
     * 0001 0111 0000 1111 1101 0111 0011 0110 1111 1110 1011 0000 0000 0000 0000 0000
     * 右移8位 后
     * 0000 0000 0001 0111 0000 1111 1101 0111 0011 0110 1111 1110 1011 0000 0000 0000
     * 把时间戳移动到了中间
     *
     * 节点id  myid = 1 的二进制
     * 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0001
     *  左移 id << 56
     * 0000 0001 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000
     *
     * nextSid | id << 56
     * 0000 0001 0001 0111 0000 1111 1101 0111 0011 0110 1111 1110 1011 0000 0000 0000
     *
     * 这样 session id 初始化的时候就具有 节点id 和 时间戳的特点，保证了全局唯一
     * @param id
     * @return
     */
    public static long initializeNextSession(long id) {
        long nextSid = 0;
        // 初始化 sessionId，使之同时具有节点myid和时间戳的特性，保证全局唯一
        nextSid = (System.currentTimeMillis() << 24) >> 8;
        nextSid =  nextSid | (id <<56);
        return nextSid;
    }

    static class SessionSet {
        HashSet<SessionImpl> sessions = new HashSet<SessionImpl>();
    }

    SessionExpirer expirer;

    /**
     * 过期时刻处理为 tickTime 的倍数
     * @param time
     * @return
     */
    private long roundToInterval(long time) {
        // We give a one interval grace period
        // expirationInterval 默认为tickTime=2秒
        return (time / expirationInterval + 1) * expirationInterval;
    }

    public SessionTrackerImpl(SessionExpirer expirer,
            ConcurrentHashMap<Long, Integer> sessionsWithTimeout, int tickTime,
            long sid)
    {
        super("SessionTracker");
        this.expirer = expirer;
        this.expirationInterval = tickTime;
        this.sessionsWithTimeout = sessionsWithTimeout;
        nextExpirationTime = roundToInterval(System.currentTimeMillis());

        for (Entry<Long, Integer> e : sessionsWithTimeout.entrySet()) {
            addSession(e.getKey(), e.getValue());
        }
    }

    volatile boolean running = true;

    volatile long currentTime;

    synchronized public void dumpSessions(PrintWriter pwriter) {
        pwriter.print("Session Sets (");
        pwriter.print(sessionSets.size());
        pwriter.println("):");
        ArrayList<Long> keys = new ArrayList<Long>(sessionSets.keySet());
        Collections.sort(keys);
        for (long time : keys) {
            pwriter.print(sessionSets.get(time).sessions.size());
            pwriter.print(" expire at ");
            pwriter.print(new Date(time));
            pwriter.println(":");
            for (SessionImpl s : sessionSets.get(time).sessions) {
                pwriter.print("\t0x");
                pwriter.println(Long.toHexString(s.sessionId));
            }
        }
    }

    @Override
    synchronized public String toString() {
        StringWriter sw = new StringWriter();
        PrintWriter pwriter = new PrintWriter(sw);
        dumpSessions(pwriter);
        pwriter.flush();
        pwriter.close();
        return sw.toString();
    }

    @Override
    synchronized public void run() {
        try {
            while (running) {
                currentTime = System.currentTimeMillis();
                // 如果还没到下次检查时间，就先等待
                if (nextExpirationTime > currentTime) {
                    this.wait(nextExpirationTime - currentTime);
                    continue;
                }
                // 将到期时间对应的 session 集合取出来
                SessionSet set;
                set = sessionSets.remove(nextExpirationTime);
                if (set != null) {
                    for (SessionImpl s : set.sessions) {
                        // 逐个设置session的状态为关闭
                        setSessionClosing(s.sessionId);
                        // 提交一个  OpCode.closeSession 请求，
                        // ZookeeperServer在finalRequestProcessor处理的时候会关闭网络连接
                        expirer.expire(s);
                    }
                }
                // 下一个到期时间 +2秒
                nextExpirationTime += expirationInterval;
            }
        } catch (InterruptedException e) {
            LOG.error("Unexpected interruption", e);
        }
        LOG.info("SessionTrackerImpl exited loop!");
    }

    synchronized public boolean touchSession(long sessionId, int timeout) {
        if (LOG.isTraceEnabled()) {
            ZooTrace.logTraceMessage(LOG,
                                     ZooTrace.CLIENT_PING_TRACE_MASK,
                                     "SessionTrackerImpl --- Touch session: 0x"
                    + Long.toHexString(sessionId) + " with timeout " + timeout);
        }
        SessionImpl s = sessionsById.get(sessionId);
        // Return false, if the session doesn't exists or marked as closing
        if (s == null || s.isClosing()) {
            return false;
        }
        // 处理 session 过期时刻 为 tickTime=2秒的倍数
        long expireTime = roundToInterval(System.currentTimeMillis() + timeout);
        if (s.tickTime >= expireTime) {
            // Nothing needs to be done
            return true;
        }
        // 处理session到期时间，将其挪到新的分桶中
        SessionSet set = sessionSets.get(s.tickTime);
        if (set != null) {
            set.sessions.remove(s);
        }
        s.tickTime = expireTime;
        set = sessionSets.get(s.tickTime);
        if (set == null) {
            set = new SessionSet();
            sessionSets.put(expireTime, set);
        }
        set.sessions.add(s);
        return true;
    }

    synchronized public void setSessionClosing(long sessionId) {
        if (LOG.isTraceEnabled()) {
            LOG.info("Session closing: 0x" + Long.toHexString(sessionId));
        }
        SessionImpl s = sessionsById.get(sessionId);
        if (s == null) {
            return;
        }
        s.isClosing = true;
    }

    synchronized public void removeSession(long sessionId) {
        SessionImpl s = sessionsById.remove(sessionId);
        sessionsWithTimeout.remove(sessionId);
        if (LOG.isTraceEnabled()) {
            ZooTrace.logTraceMessage(LOG, ZooTrace.SESSION_TRACE_MASK,
                    "SessionTrackerImpl --- Removing session 0x"
                    + Long.toHexString(sessionId));
        }
        if (s != null) {
            SessionSet set = sessionSets.get(s.tickTime);
            // Session expiration has been removing the sessions   
            if(set != null){
                set.sessions.remove(s);
            }
        }
    }

    public void shutdown() {
        LOG.info("Shutting down");

        running = false;
        if (LOG.isTraceEnabled()) {
            ZooTrace.logTraceMessage(LOG, ZooTrace.getTextTraceLevel(),
                                     "Shutdown SessionTrackerImpl!");
        }
    }


    synchronized public long createSession(int sessionTimeout) {
        addSession(nextSessionId, sessionTimeout);
        return nextSessionId++;
    }

    synchronized public void addSession(long id, int sessionTimeout) {
        // sessionId对应的过期时间
        sessionsWithTimeout.put(id, sessionTimeout);
        if (sessionsById.get(id) == null) {
            // 创建session
            SessionImpl s = new SessionImpl(id, sessionTimeout, 0);
            // 放入 sessionId 和 实例的映射中
            sessionsById.put(id, s);
            if (LOG.isTraceEnabled()) {
                ZooTrace.logTraceMessage(LOG, ZooTrace.SESSION_TRACE_MASK,
                        "SessionTrackerImpl --- Adding session 0x"
                        + Long.toHexString(id) + " " + sessionTimeout);
            }
        } else {
            if (LOG.isTraceEnabled()) {
                ZooTrace.logTraceMessage(LOG, ZooTrace.SESSION_TRACE_MASK,
                        "SessionTrackerImpl --- Existing session 0x"
                        + Long.toHexString(id) + " " + sessionTimeout);
            }
        }
        // 更新session的过期时刻
        touchSession(id, sessionTimeout);
    }

    synchronized public void checkSession(long sessionId, Object owner) throws KeeperException.SessionExpiredException, KeeperException.SessionMovedException {
        SessionImpl session = sessionsById.get(sessionId);
        if (session == null || session.isClosing()) {
            throw new KeeperException.SessionExpiredException();
        }
        if (session.owner == null) {
            session.owner = owner;
        } else if (session.owner != owner) {
            throw new KeeperException.SessionMovedException();
        }
    }

    synchronized public void setOwner(long id, Object owner) throws SessionExpiredException {
        SessionImpl session = sessionsById.get(id);
        if (session == null || session.isClosing()) {
            throw new KeeperException.SessionExpiredException();
        }
        session.owner = owner;
    }
}
